// MyWisper - entry point. Hold Win + Ctrl, speak, release → clean text is typed into the focused app.
// Select text → MyWisper · Rewrite → a saved prompt (or a spoken instruction) rewrites it.
const { app, BrowserWindow, ipcMain, screen, session, clipboard, powerMonitor } = require('electron');
const path = require('path');

if (!app.isPackaged) require('dotenv').config({ path: path.join(__dirname, '..', '..', '.env'), quiet: true });

const config = require('./config');
const history = require('./history');
const hotkey = require('./hotkey');
const groq = require('./groq');
const { pasteText, copyText } = require('./paste');
const tray = require('./tray');
const winfo = require('./winfo');
const { detect } = require('./context');
const selection = require('./selection');
const transform = require('./transform');
const library = require('./library');
const auth = require('./auth');
const sync = require('./sync');
const api = require('./api');

const RENDERER = path.join(__dirname, '..', 'renderer');
const EDGE_GAP = 6;                 // distance between the pill window and the screen edge / taskbar
const IDLE_SIZE = { width: 56, height: 25 }; // 40 × 9 bar + page padding
const PREVIEW_W = 580;
const SELECTION_HIDE_MS = 8000;
const COMMAND_WAIT_MS = 15000;      // how long "Say what to do" waits for Win + Ctrl
const FULLSCREEN_CHECK_MS = 1500;
const MIN_AUDIO_SEC = 0.4;
const SILENCE_PEAK = 0.01;  // fallback only: RMS below this for the whole clip = nobody spoke
const NO_VOICE_PROB = 0.35;  // voice detector's best score below this = nobody spoke (noise scores ~0.1-0.3)
const CLEAR_VOICE_MS = 1000; // at least this much clear speech → trust Whisper's text fully
const DEBUG = !!process.env.MYWISPER_DEBUG;
const NO_PASTE = !!process.env.MYWISPER_NOPASTE; // testing: log the text instead of typing it
// Important events always go to %APPDATA%\MyWisper\mywisper.log (for troubleshooting); chatty ones only in debug mode.
const LOG_FILE = path.join(app.getPath('userData'), 'mywisper.log');
function log(...a) {
  if (DEBUG) console.log('[mywisper]', ...a);
  try {
    const fs = require('fs');
    if (fs.existsSync(LOG_FILE) && fs.statSync(LOG_FILE).size > 1_000_000) fs.renameSync(LOG_FILE, `${LOG_FILE}.old`);
    fs.appendFileSync(LOG_FILE, `${new Date().toISOString()} ${a.join(' ')}\n`);
  } catch { /* logging must never break the app */ }
}
const trace = (...a) => DEBUG && console.log('[mywisper]', ...a);

let pillWin = null;
let dashWin = null;
let previewWin = null;
// What the pill is doing: idle | dictation | select (text selected) | working | await (waiting for a
// spoken instruction) | message (short info/error before going back to idle)
let pillMode = 'idle';
let pillSize = { ...IDLE_SIZE };
let pillDisplayId = null;   // display the expanded pill is on (idle bar always sits on the primary display)
let hinting = false;        // idle bar is showing the hover hint
let barOff = false;         // idle bar hidden (fullscreen app in front, or turned off in Settings)
let collapseTimer = null;
let recordingKind = 'dictation'; // 'dictation' | 'command' (spoken instruction for selected text)
let dictationContext = Promise.resolve(null); // app we're dictating into (for smart formatting)

// Only one copy of the app may run (two keyboard hooks would type everything twice).
if (!app.requestSingleInstanceLock()) app.quit();
app.on('second-instance', () => openDashboard('overview'));

// Let the pill's audio start without a click.
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
// Testing: MYWISPER_FAKE_MIC=<file.wav> makes that file the microphone (played once from when the mic opens),
// so tests get the same audio every time without speakers or room noise.
if (process.env.MYWISPER_FAKE_MIC) {
  app.commandLine.appendSwitch('use-fake-device-for-media-stream');
  app.commandLine.appendSwitch('use-file-for-fake-audio-capture', `${process.env.MYWISPER_FAKE_MIC}%noloop`);
}

// ---------- the pill: always visible, it only changes size ----------
// It is never hidden and shown again: on Windows that made clicks stop working (the second-rewrite bug).
function createPill() {
  pillWin = new BrowserWindow({
    ...IDLE_SIZE, show: false, frame: false, transparent: true, resizable: false,
    movable: false, skipTaskbar: true, focusable: false, alwaysOnTop: true, hasShadow: false,
    webPreferences: { preload: path.join(RENDERER, 'preload.js'), backgroundThrottling: false },
  });
  pillWin.setAlwaysOnTop(true, 'screen-saver');
  pillWin.loadFile(path.join(RENDERER, 'pill.html'));
  pillWin.webContents.on('did-finish-load', () => {
    pillWin.webContents.send('pill:layout', { position: pillTop() ? 'top' : 'bottom' });
    sendPillMenu();
    refreshIdleBar();
    placePill();
    pillWin.showInactive(); // never takes focus from the app you're typing in
  });
  if (DEBUG) {
    pillWin.webContents.on('console-message', (e) => {
      trace('pill:', e.message);
      if (e.message === 'menu-open') logButtonPositions(pillWin, '#pill, .menu button');
    });
  }
  // Windows can push "always on top" windows down (other topmost windows, the taskbar);
  // re-raise often while active, and every ~2 s while idle.
  let tick = 0;
  setInterval(() => {
    if (pillMode !== 'idle' || ++tick % 7 === 0) {
      pillWin.setAlwaysOnTop(true, 'screen-saver', 1);
      pillWin.moveTop();
    }
  }, 300);
}

