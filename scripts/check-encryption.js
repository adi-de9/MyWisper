// Dev check (run: npx electron scripts/check-encryption.js). Reads the raw Firestore docs (as stored in the cloud) for the signed-in account and reports whether text is encrypted.
const path = require('path');
const { app } = require('electron');
app.setName('MyWisper');
require('dotenv').config({ path: path.join(__dirname, '..', '.env'), quiet: true });
app.whenReady().then(async () => {
  const auth = require('../src/main/auth');
  const { getFirestore, collection, getDocs, doc, getDoc } = require('firebase/firestore');
  console.log('restoring');
  auth.restore();
  for (let i = 0; i < 40 && !auth.isOnline(); i++) await new Promise((r) => setTimeout(r, 250));
  if (!auth.isOnline()) { console.log('not signed in'); app.quit(); return; }
  console.log('online as', auth.user().email);
  const db = getFirestore(auth.app);
  const uid = auth.user().uid;
  const hist = await getDocs(collection(db, 'users', uid, 'history'));
  let plain = 0, enc = 0, tomb = 0;
  for (const d of hist.docs) {
    const x = d.data();
    if (x.deleted) { tomb++; continue; }
    const t = x.cleanText || x.result || x.rawText || '';
    if (t.startsWith('enc1:')) enc++; else plain++;
  }
  console.log(`history: ${hist.size} docs, encrypted ${enc}, PLAIN ${plain}, deleted ${tomb}`);
  const sample = hist.docs.find((d) => !d.data().deleted)?.data();
  if (sample) console.log('sample stored doc:', JSON.stringify({ cleanText: sample.cleanText?.slice(0, 40), app: sample.app?.slice(0, 30), words: sample.words, time: sample.time }));
  const pr = await getDocs(collection(db, 'users', uid, 'prompts'));
  console.log('prompts:', pr.docs.map((d) => Object.keys(d.data()).join('+')).join(' | '));
  const dict = await getDoc(doc(db, 'users', uid, 'meta', 'dictionary'));
  console.log('dictionary fields:', Object.keys(dict.data() || {}).join(','), '| keycheck exists:', (await getDoc(doc(db, 'users', uid, 'meta', 'keycheck'))).exists());
  app.quit();
});


