// Dashboard: overview (stats, free-plan usage, charts), history, dictionary, settings.
const api = window.mywisper;
const $ = (id) => document.getElementById(id);

let historyData = [];
let cfg = null;
let dict = { words: [], replacements: {} };
let kindFilter = 'all';
const charts = {};

// Groq free-plan daily limits (see console.groq.com/docs/rate-limits).
const FREE_PLAN = { llmRequests: 1000, llmTokens: 200_000, sttRequests: 2000, audioSec: 28_800 };
const TAB_NAMES = { overview: 'Overview', history: 'History', prompts: 'Prompts', dictionary: 'Dictionary', settings: 'Settings' };
const ACTION_LABELS = { prompt: 'Pro prompt', polish: 'Polish', grammar: 'Fix grammar', translate: 'Translate', eod: 'EOD report' };
const ICONS = {
  copy: '<svg viewBox="0 0 24 24"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V6a2 2 0 0 1 2-2h8"/></svg>',
  check: '<svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
  trash: '<svg viewBox="0 0 24 24"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3"/></svg>',
  x: '<svg viewBox="0 0 24 24"><path d="M7 7l10 10M17 7L7 17"/></svg>',
};

// ---------- helpers ----------
const fmtInt = (n) => Math.round(n).toLocaleString('en-IN');
const fmtSec = (ms) => `${(ms / 1000).toFixed(1)}s`;
const fmtCompact = (n) => (n >= 1000 ? `${(n / 1000).toFixed(n >= 10_000 ? 0 : 1)}K` : String(Math.round(n)));
const sameDay = (a, b) => a.toDateString() === b.toDateString();
const plural = (n, word) => `${fmtInt(n)} ${word}${n === 1 ? '' : 's'}`;
function fmtDuration(min) {
  if (min < 1) return `${Math.round(min * 60)}s`;
  if (min < 60) return `${Math.round(min)}m`;
  return `${Math.floor(min / 60)}h ${Math.round(min % 60)}m`;
}
function fmtTime(iso) {
  const d = new Date(iso);
  const time = d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  if (sameDay(d, new Date())) return `Today ${time}`;
  if (sameDay(d, new Date(Date.now() - 864e5))) return `Yesterday ${time}`;
  return `${d.toLocaleDateString([], { day: 'numeric', month: 'short' })} ${time}`;
}
function el(tag, props = {}, ...children) {
  const node = Object.assign(document.createElement(tag), props);
  for (const c of children) if (c != null) node.append(c);
  return node;
}
function iconButton(icon, label, extraClass = '') {
  const b = el('button', { className: `icon-btn ${extraClass}`, title: label });
  b.setAttribute('aria-label', label);
  b.innerHTML = ICONS[icon];
  return b;
}
const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const isDictation = (e) => e.kind !== 'transform';

let toastTimer = null;
function toast(text) {
  $('settingsSaved').textContent = text;
  $('settingsSaved').hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { $('settingsSaved').hidden = true; }, 1600);
}

// ---------- tabs ----------
function showTab(tab) {
  if (!TAB_NAMES[tab]) tab = 'overview';
  document.querySelectorAll('.tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  document.querySelectorAll('.tab').forEach((s) => s.classList.toggle('active', s.id === `tab-${tab}`));
  $('crumb').textContent = TAB_NAMES[tab];
  if (tab !== 'settings') stopMicTest();
}
document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));
api.onTab(showTab);