const pillTop = () => config.getConfig().pillPosition === 'top';

function pillDisplay() {
  if (pillMode !== 'idle' && pillDisplayId != null) {
    const d = screen.getAllDisplays().find((x) => x.id === pillDisplayId);
    if (d) return d;
  }
  return screen.getPrimaryDisplay();
}

// Puts the window at the bottom (or top) centre of the display, sized to the pill's content.
function placePill() {
  if (!pillWin) return;
  const { workArea } = pillDisplay();
  const { width, height } = pillSize;
  pillWin.setBounds({
    x: Math.round(workArea.x + (workArea.width - width) / 2),
    y: Math.round(pillTop() ? workArea.y + EDGE_GAP : workArea.y + workArea.height - height - EDGE_GAP),
    width, height,
  });
}

function pillState(state, text) {
  pillWin?.webContents.send('pill:state', { state, text });
}

// Leave idle: the pill moves to the screen the mouse is on and becomes clickable.
function expandPill(mode) {
  clearTimeout(collapseTimer);
  hinting = false;
  const before = pillDisplayId;
  pillMode = mode;
  pillDisplayId = screen.getDisplayNearestPoint(screen.getCursorScreenPoint()).id;
  pillWin.setIgnoreMouseEvents(false);
  if (before !== pillDisplayId) placePill();
}

// Back to the idle bar (after an optional delay, e.g. to show an error first).
function collapsePill(delay = 0) {
  clearTimeout(collapseTimer);
  collapseTimer = setTimeout(() => {
    pillMode = 'idle';
    hinting = false;
    pillState(barOff ? 'off' : 'idle');
    pillWin.setIgnoreMouseEvents(barOff);
  }, delay);
}

// Show a short message in the pill, then go back to the bar.
function pillMessage(text, kind = 'error', ms = 2000) {
  expandPill('message');
  pillState(kind, text);
  collapsePill(ms);
}

function cursorOver(win) {
  if (!win?.isVisible()) return false;
  const c = screen.getCursorScreenPoint();
  const b = win.getBounds();
  return c.x >= b.x && c.x <= b.x + b.width && c.y >= b.y && c.y <= b.y + b.height;
}
const cursorOverPill = () => !barOff || pillMode !== 'idle' ? cursorOver(pillWin) : false;

// Hover over the idle bar → show "Hold Win + Ctrl to talk".
function watchHover() {
  setInterval(() => {
    if (pillMode !== 'idle' || barOff) return;
    const over = cursorOverPill();
    if (over !== hinting) {
      hinting = over;
      pillState(over ? 'hint' : 'idle');
    }
  }, 100);
}

// Hide the idle bar while a fullscreen app (video, game, slides) is in front.
function isFullscreen(fg) {
  if (!fg || ['explorer', 'mywisper', 'electron'].includes((fg.process || '').toLowerCase())) return false;
  const r = screen.screenToDipRect(null, { x: fg.x, y: fg.y, width: fg.w, height: fg.h });
  const d = screen.getDisplayMatching(r).bounds;
  return r.x <= d.x && r.y <= d.y && r.x + r.width >= d.x + d.width && r.y + r.height >= d.y + d.height;
}

async function refreshIdleBar() {
  const fs = isFullscreen(await winfo.foreground());
  const off = fs || config.getConfig().showIdleLine === false;
  if (off === barOff) return;
  barOff = off;
  log(`idle bar ${off ? 'hidden' : 'shown'}${fs ? ' (fullscreen app)' : ''}`);
  if (pillMode === 'idle') {
    hinting = false;
    pillState(off ? 'off' : 'idle');
    pillWin.setIgnoreMouseEvents(off);
  }
}

function sendPillMenu() {
  const pinned = library.list().filter((p) => p.pinned).map(({ id, name, description }) => ({ id, name, description }));
  pillWin?.webContents.send('pill:menu', pinned);
}

function openDashboard(tab = 'overview') {
  if (dashWin && !dashWin.isDestroyed()) {
    dashWin.webContents.send('dashboard:tab', tab);
    if (dashWin.isMinimized()) dashWin.restore();
    dashWin.show();
    dashWin.focus();
    return;
  }
  dashWin = new BrowserWindow({
    width: 1120, height: 780, minWidth: 760, minHeight: 560, show: false,
    title: 'MyWisper', icon: path.join(__dirname, '..', '..', 'assets', 'icon.png'),
    autoHideMenuBar: true, backgroundColor: '#000000',
    webPreferences: { preload: path.join(RENDERER, 'preload.js') },
  });
  dashWin.loadFile(path.join(RENDERER, 'dashboard.html'), { hash: tab });
  dashWin.once('ready-to-show', () => {
    dashWin.show();
    if (DEBUG) setTimeout(() => logButtonPositions(dashWin, '#todayEod'), 800);
  });
  dashWin.on('closed', () => { dashWin = null; });
}

// ---------- dictation pipeline ----------
function estimateCost({ cfg, audioSec, promptTokens, completionTokens }) {
  const sttPrice = cfg.prices.sttPerHour[cfg.sttModel] ?? 0;
  const llmPrice = cfg.prices.llmPerMillion[cfg.llmModel] ?? { input: 0, output: 0 };
  const billedSec = Math.max(audioSec, 10); // Groq bills at least 10 s per request
  return (billedSec / 3600) * sttPrice
    + (promptTokens / 1e6) * llmPrice.input + (completionTokens / 1e6) * llmPrice.output;
}

