// Every dictation is saved to %APPDATA%\MyWisper\history.json (newest first, last 2000 kept).
const fs = require('fs');
const path = require('path');
const { dir } = require('./config');

const MAX_ENTRIES = 2000;
let entries = null;
let saveTimer = null;

const file = () => path.join(dir(), 'history.json');

function load() {
  if (entries) return entries;
  try { entries = JSON.parse(fs.readFileSync(file(), 'utf8')); } catch { entries = []; }
  return entries;
}

// Write to disk shortly after changes (so many quick changes = one write).
function scheduleSave() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    fs.mkdirSync(dir(), { recursive: true });
    fs.writeFile(file(), JSON.stringify(entries), () => {});
  }, 300);
}

function add(entry) {
  load().unshift({ id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`, time: new Date().toISOString(), ...entry });
  if (entries.length > MAX_ENTRIES) entries.length = MAX_ENTRIES;
  scheduleSave();
  return entries[0];
}

function list() { return load(); }

function remove(id) {
  entries = load().filter((e) => e.id !== id);
  scheduleSave();
}

function clear() {
  entries = [];
  scheduleSave();
}

// Make sure pending changes are written before the app quits.
function flush() {
  if (!entries) return;
  clearTimeout(saveTimer);
  fs.mkdirSync(dir(), { recursive: true });
  fs.writeFileSync(file(), JSON.stringify(entries));
}

module.exports = { add, list, remove, clear, flush };
