# Firebase setup (one time, ~10 minutes)

MyWisper uses Firebase for Google sign-in and to sync history, prompts and the dictionary between Windows and Android.
The free Spark plan is enough.

## 1. Create the project
1. Open https://console.firebase.google.com → **Create a project** → name it `mywisper` → Google Analytics: off → Create.

## 2. Turn on Google sign-in
1. **Build → Authentication → Get started → Sign-in method → Google → Enable**.
2. Pick your email as the support email → **Save**.

## 3. Create the database
1. **Build → Firestore Database → Create database**.
2. Location: `asia-south1 (Mumbai)` → **Start in production mode** → Create.
3. Open the **Rules** tab, replace everything with the contents of `firebase/firestore.rules` → **Publish**.

## 4. Add the Android app
1. Project overview (home) → **Add app → Android**.
2. Package name: `com.mywisper.app`, nickname `MyWisper Android`.
3. Debug signing SHA-1: `E2:DE:60:2F:82:3C:EE:33:D6:05:EF:B9:57:CB:AF:5C:26:A3:29:1C` → Register app.
4. **Download `google-services.json`** and put it in `D:\mywisper\android\app\`. Skip the remaining steps (the code is done).
5. Project settings (gear) → **Your apps → MyWisper Android → Add fingerprint**, add the release SHA-1:
   `C7:A4:B0:1C:4C:83:9A:07:F1:D0:26:18:66:3A:15:EE:B7:54:CD:D0`

## 5. Add a Web app (gives the config the Windows app needs)
1. Project overview → **Add app → Web (</>)**, nickname `MyWisper Windows`, no hosting → Register.
2. Copy the `firebaseConfig` block it shows (apiKey, authDomain, projectId, appId…) and send it to Claude
   (these values are not secret — security comes from the rules in step 3).

## 6. Desktop OAuth client (Google login in the browser for Windows)
1. Open https://console.cloud.google.com → top bar: select the `mywisper` project.
2. **APIs & Services → OAuth consent screen**: if asked, choose **External**, app name `MyWisper`, your email → Save.
   Under **Audience / Test users** add your Google account(s) (or click **Publish app** so anyone can sign in).
3. **APIs & Services → Credentials → Create credentials → OAuth client ID → Application type: Desktop app**, name `MyWisper Windows` → Create.
4. Copy the **Client ID** and **Client secret** into `D:\mywisper\.env`:
   ```
   GOOGLE_OAUTH_CLIENT_ID=...apps.googleusercontent.com
   GOOGLE_OAUTH_CLIENT_SECRET=...
   ```
   (For a desktop app Google treats this "secret" as public; it is still kept out of git.)
5. Back in Firebase **Authentication → Sign-in method → Google → Web SDK configuration → Safelisted client IDs**,
   add that same Client ID → Save. (Lets Firebase accept tokens from the Windows sign-in.)

## 7. The MyWisper server (Groq + free limit + sync keys)
Both apps send speech and text to a small server in this project (`functions/`), not to Groq directly.
The server holds the Groq key, gives every account **15 minutes of speech a day**, and hands each signed-in
user their own sync key. Neither the Groq key nor the sync secret is inside the apps any more.

1. Firebase console → ⚙ → **Usage and billing** → switch to the **Blaze** plan (pay as you go; Cloud Functions need it).
   Then Google Cloud console → Billing → **Budgets & alerts** → add a budget (e.g. ₹500/month) with email alerts.
2. Groq console → **Settings → Billing** → move to the paid Developer tier. The free tier is shared by all users
   (about 2,000 requests and 8 hours of audio a day in total).
3. Store the two secrets on the server (each command asks you to paste the value; it is never shown):
   ```
   npx firebase functions:secrets:set GROQ_API_KEY --project mywisper-c041c
   npx firebase functions:secrets:set DATA_SECRET --project mywisper-c041c
   ```
   `DATA_SECRET` must be **exactly** the `MYWISPER_DATA_SECRET` value from `.env`, so data that is already
   synced stays readable.
4. Deploy: `npm run deploy:server` (functions + Firestore rules).

Changing the limits without a deploy: Firestore → collection `config` → document `limits` with any of
`dailyAudioSec` (default 900), `dailyTokens` (200000), `perMinute` (40) and `enabled` (set `false` to pause everything).

Testing locally: `node scripts/serve-local.js` runs the server in the emulators (secrets from `.env`), then
`MYWISPER_API_BASE=http://127.0.0.1:5001/mywisper-c041c/asia-south1/api` makes the Windows app use it;
`npx electron scripts/check-server.js` checks the whole path with your signed-in account.
Server tests: `npm run test:functions`.
