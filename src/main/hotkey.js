// Watches the keyboard globally. Holding Win + Ctrl emits "start", releasing emits "stop".
// Electron's globalShortcut can't detect key-up or a modifier-only combo, so we use uiohook-napi.
const { EventEmitter } = require('events');
const { uIOhook, UiohookKey } = require('uiohook-napi');
const winfo = require('./winfo');

const CTRL_KEYS = [UiohookKey.Ctrl, UiohookKey.CtrlRight];
const WIN_KEYS = [UiohookKey.Meta, UiohookKey.MetaRight];
const MIN_HOLD_MS = 300;             // shorter taps are ignored (accidental presses)
const MAX_RECORD_MS = 5 * 60 * 1000; // auto-stop after 5 minutes
// A key-up can get lost (e.g. Win+L locks the PC before Win is released). A key that has
// been "down" this long without being part of a recording is treated as released.
const STALE_KEY_MS = 15_000;
// uiohook reports the Start-menu mask key (VK 0xE8, see begin()) as keycode 0 ("undefined").
const MASK_KEYCODE = 0;

class Hotkey extends EventEmitter {
  constructor() {
    super();
    this.down = new Map();   // keycode → time it was pressed
    this.recording = false;
    this.startedAt = 0;
    this.maxTimer = null;
    this.paused = false;     // true while we press Ctrl+V ourselves
  }

  isCtrl() { return CTRL_KEYS.some((k) => this.down.has(k)); }
  isWin() { return WIN_KEYS.some((k) => this.down.has(k)); }
  // uiohook's own ctrlKey/shiftKey flags are unreliable, so other modules ask here.
  isShift() { return [UiohookKey.Shift, UiohookKey.ShiftRight].some((k) => this.down.has(k)); }
  isAlt() { return [UiohookKey.Alt, UiohookKey.AltRight].some((k) => this.down.has(k)); }

  start() {
    uIOhook.on('keydown', (e) => this.onDown(e.keycode));
    uIOhook.on('keyup', (e) => this.onUp(e.keycode));
    uIOhook.start();
  }

  stop() { uIOhook.stop(); }

  onDown(key) {
    if (this.paused || key === MASK_KEYCODE) return;
    if (!this.recording) this.dropStaleKeys();
    if (!this.down.has(key)) this.down.set(key, Date.now()); // ignore auto-repeat
    const isHotkeyKey = CTRL_KEYS.includes(key) || WIN_KEYS.includes(key);

    if (!this.recording && this.isCtrl() && this.isWin()) {
      // Only start if nothing else is held (so Win+Ctrl+D etc. still work normally).
      const onlyHotkey = [...this.down.keys()].every((k) => CTRL_KEYS.includes(k) || WIN_KEYS.includes(k));
      if (onlyHotkey) this.begin();
    } else if (this.recording && !isHotkeyKey) {
      // User pressed e.g. Win+Ctrl+Left (switch desktop) → not a dictation.
      this.finish('cancel');
    }
  }

  onUp(key) {
    this.down.delete(key);
    if (this.recording && !(this.isCtrl() && this.isWin())) {
      this.finish(Date.now() - this.startedAt < MIN_HOLD_MS ? 'cancel' : 'stop');
    }
  }

  dropStaleKeys() {
    const now = Date.now();
    for (const [key, since] of this.down) if (now - since > STALE_KEY_MS) this.down.delete(key);
  }

  // Forget all held keys (called when the PC locks/sleeps, where key-ups are never seen).
  reset() {
    if (this.recording) this.finish('cancel');
    this.down.clear();
  }

  begin() {
    this.recording = true;
    this.startedAt = Date.now();
    this.maxTimer = setTimeout(() => this.finish('stop'), MAX_RECORD_MS);
    // Windows opens the Start menu when Win is released with no other key pressed in between.
    // Tapping an unassigned key (VK 0xE8, sent by winhelper.exe) while Win is held prevents that.
    // Never use F24 here: Win + Ctrl + F24 is Windows' "touchpad on/off" shortcut.
    winfo.tapMaskKey();
    this.emit('start');
  }

  finish(kind) {
    this.recording = false;
    clearTimeout(this.maxTimer);
    this.emit(kind);
  }

  // Resolves once Win and Ctrl are both released (or after a timeout),
  // so our Ctrl+V doesn't turn into Win+Ctrl+V.
  waitForRelease(timeoutMs = 3000) {
    return new Promise((resolve) => {
      const t0 = Date.now();
      const check = () => {
        if ((!this.isCtrl() && !this.isWin()) || Date.now() - t0 > timeoutMs) return resolve();
        setTimeout(check, 20);
      };
      check();
    });
  }

  // Presses a shortcut (Ctrl+V, Ctrl+C…) in whatever app has focus. While `paused`, our own
  // key presses are ignored here and by selection.js.
  pressKeys(key, modifiers = [UiohookKey.Ctrl]) {
    this.paused = true;
    clearTimeout(this.pauseTimer);
    try {
      uIOhook.keyTap(key, modifiers);
    } finally {
      this.pauseTimer = setTimeout(() => { this.paused = false; }, 150);
    }
  }

  pressPaste() { this.pressKeys(UiohookKey.V); }
  pressCopy() { this.pressKeys(UiohookKey.C); }
}

module.exports = new Hotkey();