// ---------- overview ----------
function renderOverview() {
  // Speaking stats only count dictations (not rewrites of selected text).
  const ok = historyData.filter((e) => isDictation(e) && e.status !== 'error' && e.words > 0);
  const now = new Date();
  const today = ok.filter((e) => sameDay(new Date(e.time), now));
  const month = historyData.filter((e) => {
    const d = new Date(e.time);
    return d.getMonth() === now.getMonth() && d.getFullYear() === now.getFullYear();
  });

  const totalWords = ok.reduce((s, e) => s + e.words, 0);
  const totalAudioMin = ok.reduce((s, e) => s + e.audioSec, 0) / 60;
  const typingWpm = cfg?.typingWpm || 40;
  const recent = ok.slice(0, 50);

  $('stWordsToday').textContent = fmtInt(today.reduce((s, e) => s + e.words, 0));
  $('stDictToday').textContent = plural(today.length, 'dictation');
  $('stWordsTotal').textContent = fmtInt(totalWords);
  $('stDictTotal').textContent = plural(ok.length, 'dictation');
  $('stWpm').textContent = totalAudioMin > 0 ? fmtInt(totalWords / totalAudioMin) : '–';
  if (recent.length) {
    const avg = (k) => recent.reduce((s, e) => s + (e[k] || 0), 0) / recent.length;
    $('stLatency').textContent = fmtSec(avg('totalMs'));
    $('stLatencySplit').textContent = `whisper ${fmtSec(avg('sttMs'))} · ai ${fmtSec(avg('llmMs'))}`;
  } else {
    $('stLatency').textContent = '–';
    $('stLatencySplit').textContent = '';
  }
  $('stSaved').textContent = totalWords ? fmtDuration(Math.max(0, totalWords / typingWpm - totalAudioMin)) : '–';
  $('stSavedNote').textContent = `vs typing at ${typingWpm} wpm`;
  const cost = month.reduce((s, e) => s + (e.costUsd || 0), 0);
  $('stCost').textContent = `$${cost.toFixed(cost > 0 && cost < 0.01 ? 4 : 2)}`;
  $('ovSubtitle').textContent = ok.length
    ? `Last dictation ${fmtTime(ok[0].time).toLowerCase()}`
    : 'No dictations yet. Click into any text box, hold Win + Ctrl and speak.';

  renderUsage();
  renderCharts(ok);
}

// Today's use against the Groq free-plan daily limits.
function renderUsage() {
  const now = new Date();
  const today = historyData.filter((e) => sameDay(new Date(e.time), now));
  const llmCalls = today.filter((e) => (e.promptTokens || 0) > 0).length;
  const tokens = today.reduce((s, e) => s + (e.promptTokens || 0) + (e.completionTokens || 0), 0);
  const sttCalls = today.filter((e) => isDictation(e) && (e.sttMs || 0) > 0).length;
  const audio = today.filter(isDictation).reduce((s, e) => s + (e.audioSec || 0), 0);
  const items = [
    { name: 'AI requests', used: llmCalls, limit: FREE_PLAN.llmRequests, show: (n) => fmtInt(n) },
    { name: 'AI tokens', used: tokens, limit: FREE_PLAN.llmTokens, show: fmtCompact },
    { name: 'Whisper requests', used: sttCalls, limit: FREE_PLAN.sttRequests, show: (n) => fmtInt(n) },
    { name: 'Audio', used: audio, limit: FREE_PLAN.audioSec, show: (s) => fmtDuration(s / 60) },
  ];
  $('usage').replaceChildren(...items.map((m) => {
    const pct = Math.min(100, (m.used / m.limit) * 100);
    const track = el('div', { className: `track${pct >= 95 ? ' full' : pct >= 75 ? ' warn' : ''}` }, el('div'));
    track.firstChild.style.width = `${pct}%`;
    const value = el('span', { className: 'mono' }, m.show(m.used), el('i', { textContent: ` / ${m.show(m.limit)}` }));
    return el('div', { className: 'meter-item' },
      el('div', { className: 'meter-top' }, el('span', { textContent: m.name }), value),
      track,
      el('small', { textContent: `${pct < 1 && m.used > 0 ? '<1' : Math.round(pct)}% used` }));
  }));
}

