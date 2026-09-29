// Selected-text actions: get the selected text, rewrite it with a prompt from the library, put it back.
const config = require('./config');
const history = require('./history');
const groq = require('./groq');
const winfo = require('./winfo');
const library = require('./library');
const { copySelection, pasteText, copyText } = require('./paste');
const { detect } = require('./context');

/** Text from UI Automation if we already have it, otherwise copy it with Ctrl+C. */
async function getSelectedText(selection) {
  if (selection?.text) return selection.text;
  const copied = await copySelection();
  return copied.trim() ? copied : '';
}

/** Display name for a job: the prompt's name, or "Your instruction" for a one-off spoken request. */
function label(job) {
  if (job.label) return job.label;
  return job.promptId ? (library.get(job.promptId)?.name || job.promptId) : 'Your instruction';
}

/**
 * Runs a job: { promptId (null = one-off), extra, text, fg, instruction, label }.
 * Returns { text, ms } and logs it to history.
 */
async function run(job) {
  const cfg = config.getConfig();
  const entry = job.promptId ? library.get(job.promptId) : null;
  const out = await groq.transform({
    apiKey: cfg.groqApiKey,
    system: library.render(entry, job.extra),
    temperature: entry?.temperature ?? 0.3,
    text: job.text,
    model: cfg.transformModel,
  });
  const llm = cfg.prices.llmPerMillion[cfg.transformModel] ?? { input: 0, output: 0 };
  history.add({
    kind: 'transform', action: job.promptId || 'custom', label: label(job),
    instruction: job.instruction, extra: job.extra,
    app: job.fg ? detect(job.fg).app : 'MyWisper', status: out.text ? 'ok' : 'error',
    rawText: job.text, cleanText: out.text, llmModel: cfg.transformModel, llmMs: out.ms, totalMs: out.ms,
    promptTokens: out.promptTokens, completionTokens: out.completionTokens, words: 0, wpm: 0, audioSec: 0,
    costUsd: (out.promptTokens / 1e6) * llm.input + (out.completionTokens / 1e6) * llm.output,
    error: out.text ? undefined : 'Empty answer from AI',
  });
  return out;
}

/**
 * Replaces the selection in the original app with `text`.
 * If the user switched to another app meanwhile, only copies it (returns "copied").
 */
async function replace(text, fg) {
  const now = await winfo.foreground();
  if (!now || !fg || now.process !== fg.process) {
    await copyText(text);
    return 'copied';
  }
  await pasteText(text);
  return 'replaced';
}

module.exports = { getSelectedText, run, replace, label };
