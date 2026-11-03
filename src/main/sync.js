// Keeps history, prompts and the dictionary the same on every device signed in to the same Google account.
// Firestore: users/{uid}/history/{id}, users/{uid}/prompts/{id}, users/{uid}/meta/dictionary (+ meta/keycheck).
// Every item has updatedAt (ms); the newer version always wins. Deletions are items with deleted: true.
// All text is encrypted before upload (crypto.js); the local JSON files stay the app's source of truth.
const fs = require('fs');
const path = require('path');
const {
  initializeFirestore, collection, doc, setDoc, getDoc, getDocs, writeBatch, onSnapshot, query, where,
} = require('firebase/firestore');
const auth = require('./auth');
const config = require('./config');
const history = require('./history');
const library = require('./library');
const crypto = require('./crypto');
const api = require('./api');

const BATCH = 400;          // Firestore allows 500 writes per batch
const FORMAT = 2;           // 2 = encrypted. A change re-uploads everything in the new format.
const KEYCHECK_TEXT = 'mywisper';
const stateFile = () => path.join(config.dir(), 'sync-state.json');

const db = initializeFirestore(auth.app, { ignoreUndefinedProperties: true });

let uid = null;
let key = null;
let unsubscribers = [];
let state = null; // { uid, format, pushedAt, since: { history, prompts } }
let status = 'off'; // off | syncing | ok | mismatch | error
let onRemote = () => {};
let onStatus = () => {};
let log = () => {};

function setStatus(s) { status = s; onStatus(s); }

function loadState(forUid) {
  try { state = JSON.parse(fs.readFileSync(stateFile(), 'utf8')); } catch { state = null; }
  if (!state || state.uid !== forUid || state.format !== FORMAT) {
    state = { uid: forUid, format: FORMAT, pushedAt: 0, since: { history: 0, prompts: 0 } };
  }
}

function saveState() {
  try { fs.writeFileSync(stateFile(), JSON.stringify(state)); } catch { /* ignore */ }
}

const col = (name) => collection(db, 'users', uid, name);
const dictRef = () => doc(db, 'users', uid, 'meta', 'dictionary');
const keycheckRef = () => doc(db, 'users', uid, 'meta', 'keycheck');

const seal = (name, item) => (name === 'history' ? crypto.sealHistory(key, item) : crypto.sealBlob(key, item));
const open = (name, item) => (name === 'history' ? crypto.openHistory(key, item) : crypto.openBlob(key, item));
const refFor = (name, item) => (name === 'dictionary' ? dictRef() : doc(col(name), item.id));

/**
 * Makes sure this device's secret matches the one the account's data was encrypted with.
 * First device: writes the check value. Mismatch: sync stops instead of writing data nobody can read.
 */
async function checkKey() {
  const snap = await getDoc(keycheckRef());
  if (!snap.exists()) {
    await setDoc(keycheckRef(), { value: crypto.encrypt(key, KEYCHECK_TEXT), updatedAt: Date.now() });
    return true;
  }
  try { return crypto.decrypt(key, snap.data().value) === KEYCHECK_TEXT; } catch { return false; }
}

// ---------- local → cloud ----------
function push(name, item) {
  if (!uid || status !== 'ok') return;
  setDoc(refFor(name, item), seal(name, item)).catch((err) => log(`sync: upload ${name} failed: ${err.message}`));
}

/** Uploads everything changed since the last full upload (first sign-in / new format: everything). */
async function catchUp() {
  const started = Date.now();
  const items = [
    ...history.changesSince(state.pushedAt).map((it) => ['history', it]),
    ...library.changesSince(state.pushedAt).map((it) => ['prompts', it]),
  ];
  const dict = config.getDictionary();
  if ((dict.updatedAt || 1) > state.pushedAt) items.push(['dictionary', { ...dict, updatedAt: dict.updatedAt || 1 }]);
  for (let i = 0; i < items.length; i += BATCH) {
    const batch = writeBatch(db);
    for (const [name, item] of items.slice(i, i + BATCH)) batch.set(refFor(name, item), seal(name, item));
    await batch.commit();
  }
  state.pushedAt = started;
  saveState();
  if (items.length) log(`sync: uploaded ${items.length} change(s) (encrypted)`);
}

