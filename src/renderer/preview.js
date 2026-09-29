// The preview window: confirm a saved prompt, pick another one, or review the result.
// It never takes keyboard focus, so the selection in the original app stays active.
// Clicks are resolved by main.js through previewHit() (see pillHit in pill.js for why).
const $ = (id) => document.getElementById(id);
const fmtChars = (n) => `${n.toLocaleString()} ${n === 1 ? 'char' : 'chars'}`;
const RESULT_HEIGHT = 460;

let view = 'result';

function setView(next) {
  view = next;
  document.body.className = `view-${next}`;
  reportHeight();
}

// Confirm/pick are as tall as their content; the result view uses a fixed size.
function reportHeight() {
  requestAnimationFrame(() => {
    const h = view === 'result' ? RESULT_HEIGHT : $('card').offsetHeight + 16;
    window.mywisper.setPreviewHeight(Math.ceil(h));
  });
}

function pickButton(cmd, name, description, extraClass = '') {
  const b = document.createElement('button');
  b.dataset.cmd = cmd;
  if (extraClass) b.className = extraClass;
  const t = document.createElement('b');
  t.textContent = name;
  const s = document.createElement('small');
  s.textContent = description || '';
  b.append(t, s);
  return b;
}

// data: { view, said, promptName, extra, prompts, title, meta, original, result, loading, status, statusKind, canReplace }
window.mywisper.onPreview((data) => {
  if (data.view === 'confirm') {
    $('confirmSaid').textContent = `“${data.said}”`;
    $('confirmName').textContent = data.promptName;
    $('confirmExtra').textContent = data.extra || '';
    $('confirmExtraRow').hidden = !data.extra;
  }
  if (data.view === 'pick') {
    $('pickSaid').textContent = data.said ? `“${data.said}”` : 'Choose a prompt';
    $('pickList').replaceChildren(
      ...data.prompts.map((p) => pickButton(`pick:${p.id}`, p.name, p.description)),
      ...(data.said ? [pickButton('words', 'Use my words as the instruction', `“${data.said}”`, 'words')] : []),
    );
  }
  if ('title' in data) $('title').textContent = data.title;
  if ('meta' in data) $('meta').textContent = data.meta || '';
  if ('original' in data) {
    $('original').textContent = data.original;
    $('originalCount').textContent = fmtChars(data.original.length);
    $('originalBox').open = false;
  }
  if ('originalLabel' in data) $('originalBox').querySelector('summary span').textContent = data.originalLabel;
  if ('canReplace' in data) {
    // No selection to replace (e.g. EOD from today's dictations): Copy becomes the main button.
    $('replaceBtn').hidden = !data.canReplace;
    $('copyBtn').className = data.canReplace ? 'ghost' : 'primary';
    $('closeBtn').textContent = data.canReplace ? 'Cancel' : 'Close';
  }
  if ('result' in data) {
    $('result').textContent = data.result;
    $('resultBox').scrollTop = 0;
  }
  if ('loading' in data) {
    $('loading').hidden = !data.loading;
    for (const id of ['replaceBtn', 'copyBtn']) $(id).disabled = data.loading;
    document.querySelector('[data-cmd="retry"]').disabled = data.loading;
  }
  if ('status' in data) {
    $('status').textContent = data.status || '';
    $('status').className = `status ${data.statusKind || ''}`;
  }
  if (data.view) setView(data.view);
});

window.previewHit = (x, y) => {
  const target = document.elementFromPoint(x, y);
  if (target?.closest('summary')) {
    $('originalBox').open = !$('originalBox').open;
    return 'toggle-original';
  }
  const btn = target?.closest('button[data-cmd]');
  return btn && !btn.disabled && !btn.hidden ? btn.dataset.cmd : null;
};
// A DOM click that Windows does deliver on the summary would toggle it twice; stop the default.
$('originalBox').querySelector('summary').addEventListener('click', (e) => e.preventDefault());
