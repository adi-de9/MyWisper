// Copies the dictation prompts to the Android app (android/app/src/main/assets/prompts.json),
// so desktop and mobile always clean up text the same way. Runs automatically before every Android build.
const fs = require('fs');
const path = require('path');
const { cleanupSystemPrompt, APP_STYLES, DEFAULT_PROMPTS, MATCH_SYSTEM } = require('../src/main/prompts');
const { HALLUCINATIONS, HALLUCINATIONS_ALWAYS, whisperPrompt } = require('../src/main/groq');

const cleanup = {};
for (const category of Object.keys(APP_STYLES)) cleanup[category] = cleanupSystemPrompt({ context: { category } });

const out = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'assets', 'prompts.json');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, JSON.stringify({
  whisperPrompt: whisperPrompt(),
  hallucinations: HALLUCINATIONS,
  hallucinationsAlways: HALLUCINATIONS_ALWAYS,
  cleanup,
  // Rewrite selected text (desktop library.js / prompts.js render()):
  library: DEFAULT_PROMPTS,
  matchSystem: MATCH_SYSTEM,
  renderNoEntry: 'Rewrite the text as instructed below.',
  renderExtra: 'Additional instruction from me (follow it): ',
  renderWrapper: 'The text to work on is inside <text> tags in my message. Output ONLY the finished result: no preface, no explanation, no quotes around it.',
}, null, 2));
console.log(`Wrote ${path.relative(process.cwd(), out)} (${Object.keys(cleanup).join(', ')})`);
