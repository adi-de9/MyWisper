// Tests the API with a fake Groq server and in-memory usage (no Firebase, no real Groq).
// Run: cd functions && npm test
const test = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const { createHandler } = require('../lib/app');
const { memoryStore, today } = require('../lib/usage');
const { userKey } = require('../lib/keys');

// Minimal Express-like req/res on top of node:http (what Cloud Functions gives the handler).
function serve(handler) {
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      req.rawBody = Buffer.concat(chunks);
      req.path = new URL(req.url, 'http://x').pathname;
      req.get = (h) => req.headers[h.toLowerCase()];
      res.status = (s) => { res.statusCode = s; return res; };
      res.set = (k, v) => { res.setHeader(k, v); return res; };
      res.json = (b) => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(b)); };
      handler(req, res);
    });
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ server, url: `http://127.0.0.1:${server.address().port}` })));
}

// Fake Groq: records what it got; transcription "lasts" `duration` seconds.
function fakeGroq(state) {
  return serve((req, res) => {
    state.calls.push({ path: req.path, auth: req.get('authorization'), body: req.rawBody.toString('latin1') });
    if (req.path.endsWith('/audio/transcriptions')) return res.json({ text: 'namaste duniya', duration: state.duration, segments: [] });
    const body = JSON.parse(req.rawBody.toString());
    state.lastChat = body;
    return res.json({ choices: [{ message: { content: 'ok' } }], usage: { total_tokens: 120 } });
  });
}

async function setup(limits = {}) {
  const state = { calls: [], duration: 30 };
  const groq = await fakeGroq(state);
  const store = memoryStore(limits);
  const handler = createHandler({
    verifyToken: async (t) => { if (t.startsWith('good-')) return { uid: t.slice(5) }; throw new Error('bad token'); },
    store,
    groqKey: () => 'server-groq-key',
    dataSecret: () => Buffer.alloc(32, 1),
    groqBase: `${groq.url}/openai/v1`,
    log: { warn() {}, error() {} },
  });
  const api = await serve(handler);
  return { state, store, url: api.url, close: () => { api.server.close(); groq.server.close(); } };
}

function audioForm(model = 'whisper-large-v3') {
  const f = new FormData();
  f.append('model', model);
  f.append('response_format', 'json');
  f.append('file', new Blob([Buffer.alloc(2000, 7)], { type: 'audio/wav' }), 'speech.wav');
  return f;
}

test('sync key: same as the apps derived before (test vector)', () => {
  assert.strictEqual(userKey('test-uid', Buffer.alloc(32, 1)).toString('hex').slice(0, 8), '34c3c3e8');
});

test('needs a valid sign-in', async () => {
  const t = await setup();
  try {
    let r = await fetch(`${t.url}/dataKey`);
    assert.strictEqual(r.status, 401);
    r = await fetch(`${t.url}/dataKey`, { headers: { Authorization: 'Bearer forged' } });
    assert.strictEqual(r.status, 401);
    r = await fetch(`${t.url}/openai/v1/audio/transcriptions`, { method: 'POST', body: audioForm() });
    assert.strictEqual(r.status, 401);
    assert.strictEqual(t.state.calls.length, 0, 'nothing reached Groq');
    r = await fetch(`${t.url}/ping`);
    assert.strictEqual(r.status, 204);
  } finally { t.close(); }
});

test('dataKey returns only the caller\'s own key', async () => {
  const t = await setup();
  try {
    const a = await (await fetch(`${t.url}/dataKey`, { headers: { Authorization: 'Bearer good-alice' } })).json();
    const b = await (await fetch(`${t.url}/dataKey`, { headers: { Authorization: 'Bearer good-bob' } })).json();
    assert.strictEqual(a.key, userKey('alice', Buffer.alloc(32, 1)).toString('base64'));
    assert.notStrictEqual(a.key, b.key);
  } finally { t.close(); }
});

test('transcription goes to Groq with the server key and counts audio seconds', async () => {
  const t = await setup();
  try {
    const r = await fetch(`${t.url}/openai/v1/audio/transcriptions`, { method: 'POST', headers: { Authorization: 'Bearer good-alice' }, body: audioForm() });
    assert.strictEqual(r.status, 200);
    assert.deepStrictEqual(await r.json(), { text: 'namaste duniya', duration: 30 });
    assert.strictEqual(t.state.calls[0].auth, 'Bearer server-groq-key');
    assert.match(t.state.calls[0].body, /verbose_json/);
    assert.strictEqual((await t.store.get('alice', today())).audioSec, 30);
    const u = await (await fetch(`${t.url}/usage`, { headers: { Authorization: 'Bearer good-alice' } })).json();
    assert.strictEqual(u.audioSec, 30);
    assert.strictEqual(u.limitSec, 900);
  } finally { t.close(); }
});

test('blocks after the daily limit, other users unaffected', async () => {
  const t = await setup({ dailyAudioSec: 60 });
  try {
    const post = (who) => fetch(`${t.url}/openai/v1/audio/transcriptions`, { method: 'POST', headers: { Authorization: `Bearer good-${who}` }, body: audioForm() });
    assert.strictEqual((await post('alice')).status, 200); // 30s
    assert.strictEqual((await post('alice')).status, 200); // 60s
    const r = await post('alice');
    assert.strictEqual(r.status, 429);
    const body = await r.json();
    assert.strictEqual(body.error.code, 'daily_limit');
    assert.ok(body.resetAt > Date.now());
    assert.strictEqual(r.headers.get('x-should-retry'), 'false');
    // chat is blocked too once audio is used up
    const c = await fetch(`${t.url}/openai/v1/chat/completions`, { method: 'POST', headers: { Authorization: 'Bearer good-alice' }, body: JSON.stringify({ model: 'qwen/qwen3.8-27b', messages: [] }) });
    assert.strictEqual(c.status, 429);
    assert.strictEqual((await post('bob')).status, 200);
  } finally { t.close(); }
});

test('chat: only allowed models, output tokens capped, usage counted', async () => {
  const t = await setup();
  try {
    const chat = (body) => fetch(`${t.url}/openai/v1/chat/completions`, { method: 'POST', headers: { Authorization: 'Bearer good-alice', 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    let r = await chat({ model: 'some/expensive-model', messages: [] });
    assert.strictEqual(r.status, 400);
    r = await chat({ model: 'openai/gpt-oss-120b', messages: [{ role: 'user', content: 'hi' }], max_completion_tokens: 99999, stream: true });
    assert.strictEqual(r.status, 200);
    assert.strictEqual(t.state.lastChat.max_completion_tokens, 4096);
    assert.strictEqual(t.state.lastChat.stream, false);
    assert.strictEqual((await t.store.get('alice', today())).tokens, 120);
  } finally { t.close(); }
});

test('kill switch pauses everything', async () => {
  const t = await setup({ enabled: false });
  try {
    const r = await fetch(`${t.url}/openai/v1/audio/transcriptions`, { method: 'POST', headers: { Authorization: 'Bearer good-alice' }, body: audioForm() });
    assert.strictEqual(r.status, 503);
  } finally { t.close(); }
});
