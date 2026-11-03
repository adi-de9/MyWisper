// Every dictation is saved to %APPDATA%\MyWisper\history.json (newest first).
// When signed in, sync.js mirrors it to Firestore: every entry has an updatedAt time, and deletions are
// remembered (history-deleted.json) so other devices delete them too.
const fs = require('fs');
const path = require('path');
const { dir } = require('./config');

const MAX_ENTRIES = 20000;
let entries = null;
let deleted = null; // id → time it was deleted
let saveTimer = null;
let changed = () => {}; // set by sync.js: called with the entry (or { id, deleted: true }) after a local change

const file = () => path.join(dir(), 'history.json');
const deletedFile = () => path.join(dir(), 'history-deleted.json');

function load() {
  if (entries) return entries;
  try { entries = JSON.parse(fs.readFileSync(file(), 'utf8')); } catch { entries = []; }
  try { deleted = JSON.parse(fs.readFileSync(deletedFile(), 'utf8')); } catch { deleted = {}; }
  return entries;
}

// Write to disk shortly after changes (so many quick changes = one write).
function scheduleSave() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(flush, 300);
}

function add(entry) {
  const now = Date.now();
  const e = {
    id: `${now}-${Math.random().toString(36).slice(2, 7)}`, time: new Date(now).toISOString(), device: 'windows',
    ...entry, updatedAt: now,
  };
  load().unshift(e);
  if (entries.length > MAX_ENTRIES) entries.length = MAX_ENTRIES;
  scheduleSave();
  changed(e);
  return e;
}

function list() { return load(); }

function remove(id) {
  entries = load().filter((e) => e.id !== id);
  const tomb = { id, deleted: true, updatedAt: Date.now() };
  deleted[id] = tomb.updatedAt;
  scheduleSave();
  changed(tomb);
}

function clear() {
  for (const e of [...load()]) remove(e.id);
}

/** A change from another device (Firestore). The newer one wins. Returns true if anything changed here. */
function applyRemote(doc) {
  load();
  const i = entries.findIndex((e) => e.id === doc.id);
  if (doc.deleted) {
    deleted[doc.id] = Math.max(deleted[doc.id] || 0, doc.updatedAt || 0);
    if (i < 0) return false;
    entries.splice(i, 1);
    scheduleSave();
    return true;
  }
  if ((deleted[doc.id] || 0) >= (doc.updatedAt || 0)) return false; // deleted here after that edit
  if (i >= 0) {
    if ((entries[i].updatedAt || 0) >= (doc.updatedAt || 0)) return false;
    entries[i] = doc;
  } else {
    let at = entries.findIndex((e) => (e.time || '') < (doc.time || '')); // keep newest first
    if (at < 0) at = entries.length;
    entries.splice(at, 0, doc);
  }
  scheduleSave();
  return true;
}

/** Everything changed after a time (for the first upload and catch-up after being offline). */
function changesSince(ms) {
  load();
  const live = entries
    .map((e) => (e.updatedAt ? e : { ...e, updatedAt: Date.parse(e.time) || 1 }))
    .filter((e) => e.updatedAt > ms);
  const gone = Object.entries(deleted).filter(([, t]) => t > ms).map(([id, t]) => ({ id, deleted: true, updatedAt: t }));
  return [...live, ...gone];
}

// Make sure pending changes are written before the app quits.
function flush() {
  if (!entries) return;
  clearTimeout(saveTimer);
  fs.mkdirSync(dir(), { recursive: true });
  fs.writeFileSync(file(), JSON.stringify(entries));
  fs.writeFileSync(deletedFile(), JSON.stringify(deleted));
}

module.exports = { add, list, remove, clear, flush, applyRemote, changesSince, onChange: (fn) => { changed = fn; } };
