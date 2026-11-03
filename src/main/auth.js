// Google sign-in for the Windows app, then Firebase.
// Desktop apps can't show Google's login page inside themselves, so: open Google in the user's browser
// (OAuth with PKCE), catch the answer on http://127.0.0.1:<port>, swap the code for tokens, and sign in
// to Firebase with the Google ID token. The Google refresh token is kept encrypted (Windows DPAPI via
// safeStorage) so the next start signs in silently.
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { shell, safeStorage } = require('electron');
const { initializeApp } = require('firebase/app');
const { initializeAuth, inMemoryPersistence, GoogleAuthProvider, signInWithCredential, signOut: fbSignOut } = require('firebase/auth');
const { dir } = require('./config');
const firebaseConfig = require('./firebase-config');

const SIGN_IN_TIMEOUT_MS = 5 * 60_000;
const tokenFile = () => path.join(dir(), 'auth.bin');       // encrypted Google refresh token
const profileFile = () => path.join(dir(), 'account.json'); // { uid, email, name, photo } (not secret)

const app = initializeApp(firebaseConfig);
const auth = initializeAuth(app, { persistence: inMemoryPersistence });

let profile = null;        // who is signed in (also known while offline)
let firebaseReady = false; // signed in to Firebase right now (needed for sync)
const listeners = [];

function oauthClient() {
  // While developing: from .env. In the installed app: baked in at build time (scripts/build-helper.js).
  let id = process.env.GOOGLE_OAUTH_CLIENT_ID;
  let secret = process.env.GOOGLE_OAUTH_CLIENT_SECRET;
  if (!id) {
    try { ({ id, secret } = require('./oauth-client.json')); } catch { /* not built in */ }
  }
  if (!id || !secret) throw new Error('Google sign-in is not set up (GOOGLE_OAUTH_CLIENT_ID / _SECRET missing).');
  return { id, secret };
}

const b64url = (buf) => buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

async function tokenRequest(params) {
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(json.error_description || json.error || `Google said ${res.status}`);
    err.code = json.error;
    throw err;
  }
  return json;
}

// Shown in the browser tab after Google redirects back.
const PAGE = (title, text) => `<!doctype html><meta charset="utf-8"><title>MyWisper</title>
<body style="margin:0;height:100vh;display:grid;place-items:center;background:#0a0a0a;color:#ededed;font:15px system-ui,sans-serif">
<div style="text-align:center"><h2 style="font-weight:600;margin:0 0 8px">${title}</h2><p style="color:#a1a1a1;margin:0">${text}</p></div>`;

/**
 * Starts a one-time web server on 127.0.0.1 for Google's redirect.
 * Resolves with { redirectUri, code } where code is a promise of the authorization code.
 */
function listenForCode(state) {
  return new Promise((ready, fail) => {
    let gotCode;
    let gotError;
    const code = new Promise((res, rej) => { gotCode = res; gotError = rej; });
    const server = http.createServer((req, res) => {
      const url = new URL(req.url, 'http://127.0.0.1');
      if (url.pathname !== '/') { res.writeHead(404); res.end(); return; }
      const error = url.searchParams.get('error');
      const value = url.searchParams.get('code');
      const ok = !error && value && url.searchParams.get('state') === state;
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(ok ? PAGE('Signed in to MyWisper', 'You can close this tab and go back to the app.')
        : PAGE('Sign-in cancelled', 'Go back to MyWisper and try again.'));
      clearTimeout(timer);
      server.close();
      if (ok) gotCode(value);
      else gotError(new Error(error === 'access_denied' ? 'Sign-in was cancelled.' : 'Sign-in failed.'));
    });
    const timer = setTimeout(() => { server.close(); gotError(new Error('Sign-in timed out.')); }, SIGN_IN_TIMEOUT_MS);
    server.on('error', fail);
    server.listen(0, '127.0.0.1', () => ready({ redirectUri: `http://127.0.0.1:${server.address().port}`, code }));
  });
}

