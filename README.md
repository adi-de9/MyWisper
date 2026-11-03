# MyWisper

Voice typing for Windows 11, like Wispr Flow. **Hold `Win` + `Ctrl`, speak (Hindi, English or Hinglish), release.** Clean text appears wherever your cursor is: Gmail, WhatsApp, Slack, Notion, Google Docs, ChatGPT, Claude, Cursor, VS Code, anything.

```
Hold Win+Ctrl → mic records (+ offline voice detector) → Groq Whisper → Groq AI cleanup → Ctrl+V into your app
```

## Install
Run `dist\MyWisper Setup 1.1.0.exe`. After that, MyWisper lives in the system tray (the purple mic icon near the clock). Click it to open the dashboard.

## Voice typing
1. Click into any text box.
2. Hold **Win + Ctrl** and talk. A small "Listening…" pill appears.
3. Release. After about 1 second the text is typed for you. Your clipboard is put back the way it was.

What the AI cleanup does for you:
| You say | You get |
|---|---|
| "um so uh we should launch next week" | We should launch next week. |
| "meeting at 5 **actually** 6 pm" / "5 baje **nahi nahi** 6 baje" | Meeting at 6 PM. / 6 baje |
| "budget is 10 lakh **scratch that** budget is 12 lakh" | The budget is 12 lakh. |
| "hi rahul **comma** kal milte hain **question mark**" | Hi Rahul, kal milte hain? |
| "first point **new paragraph** second point" | two paragraphs |
| "things to buy **one** milk **two** bread **three** eggs" | a numbered list 1. 2. 3. |
| "कल मीटिंग है" (Devanagari from Whisper) | kal meeting hai |

Spoken commands: comma, full stop / period, question mark, exclamation mark, colon, semicolon, new line / nayi line, new paragraph / naya paragraph, open/close bracket, quote … unquote.

**Adapts to the app** (Settings → "Adapt formatting to the app"):
- Gmail / Outlook: email layout (greeting line, paragraphs, sign-off only if you say one)
- WhatsApp / Slack / Discord / Teams: casual chat message
- ChatGPT / Claude / Gemini / Cursor: clear prompt, with 3+ requests turned into a numbered list
- Notion / Google Docs / Word: proper paragraphs and headings
- VS Code / JetBrains: plain text, code names kept exactly

**Live typing** (Settings → "Live typing", off by default):
1. **Tap** Win + Ctrl (press and let go) and keep talking. The pill shows "Live".
2. Each time you pause (~0.7 s), that sentence is cleaned up and typed, while you go on speaking.
3. Tap Win + Ctrl again, press **Esc** or click the pill to stop. It also stops after a minute of silence.

Holding Win + Ctrl still works as before (everything is typed at the end). Each sentence is cleaned on its own,
so "scratch that" and spoken lists only work within one sentence; use the normal hold for those.

## The pill
A small **idle line** (40 × 5 px) always sits at the bottom of the screen (or top, per Settings):
- **Hover** it to see "Hold Win + Ctrl to talk"; **click** it to open the dashboard.
- It **grows into the recording pill** while you hold Win + Ctrl, and into **MyWisper · Rewrite** when you select text, then shrinks back.
- It **hides while a fullscreen app** (video, game, slides) is in front. Turn it off entirely in Settings → Pill.

## Rewrite selected text
Select text in any app (drag, double-click, Ctrl+A or Shift+arrows). The pill becomes **MyWisper · Rewrite**; click it to open the menu:
- **Say what to do**: then hold **Win + Ctrl** and speak, e.g. "make EOD", "EOD banao, keep it short", "make it more formal".
  MyWisper picks the matching saved prompt and asks **"Use EOD report? Yes / No"**. No shows your other prompts, plus "Use my words".
  If nothing matches, your words are used as a one-off instruction. It waits 15 s for Win + Ctrl (Esc or ✕ cancels).
- **Your pinned prompts**, one click each (Pro prompt, Polish writing, Fix grammar, Translate to English, EOD report by default).