async function processAudio({ audio, durationSec, peak, speechMs, vadMaxProb, error }) {
  const cfg = config.getConfig();
  const dictionary = config.getDictionary();
  const kind = recordingKind;
  const releasedAt = Date.now();
  log(`audio (${kind}): ${audio?.length || 0} bytes, ${durationSec?.toFixed(2)} s, peak ${peak?.toFixed(4)}, ` +
    `voice ${speechMs ?? 'n/a'} ms (max prob ${vadMaxProb?.toFixed(2) ?? 'n/a'})`, error || '');

  if (error) {
    pillMessage('Mic error');
    history.add({ status: 'error', error, audioSec: 0, words: 0 });
    notifyDashboard();
    return;
  }
  // No real voice → don't call Whisper (it would invent text like "Thank you." from noise).
  // vadMaxProb is null only if the voice detector failed to load; then fall back to a loudness check.
  const noVoice = vadMaxProb != null ? vadMaxProb < NO_VOICE_PROB : peak < SILENCE_PEAK;
  if (durationSec < MIN_AUDIO_SEC || noVoice || !audio?.length) {
    if (kind === 'command') return retryCommand();
    pillMessage('No speech heard', 'info', 1200);
    return;
  }

  const context = kind === 'dictation' ? await dictationContext : null; // looked up while you were speaking
  const entry = {
    kind: 'dictation', app: context?.app, category: context?.category,
    audioSec: +durationSec.toFixed(2), sttModel: cfg.sttModel, llmModel: cfg.llmModel,
    rawText: '', cleanText: '', sttMs: 0, llmMs: 0, pasteMs: 0, totalMs: 0,
    promptTokens: 0, completionTokens: 0, words: 0, wpm: 0, costUsd: 0, status: 'ok',
  };

  try {
    const stt = await groq.transcribe({
      audio: Buffer.from(audio), fileName: 'speech.webm',
      model: cfg.sttModel, language: cfg.language, dictionary,
      strict: speechMs == null || speechMs < CLEAR_VOICE_MS, // unclear voice → drop typical made-up phrases
    });
    entry.rawText = stt.text;
    entry.sttMs = stt.ms;
    if (!stt.text) {
      if (kind === 'command') return retryCommand();
      pillMessage('No speech heard', 'info', 1200);
      return;
    }
    // A spoken instruction for selected text: no cleanup, no paste.
    if (kind === 'command') return handleCommand(stt.text);

    let finalText = stt.text;
    try {
      const llm = await groq.cleanup({
        text: stt.text, model: cfg.llmModel, dictionary,
        context: cfg.smartFormat ? context : null,
      });
      Object.assign(entry, { llmMs: llm.ms, promptTokens: llm.promptTokens, completionTokens: llm.completionTokens });
      if (llm.text) finalText = llm.text;
    } catch (err) {
      // Cleanup failed → still type the raw Whisper text rather than losing it.
      entry.status = 'raw';
      entry.error = `Cleanup failed: ${err.message}`;
    }
    finalText = groq.applyReplacements(finalText, dictionary.replacements);
    entry.cleanText = finalText;

    log(`context: ${context?.app} (${context?.category})`);
    log(`whisper ${entry.sttMs} ms: ${entry.rawText}`);
    log(`cleanup ${entry.llmMs} ms: ${finalText}`);
    const t0 = Date.now();
    if (NO_PASTE) log('paste skipped (MYWISPER_NOPASTE)');
    else await pasteText(finalText);
    entry.pasteMs = Date.now() - t0;
    collapsePill();
  } catch (err) {
    entry.status = 'error';
    entry.error = err.message;
    // Keep whatever we have on the clipboard so nothing is lost.
    if (entry.rawText) clipboard.writeText(entry.cleanText || entry.rawText).catch(() => {});
    pillMessage(groq.errorMessage(err), 'error', 2500);
    if (kind === 'command') {
      pendingCommand = null;
      return;
    }
  }

  entry.totalMs = Date.now() - releasedAt;
  entry.words = (entry.cleanText || '').split(/\s+/).filter(Boolean).length;
  entry.wpm = entry.words ? Math.round(entry.words / (durationSec / 60)) : 0;
  entry.costUsd = estimateCost({ cfg, audioSec: durationSec, ...entry });
  history.add(entry);
  notifyDashboard();
}

// ---------- live typing ----------
// With "Live typing" on, a quick tap of Win + Ctrl starts it: the pill sends every sentence after a
// short pause; each one is transcribed and cleaned straight away and typed in order while the user
// keeps talking. A tap of Win + Ctrl, Esc or a click on the pill stops it.
const LIVE_QUIET_STOP_MS = 60_000;   // stop after a minute without a sentence
const LIVE_MAX_MS = 15 * 60_000;
let live = null; // { id, context, typing, sentences, typed, stopping, error, timers }
let liveCounter = 0;

function startLive() {
  if (!auth.user()) return needSignIn();
  const s = { id: ++liveCounter, typing: Promise.resolve(), sentences: 0, typed: 0, stopping: false, error: null,
    context: winfo.foreground().then(detect).catch(() => null) };
  live = s;
  log('live: start');
  expandPill('live');
  tray.setRecording(true);
  pillWin.webContents.send('record:cancel'); // the normal recording that began when the keys went down
  pillWin.webContents.send('live:start', { sessionId: s.id });
  s.maxTimer = setTimeout(() => stopLive('15 min limit'), LIVE_MAX_MS);
  keepLiveAwake(s);
}

