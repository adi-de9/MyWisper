// All calls to the Groq API live here: speech-to-text (Whisper), text cleanup (LLM) and the key test.
// This file does not use Electron, so scripts/test-api.js can use it too.
const Groq = require('groq-sdk');
const { toFile } = require('groq-sdk');
const { cleanupSystemPrompt, MATCH_SYSTEM } = require('./prompts');

// Whisper sometimes "hears" these phrases in silence/noise. Dropped only when little voice was detected.
const HALLUCINATIONS = [
  'thank you', 'thank you.', 'thanks for watching', 'thanks for watching!', 'thank you for watching',
  'you', 'bye', 'bye.', '.', 'subtitles by the amara.org community', 'please subscribe',
  'thank you for watching.', 'okay.', 'obrigado.',
];

function makeClient(apiKey) {
  if (!apiKey) throw new Error('No Groq API key. Add it in Dashboard → Settings.');
  return new Groq({ apiKey, timeout: 30_000, maxRetries: 1 });
}

// Whisper's "prompt" is a hint about style + vocabulary (max ~224 tokens, so keep it short).
function whisperPrompt(dictionary) {
  const words = (dictionary?.words || []).slice(0, 40).join(', ');
  let prompt = 'Haan, theek hai, meeting kal hai.';
  if (words) prompt += ` ${words}.`;
  return prompt.slice(0, 800);
}

/**
 * Speech → raw text.
 * @param {object} o
 * @param {string} o.apiKey
 * @param {Buffer} o.audio      recorded audio bytes
 * @param {string} o.fileName   e.g. "speech.webm" (extension tells Groq the format)
 * @param {string} o.model      e.g. "whisper-large-v3"
 * @param {string} o.language   "auto" | "hi" | "en"
 * @param {object} o.dictionary { words: [], replacements: {} }
 */
async function transcribe({ apiKey, audio, fileName = 'speech.webm', model, language = 'auto', dictionary, strict = true }) {
  const t0 = Date.now();
  const params = {
    file: await toFile(audio, fileName),
    model,
    prompt: whisperPrompt(dictionary),
    temperature: 0,
    response_format: 'json',
  };
  if (language && language !== 'auto') params.language = language;

  const res = await makeClient(apiKey).audio.transcriptions.create(params);
  let text = (res.text || '').trim();
  // "strict" = little or unknown voice, so a typical silence phrase is probably invented.
  if (strict && HALLUCINATIONS.includes(text.toLowerCase())) text = '';
  if (!/[\p{L}\p{N}]/u.test(text)) text = ''; // only punctuation, e.g. " ."
  return { text, ms: Date.now() - t0 };
}

// Extra request options that depend on the model family (reasoning models need to be told to think briefly).
function modelOptions(model) {
  if (model.startsWith('openai/gpt-oss')) return { reasoning_effort: 'low', include_reasoning: false };
  if (model.startsWith('qwen/')) return { reasoning_effort: 'none', reasoning_format: 'hidden' };
  return {};
}

// One chat call with a system prompt and wrapped user text. Returns text + timing + token usage.
async function chat({ apiKey, model, system, user, temperature, maxTokens = 2048 }) {
  const t0 = Date.now();
  const res = await makeClient(apiKey).chat.completions.create({
    model,
    temperature,
    max_completion_tokens: maxTokens,
    messages: [{ role: 'system', content: system }, { role: 'user', content: user }],
    ...modelOptions(model),
  });
  const text = (res.choices?.[0]?.message?.content || '')
    .replace(/<think>[\s\S]*?<\/think>/g, '') // in case a model still shows its thinking
    .replace(/<\/?(dictation|text)>/g, '')
    // Models sometimes emit look-alike characters (non-breaking hyphen, narrow/no-break spaces)
    // that show up as "?" or boxes in some apps; use the plain ones.
    .replace(/[‐‑‒]/g, '-')
    .replace(/[    ]/g, ' ')
    .trim();
  return {
    text,
    ms: Date.now() - t0,
    promptTokens: res.usage?.prompt_tokens || 0,
    completionTokens: res.usage?.completion_tokens || 0,
  };
}

/**
 * Raw dictation → clean Roman Hinglish text.
 * context (optional) = { app, category } from context.js, used for per-app formatting.
 */
function cleanup({ apiKey, text, model, dictionary, context }) {
  return chat({
    apiKey, model, temperature: 0.2,
    system: cleanupSystemPrompt({ dictionary, context }),
    user: `<dictation>\n${text}\n</dictation>`,
  });
}

/** Selected text → rewritten with a system prompt from the library (library.render()). */
function transform({ apiKey, system, temperature = 0.3, text, model }) {
  return chat({ apiKey, model, temperature, maxTokens: 4096, system, user: `<text>\n${text}\n</text>` });
}

/**
 * Which saved prompt did the user ask for? ("make eod", "EOD banao, keep it short", "make it formal")
 * prompts: [{ id, name, description }]. Returns { id: string|null, extra: string, ms }.
 */
async function matchPrompt({ apiKey, instruction, prompts, model = 'qwen/qwen3.8-27b' }) {
  const t0 = Date.now();
  const list = prompts.map((p) => ({ id: p.id, name: p.name, description: p.description }));
  const res = await makeClient(apiKey).chat.completions.create({
    model,
    temperature: 0,
    max_completion_tokens: 300,
    response_format: { type: 'json_object' },
    messages: [
      { role: 'system', content: MATCH_SYSTEM },
      { role: 'user', content: `Saved prompts:\n${JSON.stringify(list)}\n\nInstruction: ${instruction}` },
    ],
    ...modelOptions(model),
  });
  let out = {};
  try { out = JSON.parse(res.choices?.[0]?.message?.content || '{}'); } catch { /* treat as no match */ }
  const id = list.some((p) => p.id === out.id) ? out.id : null;
  const extra = typeof out.extra === 'string' ? out.extra.trim() : '';
  return { id, extra: id ? extra : (extra || instruction), ms: Date.now() - t0 };
}

/** Case-insensitive find/replace for the dictionary's "replacements". */
function applyReplacements(text, replacements = {}) {
  let out = text;
  for (const [from, to] of Object.entries(replacements)) {
    if (!from.trim()) continue;
    const escaped = from.trim().replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    out = out.replace(new RegExp(`\\b${escaped}\\b`, 'gi'), to);
  }
  return out;
}

/** Checks the key by listing models. Returns { ok, ms, models, error }. */
async function testKey(apiKey) {
  const t0 = Date.now();
  try {
    const list = await makeClient(apiKey).models.list();
    return { ok: true, ms: Date.now() - t0, models: list.data.map((m) => m.id).sort() };
  } catch (err) {
    return { ok: false, ms: Date.now() - t0, error: err.message };
  }
}

module.exports = { transcribe, cleanup, transform, matchPrompt, applyReplacements, testKey };
