// Run with: npm run smoke
// Starts the app in self-test mode: sample clip → Whisper → cleanup (no paste), then screenshots the dashboard.
const { spawn } = require('child_process');
const os = require('os');
const path = require('path');
const electron = require('electron'); // path to electron.exe when required from Node

const outDir = process.argv[2] || path.join(os.tmpdir(), 'mywisper-smoke');
spawn(electron, ['.'], {
  cwd: path.join(__dirname, '..'),
  stdio: 'inherit',
  env: { ...process.env, MYWISPER_SMOKE: outDir },
}).on('exit', (code) => process.exit(code ?? 0));