A preview shows the result: **Replace selection**, **Copy**, **Try again** or **Cancel** (or Esc). Clicking the pill never takes focus away from your app, so the selection stays active.
The pill never appears in terminals, because the automatic Ctrl+C would stop running programs there. You can add more apps in Settings.

## Prompts (dashboard → Prompts)
- 10 built-in prompts: Pro prompt, Polish writing, Fix grammar, Translate to English, EOD report, Jira ticket, Standup update, Email reply, Commit message, LinkedIn post.
  All are editable, and **Reset to default** restores the original.
- **New prompt** adds your own. Write `{date}` for today's date. The short description helps MyWisper match what you say.
- **In menu** pins a prompt as a button in the Rewrite menu; unpinned prompts still work by voice.
- The EOD report uses plain heading lines and `•` bullets (no `#` or `**`).

## EOD from today's dictations
Tray menu or Overview → **Create today's EOD** turns everything you dictated today into an EOD report (with your EOD prompt) and opens it with **Copy**.

## Dashboard
- **Overview:** words today and in total, speaking speed (WPM), response time, time saved, Groq free-plan usage, and **Create today's EOD**.
- **History:** every dictation and rewrite with its app, timing and cost (and what you said, for spoken instructions); search, copy, delete.
- **Prompts:** your prompt library (see above).
- **Dictionary:** your words and "say X → type Y" replacements.
- **Settings:** API key with a **Test key** button, models, pill position and idle line, smart formatting, the rewrite tool, and a microphone test.

Your data is kept in `%APPDATA%\MyWisper\` (`config.json`, `dictionary.json`, `history.json`, `prompts.json`, `mywisper.log`).

## Groq free-plan limits
Each dictation sends about 900 tokens to the cleanup AI. On the free plan (8,000 tokens per minute, 200,000 per day) that's about **8 dictations per minute and about 200 per day**. Groq's Developer plan raises these limits.

## Developing
```bash
npm install          # first time
npm run build:helper # compile helper/WinHelper.cs → assets/bin/winhelper.exe (also done by dist)
npm run test:api     # check the key, Whisper and every writing feature (pass/fail per feature)
npm start            # run the app from source
npm run smoke        # self-test: sample audio → Whisper → cleanup + dashboard screenshots (never pastes)
npm run dist         # build dist\MyWisper Setup x.y.z.exe
```
Environment switches for testing:
- `MYWISPER_DEBUG=1`: log each step
- `MYWISPER_NOPASTE=1`: log the text instead of typing it
- `MYWISPER_NO_UIA=1`: ignore UI Automation, so the Ctrl+C path gets exercised

Speech and text go through the MyWisper server (`functions/`), which holds the Groq key and gives every account 15 minutes of speech a day; see docs/FIREBASE_SETUP.md step 7. `MYWISPER_API_BASE=<url>` points the app at another server (e.g. the local emulator). `.env` is never packed into the installer.

## Project layout
| File | What it does |
|---|---|
| `src/main/main.js` | Starts the app; dictation pipeline; selection pill + preview windows |
| `src/main/hotkey.js` | Global Win+Ctrl hold detection (uiohook-napi), Start-menu fix, held-key tracking |
| `src/main/selection.js` | Notices text selections (drag, double-click, Ctrl+A, Shift+arrows) |
| `src/main/transform.js` | Gets the selected text (UI Automation or Ctrl+C), runs an action, replaces it |
| `src/main/prompts.js` | **All AI instructions**: dictation cleanup, per-app styles, ✨ actions |
| `src/main/context.js` | Works out which kind of app you're typing in |
| `src/main/winfo.js` + `helper/WinHelper.cs` | Native helper: active window and selected text (UI Automation) |
| `src/main/groq.js` | Groq API calls: `transcribe`, `cleanup`, `transform`, `testKey` |
| `src/main/paste.js` | Clipboard backup → Ctrl+V / Ctrl+C → restore |
| `src/main/config.js` / `history.js` | Settings, dictionary and history files |
| `src/renderer/pill.*` + `vad.js` | Floating pill: recording, waveform, voice detector, ✨ menu |
| `src/renderer/preview.*` | Preview window for ✨ actions |
| `src/renderer/dashboard.*` | Dashboard window |