function keepLiveAwake(s) {
  clearTimeout(s.quietTimer);
  s.quietTimer = setTimeout(() => stopLive('quiet for a minute'), LIVE_QUIET_STOP_MS);
}

function stopLive(reason) {
  const s = live;
  if (!s || s.stopping) return;
  s.stopping = true;
  clearTimeout(s.quietTimer);
  clearTimeout(s.maxTimer);
  log(`live: stop (${reason}), ${s.sentences} sentence(s)`);
  tray.setRecording(false);
  pillState('processing', 'Typing');
  pillWin.webContents.send('live:stop');
  s.doneTimer = setTimeout(() => finishLive(s), 20_000); // safety net if the pill never answers
}

// All sentences typed: back to the idle bar (or show the error that stopped it).
function finishLive(s) {
  clearTimeout(s.doneTimer);
  if (live !== s) return;
  live = null;
  if (s.error) pillMessage(s.error, 'error', 2500);
  else if (pillMode === 'live') collapsePill();
}

function onLiveSentence(msg) {
  const s = live;
  if (!s || msg.sessionId !== s.id) return;
  if (msg.error) {
    log('live:', msg.error);
    s.error = 'Mic error';
    stopLive('mic error');
    return finishLive(s);
  }
  if (msg.done) {
    s.typing = s.typing.then(() => finishLive(s));
    return;
  }
  keepLiveAwake(s);
  const seq = ++s.sentences;
  // Transcribe now (in parallel with earlier sentences), but type strictly in the order spoken.
  const text = liveTranscribe(s, msg, seq);
  s.typing = s.typing.then(async () => {
    const t = await text;
    if (!t) return;
    const out = s.typed > 0 && !/^[\s.,!?;:)\]]/.test(t) ? ` ${t}` : t;
    if (NO_PASTE) log(`paste skipped (MYWISPER_NOPASTE): ${out}`);
    else await pasteText(out);
    s.typed++;
  }).catch((err) => log('live typing failed:', err.message));
}

async function liveTranscribe(s, { audio, durationSec, speechMs, vadMaxProb }, seq) {
  if (vadMaxProb < NO_VOICE_PROB) return '';
  const cfg = config.getConfig();
  const dictionary = config.getDictionary();
  const context = await s.context;
  const t0 = Date.now();
  const entry = {
    kind: 'dictation', live: true, app: context?.app, category: context?.category,
    audioSec: +durationSec.toFixed(2), sttModel: cfg.sttModel, llmModel: cfg.llmModel,
    rawText: '', cleanText: '', sttMs: 0, llmMs: 0, pasteMs: 0, totalMs: 0,
    promptTokens: 0, completionTokens: 0, words: 0, wpm: 0, costUsd: 0, status: 'ok',
  };
  try {
    const stt = await groq.transcribe({
      audio: Buffer.from(audio), fileName: 'speech.wav',
      model: cfg.sttModel, language: cfg.language, dictionary, strict: speechMs < CLEAR_VOICE_MS,
    });
    entry.rawText = stt.text;
    entry.sttMs = stt.ms;
    if (!stt.text) return '';
    let text = stt.text;
    try {
      const llm = await groq.cleanup({
        text: stt.text, model: cfg.llmModel, dictionary,
        context: cfg.smartFormat ? context : null,
      });
      Object.assign(entry, { llmMs: llm.ms, promptTokens: llm.promptTokens, completionTokens: llm.completionTokens });
      if (llm.text) text = llm.text;
    } catch (err) {
      entry.status = 'raw'; // cleanup failed → still type what Whisper heard
      entry.error = `Cleanup failed: ${err.message}`;
    }
    text = groq.applyReplacements(text, dictionary.replacements);
    entry.cleanText = text;
    log(`live #${seq}: whisper ${entry.sttMs} ms, cleanup ${entry.llmMs} ms: ${text}`);
    return text;
  } catch (err) {
    entry.status = 'error';
    entry.error = err.message;
    log(`live #${seq} failed: ${err.message}`);
    s.error = groq.errorMessage(err);
    stopLive('error');
    return '';
  } finally {
    entry.totalMs = Date.now() - t0;
    entry.words = (entry.cleanText || '').split(/\s+/).filter(Boolean).length;
    entry.wpm = entry.words ? Math.round(entry.words / (durationSec / 60)) : 0;
    entry.costUsd = estimateCost({ cfg, audioSec: durationSec, ...entry });
    if (entry.rawText || entry.status === 'error') {
      history.add(entry);
      notifyDashboard();
    }
  }
}

// Debug only (used by automated UI tests): logs the screen position (physical pixels) of buttons.
async function logButtonPositions(win, selector) {
  if (!DEBUG) return;
  const json = await win.webContents.executeJavaScript(`JSON.stringify([...document.querySelectorAll(${JSON.stringify(selector)})]
    .filter((e) => e.offsetParent).map((e) => { const r = e.getBoundingClientRect();
      return { id: e.id || e.dataset.action || e.dataset.cmd, x: r.x + r.width / 2, y: r.y + r.height / 2 }; }))`);
  const b = win.getBounds();
  const sf = screen.getDisplayMatching(b).scaleFactor;
  for (const r of JSON.parse(json)) trace(`button ${r.id} ${Math.round((b.x + r.x) * sf)} ${Math.round((b.y + r.y) * sf)}`);
}