function renderCharts(ok) {
  if (!window.Chart) return;
  const fg = css('--fg');
  const muted = css('--fg-3');
  const line = css('--line');
  Chart.defaults.color = muted;
  Chart.defaults.font.family = '"Geist Mono", monospace';
  Chart.defaults.font.size = 11;

  const days = [];
  for (let i = 29; i >= 0; i--) days.push({ d: new Date(Date.now() - i * 864e5), words: 0 });
  for (const e of ok) {
    const day = days.find((x) => sameDay(x.d, new Date(e.time)));
    if (day) day.words += e.words;
  }
  const wordsData = {
    labels: days.map((x) => x.d.toLocaleDateString([], { day: 'numeric', month: 'short' })),
    datasets: [{ label: 'Words', data: days.map((x) => x.words), backgroundColor: fg, borderRadius: 2, maxBarThickness: 14 }],
  };

  const last = ok.slice(0, 40).reverse();
  const latencyData = {
    labels: last.map((e) => fmtTime(e.time)),
    datasets: [
      { label: 'Whisper', data: last.map((e) => (e.sttMs || 0) / 1000), backgroundColor: fg, maxBarThickness: 14 },
      { label: 'AI cleanup', data: last.map((e) => (e.llmMs || 0) / 1000), backgroundColor: muted, maxBarThickness: 14 },
      { label: 'Paste', data: last.map((e) => (e.pasteMs || 0) / 1000), backgroundColor: css('--line-2'), maxBarThickness: 14 },
    ],
  };

  const options = (stacked, unit) => ({
    responsive: true, maintainAspectRatio: false, animation: false,
    plugins: {
      legend: { display: stacked, position: 'bottom', align: 'start', labels: { boxWidth: 8, boxHeight: 8, padding: 14, font: { family: '"Geist", sans-serif', size: 12 } } },
      tooltip: {
        backgroundColor: css('--invert-bg'), titleColor: css('--invert-fg'), bodyColor: css('--invert-fg'), displayColors: false, padding: 10, cornerRadius: 6,
        callbacks: { label: (c) => `${c.dataset.label}: ${unit === 's' ? `${c.parsed.y.toFixed(2)}s` : fmtInt(c.parsed.y)}` },
      },
    },
    scales: {
      x: { stacked, grid: { display: false }, border: { color: line }, ticks: { maxRotation: 0, autoSkip: true, maxTicksLimit: 6 } },
      y: { stacked, beginAtZero: true, grid: { color: line }, border: { display: false }, ticks: { maxTicksLimit: 5, callback: (v) => (unit === 's' ? `${v}s` : v) } },
    },
  });

  if (charts.words) { charts.words.data = wordsData; charts.words.update(); }
  else charts.words = new Chart($('chWords'), { type: 'bar', data: wordsData, options: options(false, 'words') });
  if (charts.latency) { charts.latency.data = latencyData; charts.latency.update(); }
  else charts.latency = new Chart($('chLatency'), { type: 'bar', data: latencyData, options: options(true, 's') });
}

// ---------- history ----------
function renderHistory() {
  const q = $('search').value.trim().toLowerCase();
  const items = historyData
    .filter((e) => kindFilter === 'all' || (kindFilter === 'transform') === !isDictation(e))
    .filter((e) => !q || `${e.cleanText} ${e.rawText}`.toLowerCase().includes(q));
  $('historyList').replaceChildren(...items.slice(0, 300).map(historyItem));
  $('historyEmpty').hidden = items.length > 0;
}

