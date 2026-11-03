// Per-user daily usage: usage/{uid}/days/{YYYY-MM-DD} = { audioSec, tokens, requests }.
// Only the server writes it (firebase/firestore.rules lets the owner read it for the usage meter).

/** UTC day, e.g. "2026-10-09". The free limit resets at UTC midnight. */
function today(now = Date.now()) {
  return new Date(now).toISOString().slice(0, 10);
}

/** Next UTC midnight in ms (sent to the apps so they can show the reset time in local time). */
function resetAt(now = Date.now()) {
  const d = new Date(now);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1);
}

/** Firestore-backed store (production). */
function firestoreStore(db, FieldValue) {
  const ref = (uid, day) => db.collection('usage').doc(uid).collection('days').doc(day);
  return {
    async get(uid, day) {
      const snap = await ref(uid, day).get();
      return snap.exists ? snap.data() : {};
    },
    async add(uid, day, { audioSec = 0, tokens = 0 }) {
      await ref(uid, day).set({
        audioSec: FieldValue.increment(audioSec),
        tokens: FieldValue.increment(tokens),
        requests: FieldValue.increment(1),
        updatedAt: Date.now(),
      }, { merge: true });
    },
    async limits() {
      const snap = await db.collection('config').doc('limits').get();
      return snap.exists ? snap.data() : {};
    },
  };
}

/** In-memory store (tests). */
function memoryStore(limits = {}) {
  const days = new Map();
  return {
    days,
    limitsDoc: limits,
    async get(uid, day) { return { ...(days.get(`${uid}/${day}`) || {}) }; },
    async add(uid, day, { audioSec = 0, tokens = 0 }) {
      const k = `${uid}/${day}`;
      const d = days.get(k) || { audioSec: 0, tokens: 0, requests: 0 };
      days.set(k, { audioSec: d.audioSec + audioSec, tokens: d.tokens + tokens, requests: d.requests + 1 });
    },
    async limits() { return this.limitsDoc; },
  };
}

module.exports = { today, resetAt, firestoreStore, memoryStore };
