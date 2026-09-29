// Run with: npm run test:api          (all checks)
//           npm run test:api -- quick (skip the model comparison)
// Checks the Groq key, Whisper, and every writing feature of the cleanup + selection actions.
require('dotenv').config({ path: require('path').join(__dirname, '..', '.env'), quiet: true });
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const { transcribe, cleanup, transform, matchPrompt, testKey } = require('../src/main/groq');
const { DEFAULT_PROMPTS, render } = require('../src/main/prompts');

const apiKey = process.env.GROQ_API_KEY;
const dictionary = { words: ['Aditya', 'MyWisper'], replacements: {} };
const CLEANUP_MODEL = 'qwen/qwen3.8-27b';
const TRANSFORM_MODEL = 'openai/gpt-oss-120b';
const quick = process.argv.includes('quick');

// Makes a small WAV file with Windows' built-in text-to-speech voice.
function makeSampleWav() {
  const file = path.join(os.tmpdir(), 'mywisper-sample.wav');
  if (fs.existsSync(file)) return file;
  const ps = `Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; ` +
    `$s.SetOutputToWaveFile('${file}'); $s.Speak('Um, hello Aditya, the meeting is tomorrow at five, uh, please send the report.'); $s.Dispose()`;
  execFileSync('powershell', ['-NoProfile', '-Command', ps]);
  return file;
}

// Each feature case: input dictation, optional app context, and a check on the output.
const CASES = [
  { name: 'Filler removal', text: 'um so uh I think we should uh launch next week', check: (o) => !/\b(um|uh)\b/i.test(o) },
  { name: 'Backtrack (actually)', text: 'meeting at 5 actually 6 pm', check: (o) => /6\s*PM/i.test(o) && !/\b5\b/.test(o) },
  { name: 'Backtrack (Hinglish)', text: 'kal 5 baje milte hain nahi nahi 6 baje', check: (o) => /6 baje/i.test(o) && !/5 baje/i.test(o) },
  { name: 'Backtrack (scratch that)', text: 'the budget is 10 lakh scratch that the budget is 12 lakh', check: (o) => /12 lakh/i.test(o) && !/10 lakh/i.test(o) },
  { name: 'Spoken punctuation', text: 'hi rahul comma kal milte hain question mark', check: (o) => /Rahul,/i.test(o) && /\?$/.test(o.trim()) && !/comma|question mark/i.test(o) },
  { name: 'New paragraph', text: 'first point is ready new paragraph second point is pending', check: (o) => /\n\s*\n/.test(o) && !/new paragraph/i.test(o) },
  { name: 'Numbered list', text: 'things to buy one milk two bread three eggs', check: (o) => /1\.\s*milk/i.test(o) && /2\.\s*bread/i.test(o) && /3\.\s*eggs/i.test(o) },
  { name: 'Does not answer questions', text: 'what is the capital of france', check: (o) => /capital of France\?/i.test(o) && !/paris/i.test(o) },
  { name: 'Devanagari → Roman', text: 'कल की मीटिंग पांच बजे है, report भेज देना please', check: (o) => !/[ऀ-ॿ]/.test(o) && /baje/i.test(o) },
  {
    name: 'Email context', context: { app: 'Gmail (Chrome)', category: 'email' },
    text: 'hi rahul new paragraph the report is ready I will send it by friday new paragraph regards aditya',
    check: (o) => /^Hi Rahul,?\s*\n/i.test(o) && /Regards,?\s*\n\s*Aditya/i.test(o),
  },
  {
    name: 'Chat context', context: { app: 'WhatsApp', category: 'chat' },
    text: 'haan bhai main 10 minute mein aa raha hoon', check: (o) => o.split('\n').length === 1 && /10 minute/i.test(o),
  },
  {
    name: 'AI prompt context', context: { app: 'ChatGPT (Chrome)', category: 'ai' },
    text: 'write a blog about ai for students and also give me 5 title ideas and also write a short linkedin summary of it',
    check: (o) => /\n\s*(1\.|-)/.test(o) && /title/i.test(o),
  },
];