function historyItem(e) {
  const text = e.cleanText || e.rawText || e.error || '(empty)';
  const copyBtn = iconButton('copy', 'Copy text');
  copyBtn.onclick = async () => {
    await api.copyText(e.cleanText || e.rawText || '');
    copyBtn.innerHTML = ICONS.check;
    setTimeout(() => { copyBtn.innerHTML = ICONS.copy; }, 1200);
  };
  const delBtn = iconButton('trash', 'Delete', 'danger');
  delBtn.onclick = async () => { await api.deleteHistory(e.id); load(); };

  const kind = isDictation(e) ? 'Dictation' : (e.label || ACTION_LABELS[e.action] || e.action);
  const statusText = { raw: 'Typed without cleanup', error: 'Failed' }[e.status];
  const meta = el('div', { className: 'row-meta mono' },
    el('span', { textContent: fmtTime(e.time) }),
    el('span', { className: 'kind', textContent: kind }),
    statusText ? el('span', { className: `st ${e.status}`, textContent: statusText }) : null,
    e.app ? el('span', { textContent: e.category && e.category !== 'general' ? `${e.app} · ${e.category}` : e.app }) : null,
    e.audioSec ? el('span', { textContent: `${e.audioSec.toFixed(1)}s audio` }) : null,
    e.words ? el('span', { textContent: `${e.words} words · ${e.wpm} wpm` }) : null,
    e.totalMs ? el('span', { textContent: isDictation(e) ? `${fmtSec(e.totalMs)} (whisper ${fmtSec(e.sttMs)}, ai ${fmtSec(e.llmMs)})` : fmtSec(e.totalMs) }) : null,
    e.costUsd ? el('span', { textContent: `$${e.costUsd.toFixed(5)}` }) : null,
  );

  const details = (e.rawText || e.error) ? el('details', {},
    el('summary', { textContent: isDictation(e) ? 'What Whisper heard' : 'Original text' }),
    e.instruction ? el('p', { textContent: `You said: “${e.instruction}”${e.extra && e.action !== 'custom' ? ` · extra: ${e.extra}` : ''}` }) : null,
    e.rawText ? el('p', { textContent: e.rawText }) : null,
    e.error ? el('p', { textContent: `Error: ${e.error}` }) : null,
  ) : null;

  return el('li', {},
    el('div', {}, el('p', { className: `row-text${e.status === 'error' ? ' err' : ''}`, textContent: text }), meta, details),
    el('div', { className: 'row-actions' }, copyBtn, delBtn));
}

$('search').addEventListener('input', renderHistory);
document.querySelectorAll('#kindFilter button').forEach((b) => b.addEventListener('click', () => {
  kindFilter = b.dataset.kind;
  document.querySelectorAll('#kindFilter button').forEach((x) => x.classList.toggle('active', x === b));
  renderHistory();
}));
$('clearAll').addEventListener('click', async () => {
  if (!confirm('Delete all history? This can’t be undone.')) return;
  await api.clearHistory();
  load();
});

// ---------- dictionary ----------
function renderDictionary() {
  $('wordChips').replaceChildren(...dict.words.map((w) => {
    const x = el('button', { title: `Remove ${w}` });
    x.innerHTML = ICONS.x;
    x.onclick = () => { dict.words = dict.words.filter((v) => v !== w); saveDict(); };
    return el('span', { className: 'chip' }, w, x);
  }));
  $('replBody').replaceChildren(...Object.entries(dict.replacements).map(([from, to]) => {
    const del = el('button', { className: 'btn ghost sm', textContent: 'Remove' });
    del.onclick = () => { delete dict.replacements[from]; saveDict(); };
    return el('tr', {}, el('td', { textContent: from }), el('td', { textContent: `→ ${to}` }), el('td', {}, del));
  }));
}
async function saveDict() {
  dict = await api.saveDictionary(dict);
  renderDictionary();
}
$('wordForm').addEventListener('submit', (ev) => {
  ev.preventDefault();
  const words = $('wordInput').value.split(',').map((w) => w.trim()).filter(Boolean);
  if (!words.length) return;
  dict.words.push(...words);
  $('wordInput').value = '';
  saveDict();
});
$('replForm').addEventListener('submit', (ev) => {
  ev.preventDefault();
  const from = $('replFrom').value.trim();
  const to = $('replTo').value.trim();
  if (!from || !to) return;
  dict.replacements[from.toLowerCase()] = to;
  $('replFrom').value = $('replTo').value = '';
  saveDict();
});

