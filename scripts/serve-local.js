// Runs the MyWisper server locally (Firebase emulators: functions + Firestore) for testing the apps
// without deploying. Secrets come from .env (GROQ_API_KEY, MYWISPER_DATA_SECRET) and are never printed.
// Then start the Windows app with MYWISPER_API_BASE=http://127.0.0.1:5001/mywisper-c041c/asia-south1/api
require('dotenv').config({ path: require('path').join(__dirname, '..', '.env'), quiet: true });
const { spawn } = require('child_process');

const env = { ...process.env, DATA_SECRET: process.env.MYWISPER_DATA_SECRET };
env.FUNCTIONS_DISCOVERY_TIMEOUT = '60'; // seconds to load functions/ (default 10 is too short on a busy PC)
const pathKey = Object.keys(env).find((k) => k.toUpperCase() === 'PATH') || 'PATH'; // "Path" on Windows
env[pathKey] = `C:\\Program Files\\Android\\Android Studio\\jbr\\bin;${env[pathKey]}`; // Java 21 for the Firestore emulator
if (!env.GROQ_API_KEY || !env.DATA_SECRET) { console.error('GROQ_API_KEY / MYWISPER_DATA_SECRET missing in .env'); process.exit(1); }
spawn('npx', ['firebase', 'emulators:start', '--only', 'functions,firestore', '--project', 'mywisper-c041c'],
  { env, stdio: 'inherit', shell: true }).on('exit', (c) => process.exit(c ?? 0));
