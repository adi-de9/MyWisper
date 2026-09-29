// Settings (config.json) and custom dictionary (dictionary.json), stored in %APPDATA%\MyWisper.
const { app } = require('electron');
const fs = require('fs');
const path = require('path');

const DEFAULT_CONFIG = {
  groqApiKey: '',
  sttModel: 'whisper-large-v3',
  llmModel: 'qwen/qwen3.8-27b',
  language: 'auto',            // "auto" | "hi" | "en" (hint for Whisper)
  typingWpm: 40,               // used for "time saved" on the dashboard
  pillPosition: 'bottom',      // "bottom" | "top" of the screen
  showIdleLine: true,          // the small bar that is always visible (hidden in fullscreen apps)
  smartFormat: true,           // adapt dictation formatting to the app (email, chat, AI prompt…)
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
  // First run: take the key from .env (only exists while developing).
  if (!config.groqApiKey && process.env.GROQ_API_KEY) {
    config.groqApiKey = process.env.GROQ_API_KEY;
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

function saveDictionary(dict) {
  dictionary = {
    words: [...new Set((dict.words || []).map((w) => w.trim()).filter(Boolean))],
    replacements: dict.replacements || {},
  };
  writeJson(dictFile(), dictionary);
  return dictionary;
}

module.exports = { getConfig, saveConfig, getDictionary, saveDictionary, dir };
