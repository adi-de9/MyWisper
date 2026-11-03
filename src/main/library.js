// The prompt library (dashboard → Prompts): built-in prompts the user can edit, plus their own.
// Stored in %APPDATA%\MyWisper\prompts.json.
const fs = require('fs');
const path = require('path');
const { dir } = require('./config');
const { DEFAULT_PROMPTS, render, todayLabel } = require('./prompts');

const file = () => path.join(dir(), 'prompts.json');
let saved = null; // { entries: [...] } exactly as on disk
let changed = () => {}; // set by sync.js: called with an entry after a local change

function load() {
  if (saved) return saved;
  try { saved = JSON.parse(fs.readFileSync(file(), 'utf8')); } catch { saved = { entries: [] }; }
  if (!Array.isArray(saved.entries)) saved.entries = [];
  return saved;
}

function persist() {
  fs.mkdirSync(dir(), { recursive: true });
  fs.writeFileSync(file(), JSON.stringify(saved, null, 2));
}

// Saves one entry (stamped with updatedAt) and tells sync about it.
function put(entry) {
  const e = { ...entry, updatedAt: Date.now() };
  const i = saved.entries.findIndex((x) => x.id === e.id);
  if (i >= 0) saved.entries[i] = e; else saved.entries.push(e);
  persist();
  changed(e);
  return e;
}

/**
 * All prompts, built-ins first (in default order), then the user's own.
 * Built-ins come from DEFAULT_PROMPTS with the user's edits on top, so new app versions can add
 * new defaults without losing anything the user changed.
 */
function list() {
  const edits = new Map(load().entries.map((e) => [e.id, e]));
  const builtIns = DEFAULT_PROMPTS
    .filter((d) => !edits.get(d.id)?.deleted)
    .map((d) => {
      const e = edits.get(d.id) || {};
      const edited = ['name', 'description', 'prompt'].some((k) => e[k] !== undefined && e[k] !== d[k]);
      return { ...d, ...e, builtIn: true, edited };
    });
  const own = saved.entries.filter((e) => !DEFAULT_PROMPTS.some((d) => d.id === e.id) && !e.deleted)
    .map((e) => ({ temperature: 0.3, pinned: false, ...e, builtIn: false }));
  return [...builtIns, ...own];
}

const get = (id) => list().find((p) => p.id === id) || null;

function slug(name) {
  return (name || 'prompt').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 30) || 'prompt';
}

/** Creates or updates a prompt. Returns the saved entry. */
function save(entry) {
  load();
  let id = entry.id;
  if (!id) {
    id = slug(entry.name);
    while (list().some((p) => p.id === id)) id = `${slug(entry.name)}-${Math.random().toString(36).slice(2, 5)}`;
  }
  const clean = {
    id,
    name: String(entry.name || 'Untitled prompt').trim().slice(0, 60),
    description: String(entry.description || '').trim().slice(0, 200),
    prompt: String(entry.prompt || '').trim(),
    pinned: !!entry.pinned,
  };
  if (entry.temperature !== undefined) clean.temperature = Number(entry.temperature) || 0;
  const old = saved.entries.find((e) => e.id === id);
  put({ ...(old?.deleted ? {} : old), ...clean, deleted: false });
  return get(id);
}

/** Only changes the "Show in menu" switch. */
function setPinned(id, pinned) {
  load();
  const old = saved.entries.find((e) => e.id === id) || { id };
  put({ ...old, pinned: !!pinned });
}

/** Deletes a user's own prompt. Built-ins can't be deleted, only reset. */
function remove(id) {
  load();
  if (DEFAULT_PROMPTS.some((d) => d.id === id)) return false;
  put({ id, deleted: true }); // remembered, so other devices delete it too
  return true;
}

/** Puts a built-in prompt back to its default text (keeps the "Show in menu" choice). */
function reset(id) {
  load();
  const e = saved.entries.find((x) => x.id === id);
  put(e?.pinned !== undefined ? { id, pinned: e.pinned } : { id });
  return get(id);
}

/** A change from another device (Firestore): the newer version wins. Returns true if it changed anything. */
function applyRemote(doc) {
  load();
  const i = saved.entries.findIndex((x) => x.id === doc.id);
  if (i >= 0 && (saved.entries[i].updatedAt || 0) >= (doc.updatedAt || 0)) return false;
  if (i >= 0) saved.entries[i] = doc; else saved.entries.push(doc);
  persist();
  return true;
}

/** Entries changed after a time (for the first upload and catch-up after being offline). */
function changesSince(ms) {
  return load().entries.map((e) => (e.updatedAt ? e : { ...e, updatedAt: 1 })).filter((e) => e.updatedAt > ms);
}

module.exports = {
  list, get, save, setPinned, remove, reset, render, todayLabel, applyRemote, changesSince,
  onChange: (fn) => { changed = fn; },
};
