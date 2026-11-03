// The MyWisper API: a Groq proxy with a daily free limit per user, plus each user's sync key.
// Both apps talk to it like they talked to Groq (same /openai/v1/... paths), but sign each request
// with their Firebase ID token instead of a Groq key. The Groq key and the data secret never leave the server.
//
//   GET  /ping                          wakes the server (no sign-in)
//   GET  /usage                         { audioSec, limitSec, resetAt }
//   GET  /dataKey                       { key }  the caller's own sync key (base64)
//   POST /openai/v1/audio/transcriptions  multipart, like Groq; answers { text }
//   POST /openai/v1/chat/completions      JSON, like Groq
const Busboy = require('busboy');
const { userKey } = require('./keys');
const { today, resetAt } = require('./usage');

const MODELS_AUDIO = ['whisper-large-v3', 'whisper-large-v3-turbo'];
const MODELS_CHAT = ['qwen/qwen3.8-27b', 'openai/gpt-oss-120b', 'openai/gpt-oss-20b', 'llama-3.1-8b-instant'];
const MAX_AUDIO_BYTES = 25 * 1024 * 1024;
const MAX_CHAT_BYTES = 256 * 1024;
const MAX_TOKENS = 4096;
const DEFAULT_LIMITS = { enabled: true, dailyAudioSec: 15 * 60, dailyTokens: 200_000, perMinute: 40 };

function send(res, status, body, headers = {}) {
  res.status(status);
  for (const [k, v] of Object.entries(headers)) res.set(k, v);
  if (body === undefined) return res.end();
  return res.json(body);
}

// OpenAI-style error, so the Groq SDK (Windows) shows `message`. x-should-retry stops its automatic retry.
function fail(res, status, code, message, extra = {}) {
  return send(res, status, { error: { message, type: code, code }, ...extra }, { 'x-should-retry': 'false' });
}

function parseMultipart(req) {
  return new Promise((resolve, reject) => {
    const fields = {};
    let file = null;
    const bb = Busboy({ headers: req.headers, limits: { fileSize: MAX_AUDIO_BYTES, files: 1, fields: 20 } });
    bb.on('field', (name, value) => { fields[name] = value; });
    bb.on('file', (name, stream, info) => {
      const chunks = [];
      stream.on('data', (c) => chunks.push(c));
      stream.on('end', () => { file = { name, data: Buffer.concat(chunks), filename: info.filename, mimeType: info.mimeType }; });
    });
    bb.on('error', reject);
    bb.on('close', () => resolve({ fields, file }));
    bb.end(req.rawBody);
  });
}

/**
 * deps: {
 *   verifyToken(idToken) → { uid }      (firebase-admin auth().verifyIdToken)
 *   store                               (usage.firestoreStore / memoryStore)
 *   groqKey() → string, dataSecret() → Buffer
 *   groqBase  = 'https://api.groq.com/openai/v1'
 *   fetch, now, log
 * }
 */