// ---------- prompts ----------
let prompts = [];
let editing = null; // prompt being edited, or {} for a new one

function renderPrompts() {
  $('promptList').replaceChildren(...prompts.map((p) => {
    const pin = el('label', { className: 'pin', title: 'Show this prompt as a button in the Rewrite menu' },
      el('input', { type: 'checkbox', checked: !!p.pinned }), el('span', { className: 'switch' }), 'In menu');
    pin.querySelector('input').addEventListener('change', async (ev) => {
      await api.pinPrompt(p.id, ev.target.checked);
      await loadPrompts();
      toast(ev.target.checked ? `${p.name} added to the menu` : `${p.name} removed from the menu`);
    });
    const edit = el('button', { className: 'btn ghost sm', textContent: 'Edit' });
    edit.onclick = () => openEditor(p);
    const tag = p.builtIn ? el('span', { className: 'tag', textContent: p.edited ? 'Edited' : 'Built-in' }) : null;
    return el('li', {},
      el('div', {}, el('div', { className: 'prompt-name' }, p.name, tag), el('p', { className: 'prompt-desc', textContent: p.description || '' })),
      el('div', { className: 'prompt-actions' }, pin, edit));
  }));
}

function openEditor(p) {
  editing = p || {};
  $('editorTitle').textContent = p ? `Edit "${p.name}"` : 'New prompt';
  $('pName').value = p?.name || '';
  $('pDesc').value = p?.description || '';
  $('pText').value = p?.prompt || '';
  $('pReset').hidden = !p?.builtIn;
  $('pDelete').hidden = !p || p.builtIn;
  $('promptEditor').hidden = false;
  $('promptEditor').scrollIntoView({ behavior: 'smooth', block: 'start' });
  $('pName').focus();
}

function closeEditor() {
  editing = null;
  $('promptEditor').hidden = true;
}

async function loadPrompts() {
  prompts = await api.listPrompts();
  renderPrompts();
}

$('newPrompt').addEventListener('click', () => openEditor(null));
$('pCancel').addEventListener('click', closeEditor);
$('pSave').addEventListener('click', async () => {
  const name = $('pName').value.trim();
  const prompt = $('pText').value.trim();
  if (!name || !prompt) return toast('Add a name and the prompt text first');
  await api.savePrompt({ ...editing, name, description: $('pDesc').value.trim(), prompt, pinned: editing.id ? editing.pinned : true });
  closeEditor();
  await loadPrompts();
  toast('Prompt saved');
});
$('pReset').addEventListener('click', async () => {
  if (!editing?.builtIn || !confirm(`Put "${editing.name}" back to the original text?`)) return;
  await api.resetPrompt(editing.id);
  closeEditor();
  await loadPrompts();
  toast('Reset to default');
});
$('pDelete').addEventListener('click', async () => {
  if (!editing?.id || editing.builtIn || !confirm(`Delete "${editing.name}"?`)) return;
  await api.removePrompt(editing.id);
  closeEditor();
  await loadPrompts();
  toast('Prompt deleted');
});

$('todayEod').addEventListener('click', async () => {
  const ok = await api.createTodayEod();
  if (!ok) toast('No dictations today yet');
});

// ---------- settings ----------
function renderSettings() {
  $('keyMasked').textContent = cfg.hasKey ? cfg.keyMasked : 'none';
  $('sttModel').value = cfg.sttModel;
  $('llmModel').value = cfg.llmModel;
  $('language').value = cfg.language;
  $('typingWpm').value = cfg.typingWpm;
  $('pillPosition').value = cfg.pillPosition || 'bottom';
  $('smartFormat').checked = cfg.smartFormat !== false;
  $('selectionTool').checked = cfg.selectionTool !== false;
  $('showIdleLine').checked = cfg.showIdleLine !== false;
  $('transformModel').value = cfg.transformModel;
  $('excludedApps').value = (cfg.excludedApps || []).join(', ');
  $('dataDir').textContent = cfg.dataDir;
  const dot = el('span', { className: 'dot' });
  dot.style.background = cfg.hasKey ? css('--green') : css('--red');
  $('keyStatus').replaceChildren(dot, cfg.hasKey ? 'Groq connected' : 'Add your API key in Settings');
}