const TRANSFORM_CASES = [
  { action: 'prompt', text: 'write blog about ai for students 1000 words simple language', check: (o) => /## Role/i.test(o) && /## Task/i.test(o) && /## Output format/i.test(o) && /1000|1,000/.test(o) },
  { action: 'polish', text: 'hey so i was thinking we can maybe do the meeting tomorow if u are free', check: (o) => o.length > 20 && !/\bu\b/.test(o) },
  { action: 'grammar', text: 'she dont like going their on sundays', check: (o) => /doesn't|does not/i.test(o) && /there/i.test(o) },
  { action: 'translate', text: 'kal subah 10 baje office aana, client meeting hai', check: (o) => /tomorrow/i.test(o) && /10/.test(o) && /client/i.test(o) },
  {
    action: 'eod',
    text: 'fixed rsvp guest count bug QP-1423 root cause was null check missing on plus one field. qe app login screen ui done, tested on android, ios testing pending. evar export api halfway, will finish tomorow. also reviewed PR 88',
    // Plain heading lines + "• " bullets, no markdown; every item kept.
    check: (o) => /^EOD Report – \d/.test(o) && !/[#*]/.test(o) && /^• /m.test(o) && !/^- /m.test(o)
      && o.includes('QP-1423') && /pending/i.test(o) && /PR #?88/i.test(o),
  },
  // starter prompts
  {
    action: 'jira',
    text: 'bug on QE-812 checkout page, when coupon applied total shows negative on ios app v2.3, should never go below zero',
    check: (o) => o.includes('QE-812') && /2\.3/.test(o) && /expected/i.test(o) && !/[#*]/.test(o),
  },
  {
    action: 'standup',
    text: 'yesterday finished rsvp export QP-77, today working on evar login, no blockers',
    check: (o) => /yesterday/i.test(o) && /today/i.test(o) && /blockers/i.test(o) && o.includes('QP-77') && /^• /m.test(o),
  },
  {
    action: 'commit',
    text: 'changed paste.js to restore clipboard after ctrl v, fixes QP-90 where clipboard was lost',
    check: (o) => /^(feat|fix|refactor|docs|test|chore)(\(.+\))?: /.test(o) && o.split('\n')[0].length <= 72 && o.includes('QP-90'),
  },
];

// Spoken instruction → saved prompt id (null = one-off instruction) + extra wishes.
const MATCH_CASES = [
  { said: 'make EOD', id: 'eod' },
  { said: 'EOD banao', id: 'eod' },
  { said: 'end of day report bana do', id: 'eod' },
  { said: 'make eod and keep it short', id: 'eod', extra: /short/i },
  { said: 'fix the grammar', id: 'grammar' },
  { said: 'make a jira ticket', id: 'jira' },
  { said: 'make it more formal', id: null, extra: /formal/i },
  { said: 'write a haiku about this', id: null, extra: /haiku/i },
];

const show = (s) => s.replace(/\n/g, '⏎ ');
let failed = 0;
function report(ok, name, ms, input, output) {
  if (!ok) failed++;
  console.log(`   ${ok ? '✅' : '❌'} ${name.padEnd(26)} ${String(ms).padStart(5)} ms`);
  if (!ok || process.argv.includes('verbose')) console.log(`        in : ${show(input)}\n        out: ${show(output)}`);
}

(async () => {
  console.log('1) Checking API key...');
  const k = await testKey(apiKey);
  if (!k.ok) { console.error('   ❌ Key failed:', k.error); process.exit(1); }
  console.log(`   ✅ Key OK (${k.ms} ms).`);

  console.log('\n2) Whisper transcription of a sample clip...');
  const wav = makeSampleWav();
  const r = await transcribe({ apiKey, audio: fs.readFileSync(wav), fileName: 'sample.wav', model: 'whisper-large-v3', dictionary });
  console.log(`   ${r.ms} ms  →  ${r.text}`);

  console.log(`\n3) Dictation features (${CLEANUP_MODEL})...`);
  for (const c of CASES) {
    const out = await cleanup({ apiKey, text: c.text, model: CLEANUP_MODEL, dictionary, context: c.context });
    report(c.check(out.text), c.name, out.ms, c.text, out.text);
  }

  console.log(`\n4) Selected-text actions (${TRANSFORM_MODEL})...`);
  for (const c of TRANSFORM_CASES) {
    const entry = DEFAULT_PROMPTS.find((p) => p.id === c.action);
    const out = await transform({ apiKey, system: render(entry), temperature: entry.temperature, text: c.text, model: TRANSFORM_MODEL });
    report(c.check(out.text), c.action, out.ms, c.text, out.text);
    if (c.action === 'eod') console.log(out.text.split('\n').map((l) => `        │ ${l}`).join('\n'));
  }

  console.log('\n5) Voice instruction → saved prompt...');
  for (const c of MATCH_CASES) {
    const m = await matchPrompt({ apiKey, instruction: c.said, prompts: DEFAULT_PROMPTS });
    const ok = m.id === c.id && (!c.extra || c.extra.test(m.extra));
    report(ok, `"${c.said}"`, m.ms, c.said, `id=${m.id} extra="${m.extra}"`);
  }

  if (!quick) {
    console.log('\n5) Model comparison for cleanup (same feature cases)...');
    for (const model of ['openai/gpt-oss-20b', 'openai/gpt-oss-120b']) {
      let pass = 0; let ms = 0;
      for (const c of CASES) {
        const out = await cleanup({ apiKey, text: c.text, model, dictionary, context: c.context });
        if (c.check(out.text)) pass++;
        ms += out.ms;
      }
      console.log(`   ${model.padEnd(22)} ${pass}/${CASES.length} passed, avg ${Math.round(ms / CASES.length)} ms`);
    }
  }

  console.log(failed ? `\n${failed} check(s) failed.` : '\nAll checks passed.');
  process.exit(failed ? 1 : 0);
})();
