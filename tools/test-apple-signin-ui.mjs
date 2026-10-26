import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const script = readFileSync(new URL('../app/src/main/assets/gecko-auth/signin-ui.js', import.meta.url), 'utf8');
function page(host = 'idmsa.apple.com') {
  const frames = [];
  const nodes = [];
  const callbacks = [];
  const observers = [];
  const doc = {
    location: { hostname: host }, activeElement: null,
    querySelectorAll(selector) { return selector === 'iframe' ? frames : nodes; },
    getElementById(id) { return nodes.find(node => node.id === id) || null; },
    defaultView: {
      requestAnimationFrame(callback) { callbacks.push(callback); },
      MutationObserver: class {
        constructor(callback) { observers.push(callback); }
        observe() {}
      },
    },
  };
  function node(text, tagName = 'BUTTON', attrs = {}) {
    const result = {
      textContent: text, tagName, disabled: false, parentElement: null, nextElementSibling: null,
      style: { setProperty(name, value, priority) { this[name] = value; this.priority = priority; } },
      getAttribute(name) { return attrs[name] || null; },
      setAttribute(name, value) { attrs[name] = value; },
      contains(other) { return result === other; },
      focus() { doc.activeElement = result; },
    };
    nodes.push(result);
    return result;
  }
  return { doc, node,
    run() { vm.runInNewContext(script, { document: doc }); },
    mutate() { observers.forEach(callback => callback()); while (callbacks.length) callbacks.shift()(); },
    observerCount() { return observers.length; },
  };
}
const hidden = node => node.getAttribute('data-amatv-phone-signin') === 'hidden';

for (const [passwordLabel, phoneLabel] of [
  ['繼續使用密碼登入', '使用 iPhone 登入'],
  ['继续使用密码登录', '使用 iPhone 登录'],
  ['Continue with Password', 'Sign in with Passkey'],
  ['Continue with Password', 'Sign in with iPhone'],
]) {
  const fixture = page();
  const password = fixture.node(passwordLabel);
  const phone = fixture.node(phoneLabel);
  const verification = fixture.node('傳送驗證碼到 iPhone');
  const recovery = fixture.node('Forgot password?', 'A');
  fixture.doc.activeElement = phone;
  fixture.run();
  assert(hidden(phone), phoneLabel);
  assert(!hidden(password));
  assert(!hidden(verification));
  assert(!hidden(recovery));
  assert.equal(fixture.doc.activeElement, password);
  assert.equal(phone.style.display, 'none');
  assert.equal(phone.style.priority, 'important');
  assert.equal(phone.hidden, true);
  fixture.run();
  assert.equal(fixture.observerCount(), 1, 'repeated WebView injection must be idempotent');
}

const dynamic = page();
dynamic.node('繼續使用密碼登入');
dynamic.run();
const latePhone = dynamic.node('使用 iPhone 登入');
assert(!hidden(latePhone));
dynamic.mutate();
assert(hidden(latePhone), 'React-rendered controls must also be hidden');

const unrelated = page('music.apple.com');
const phone = unrelated.node('Sign in with iPhone');
unrelated.run();
assert(!hidden(phone));
assert.equal(unrelated.observerCount(), 0);
console.log('Apple sign-in UI: localized phone buttons, dynamic rendering, focus and MFA preservation passed.');