function showResult(ok, msg) {
  $('testResult').className = `result ${ok ? 'ok' : 'bad'}`;
  $('testResult').textContent = msg;
}

$('testKey').addEventListener('click', async () => {
  showResult(true, 'Testing…');
  const r = await api.testApi($('keyInput').value.trim());
  if (!r.ok) return showResult(false, `Key didn't work: ${r.error}`);
  const missing = ['whisper-large-v3', cfg.llmModel, cfg.transformModel].filter((m) => !r.models.includes(m));
  showResult(!missing.length, missing.length
    ? `Key works, but these models aren't available: ${missing.join(', ')}`
    : `Key works. Groq answered in ${r.ms} ms.`);
});

$('saveKey').addEventListener('click', async () => {
  const key = $('keyInput').value.trim();
  if (!key) return showResult(false, 'Paste a key first.');
  const r = await api.testApi(key);
  if (!r.ok) return showResult(false, `Not saved. The key didn't work: ${r.error}`);
  await api.saveConfig({ groqApiKey: key });
  $('keyInput').value = '';
  cfg = await api.getConfig();
  renderSettings();
  showResult(true, 'Key tested and saved.');
});

const SETTING_VALUE = {
  typingWpm: (input) => Math.max(10, Number(input.value) || 40),
  smartFormat: (input) => input.checked,
  showIdleLine: (input) => input.checked,
  selectionTool: (input) => input.checked,
  excludedApps: (input) => input.value.split(',').map((s) => s.trim()).filter(Boolean),
};
for (const id of ['sttModel', 'llmModel', 'language', 'typingWpm', 'pillPosition', 'smartFormat', 'selectionTool', 'transformModel', 'excludedApps', 'showIdleLine']) {
  $(id).addEventListener('change', async () => {
    const value = SETTING_VALUE[id] ? SETTING_VALUE[id]($(id)) : $(id).value;
    await api.saveConfig({ [id]: value });
    cfg = await api.getConfig();
    renderSettings();
    renderOverview();
    toast('Saved');
  });
}

// microphone test
let mic = null;
async function startMicTest() {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  const ctx = new AudioContext();
  const analyser = ctx.createAnalyser();
  analyser.fftSize = 512;
  ctx.createMediaStreamSource(stream).connect(analyser);
  const data = new Float32Array(analyser.fftSize);
  mic = { stream, ctx, raf: 0 };
  const tick = () => {
    analyser.getFloatTimeDomainData(data);
    const rms = Math.sqrt(data.reduce((s, v) => s + v * v, 0) / data.length);
    $('micLevel').style.width = `${Math.min(100, rms * 800)}%`;
    mic.raf = requestAnimationFrame(tick);
  };
  tick();
  $('micTest').textContent = 'Stop test';
}
function stopMicTest() {
  if (!mic) return;
  cancelAnimationFrame(mic.raf);
  mic.stream.getTracks().forEach((t) => t.stop());
  mic.ctx.close();
  mic = null;
  $('micLevel').style.width = '0';
  $('micTest').textContent = 'Start test';
}
$('micTest').addEventListener('click', () => (mic ? stopMicTest() : startMicTest().catch((err) => alert(`Couldn't open the microphone: ${err.message}`))));

// ---------- load ----------
async function load() {
  [historyData, cfg, dict, prompts] = await Promise.all([api.listHistory(), api.getConfig(), api.getDictionary(), api.listPrompts()]);
  renderPrompts();
  renderSettings();
  renderOverview();
  renderHistory();
  renderDictionary();
}

api.onHistoryChanged(load);
showTab(location.hash.slice(1) || 'overview');
load();
