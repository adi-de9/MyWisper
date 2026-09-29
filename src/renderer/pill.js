// The always-visible MyWisper pill: an idle bar that morphs into the recording pill (Win + Ctrl),
// the "MyWisper · Rewrite" pill (text selected) and back. Also records the microphone, draws the
// waveform, and checks (with the voice detector in vad.js) whether anyone actually spoke.
const pill = document.getElementById('pill');
const label = document.getElementById('label');
const timer = document.getElementById('timer');
const menu = document.getElementById('menu');
const canvas = document.getElementById('wave');
const ctx2d = canvas.getContext('2d');

const MIC_IDLE_RELEASE_MS = 30_000; // keep the mic open briefly so back-to-back dictations start instantly
const BARS = 20;
const MORPH_MS = 200;               // a little longer than the CSS transition (180 ms)
const HINT_HTML = 'Hold <kbd>Win</kbd><kbd>Ctrl</kbd> to talk';

let stream = null;
let audioCtx = null;
let analyser = null;
let processor = null;
let releaseTimer = null;
let session = null; // { id, recorder, chunks, startedAt, peak, cancelled, stopRequested, vad }
let sessionCounter = 0;
let levels = new Array(BARS).fill(0);

Vad.load()
  .then(() => console.log('voice detector ready'))
  .catch((err) => console.error('Voice detector failed to load:', err));

async function getMic() {
  clearTimeout(releaseTimer);
  if (stream && stream.active) return stream;
  stream = await navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 },
  });
  // 16 kHz is what the voice detector (and Whisper) work with; Chromium resamples the mic for us.
  audioCtx = new AudioContext({ sampleRate: 16000 });
  const source = audioCtx.createMediaStreamSource(stream);
  analyser = audioCtx.createAnalyser();
  analyser.fftSize = 512;
  source.connect(analyser);
  // Hands 512-sample (32 ms) frames to the voice detector while recording.
  processor = audioCtx.createScriptProcessor(Vad.FRAME, 1, 1);
  processor.onaudioprocess = (e) => session?.vad?.push(new Float32Array(e.inputBuffer.getChannelData(0)));
  source.connect(processor);
  processor.connect(audioCtx.destination); // required for processing to run; output stays silent
  return stream;
}

function releaseMicLater() {
  clearTimeout(releaseTimer);
  releaseTimer = setTimeout(() => {
    stream?.getTracks().forEach((t) => t.stop());
    audioCtx?.close();
    stream = audioCtx = analyser = processor = null;
  }, MIC_IDLE_RELEASE_MS);
}

// ---------- states + morph ----------
// state: idle | off (hidden bar) | hint | recording | processing | error | info | select | await
let state = 'idle';
let morphTimer = null;

// Tells main how big the window must be (it anchors it to the bottom/top centre of the screen).
function reportSize(w, h) {
  const pad = 16; // body padding on both sides
  const menuOpen = !menu.hidden;
  const width = Math.max(w ?? pill.offsetWidth, menuOpen ? menu.offsetWidth : 0) + pad;
  const height = (h ?? pill.offsetHeight) + (menuOpen ? menu.offsetHeight + 6 : 0) + pad;
  window.mywisper.setPillSize({ width: Math.ceil(width), height: Math.ceil(height) });
}

// Switch state with a smooth size change: measure the new natural size, animate from the old one.
function setView(next, text = '') {
  const from = { w: pill.offsetWidth, h: pill.offsetHeight };
  pill.classList.add('no-anim');
  pill.style.width = '';
  pill.style.height = '';
  state = next;
  pill.dataset.state = next;
  if (next === 'hint') label.innerHTML = HINT_HTML;
  else label.textContent = text;
  if (next !== 'select') setMenu(false, false);
  if (next === 'recording') timer.textContent = '0:00';
  const to = { w: pill.offsetWidth, h: pill.offsetHeight };

  // Start at the old size, then let CSS animate to the new one.
  pill.style.width = `${from.w}px`;
  pill.style.height = `${from.h}px`;
  void pill.offsetWidth; // apply before re-enabling transitions
  pill.classList.remove('no-anim');
  pill.style.width = `${to.w}px`;
  pill.style.height = `${to.h}px`;

  // Grow the window right away (so nothing is clipped); shrink it once the animation is done.
  reportSize(Math.max(from.w, to.w), Math.max(from.h, to.h));
  clearTimeout(morphTimer);
  morphTimer = setTimeout(() => {
    pill.style.width = ''; // back to natural size (e.g. the timer text can change width)
    pill.style.height = '';
    reportSize();
  }, MORPH_MS);
}

function setMenu(open, report = true) {
  menu.hidden = !open;
  pill.classList.toggle('open', open);
  if (report) reportSize();
  if (open) console.log('menu-open'); // lets the debug log report button positions
}

// ---------- the Rewrite menu (built from the prompt library) ----------
const ICONS = {
  voice: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21"/>',
  prompt: '<path d="M12 3l1.8 5.4L19 10l-5.2 1.6L12 17l-1.8-5.4L5 10l5.2-1.6z"/><path d="M19 3v4M17 5h4M5 17v3M3.5 18.5h3"/>',
  polish: '<path d="M4 20L14 10"/><path d="M15 3v3M15 12v3M11 7.5h3M17 7.5h3"/><path d="M13.5 6l3 3"/>',
  grammar: '<path d="M4 12.5l5 5L20 6.5"/>',
  translate: '<path d="M4 5h9M8.5 3v2M11 5c-1 4-3.5 7-7 9M6 8.5c1.2 2.4 3 4.2 5.5 5.2"/><path d="M13 21l4-9 4 9M14.5 17.5h5"/>',
  eod: '<rect x="5" y="4" width="14" height="17" rx="2"/><path d="M9 4V3h6v1M9 10h6M9 14h6M9 18h3"/>',
  default: '<path d="M4 6h16M4 12h16M4 18h10"/>',
};