// MyWisper needs a Google account (for sync): without one, Win + Ctrl only points to the sign-in screen.
let signInHintAt = 0;
function needSignIn() {
  if (Date.now() - signInHintAt < 3000) return;
  signInHintAt = Date.now();
  pillMessage('Sign in to MyWisper first', 'info', 2500);
  openDashboard('overview');
}

function sendAccount() {
  if (dashWin && !dashWin.isDestroyed()) dashWin.webContents.send('auth:changed', accountInfo());
}
const accountInfo = () => ({ user: auth.user(), online: auth.isOnline(), sync: sync.status() });

function notifyDashboard() {
  if (dashWin && !dashWin.isDestroyed()) dashWin.webContents.send('history:changed');
}

// ---------- selection tool: select text → MyWisper · Rewrite → prompt → preview ----------
let selectionCtx = null;   // { fg, text } of the latest selection
let selHideTimer = null;
let pendingCommand = null; // { text, fg, timer } while waiting for a spoken instruction
let job = null;            // { promptId, extra, text, fg, instruction, label, canReplace, result }

function onSelection(sel) {
  if (!auth.user()) return;
  // A short pill message ("Timed out", "No speech heard") never blocks a new selection.
  if (hotkey.recording || !['idle', 'select', 'message'].includes(pillMode) || previewWin?.isVisible()) return;
  if (pillMode === 'message') pillMode = 'idle';
  selectionCtx = sel;
  log(`selection in ${sel.fg.process}: ${sel.text ? `${sel.text.length} chars (UI Automation)` : 'unknown text (will use Ctrl+C)'}`);
  // Pill already showing: just keep the newest selection. Re-drawing it would close an open menu.
  if (pillMode === 'select') {
    scheduleSelectionHide(SELECTION_HIDE_MS);
    return;
  }
  expandPill('select');
  pillState('select', 'MyWisper');
  scheduleSelectionHide(SELECTION_HIDE_MS);
  if (DEBUG) setTimeout(() => logButtonPositions(pillWin, '#pill'), 400);
}

// Back to the bar after a while, but never while the mouse is on the pill.
function scheduleSelectionHide(ms) {
  clearTimeout(selHideTimer);
  selHideTimer = setTimeout(() => {
    if (pillMode !== 'select') return;
    if (cursorOverPill()) scheduleSelectionHide(1500);
    else endSelectionMode('timeout');
  }, ms);
}

function endSelectionMode(reason = '') {
  if (pillMode !== 'select') return;
  log('selection pill closed', reason);
  clearTimeout(selHideTimer);
  collapsePill();
}

// Reads the selected text for an action (UI Automation, else Ctrl+C). Returns '' if nothing is selected.
async function readSelection() {
  const sel = selectionCtx;
  if (!sel || pillMode !== 'select') return null;
  pillMode = 'working';
  clearTimeout(selHideTimer);
  pillState('processing', 'Reading selection');
  const text = await transform.getSelectedText(sel);
  log(`selected text: ${text.length} chars${sel.text ? '' : ' (via Ctrl+C)'}`);
  if (!text.trim()) {
    pillMessage('No text selected');
    return null;
  }
  return { text, fg: sel.fg };
}

// A prompt from the Rewrite menu.
async function runMenuPrompt(promptId) {
  const sel = await readSelection();
  if (!sel) return;
  collapsePill();
  job = { promptId, extra: '', text: sel.text, fg: sel.fg, canReplace: true };
  startResult();
}

// "Say what to do": grab the selection now, then wait for Win + Ctrl.
async function startVoiceCommand() {
  const sel = await readSelection();
  if (!sel) return;
  pendingCommand = { ...sel, timer: setTimeout(() => cancelCommand('timeout'), COMMAND_WAIT_MS) };
  pillMode = 'await';
  pillState('await', 'Hold Win + Ctrl and say what to do');
  log('waiting for a spoken instruction');
  if (DEBUG) setTimeout(() => logButtonPositions(pillWin, '#cancel'), 400);
}

// Nothing was heard for the instruction: keep waiting so the user can try again.
function retryCommand() {
  if (!pendingCommand) return pillMessage('No speech heard', 'info', 1200);
  clearTimeout(pendingCommand.timer);
  pendingCommand.timer = setTimeout(() => cancelCommand('timeout'), COMMAND_WAIT_MS);
  pillMode = 'await';
  pillState('await', "Didn't catch that. Hold Win + Ctrl again");
  log('instruction not heard, waiting again');
}

function cancelCommand(reason) {
  if (!pendingCommand) return;
  clearTimeout(pendingCommand.timer);
  pendingCommand = null;
  log('spoken instruction cancelled', reason);
  if (pillMode === 'await') pillMessage(reason === 'timeout' ? 'Timed out' : 'Cancelled', 'info', 1200);
}

// The spoken instruction arrived: find the matching saved prompt, then ask before applying it.
async function handleCommand(instruction) {
  const cmd = pendingCommand;
  pendingCommand = null;
  if (!cmd) return collapsePill();
  clearTimeout(cmd.timer);
  log(`instruction: "${instruction}"`);
  pillState('processing', 'Finding your prompt');
  const prompts = library.list();
  let match = { id: null, extra: instruction };
  try {
    match = await groq.matchPrompt({ instruction, prompts });
  } catch (err) {
    log(`prompt match failed: ${err.message}`);
  }
  log(`matched: ${match.id || 'none'}${match.extra ? ` + "${match.extra}"` : ''}`);
  collapsePill();
  job = { promptId: match.id, extra: match.extra, text: cmd.text, fg: cmd.fg, instruction, canReplace: true };
  if (match.id) {
    showPreview({ view: 'confirm', said: instruction, promptName: library.get(match.id)?.name, extra: match.extra });
  } else {
    showPreview({ view: 'pick', said: instruction, prompts: promptChoices() });
  }
}

