// Proves the Firestore rules (firebase/firestore.rules): each account can only see and change its own data.
// Run: npm run test:rules  (starts a local Firestore emulator; nothing touches the real database)
const fs = require('fs');
const path = require('path');
const { initializeTestEnvironment, assertSucceeds, assertFails } = require('@firebase/rules-unit-testing');
const { doc, getDoc, setDoc, deleteDoc, collection, getDocs } = require('firebase/firestore');

let failed = 0;
async function check(name, promise) {
  try { await promise; console.log(`  ok   ${name}`); } catch (err) { failed++; console.log(`  FAIL ${name}: ${err.message}`); }
}

(async () => {
  const env = await initializeTestEnvironment({
    projectId: 'demo-mywisper',
    firestore: { rules: fs.readFileSync(path.join(__dirname, '..', 'firebase', 'firestore.rules'), 'utf8'), host: '127.0.0.1', port: 8085 },
  });
  const alice = env.authenticatedContext('alice').firestore();
  const bob = env.authenticatedContext('bob').firestore();
  const nobody = env.unauthenticatedContext().firestore();
  const entry = doc(alice, 'users/alice/history/h1');

  console.log('Own data');
  await check('Alice writes her history', assertSucceeds(setDoc(entry, { cleanText: 'enc1:xyz', updatedAt: 1 })));
  await check('Alice reads her history', assertSucceeds(getDoc(entry)));
  await check('Alice lists her history', assertSucceeds(getDocs(collection(alice, 'users/alice/history'))));
  await check('Alice writes her dictionary', assertSucceeds(setDoc(doc(alice, 'users/alice/meta/dictionary'), { data: 'enc1:x' })));

  console.log("Another account (Bob) on Alice's data");
  await check('Bob cannot read it', assertFails(getDoc(doc(bob, 'users/alice/history/h1'))));
  await check('Bob cannot list it', assertFails(getDocs(collection(bob, 'users/alice/history'))));
  await check('Bob cannot change it', assertFails(setDoc(doc(bob, 'users/alice/history/h1'), { cleanText: 'hacked' })));
  await check('Bob cannot delete it', assertFails(deleteDoc(doc(bob, 'users/alice/history/h1'))));
  await check('Bob cannot add to it', assertFails(setDoc(doc(bob, 'users/alice/history/new'), { x: 1 })));
  await check("Bob cannot read Alice's dictionary", assertFails(getDoc(doc(bob, 'users/alice/meta/dictionary'))));
  await check('Bob cannot list all users', assertFails(getDocs(collection(bob, 'users'))));

  console.log('Signed out');
  await check('cannot read', assertFails(getDoc(doc(nobody, 'users/alice/history/h1'))));
  await check('cannot write', assertFails(setDoc(doc(nobody, 'users/alice/history/x'), { x: 1 })));

  console.log('Outside users/');
  await check('cannot write anywhere else', assertFails(setDoc(doc(alice, 'other/x'), { x: 1 })));

  console.log('Usage counter (server-only)');
  await env.withSecurityRulesDisabled(async (ctx) => { await setDoc(doc(ctx.firestore(), 'usage/alice/days/2026-10-09'), { audioSec: 30 }); });
  await check('Alice reads her usage', assertSucceeds(getDoc(doc(alice, 'usage/alice/days/2026-10-09'))));
  await check('Alice cannot reset her usage', assertFails(setDoc(doc(alice, 'usage/alice/days/2026-10-09'), { audioSec: 0 })));
  await check('Alice cannot delete her usage', assertFails(deleteDoc(doc(alice, 'usage/alice/days/2026-10-09'))));
  await check("Bob cannot read Alice's usage", assertFails(getDoc(doc(bob, 'usage/alice/days/2026-10-09'))));
  await check('Nobody can change the limits', assertFails(setDoc(doc(alice, 'config/limits'), { dailyAudioSec: 99999 })));

  console.log('Own delete');
  await check('Alice deletes her own entry', assertSucceeds(deleteDoc(entry)));

  await env.cleanup();
  console.log(failed ? `\n${failed} check(s) FAILED` : '\nAll rules checks passed');
  process.exit(failed ? 1 : 0);
})().catch((err) => { console.error(err); process.exit(1); });
