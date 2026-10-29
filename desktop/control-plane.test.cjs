const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { ControlPlane } = require('./control-plane.cjs');
const { verifyLease } = require('./lease.cjs');

function signed(identity, changes = {}) {
  const pair = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const key = { ...pair.publicKey.export({ format: 'jwk' }), kid: 'test-key' };
  const now = Math.floor(Date.now() / 1000);
  const payload = { iss: 'https://megastrem.megastation.uk', aud: 'megastream-desktop', sub: identity.installationId,
    credentialBinding: crypto.createHash('sha256').update(Buffer.from(identity.credential, 'base64url')).digest('base64url'),
    decision: 'allowed', iat: now, nbf: now, exp: now + 300, licenseStartsAt: now - 60, licenseEndsAt: now + 600, ...changes };
  const encode = data => Buffer.from(JSON.stringify(data)).toString('base64url');
  const input = encode({ alg: 'ES256', kid: key.kid }) + '.' + encode(payload);
  return { key, lease: input + '.' + crypto.sign('sha256', Buffer.from(input), { key: pair.privateKey, dsaEncoding: 'ieee-p1363' }).toString('base64url') };
}
test('signed leases reject modified, expired and other-device entitlements', () => {
  const identity = { installationId: crypto.randomUUID(), credential: crypto.randomBytes(32).toString('base64url') };
  const valid = signed(identity);
  assert.ok(verifyLease(valid.lease, valid.key, identity));
  assert.equal(verifyLease(valid.lease + 'corrupt', valid.key, identity), null);
  assert.equal(verifyLease(valid.lease, valid.key, { ...identity, installationId: crypto.randomUUID() }), null);
  for (const changes of [{ exp: 1 }, { licenseEndsAt: 1 }, { aud: 'megastream-android' }, { exp: Math.floor(Date.now() / 1000) + 4 * 86400 }]) {
    const invalid = signed(identity, changes);
    assert.equal(verifyLease(invalid.lease, invalid.key, identity), null);
  }
});
test('upgrade retains identity and local accounts, synchronizes remote changes and honors revocation offline', async () => {
  const data = new Map([['MegaStream.providers', JSON.stringify([{ id: 'local', type: 'm3u', name: 'Local', url: 'https://example.com/local.m3u' }])]]);
  const store = { get: key => data.get(key) ?? null, set: (key, value) => value === null ? data.delete(key) : data.set(key, value) };
  let allowed = true, offline = false, uiStyle = 'studio', assignments = [{ assignmentId: crypto.randomUUID(), profileRevision: 1, type: 'm3u', policy: 'required', configuration: { m3uUrl: 'https://example.com/remote.m3u' } }];
  let calls = [], lease;
  const fetcher = async (url, options) => {
    if (offline) throw new Error('Offline');
    const route = new URL(url).pathname.split('/api/v1/')[1];
    calls.push(route);
    let result = {};
    if (route === 'installations/register') lease = signed(JSON.parse(options.body));
    if (route === 'devices/heartbeat') result = { decision: { state: allowed ? 'allowed' : 'revoked' }, ...(allowed ? { lease: lease.lease } : {}) };
    if (route === 'public/signing-key') result = { keys: [lease.key] };
    if (route === 'devices/experience') {
      assert.equal(new URL(url).searchParams.get('version'), '2', 'The legacy response omits remote appearance');
      result = { allowSubscriptionDetails: false, uiStyle };
    }
    if (route === 'providers/assignments') result = { assignments, tombstones: [] };
    return new Response(JSON.stringify(result), { status: 200, headers: { 'Content-Type': 'application/json' } });
  };
  const first = new ControlPlane(store, '3.0.16', () => {}, fetcher);
  await first.sync();
  const identity = store.get('desktop.identity');
  assert.equal(first.state.state, 'allowed');
  assert.equal(first.state.uiStyle, 'studio');
  assert.equal(JSON.parse(store.get('MegaStream.providers')).length, 2);
  assignments = [];
  await first.sync();
  assert.deepEqual(JSON.parse(store.get('MegaStream.providers')).map(item => item.id), ['local']);
  const upgrade = new ControlPlane(store, '3.0.17', () => {}, fetcher);
  assert.equal(upgrade.state.uiStyle, 'studio', 'Remote design persists across upgrades');
  await upgrade.sync();
  assert.deepEqual(store.get('desktop.identity'), identity);
  offline = true;
  await upgrade.sync();
  assert.equal(upgrade.state.state, 'allowed');
  assert.equal(upgrade.state.uiStyle, 'studio', 'Offline sync retains the managed design');
  offline = false;
  const beforeAppearance = calls.length;
  uiStyle = 'modern';
  await Promise.all([upgrade.syncExperience(), upgrade.syncExperience()]);
  assert.equal(upgrade.state.uiStyle, 'modern');
  assert.deepEqual(calls.slice(beforeAppearance), ['devices/experience'], 'Appearance changes must not wait for heartbeat or provider downloads');
  offline = true;
  await assert.rejects(upgrade.syncExperience());
  assert.equal(upgrade.state.uiStyle, 'modern', 'A connection failure must not clear the managed design');
  offline = false;
  for (uiStyle of ['modern', 'classic', null, 'unknown']) {
    await upgrade.sync();
    assert.equal(upgrade.state.uiStyle, uiStyle === 'unknown' ? null : uiStyle);
  }
  offline = false; allowed = false;
  await upgrade.sync();
  assert.equal(upgrade.state.state, 'revoked');
  offline = true;
  await upgrade.sync();
  assert.notEqual(upgrade.state.state, 'allowed');
  assert.equal(calls.filter(route => route === 'installations/register').length, 2);
});