function menuButton(id, name, description) {
  const b = document.createElement('button');
  b.dataset.action = id;
  b.innerHTML = `<svg viewBox="0 0 24 24">${ICONS[id] || ICONS.default}</svg><b></b><small></small>`;
  b.querySelector('b').textContent = name;
  b.querySelector('small').textContent = description || '';
  return b;
}

window.mywisper.onPillMenu((prompts) => {
  const head = document.createElement('div');
  head.className = 'menu-head';
  head.textContent = 'Rewrite selection';
  menu.replaceChildren(
    head,
    menuButton('voice', 'Say what to do', 'Then hold Win + Ctrl and speak, e.g. "make EOD"'),
    document.createElement('hr'),
    ...prompts.map((p) => menuButton(p.id, p.name, p.description)),
  );
  if (!menu.hidden) reportSize();
});

// ---------- clicks ----------
// Clicks are handled by the main process (it sees every click system-wide); Windows doesn't always
// deliver clicks to this never-focused window. main.js calls pillHit(x, y) with window coordinates.
window.pillHit = (x, y) => {
  const target = document.elementFromPoint(x, y);
  if (!target) return null;
  // The idle bar is tiny: any click in its window (including the padding around it) opens the dashboard.
  if (state === 'idle' || state === 'hint') return 'dashboard';
  if (state === 'await') return target.closest('#cancel') ? 'cancel' : null;
  if (state !== 'select') return null;
  const btn = target.closest('button[data-action]');
  if (btn && !menu.hidden) {
    setMenu(false);
    return btn.dataset.action; // "voice" or a prompt id: main runs it
  }
  if (pill.contains(target)) {
    setMenu(menu.hidden); // the whole pill opens/closes the menu
    return 'toggle';
  }
  return null;
};

window.mywisper.onPillLayout(({ position }) => document.body.classList.toggle('top', position === 'top'));
// Main tells us what to show.
window.mywisper.onPillState(({ state: next, text }) => setView(next, text));

// ---------- waveform ----------
function draw() {
  if (!session || !analyser) return;
  const data = new Float32Array(analyser.fftSize);
  analyser.getFloatTimeDomainData(data);
  let sum = 0;
  for (const v of data) sum += v * v;
  const rms = Math.sqrt(sum / data.length);
  session.peak = Math.max(session.peak, rms);
  levels.push(Math.min(1, rms * 8));
  levels.shift();

  const { width, height } = canvas;
  ctx2d.clearRect(0, 0, width, height);
  ctx2d.fillStyle = '#ededed';
  const barW = width / BARS;
  levels.forEach((lvl, i) => {
    const h = Math.max(2, lvl * height);
    ctx2d.fillRect(Math.round(i * barW + barW / 2 - 1), Math.round((height - h) / 2), 2, Math.round(h));
  });
  const sec = Math.floor((performance.now() - session.startedAt) / 1000);
  timer.textContent = `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`;
  requestAnimationFrame(draw);
}

// ---------- recording ----------
window.mywisper.onRecordStart(async () => {
  const id = ++sessionCounter;
  session = {
    id, recorder: null, chunks: [], startedAt: performance.now(), peak: 0,
    cancelled: false, stopRequested: false, vad: null,
  };
  levels = new Array(BARS).fill(0);
  setView('recording');
  try {
    await getMic();
  } catch (err) {
    setView('error', 'Mic blocked');
    window.mywisper.sendAudio({ error: `Microphone error: ${err.message}` });
    session = null;
    return;
  }
  if (!session || session.id !== id) return; // cancelled while the mic was opening
  if (Vad.ready) session.vad = Vad.createStream();

  const recorder = new MediaRecorder(stream, { mimeType: 'audio/webm;codecs=opus' });
  session.recorder = recorder;
  recorder.ondataavailable = (e) => e.data.size && session?.chunks.push(e.data);
  recorder.onstop = async () => {
    const s = session;
    session = null;
    releaseMicLater();
    if (!s || s.cancelled) return;
    const vad = s.vad ? await s.vad.result() : null;
    const blob = new Blob(s.chunks, { type: 'audio/webm' });
    window.mywisper.sendAudio({
      audio: new Uint8Array(await blob.arrayBuffer()),
      durationSec: (performance.now() - s.startedAt) / 1000,
      peak: s.peak,
      speechMs: vad ? vad.speechMs : null,   // null = detector not available
      vadMaxProb: vad ? vad.maxProb : null,
    });
  };
  recorder.start(250);
  requestAnimationFrame(draw);
  if (session.stopRequested) recorder.stop(); // key was released while the mic was opening
});

window.mywisper.onRecordStop(() => {
  if (!session) return;
  setView('processing', 'Transcribing');
  if (session.recorder?.state === 'recording') session.recorder.stop();
  else session.stopRequested = true;
});

window.mywisper.onRecordCancel(() => {
  if (!session) return;
  session.cancelled = true;
  if (session.recorder?.state === 'recording') session.recorder.stop();
  else session = null;
  releaseMicLater();
});

reportSize();
