// Settings (config.json) and custom dictionary (dictionary.json), stored in %APPDATA%\MyWisper.
const { app } = require('electron');
const fs = require('fs');
const path = require('path');

const DEFAULT_CONFIG = {
  sttModel: 'whisper-large-v3',
  llmModel: 'qwen/qwen3.8-27b',
  language: 'auto',            // "auto" | "hi" | "en" (hint for Whisper)
  typingWpm: 40,               // used for "time saved" on the dashboard
  pillPosition: 'bottom',      // "bottom" | "top" of the screen
  showIdleLine: true,          // the small bar that is always visible (hidden in fullscreen apps)
  smartFormat: true,
  liveTyping: false,           // tap Win + Ctrl: each sentence is typed when you pause (hold = type at the end)           // adapt dictation formatting to the app (email, chat, AI prompt…)
  selectionTool: true,         // show the ✨ MyWisper pill when text is selected
  // Apps where the selection pill never appears (process names). Terminals: Ctrl+C would stop programs.
  excludedApps: ['WindowsTerminal', 'cmd', 'powershell', 'pwsh', 'conhost'],
  transformModel: 'openai/gpt-oss-120b', // model for Pro Prompt / Polish / Grammar / Translate
  // Estimated prices in USD. Update from https://groq.com/pricing if they change.
  prices: {
    sttPerHour: { 'whisper-large-v3': 0.111, 'whisper-large-v3-turbo': 0.04 },
    llmPerMillion: {
      'qwen/qwen3.8-27b': { input: 0.29, output: 0.59 },
      'openai/gpt-oss-20b': { input: 0.075, output: 0.30 },
      'openai/gpt-oss-120b': { input: 0.15, output: 0.60 },
    },
  },
};

const DEFAULT_DICTIONARY = {
  words: ['Aditya', 'MyWisper'],
  replacements: { 'my whisper': 'MyWisper' },
};

const dir = () => app.getPath('userData');
const configFile = () => path.join(dir(), 'config.json');
const dictFile = () => path.join(dir(), 'dictionary.json');

function readJson(file, fallback) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return fallback; }
}
function writeJson(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(data, null, 2));
}

let config = null;
let dictionary = null;

function getConfig() {
  if (config) return config;
  const saved = readJson(configFile(), {});
  config = { ...DEFAULT_CONFIG, ...saved, prices: { ...DEFAULT_CONFIG.prices, ...(saved.prices || {}) } };
  // Older versions kept the user's own Groq key here; the server holds the key now, so forget it.
  // The models are no longer a user choice either (MyWisper pays for them): always the defaults.
  const fixed = { sttModel: DEFAULT_CONFIG.sttModel, llmModel: DEFAULT_CONFIG.llmModel, transformModel: DEFAULT_CONFIG.transformModel };
  if ('groqApiKey' in config || Object.entries(fixed).some(([k, v]) => config[k] !== v)) {
    delete config.groqApiKey;
    Object.assign(config, fixed);
    writeJson(configFile(), config);
  }
  if (!fs.existsSync(configFile())) writeJson(configFile(), config);
  return config;
}

function saveConfig(partial) {
  config = { ...getConfig(), ...partial };
  writeJson(configFile(), config);
  return config;
}

function getDictionary() {
  if (dictionary) return dictionary;
  dictionary = readJson(dictFile(), null);
  if (!dictionary) {
    dictionary = DEFAULT_DICTIONARY;
    writeJson(dictFile(), dictionary);
  }
  dictionary.words ||= [];
  dictionary.replacements ||= {};
  return dictionary;
}

let dictChanged = () => {}; // set by sync.js

function saveDictionary(dict) {
  dictionary = {
    words: [...new Set((dict.words || []).map((w) => w.trim()).filter(Boolean))],
    replacements: dict.replacements || {},
    updatedAt: Date.now(),
  };
  writeJson(dictFile(), dictionary);
  dictChanged(dictionary);
  return dictionary;
}

/** The dictionary from another device (Firestore): the newer one wins. Returns true if it changed. */
function applyRemoteDictionary(doc) {
  if ((getDictionary().updatedAt || 0) >= (doc.updatedAt || 0)) return false;
  dictionary = { words: doc.words || [], replacements: doc.replacements || {}, updatedAt: doc.updatedAt };
  writeJson(dictFile(), dictionary);
  return true;
}

module.exports = {
  getConfig, saveConfig, getDictionary, saveDictionary, applyRemoteDictionary, dir,
  onDictionaryChange: (fn) => { dictChanged = fn; },
};
