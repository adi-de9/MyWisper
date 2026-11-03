// Dev check (run: npx electron scripts/check-server.js [file.wav]): with the signed-in account, goes through the
// MyWisper server the way the app does – sync key (and that it opens this account's Firestore keycheck),
// Whisper + cleanup through the proxy, and the daily usage counter. Set MYWISPER_API_BASE for a local server.
const path = require('path');
const fs = require('fs');
const os = require('os');
const { app } = require('electron');
app.setName('MyWisper');
require('dotenv').config({ path: path.join(__dirname, '..', '.env'), quiet: true });

function sampleWav() {
  const file = path.join(os.tmpdir(), 'mywisper-sample.wav');
  if (!fs.existsSync(file)) {
    const ps = `Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; ` +
      `$s.SetOutputToWaveFile('${file}'); $s.Speak('Um, hello, the meeting is tomorrow at five, uh, please send the report.'); $s.Dispose()`;
    require('child_process').execFileSync('powershell', ['-NoProfile', '-Command', ps]);
  }
  return file;
}

app.whenReady().then(async () => {
  const done = (code) => { app.exit(code); };
  try {
    const auth = require('../src/main/auth');
    const api = require('../src/main/api');
    const groq = require('../src/main/groq');
    const crypto = require('../src/main/crypto');
    const { getFirestore, doc, getDoc } = require('firebase/firestore');
    console.log('server:', api.BASE);
    auth.restore();
    for (let i = 0; i < 40 && !auth.isOnline(); i++) await new Promise((r) => setTimeout(r, 250));
    if (!auth.isOnline()) { console.log('not signed in'); return done(1); }
    const uid = auth.user().uid;
    console.log('signed in');

    fs.rmSync(api.keyFile(), { force: true }); // ask the server, not the saved copy
    const key = await api.dataKey(uid);
    const check = await getDoc(doc(getFirestore(auth.app), 'users', uid, 'meta', 'keycheck'));
    const opens = check.exists() ? crypto.decrypt(key, check.data().value) === 'mywisper' : 'no keycheck yet';
    console.log('sync key from server opens this account\'s data:', opens);

    groq.useServer({ baseURL: api.BASE, token: auth.idToken });
    const before = await api.usage();
    const stt = await groq.transcribe({ audio: fs.readFileSync(process.argv[2] || sampleWav()), fileName: 'speech.wav', model: 'whisper-large-v3', language: 'auto', strict: false });
    console.log(`whisper via server: ${stt.ms} ms, ${stt.text.length} chars`);
    const llm = await groq.cleanup({ text: stt.text, model: 'qwen/qwen3.8-27b', dictionary: { words: [], replacements: {} } });
    console.log(`cleanup via server: ${llm.ms} ms, ${llm.text.length} chars`);
    const after = await api.usage();
    console.log(`usage: ${before.audioSec}s → ${after.audioSec}s of ${after.limitSec}s`);
    done(opens === true || opens === 'no keycheck yet' ? 0 : 2);
  } catch (err) {
    console.log('FAILED:', err.status || '', err.message, '| app shows:', require('../src/main/groq').errorMessage(err));
    done(1);
  }
});