const promptChoices = (except) => library.list().filter((p) => p.id !== except)
  .map(({ id, name, description }) => ({ id, name, description }));

// Build an EOD report from everything dictated today.
function createTodayEod() {
  const now = new Date().toDateString();
  const notes = history.list()
    .filter((e) => e.kind !== 'transform' && e.status !== 'error' && e.cleanText && new Date(e.time).toDateString() === now)
    .reverse() // oldest first
    .map((e) => e.cleanText);
  if (!notes.length) {
    pillMessage('No dictations today yet', 'info', 2500);
    return false;
  }
  closePreview();
  job = { promptId: 'eod', extra: '', text: notes.join('\n'), fg: null, label: 'EOD report (from today)', canReplace: false };
  startResult();
  return true;
}

function startResult() {
  if (!job) return;
  showPreview({
    view: 'result', title: transform.label(job), meta: job.fg ? detect(job.fg).app : '',
    original: job.text, originalLabel: job.canReplace ? 'Selected text' : "Today's dictations",
    result: '', status: '', canReplace: job.canReplace,
  });
  generate();
}

async function generate() {
  if (!job) return;
  const current = job;
  sendPreview({ loading: true, status: '' });
  try {
    const out = await transform.run(current);
    if (job !== current) return; // preview was closed or restarted meanwhile
    current.result = out.text;
    const where = current.fg ? `${detect(current.fg).app} · ` : '';
    sendPreview({ result: out.text, loading: false, meta: `${where}${(out.ms / 1000).toFixed(1)} s` });
    log(`${current.promptId || 'custom'} ${out.ms} ms`);
    trace(`result:\n${out.text}`);
    if (DEBUG) setTimeout(() => logButtonPositions(previewWin, 'button[data-cmd]'), 300);
  } catch (err) {
    log(`${current.promptId || 'custom'} failed: ${err.message}`);
    sendPreview({ loading: false, status: `Couldn't rewrite: ${groq.errorMessage(err)}.`, statusKind: 'bad' });
  }
  notifyDashboard();
}

async function previewCommand(cmd) {
  if (!job) return;
  if (cmd === 'cancel') return closePreview();
  if (cmd === 'yes') return startResult();
  if (cmd === 'no') return sendPreview({ view: 'pick', said: job.instruction, prompts: promptChoices(job.promptId) });
  if (cmd.startsWith('pick:')) {
    job.promptId = cmd.slice(5);
    return startResult();
  }
  if (cmd === 'words') {
    job.promptId = null;
    job.extra = job.instruction;
    return startResult();
  }
  if (cmd === 'retry') return generate();
  if (!job.result) return;
  if (cmd === 'copy') {
    await copyText(job.result);
    sendPreview({ status: 'Copied to clipboard ✓', statusKind: 'ok' });
    return;
  }
  if (cmd === 'replace' && job.canReplace) {
    const { result, fg } = job;
    closePreview();
    const how = await transform.replace(result, fg);
    log(`replace → ${how}`);
    if (how === 'copied') pillMessage('App changed: copied instead', 'info', 2500);
  }
}

function createPreview() {
  previewWin = new BrowserWindow({
    width: PREVIEW_W, height: 460, show: false, frame: false, transparent: true, resizable: false,
    skipTaskbar: true, focusable: false, alwaysOnTop: true, hasShadow: false,
    webPreferences: { preload: path.join(RENDERER, 'preload.js') },
  });
  previewWin.setAlwaysOnTop(true, 'screen-saver');
  previewWin.loadFile(path.join(RENDERER, 'preview.html'));
}

// Anchored above the idle bar (or below it when the pill is at the top); height comes from the page.
let previewHeight = 460;
function placePreview() {
  const { workArea } = screen.getDisplayNearestPoint(screen.getCursorScreenPoint());
  const gap = IDLE_SIZE.height + EDGE_GAP + 8;
  previewWin.setBounds({
    x: Math.round(workArea.x + (workArea.width - PREVIEW_W) / 2),
    y: Math.round(pillTop() ? workArea.y + gap : workArea.y + workArea.height - previewHeight - gap),
    width: PREVIEW_W, height: previewHeight,
  });
}

function showPreview(data) {
  sendPreview(data);
  placePreview();
  previewWin.showInactive(); // never steal focus: the selection must stay active in the original app
  previewWin.moveTop();
}

function sendPreview(data) {
  previewWin?.webContents.send('preview:data', data);
  if (DEBUG && data.view && data.view !== 'result') setTimeout(() => logButtonPositions(previewWin, 'button[data-cmd]'), 500);
}

function closePreview() {
  job = null;
  previewWin?.hide();
}

// ---------- IPC ----------

