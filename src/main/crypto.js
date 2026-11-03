// Encrypts the text MyWisper syncs to Firestore, so nobody browsing the database can read it.
// Each user has their own AES-256 key, handed out by the MyWisper server after sign-in (api.dataKey);
// the secret it's made from never ships in the app. Same format as the Android app (core/Crypto.kt):
// "enc1:" + base64(iv | ciphertext | tag).
const crypto = require('crypto');

const PREFIX = 'enc1:';
const INFO = 'mywisper-data-v1';

/** The AES key for one user (HKDF-SHA256, salt = uid). The server does this; kept here for tests. */
function userKey(uid, secret) {
  return Buffer.from(crypto.hkdfSync('sha256', secret, Buffer.from(uid, 'utf8'), Buffer.from(INFO, 'utf8'), 32));
}

function encrypt(key, text, iv = crypto.randomBytes(12)) {
  if (text === undefined || text === null) return text;
  const c = crypto.createCipheriv('aes-256-gcm', key, iv);
  const body = Buffer.concat([c.update(String(text), 'utf8'), c.final()]);
  return PREFIX + Buffer.concat([iv, body, c.getAuthTag()]).toString('base64');
}

/** Values without the prefix (old, unencrypted docs) are returned as they are. Throws on a wrong key. */
function decrypt(key, value) {
  if (typeof value !== 'string' || !value.startsWith(PREFIX)) return value;
  const data = Buffer.from(value.slice(PREFIX.length), 'base64');
  const d = crypto.createDecipheriv('aes-256-gcm', key, data.subarray(0, 12));
  d.setAuthTag(data.subarray(data.length - 16));
  return Buffer.concat([d.update(data.subarray(12, data.length - 16)), d.final()]).toString('utf8');
}

// History: these fields hold the user's words; everything else (numbers, time, models) stays readable for stats.
const HISTORY_TEXT = ['rawText', 'cleanText', 'original', 'result', 'instruction', 'extra', 'label', 'app', 'title', 'error'];

function sealHistory(key, e) {
  if (e.deleted) return e;
  const out = { ...e };
  for (const f of HISTORY_TEXT) if (typeof out[f] === 'string') out[f] = encrypt(key, out[f]);
  return out;
}

function openHistory(key, e) {
  const out = { ...e };
  for (const f of HISTORY_TEXT) if (typeof out[f] === 'string') out[f] = decrypt(key, out[f]);
  return out;
}

// Prompts and the dictionary: everything except the sync fields goes into one encrypted "data" blob.
const KEEP = ['id', 'updatedAt', 'deleted'];

function sealBlob(key, obj) {
  const keep = {};
  const rest = {};
  for (const [k, v] of Object.entries(obj)) (KEEP.includes(k) ? keep : rest)[k] = v;
  if (obj.deleted) return keep;
  return { ...keep, data: encrypt(key, JSON.stringify(rest)) };
}

function openBlob(key, doc) {
  if (typeof doc.data !== 'string') return doc; // old unencrypted doc
  const { data, ...keep } = doc;
  return { ...JSON.parse(decrypt(key, data)), ...keep };
}

module.exports = { userKey, encrypt, decrypt, sealHistory, openHistory, sealBlob, openBlob, PREFIX };
