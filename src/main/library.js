// The prompt library (dashboard → Prompts): built-in prompts the user can edit, plus their own.
// Stored in %APPDATA%\MyWisper\prompts.json.
const fs = require('fs');
const path = require('path');
const { dir } = require('./config');
const { DEFAULT_PROMPTS, render, todayLabel } = require('./prompts');

const file = () => path.join(dir(), 'prompts.json');
let saved = null; // { entries: [...] } exactly as on disk

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
  const i = saved.entries.findIndex((e) => e.id === id);
  if (i >= 0) saved.entries[i] = { ...saved.entries[i], ...clean };
  else saved.entries.push(clean);
  persist();
  return get(id);
}

/** Only changes the "Show in menu" switch. */
function setPinned(id, pinned) {
  load();
  const i = saved.entries.findIndex((e) => e.id === id);
  if (i >= 0) saved.entries[i].pinned = !!pinned;
  else saved.entries.push({ id, pinned: !!pinned });
  persist();
}

/** Deletes a user's own prompt. Built-ins can't be deleted, only reset. */
function remove(id) {
  load();
  if (DEFAULT_PROMPTS.some((d) => d.id === id)) return false;
  saved.entries = saved.entries.filter((e) => e.id !== id);
  persist();
  return true;
}

/** Puts a built-in prompt back to its default text (keeps the "Show in menu" choice). */
function reset(id) {
  load();
  const e = saved.entries.find((x) => x.id === id);
  saved.entries = saved.entries.filter((x) => x.id !== id);
  if (e?.pinned !== undefined) saved.entries.push({ id, pinned: e.pinned });
  persist();
  return get(id);
}

module.exports = { list, get, save, setPinned, remove, reset, render, todayLabel };