async function firebaseSignIn(idToken) {
  const cred = await signInWithCredential(auth, GoogleAuthProvider.credential(idToken));
  const u = cred.user;
  profile = { uid: u.uid, email: u.email, name: u.displayName, photo: u.photoURL };
  fs.mkdirSync(dir(), { recursive: true });
  fs.writeFileSync(profileFile(), JSON.stringify(profile));
  firebaseReady = true;
  emit();
}

/** Full sign-in in the browser. Resolves with the profile. */
async function signIn() {
  const client = oauthClient();
  const verifier = b64url(crypto.randomBytes(48));
  const challenge = b64url(crypto.createHash('sha256').update(verifier).digest());
  const state = b64url(crypto.randomBytes(16));

  const { redirectUri, code: codePromise } = await listenForCode(state);
  const url = new URL('https://accounts.google.com/o/oauth2/v2/auth');
  url.search = new URLSearchParams({
    client_id: client.id, redirect_uri: redirectUri, response_type: 'code', scope: 'openid email profile',
    code_challenge: challenge, code_challenge_method: 'S256', state,
    access_type: 'offline', prompt: 'consent select_account',
  });
  await shell.openExternal(url.toString());

  const code = await codePromise;
  const tokens = await tokenRequest({
    client_id: client.id, client_secret: client.secret, code, code_verifier: verifier,
    grant_type: 'authorization_code', redirect_uri: redirectUri,
  });
  if (tokens.refresh_token && safeStorage.isEncryptionAvailable()) {
    fs.mkdirSync(dir(), { recursive: true });
    fs.writeFileSync(tokenFile(), safeStorage.encryptString(tokens.refresh_token));
  }
  await firebaseSignIn(tokens.id_token);
  return profile;
}

/**
 * On start: who was signed in last time (works offline), then a silent Firebase sign-in in the
 * background. Returns the profile or null.
 */
function restore() {
  try { profile = JSON.parse(fs.readFileSync(profileFile(), 'utf8')); } catch { profile = null; }
  if (profile) reconnect();
  return profile;
}

let retryTimer = null;
async function reconnect() {
  clearTimeout(retryTimer);
  if (!profile || firebaseReady) return;
  let refreshToken;
  try {
    refreshToken = safeStorage.decryptString(fs.readFileSync(tokenFile()));
  } catch {
    await signOut(); // token missing or unreadable: sign in again
    return;
  }
  try {
    const client = oauthClient();
    const tokens = await tokenRequest({
      client_id: client.id, client_secret: client.secret, refresh_token: refreshToken, grant_type: 'refresh_token',
    });
    await firebaseSignIn(tokens.id_token);
  } catch (err) {
    if (err.code === 'invalid_grant') { await signOut(); return; } // access revoked
    retryTimer = setTimeout(reconnect, 60_000); // offline: try again in a minute
  }
}

/** Firebase ID token for the MyWisper server (refreshed by Firebase; reconnects first if needed). */
async function idToken(force = false) {
  if (!auth.currentUser && profile) await reconnect();
  if (!auth.currentUser) throw new Error(profile ? 'Offline - check internet' : 'Sign in to MyWisper first');
  return auth.currentUser.getIdToken(force);
}

async function signOut() {
  clearTimeout(retryTimer);
  for (const f of [tokenFile(), profileFile(), path.join(dir(), 'datakey.bin')]) { try { fs.unlinkSync(f); } catch { /* not there */ } }
  profile = null;
  firebaseReady = false;
  try { await fbSignOut(auth); } catch { /* ignore */ }
  emit();
}

function emit() { for (const fn of listeners) fn(profile, firebaseReady); }

module.exports = {
  app,
  signIn, restore, signOut, reconnect, idToken,
  user: () => profile,
  isOnline: () => firebaseReady,
  onChange: (fn) => listeners.push(fn),
};
