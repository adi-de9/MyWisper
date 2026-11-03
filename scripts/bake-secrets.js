// Copies what the installed app needs (it has no .env) into a gitignored JSON file next to the code:
//   src/main/oauth-client.json – Google OAuth desktop client (Google treats this "secret" as public)
// The sync secret (MYWISPER_DATA_SECRET) and the Groq key stay on the server (functions/), never in the app.
require('dotenv').config({ path: require('path').join(__dirname, '..', '.env'), quiet: true });
const fs = require('fs');
const path = require('path');

const need = ['GOOGLE_OAUTH_CLIENT_ID', 'GOOGLE_OAUTH_CLIENT_SECRET'];
const missing = need.filter((k) => !process.env[k]);
if (missing.length) {
  console.error(`Missing in .env: ${missing.join(', ')} (see docs/FIREBASE_SETUP.md)`);
  process.exit(1);
}
const dir = path.join(__dirname, '..', 'src', 'main');
fs.writeFileSync(path.join(dir, 'oauth-client.json'),
  JSON.stringify({ id: process.env.GOOGLE_OAUTH_CLIENT_ID, secret: process.env.GOOGLE_OAUTH_CLIENT_SECRET }));
fs.rmSync(path.join(dir, 'app-secrets.json'), { force: true }); // left over from older builds
console.log('Wrote src/main/oauth-client.json');