// ---------- cloud → local ----------
function listen(name, apply) {
  const q = query(col(name), where('updatedAt', '>', state.since[name] || 0));
  return onSnapshot(q, (snap) => {
    let changed = 0;
    for (const ch of snap.docChanges()) {
      if (ch.type === 'removed') continue;
      let data;
      try { data = open(name, { ...ch.doc.data(), id: ch.doc.id }); } catch { log(`sync: could not decrypt ${name}/${ch.doc.id}`); continue; }
      if (apply(data)) changed++;
      state.since[name] = Math.max(state.since[name] || 0, data.updatedAt || 0);
    }
    if (snap.docChanges().length) saveState();
    if (changed) { log(`sync: ${changed} ${name} change(s) from another device`); onRemote(name); }
  }, (err) => log(`sync: ${name} listener failed: ${err.message}`));
}

let startTimer = null;
let starting = null;
async function start(forUid) {
  if (uid === forUid || starting === forUid) return;
  clearTimeout(startTimer);
  stop();
  starting = forUid;
  try {
    key = await api.dataKey(forUid);
  } catch (err) {
    log(`sync: could not get the sync key: ${err.message}`);
    startTimer = setTimeout(() => { if (auth.user()?.uid === forUid) start(forUid); }, 60_000); // offline: retry
    return setStatus('error');
  } finally {
    starting = null;
  }
  if (auth.user()?.uid !== forUid) return; // signed out meanwhile
  uid = forUid;
  loadState(uid);
  setStatus('syncing');
  try {
    if (!(await checkKey())) {
      log('sync: encryption key mismatch – the server key differs from the one that encrypted this account; sync stopped');
      return setStatus('mismatch');
    }
  } catch (err) {
    log(`sync: key check failed: ${err.message}`);
    return setStatus('error');
  }
  setStatus('ok');
  history.onChange((it) => push('history', it));
  library.onChange((it) => push('prompts', it));
  config.onDictionaryChange((d) => push('dictionary', d));
  unsubscribers = [
    listen('history', history.applyRemote),
    listen('prompts', library.applyRemote),
    onSnapshot(dictRef(), (snap) => {
      if (!snap.exists()) return;
      let d;
      try { d = crypto.openBlob(key, snap.data()); } catch { log('sync: could not decrypt the dictionary'); return; }
      if (config.applyRemoteDictionary(d)) { log('sync: dictionary from another device'); onRemote('dictionary'); }
    }, (err) => log(`sync: dictionary listener failed: ${err.message}`)),
  ];
  try { await catchUp(); } catch (err) { log(`sync: catch-up failed: ${err.message}`); }
}

function stop() {
  for (const u of unsubscribers) u();
  unsubscribers = [];
  uid = null;
  key = null;
  history.onChange(() => {});
  library.onChange(() => {});
  config.onDictionaryChange(() => {});
  setStatus('off');
}

/** Deletes everything this account has in Firestore (local files stay). */
async function deleteCloudData() {
  if (!uid) throw new Error('Not connected');
  const me = uid;
  stop();
  let n = 0;
  for (const name of ['history', 'prompts', 'meta']) {
    const snap = await getDocs(collection(db, 'users', me, name));
    for (let i = 0; i < snap.docs.length; i += BATCH) {
      const batch = writeBatch(db);
      for (const d of snap.docs.slice(i, i + BATCH)) batch.delete(d.ref);
      await batch.commit();
      n += Math.min(BATCH, snap.docs.length - i);
    }
  }
  try { fs.unlinkSync(stateFile()); } catch { /* ignore */ }
  log(`sync: deleted ${n} cloud document(s)`);
  // Re-uploads are off until the next sign-in, so the cloud stays empty as asked.
  return n;
}

/** Wire up: starts/stops with sign-in. remote(name) runs after another device changed something. */
function init({ remote, logger, statusChanged }) {
  onRemote = remote || onRemote;
  log = logger || log;
  onStatus = statusChanged || onStatus;
  auth.onChange((user, online) => {
    if (user && online) start(user.uid);
    else if (!user) { clearTimeout(startTimer); stop(); }
  });
}

module.exports = { init, stop, deleteCloudData, status: () => status };