function registerIpc() {
  ipcMain.on('record:audio', (_e, payload) => processAudio(payload));
  ipcMain.on('live:sentence', (_e, payload) => onLiveSentence(payload));
  ipcMain.on('pill:size', (_e, size) => {
    if (!size?.width || !size?.height) return;
    pillSize = { width: size.width, height: size.height };
    trace('pill size', JSON.stringify(pillSize));
    placePill();
  });
  ipcMain.on('preview:height', (_e, h) => {
    if (!h || h === previewHeight) return;
    previewHeight = h;
    if (previewWin?.isVisible()) placePreview();
  });

  ipcMain.handle('history:list', () => history.list());
  ipcMain.handle('history:delete', (_e, id) => history.remove(id));
  ipcMain.handle('history:clear', () => history.clear());

  ipcMain.handle('dict:get', () => config.getDictionary());
  ipcMain.handle('dict:save', (_e, d) => config.saveDictionary(d));

  ipcMain.handle('config:get', () => {
    return { ...config.getConfig(), dataDir: config.dir() };
  });
  ipcMain.handle('config:save', (_e, partial) => {
    const allowed = ['language', 'typingWpm', 'pillPosition',
      'selectionTool', 'excludedApps', 'smartFormat', 'liveTyping', 'showIdleLine'];
    const clean = Object.fromEntries(Object.entries(partial).filter(([k, v]) => allowed.includes(k) && v !== undefined && v !== ''));
    config.saveConfig(clean);
    if ('pillPosition' in clean) {
      pillWin.webContents.send('pill:layout', { position: pillTop() ? 'top' : 'bottom' });
      placePill();
    }
    if ('showIdleLine' in clean) refreshIdleBar();
    return true;
  });
  ipcMain.handle('usage:get', () => api.usage().catch(() => null));
  ipcMain.handle('clipboard:copy', (_e, text) => clipboard.writeText(text));

  // prompt library
  const changed = (result) => { sendPillMenu(); return result; };
  ipcMain.handle('prompts:list', () => library.list());
  ipcMain.handle('prompts:save', (_e, p) => changed(library.save(p)));
  ipcMain.handle('prompts:pin', (_e, id, pinned) => changed(library.setPinned(id, pinned)));
  ipcMain.handle('prompts:remove', (_e, id) => changed(library.remove(id)));
  ipcMain.handle('prompts:reset', (_e, id) => changed(library.reset(id)));
  ipcMain.handle('eod:today', () => createTodayEod());
  ipcMain.handle('auth:get', () => accountInfo());
  ipcMain.handle('auth:signin', async () => {
    try {
      await auth.signIn();
      log(`signed in as ${auth.user()?.email}`);
      return { ok: true };
    } catch (err) {
      log('sign-in failed:', err.message);
      return { ok: false, error: err.message };
    }
  });
  ipcMain.handle('sync:deleteCloud', async () => {
    try { return { ok: true, count: await sync.deleteCloudData() }; } catch (err) { return { ok: false, error: err.message }; }
  });
  ipcMain.handle('auth:signout', async () => {
    await auth.signOut();
    log('signed out');
    return true;
  });
}

// ---------- start ----------
app.whenReady().then(() => {
  // Allow microphone access for our own pages.
  session.defaultSession.setPermissionRequestHandler((_wc, perm, cb) => cb(perm === 'media'));
  session.defaultSession.setPermissionCheckHandler((_wc, perm) => perm === 'media');

  config.getConfig();
  groq.useServer({ baseURL: api.BASE, token: auth.idToken }); // Groq goes through the MyWisper server
  api.warmUp();
  registerIpc();
  sync.init({
    logger: log,
    statusChanged: sendAccount,
    remote: (name) => { notifyDashboard(); if (name === 'prompts') sendPillMenu(); },
  });
  auth.onChange(sendAccount);
  if (!auth.restore()) log('not signed in');
  winfo.start();
  createPill();
  createPreview();
  tray.createTray({ openDashboard, createTodayEod });
  watchHover();
  setInterval(() => { if (pillMode === 'idle') refreshIdleBar(); }, FULLSCREEN_CHECK_MS);
  screen.on('display-metrics-changed', placePill);
  screen.on('display-added', placePill);
  screen.on('display-removed', placePill);

  hotkey.on('start', () => {
    if (live) return;
    if (!auth.user()) return needSignIn(); // live typing is on: this press only stops it (when the keys go up)
    // Waiting for "Say what to do"? Then this recording is the instruction, not text to type.
    recordingKind = pillMode === 'await' && pendingCommand ? 'command' : 'dictation';
    log(`hotkey: start (${recordingKind})`);
    if (recordingKind === 'command') clearTimeout(pendingCommand.timer);
    else {
      endSelectionMode('dictation');
      // Find out which app we're dictating into while the user speaks (used for smart formatting).
      dictationContext = winfo.foreground().then(detect).catch(() => null);
    }
    expandPill('dictation');
    tray.setRecording(true);
    pillWin.webContents.send('record:start');
  });
  hotkey.on('stop', () => {
    if (live) return stopLive('Win + Ctrl');
    log('hotkey: stop');
    tray.setRecording(false);
    pillWin.webContents.send('record:stop');
  });
  // A quick tap: starts/stops live typing when that's on; otherwise it's just a too-short press.
  hotkey.on('tap', () => {
    if (live) return stopLive('Win + Ctrl');
    if (recordingKind === 'dictation' && config.getConfig().liveTyping) return startLive();
    onHotkeyCancel();
  });
  hotkey.on('cancel', () => {
    if (live) return stopLive('Win + Ctrl + another key');
    onHotkeyCancel();
  });
  function onHotkeyCancel() {
    log('hotkey: cancel');
    tray.setRecording(false);
    pillWin.webContents.send('record:cancel');
    if (recordingKind === 'command' && pendingCommand) {
      // A too-short tap while waiting for an instruction: keep waiting.
      pendingCommand.timer = setTimeout(() => cancelCommand('timeout'), COMMAND_WAIT_MS);
      pillMode = 'await';
      pillState('await', 'Hold Win + Ctrl and say what to do');
    } else {
      collapsePill();
    }
  }
  hotkey.start();

  selection.on('selection', onSelection);
  // A click that started and ended on the pill or preview: find the button under it and run it.
  // (Windows doesn't always deliver clicks to these never-focused windows, so we don't rely on it.)
  selection.on('ownclick', async ({ x, y }) => {
    const p = screen.screenToDipPoint({ x, y });
    for (const [win, fn] of [[pillWin, 'pillHit'], [previewWin, 'previewHit']]) {
      if (!win?.isVisible()) continue;
      const b = win.getBounds();
      if (p.x < b.x || p.x > b.x + b.width || p.y < b.y || p.y > b.y + b.height) continue;
      const hit = await win.webContents.executeJavaScript(`window.${fn}(${p.x - b.x}, ${p.y - b.y})`).catch(() => null);
      log(`click on ${fn === 'pillHit' ? 'pill' : 'preview'}: ${hit}`);
      if (fn === 'pillHit') {
        if (hit === 'dashboard') openDashboard('overview');
        else if (hit === 'toggle') scheduleSelectionHide(SELECTION_HIDE_MS);
        else if (hit === 'cancel') cancelCommand('cancelled');
        else if (hit === 'stoplive') stopLive('pill click');
        else if (hit === 'voice') startVoiceCommand();
        else if (hit) runMenuPrompt(hit);
      } else if (hit && hit !== 'toggle-original') {
        previewCommand(hit);
      }
      return;
    }
  });
  // Any click outside the pill, or any key press, closes the Rewrite pill. Esc closes the preview.
  selection.on('mousedown', (p) => {
    trace('mousedown', p.x, p.y, 'mode', pillMode, 'cursor', JSON.stringify(screen.getCursorScreenPoint()),
      'pill', JSON.stringify(pillWin.getBounds()), 'over', cursorOverPill());
    if (pillMode === 'select' && !cursorOverPill()) endSelectionMode('click outside');
  });
  selection.on('keydown', (e) => {
    const esc = e.keycode === 1;
    if (pillMode === 'select') endSelectionMode(`key ${e.keycode}`);
    if (esc && pillMode === 'await') cancelCommand('cancelled');
    if (esc && live) stopLive('Esc');
    if (esc && previewWin?.isVisible()) closePreview();
  });
  selection.isOverOwnUi = () => cursorOverPill() || cursorOver(previewWin);
  selection.start();

  // Key-ups are never seen while the PC is locked/asleep, so forget held keys then.
  for (const ev of ['lock-screen', 'unlock-screen', 'suspend', 'resume']) powerMonitor.on(ev, () => hotkey.reset());

  if (process.env.MYWISPER_SMOKE) return runSmokeTest(process.env.MYWISPER_SMOKE);
  // Testing: build today's EOD shortly after start (same function as the tray item / dashboard button).
  if (DEBUG && process.env.MYWISPER_TEST_EOD) setTimeout(createTodayEod, 3000);
  if (!auth.user()) openDashboard('overview'); // shows the sign-in screen
});

