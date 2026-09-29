// Notices when the user has probably just selected text in any app, and emits "selection".
// Signals: mouse drag, double/triple click, Ctrl+A, Shift+arrow/Home/End.
// Also emits "mousedown" and "keydown" so the main process can hide the pill again.
const { EventEmitter } = require('events');
const { uIOhook, UiohookKey } = require('uiohook-napi');
const hotkey = require('./hotkey');
const winfo = require('./winfo');
const config = require('./config');

const DRAG_MIN_PX = 10;       // physical pixels the mouse must move while held
const TITLE_BAR_PX = 45;      // a drag starting this close to the window top is a window move
const LEFT_BUTTON = 1;
const debug = (...a) => process.env.MYWISPER_DEBUG && console.log('[selection]', ...a);
const NAV_KEYS = [
  UiohookKey.ArrowLeft, UiohookKey.ArrowRight, UiohookKey.ArrowUp, UiohookKey.ArrowDown,
  UiohookKey.Home, UiohookKey.End, UiohookKey.PageUp, UiohookKey.PageDown,
];
// Numpad-style navigation keys arrive with a different high byte (e.g. End 0x0E4F vs 0xEE4F).
const SHIFT_SELECT_KEYS = [...NAV_KEYS, ...NAV_KEYS.map((k) => (k & 0xff) | 0xee00)];
const SHIFT_KEYS = [UiohookKey.Shift, UiohookKey.ShiftRight];
// Never pop up in these (lower-case process names): our own app, desktop/taskbar.
const ALWAYS_SKIP = ['mywisper', 'electron', 'explorer', 'searchhost', 'startmenuexperiencehost', 'shellexperiencehost'];
// Apps whose UI Automation reliably reports the selection: if they say "nothing selected", believe it.
const RELIABLE_UIA = ['notepad', 'wordpad', 'winword'];

class Selection extends EventEmitter {
  constructor() {
    super();
    this.down = null;   // { x, y } where the left button went down
    this.timer = null;
  }

  start() {
    uIOhook.on('mousedown', (e) => this.onMouseDown(e));
    uIOhook.on('mouseup', (e) => this.onMouseUp(e));
    uIOhook.on('keydown', (e) => this.onKeyDown(e));
    // With NumLock on, Windows fakes a Shift release around numpad navigation keys.
    uIOhook.on('keyup', (e) => { if (SHIFT_KEYS.includes(e.keycode)) this.shiftUpAt = Date.now(); });
  }

  onMouseDown(e) {
    // Remember whether the press started on MyWisper's own pill/preview: those clicks are never selections.
    if (e.button === LEFT_BUTTON) this.down = { x: e.x, y: e.y, ownUi: this.isOverOwnUi() };
    this.emit('mousedown', { x: e.x, y: e.y });
  }

  // Set by main.js: true while the cursor is over the pill or the preview window.
  isOverOwnUi() { return false; }

  onMouseUp(e) {
    if (e.button !== LEFT_BUTTON || !this.down) return;
    const from = this.down;
    this.down = null;
    if (from.ownUi) {
      // A click on MyWisper's own pill/preview: main.js works out which button it hit.
      this.emit('ownclick', { x: e.x, y: e.y });
      return;
    }
    const dragged = Math.hypot(e.x - from.x, e.y - from.y) >= DRAG_MIN_PX;
    if (dragged || e.clicks >= 2) this.candidate({ from, dragged }, 150);
  }

  onKeyDown(e) {
    if (hotkey.paused || e.keycode === 0) return; // our own Ctrl+C / Ctrl+V, or the Start-menu mask key
    this.emit('keydown', e);
    if (hotkey.recording) return;
    // Modifier state comes from hotkey.js (it sees every key first); uiohook's e.ctrlKey is unreliable.
    const ctrl = hotkey.isCtrl();
    const shift = hotkey.isShift() || Date.now() - (this.shiftUpAt || 0) < 150;
    const other = hotkey.isAlt() || hotkey.isWin();
    if (ctrl && !shift && !other && e.keycode === UiohookKey.A) this.candidate({}, 150);
    else if (shift && !other && SHIFT_SELECT_KEYS.includes(e.keycode)) this.candidate({}, 500);
  }

  // Waits a moment (the user may keep extending the selection), then checks it.
  candidate(info, delayMs) {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.check(info).catch(() => {}), delayMs);
  }

  async check({ from, dragged }) {
    const cfg = config.getConfig();
    if (!cfg.selectionTool || hotkey.recording) return;
    const fg = await winfo.foreground();
    debug('check: foreground', fg && fg.process, fg && fg.title);
    if (!fg) return;
    const proc = (fg.process || '').toLowerCase();
    const excluded = (cfg.excludedApps || []).map((a) => a.toLowerCase().replace(/\.exe$/, ''));
    if (ALWAYS_SKIP.includes(proc) || excluded.includes(proc)) return;
    if (dragged && from && from.y - fg.y < TITLE_BAR_PX) return; // dragging the window, not text

    // Ask the app directly (UI Automation). Many apps support it; others fall back to Ctrl+C later.
    // MYWISPER_NO_UIA=1 (testing) pretends the app has no UI Automation, to exercise the Ctrl+C path.
    const sel = process.env.MYWISPER_NO_UIA ? { supported: false } : await winfo.selectedText();
    const text = sel?.supported ? sel.text : null;
    debug('check: UI Automation', JSON.stringify(sel)?.slice(0, 120));
    if (text !== null && !text.trim() && RELIABLE_UIA.includes(proc)) return;
    this.emit('selection', { fg, text: text && text.trim() ? text : null });
  }
}

module.exports = new Selection();
