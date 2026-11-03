// The MyWisper server (functions/ in this repo): Groq goes through it with a daily free limit per user,
// and it hands each signed-in user their own sync key. Requests carry the Firebase ID token.
// MYWISPER_API_BASE overrides the address (e.g. the local functions emulator while developing).
const fs = require('fs');
const path = require('path');
const { safeStorage } = require('electron');
const auth = require('./auth');
const { dir } = require('./config');
const firebaseConfig = require('./firebase-config');

const BASE = process.env.MYWISPER_API_BASE || firebaseConfig.apiBase;
const keyFile = () => path.join(dir(), 'datakey.bin'); // { uid, key } encrypted with Windows DPAPI

async function request(pathname, { force = false } = {}) {
  const token = await auth.idToken(force);
  const res = await fetch(`${BASE}${pathname}`, { headers: { Authorization: `Bearer ${token}` } });
  if (res.status === 401 && !force) return request(pathname, { force: true }); // token just expired
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error?.message || `Server error ${res.status}`);
  return body;
}

/** This user's sync key (32 bytes). Asked from the server once, then kept encrypted on this PC. */
async function dataKey(uid) {
  try {
    const saved = JSON.parse(safeStorage.decryptString(fs.readFileSync(keyFile())));
    if (saved.uid === uid) return Buffer.from(saved.key, 'base64');
  } catch { /* not saved yet */ }
  const { key } = await request('/dataKey');
  const buf = Buffer.from(key, 'base64');
  if (buf.length !== 32) throw new Error('Server sent an invalid key');
  fs.mkdirSync(dir(), { recursive: true });
  fs.writeFileSync(keyFile(), safeStorage.encryptString(JSON.stringify({ uid, key })));
  return buf;
}

/** { audioSec, limitSec, resetAt } for today. */
const usage = () => request('/usage');

/** Wakes the server so the first dictation isn't slowed by a cold start. */
function warmUp() {
  fetch(`${BASE}/ping`).catch(() => {});
}

module.exports = { BASE, dataKey, usage, warmUp, keyFile };
