import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const script = readFileSync(new URL('../app/src/standaloneGecko/assets/gecko-auth/bridge.js', import.meta.url), 'utf8');

function loadBridge(url) {
  const location = new URL(url);
  const messages = [];
  const listeners = new Map();
  const pageConsole = Object.fromEntries(['error', 'warn', 'info', 'log'].map(level => [level, () => {
    // A page-world apply() cannot read the extension's privileged arguments.
    throw new Error('Permission denied to access property "length"');
  }]));
  const window = {
    isSecureContext: true,
    wrappedJSObject: { console: pageConsole },
    addEventListener(type, callback) { listeners.set(type, callback); },
  };
  window.top = window;
  vm.runInNewContext(script, {
    window, location,
    browser: { runtime: { sendNativeMessage(_app, message) {
      messages.push(message);
      return Promise.resolve({ ok: true });
    } } },
    exportFunction(fn, target, { defineAs }) { target[defineAs] = fn; },
  });
  return { window, pageConsole, location, messages, listeners };
}

const root = loadBridge('http://127.0.0.1:54321/auth/random-attempt/');
assert(root.messages.some(message => message.kind === 'bootstrap-ready' && message.secureContext));
assert.doesNotThrow(() => root.pageConsole.info('MusicKit script loaded'));
assert.doesNotThrow(() => root.pageConsole.info('MusicKit ready'));
assert(root.messages.some(message => message.message === 'MusicKit ready'));
assert.doesNotThrow(() => root.pageConsole.error({ toString() { throw new Error('unreadable'); } }));

root.pageConsole.warn('person@example.com Bearer secret-value https://apple.com/auth?private=value');
const diagnostic = root.messages.at(-1).message;
for (const privateValue of ['person@example.com', 'secret-value', '?private=value']) {
  assert(!diagnostic.includes(privateValue));
}

const token = 'test-music-user-token-long-enough';
const event = { source: root.window, origin: root.location.origin,
  data: { type: 'amatv-auth-result', nonce: 'test-nonce', token } };
root.listeners.get('message')({ ...event, origin: 'https://untrusted.example' });
assert(!root.messages.some(message => message.kind === 'authorized'));
root.listeners.get('message')(event);
assert(root.messages.some(message => message.kind === 'authorized' && message.token === token));
// Native code additionally validates the actual engine sender and per-attempt nonce.
const apple = loadBridge('https://idmsa.apple.com/appleauth/auth');
assert(!apple.listeners.has('message'));

console.log('Gecko console isolation, redaction and bridge routing checks passed.');
