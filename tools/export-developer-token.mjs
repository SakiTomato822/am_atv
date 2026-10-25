import { createPrivateKey, sign, verify } from 'node:crypto';
import { readFileSync, writeFileSync, chmodSync } from 'node:fs';
import { resolve } from 'node:path';

const required = name => {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`Missing ${name}`);
  return value;
};
const output = process.argv[2];
if (!output) throw new Error('Usage: node tools/export-developer-token.mjs /private/path/developer-token.jwt');
const key = createPrivateKey(readFileSync(required('APPLE_MUSIC_PRIVATE_KEY_PATH')));
const now = Math.floor(Date.now() / 1000);
const expiresAt = now + 180 * 24 * 60 * 60;
const encode = data => Buffer.from(JSON.stringify(data)).toString('base64url');
const input = `${encode({ alg: 'ES256', kid: required('APPLE_MUSIC_KEY_ID'), typ: 'JWT' })}.${encode({ iss: required('APPLE_TEAM_ID'), iat: now - 60, exp: expiresAt })}`;
const signature = sign('sha256', Buffer.from(input), { key, dsaEncoding: 'ieee-p1363' });
if (!verify('sha256', Buffer.from(input), { key, dsaEncoding: 'ieee-p1363' }, signature)) throw new Error('Signature verification failed');
writeFileSync(resolve(output), `${input}.${signature.toString('base64url')}\n`, { mode: 0o600 });
chmodSync(resolve(output), 0o600);
console.log(JSON.stringify({ written: true, expiresAt: new Date(expiresAt * 1000).toISOString(), lifetimeDays: 180 }));
