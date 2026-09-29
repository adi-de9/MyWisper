// Talks to winhelper.exe (helper/WinHelper.cs): which app is in front, and what text is selected.
const { spawn } = require('child_process');
const path = require('path');
const readline = require('readline');

// In the installed app the .exe is unpacked next to app.asar (see "asarUnpack" in package.json).
const EXE = path.join(__dirname, '..', '..', 'assets', 'bin', 'winhelper.exe').replace('app.asar', 'app.asar.unpacked');

let proc = null;
let nextId = 1;
const pending = new Map(); // id → { resolve, timer }

function ensureStarted() {
  if (proc && proc.exitCode === null) return;
  proc = spawn(EXE, [], { windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'] });
  proc.on('error', () => { proc = null; });
  proc.on('exit', () => {
    proc = null;
    for (const { resolve, timer } of pending.values()) { clearTimeout(timer); resolve(null); }
    pending.clear();
  });
  readline.createInterface({ input: proc.stdout }).on('line', (line) => {
    let msg;
    try { msg = JSON.parse(line); } catch { return; }
    const p = pending.get(msg.id);
    if (!p) return;
    clearTimeout(p.timer);
    pending.delete(msg.id);
    p.resolve(msg);
  });
}

// Sends one command; resolves with the answer, or null on timeout/failure (callers must handle null).
function ask(cmd, timeoutMs) {
  return new Promise((resolve) => {
    try {
      ensureStarted();
      if (!proc) return resolve(null);
      const id = nextId++;
      const timer = setTimeout(() => { pending.delete(id); resolve(null); }, timeoutMs);
      pending.set(id, { resolve, timer });
      proc.stdin.write(`${cmd} ${id}\n`);
    } catch {
      resolve(null);
    }
  });
}

/** { process, title, x, y, w, h } of the window in front (physical pixels), or null. */
const foreground = () => ask('fg', 500);

/** { supported, text } from UI Automation, or null. supported=false → the app needs Ctrl+C instead. */
const selectedText = () => ask('sel', 700);

/** Taps the unassigned VK 0xE8 key (keeps the Start menu closed after Win + Ctrl). */
const tapMaskKey = () => ask('mask', 300);

function stop() {
  try { proc?.stdin.write('quit\n'); } catch { /* already gone */ }
}

module.exports = { foreground, selectedText, tapMaskKey, start: ensureStarted, stop };
