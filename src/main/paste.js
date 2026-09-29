// Types text into the focused app: back up clipboard → copy text → Ctrl+V → restore clipboard.
// (Electron 44+ clipboard API: read()/write()/readText()/writeText() all return promises.)
const { clipboard, ClipboardItem } = require('electron');
const hotkey = require('./hotkey');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Copies every format currently on the clipboard (text, HTML, images, …) so we can put it back.
async function backupClipboard() {
  try {
    const items = await clipboard.read();
    const copies = [];
    for (const item of items) {
      const data = {};
      for (const type of item.types) {
        try { data[type] = await item.getType(type); } catch { /* skip formats we can't read */ }
      }
      if (Object.keys(data).length) copies.push(new ClipboardItem(data));
    }
    return copies;
  } catch {
    return null; // backup failed → we'll still paste, just can't restore
  }
}

async function restoreClipboard(copies) {
  if (!copies) return;
  if (copies.length) await clipboard.write(copies);
  else clipboard.clear();
}

async function pasteText(text) {
  const backup = await backupClipboard();
  await clipboard.writeText(text);
  await hotkey.waitForRelease();
  await sleep(40);
  hotkey.pressPaste();
  // Give the target app time to read the clipboard before we put the old content back.
  await sleep(400);
  try {
    if ((await clipboard.readText()) === text) await restoreClipboard(backup);
  } catch { /* leaving our text on the clipboard is harmless */ }
}

// Copies the current selection in the focused app with Ctrl+C and returns it ('' if nothing
// was selected). The user's clipboard is put back afterwards.
async function copySelection() {
  const backup = await backupClipboard();
  const marker = `mywisper-empty-${Date.now()}`;
  await clipboard.writeText(marker);
  await hotkey.waitForRelease();
  hotkey.pressCopy();
  let text = marker;
  for (let waited = 0; waited < 500 && text === marker; waited += 25) {
    await sleep(25);
    try { text = await clipboard.readText(); } catch { /* clipboard busy, retry */ }
  }
  try {
    if (backup) await restoreClipboard(backup);
    else clipboard.clear(); // couldn't back up: at least don't leave our marker behind
  } catch { /* ignore */ }
  return text === marker ? '' : text;
}

// Puts text on the clipboard for the user (used by "Copy" buttons).
const copyText = (text) => clipboard.writeText(text);

module.exports = { pasteText, copySelection, copyText };