function createHandler(deps) {
  const { verifyToken, store, groqKey, dataSecret } = deps;
  const groqBase = deps.groqBase || 'https://api.groq.com/openai/v1';
  const fetchFn = deps.fetch || fetch;
  const now = deps.now || Date.now;
  const log = deps.log || console;

  // config/limits can change the limits without a deploy (and switch everything off in an emergency).
  let limitsCache = { at: 0, value: DEFAULT_LIMITS };
  async function limits() {
    if (now() - limitsCache.at < 60_000) return limitsCache.value;
    let doc = {};
    try { doc = (await store.limits()) || {}; } catch (err) { log.warn('limits read failed', err.message); }
    limitsCache = { at: now(), value: { ...DEFAULT_LIMITS, ...doc } };
    return limitsCache.value;
  }

  // Best-effort burst limit per server instance (the daily limit below is the real one).
  const minute = new Map();
  function tooFast(uid, perMinute) {
    const t = now();
    const m = minute.get(uid);
    if (!m || t - m.start > 60_000) { minute.set(uid, { start: t, n: 1 }); return false; }
    m.n += 1;
    return m.n > perMinute;
  }

  async function auth(req) {
    const h = req.get('authorization') || '';
    const token = h.startsWith('Bearer ') ? h.slice(7).trim() : '';
    if (!token) return null;
    try { return await verifyToken(token); } catch { return null; }
  }

  async function groqError(res, r) {
    const text = await r.text();
    let body;
    try { body = JSON.parse(text); } catch { body = { error: { message: text.slice(0, 500) || `Groq error ${r.status}` } }; }
    log.warn('groq error', r.status, body?.error?.message);
    return send(res, r.status === 401 ? 502 : r.status, body, { 'x-should-retry': r.status >= 500 ? 'true' : 'false' });
  }

  async function transcribe(req, res, uid, day) {
    if ((req.rawBody?.length || 0) > MAX_AUDIO_BYTES + 64 * 1024) return fail(res, 413, 'too_large', 'Recording is too long.');
    const { fields, file } = await parseMultipart(req);
    if (!file || !file.data.length) return fail(res, 400, 'bad_request', 'No audio file.');
    if (!MODELS_AUDIO.includes(fields.model)) return fail(res, 400, 'bad_model', `Model not allowed: ${fields.model}`);

    const form = new FormData();
    for (const [k, v] of Object.entries(fields)) if (k !== 'response_format') form.append(k, v);
    form.append('response_format', 'verbose_json'); // carries the audio length, which is what the limit counts
    form.append('file', new Blob([file.data], { type: file.mimeType || 'application/octet-stream' }), file.filename || 'speech.wav');

    const r = await fetchFn(`${groqBase}/audio/transcriptions`, {
      method: 'POST', headers: { Authorization: `Bearer ${groqKey()}` }, body: form,
    });
    if (!r.ok) return groqError(res, r);
    const out = await r.json();
    const audioSec = Number(out.duration) > 0 ? Number(out.duration) : 10;
    await store.add(uid, day, { audioSec });
    return send(res, 200, { text: out.text || '', duration: audioSec });
  }

  async function chat(req, res, uid, day) {
    if ((req.rawBody?.length || 0) > MAX_CHAT_BYTES) return fail(res, 413, 'too_large', 'Text is too long.');
    let body;
    try { body = JSON.parse(req.rawBody.toString('utf8')); } catch { return fail(res, 400, 'bad_request', 'Invalid JSON.'); }
    if (!MODELS_CHAT.includes(body.model)) return fail(res, 400, 'bad_model', `Model not allowed: ${body.model}`);
    body.stream = false;
    body.n = 1;
    delete body.max_tokens;
    body.max_completion_tokens = Math.min(Number(body.max_completion_tokens) || MAX_TOKENS, MAX_TOKENS);

    const r = await fetchFn(`${groqBase}/chat/completions`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${groqKey()}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!r.ok) return groqError(res, r);
    const out = await r.json();
    await store.add(uid, day, { tokens: out.usage?.total_tokens || 0 });
    return send(res, 200, out);
  }

  return async function handler(req, res) {
    const path = (req.path || '/').replace(/\/+$/, '') || '/';
    try {
      if (path === '/ping' || path === '/') return send(res, 204);

      const user = await auth(req);
      if (!user?.uid) return fail(res, 401, 'unauthorized', 'Sign in to MyWisper again.');
      const uid = user.uid;

      if (req.method === 'GET' && path === '/dataKey') {
        return send(res, 200, { key: userKey(uid, dataSecret()).toString('base64') }, { 'Cache-Control': 'no-store' });
      }

      const lim = await limits();
      const day = today(now());
      if (req.method === 'GET' && path === '/usage') {
        const u = await store.get(uid, day);
        return send(res, 200, { audioSec: Math.round(u.audioSec || 0), limitSec: lim.dailyAudioSec, resetAt: resetAt(now()) }, { 'Cache-Control': 'no-store' });
      }

      const isAudio = path === '/openai/v1/audio/transcriptions';
      const isChat = path === '/openai/v1/chat/completions';
      if (req.method !== 'POST' || (!isAudio && !isChat)) return fail(res, 404, 'not_found', 'Not found.');

      if (lim.enabled === false) return fail(res, 503, 'paused', 'MyWisper is paused for maintenance. Try again later.');
      if (tooFast(uid, lim.perMinute)) return fail(res, 429, 'too_fast', 'Too many requests. Wait a minute.');
      const u = await store.get(uid, day);
      if ((u.audioSec || 0) >= lim.dailyAudioSec || (u.tokens || 0) >= lim.dailyTokens) {
        return fail(res, 429, 'daily_limit', 'Daily free limit reached. It resets at midnight (UTC).', { resetAt: resetAt(now()) });
      }
      return isAudio ? await transcribe(req, res, uid, day) : await chat(req, res, uid, day);
    } catch (err) {
      log.error('api error', err);
      return fail(res, 500, 'server_error', 'Server error. Try again.');
    }
  };
}

module.exports = { createHandler, DEFAULT_LIMITS, MODELS_AUDIO, MODELS_CHAT };
