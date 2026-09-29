// Works out what kind of app the user is typing into, so dictation can be formatted to fit.
// Input: { process, title } from winfo.foreground(). Output: { app, category }.

// Checked top to bottom; the first match wins. "title" rules matter for browser tabs (Chrome/Edge…).
const RULES = [
  { category: 'email', title: /gmail|outlook|yahoo mail|proton ?mail|\bmail\b/i, process: /^(outlook|olk|thunderbird|hxoutlook)$/i },
  { category: 'chat', title: /whatsapp|slack|discord|telegram|microsoft teams|messenger|signal/i, process: /^(whatsapp|whatsapp\.root|slack|discord|telegram|ms-teams|teams|signal)$/i },
  { category: 'ai', title: /chatgpt|claude|gemini|perplexity|copilot|deepseek|grok|mistral|poe\b/i, process: /^(chatgpt|claude|cursor|windsurf)$/i },
  { category: 'docs', title: /google docs|notion|word online|confluence|obsidian/i, process: /^(winword|notion|obsidian|wordpad)$/i },
  { category: 'code', title: /visual studio code|jetbrains/i, process: /^(code|code - insiders|idea64|pycharm64|webstorm64|devenv|rider64|zed|sublime_text)$/i },
];

const APP_NAMES = {
  chrome: 'Chrome', msedge: 'Edge', firefox: 'Firefox', brave: 'Brave', opera: 'Opera',
  code: 'VS Code', cursor: 'Cursor', notepad: 'Notepad', winword: 'Word', outlook: 'Outlook',
  'whatsapp.root': 'WhatsApp', whatsapp: 'WhatsApp', slack: 'Slack', discord: 'Discord', claude: 'Claude',
};

function detect(fg) {
  if (!fg) return { app: 'Unknown', category: 'general' };
  const process = (fg.process || '').toLowerCase();
  const title = fg.title || '';
  const rule = RULES.find((r) => r.title.test(title) || r.process.test(process));
  // For browsers, the tab title says more than "chrome" does, e.g. "Inbox - Gmail - Google Chrome".
  const site = title.split(' - ').slice(-2, -1)[0];
  const base = APP_NAMES[process] || fg.process || 'Unknown';
  const isBrowser = /^(chrome|msedge|firefox|brave|opera|vivaldi|arc)$/.test(process);
  return {
    app: isBrowser && site ? `${site.trim()} (${base})` : base,
    category: rule ? rule.category : 'general',
  };
}

module.exports = { detect };
