// Each user's own AES-256 key for the text MyWisper syncs to Firestore.
// Same math the apps used when the secret was built into them (src/main/crypto.js, core/Crypto.kt),
// so everything already in the database stays readable. The secret now lives only on the server.
const crypto = require('crypto');

const INFO = 'mywisper-data-v1';

function secretBytes(base64) {
  const key = Buffer.from(base64 || '', 'base64');
  if (key.length !== 32) throw new Error('DATA_SECRET must be 32 bytes (base64)');
  return key;
}

/** HKDF-SHA256(secret, salt = uid, info) → 32-byte key. */
function userKey(uid, secret) {
  return Buffer.from(crypto.hkdfSync('sha256', secret, Buffer.from(uid, 'utf8'), Buffer.from(INFO, 'utf8'), 32));
}

module.exports = { secretBytes, userKey };
