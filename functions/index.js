// MyWisper backend (Firebase Cloud Functions). See lib/app.js for what the API does.
// Secrets (set once with `firebase functions:secrets:set NAME`, never in code or git):
//   GROQ_API_KEY  the Groq key all users share (with a daily free limit each)
//   DATA_SECRET   the same value as MYWISPER_DATA_SECRET in .env – derives each user's sync key
const { onRequest } = require('firebase-functions/v2/https');
const { defineSecret } = require('firebase-functions/params');

const GROQ_API_KEY = defineSecret('GROQ_API_KEY');
const DATA_SECRET = defineSecret('DATA_SECRET');

// firebase-admin is loaded on the first request, not at startup: deploys and the emulator read this file
// quickly (they time out after 10 s), and cold starts stay short.
let handler;
function getHandler() {
  if (!handler) {
    const { initializeApp } = require('firebase-admin/app');
    const { getAuth } = require('firebase-admin/auth');
    const { getFirestore, FieldValue } = require('firebase-admin/firestore');
    const logger = require('firebase-functions/logger');
    const { createHandler } = require('./lib/app');
    const { firestoreStore } = require('./lib/usage');
    const { secretBytes } = require('./lib/keys');
    initializeApp();
    handler = createHandler({
      verifyToken: (t) => getAuth().verifyIdToken(t),
      store: firestoreStore(getFirestore(), FieldValue),
      groqKey: () => GROQ_API_KEY.value(),
      dataSecret: () => secretBytes(DATA_SECRET.value()),
      log: logger,
    });
  }
  return handler;
}

exports.api = onRequest({
  region: 'asia-south1',
  secrets: [GROQ_API_KEY, DATA_SECRET],
  memory: '512MiB',
  timeoutSeconds: 60,
  maxInstances: 20,
  cors: false,
}, (req, res) => getHandler()(req, res));