// Self-test (npm run smoke): screenshots of every pill state, the preview views and each dashboard
// tab, saved into the given folder. Nothing is typed or pasted.
async function runSmokeTest(outDir) {
  const fs = require('fs');
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const shot = async (win, name) => fs.writeFileSync(path.join(outDir, `${name}.png`), (await win.webContents.capturePage()).toPNG());
  fs.mkdirSync(outDir, { recursive: true });
  await wait(1500);
  await shot(pillWin, 'pill-idle');
  pillState('hint');
  await wait(400);
  await shot(pillWin, 'pill-hint');
  expandPill('select');
  pillState('select', 'MyWisper');
  await wait(400);
  await shot(pillWin, 'pill-select');
  await pillWin.webContents.executeJavaScript("(() => { const r = document.getElementById('pill').getBoundingClientRect(); return window.pillHit(r.x + r.width / 2, r.y + r.height / 2); })()");
  await wait(500);
  await shot(pillWin, 'pill-menu');
  pillMode = 'await';
  pillState('await', 'Hold Win + Ctrl and say what to do');
  await wait(400);
  await shot(pillWin, 'pill-await');
  collapsePill();
  job = { promptId: 'eod', extra: 'keep it short', text: 'notes', instruction: 'make EOD and keep it short', canReplace: true };
  showPreview({ view: 'confirm', said: job.instruction, promptName: 'EOD report', extra: job.extra });
  await wait(700);
  await shot(previewWin, 'preview-confirm');
  showPreview({ view: 'pick', said: job.instruction, prompts: promptChoices('eod') });
  await wait(700);
  await shot(previewWin, 'preview-pick');
  showPreview({ view: 'result', title: 'EOD report', meta: 'Notepad · 1.1 s', original: 'notes', originalLabel: 'Selected text',
    result: 'EOD Report – 29 Sep 2026\n\nRSVP\n• Fixed guest count bug (QP-1423).\n\nPending\n• iOS testing', loading: false, status: '', canReplace: true });
  await wait(700);
  await shot(previewWin, 'preview-result');
  closePreview();
  openDashboard('overview');
  await wait(2500);
  for (const tab of ['overview', 'history', 'prompts', 'dictionary', 'settings']) {
    dashWin.webContents.send('dashboard:tab', tab);
    await wait(700);
    await shot(dashWin, `dash-${tab}`);
  }
  console.log('SMOKE screenshots saved to', outDir);
  app.quit();
}

// Tray app: closing the dashboard must not quit.
app.on('window-all-closed', () => {});
app.on('before-quit', () => {
  hotkey.stop();
  winfo.stop();
  history.flush();
});
