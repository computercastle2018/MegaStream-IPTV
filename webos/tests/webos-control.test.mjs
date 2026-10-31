import assert from 'node:assert/strict';
import test from 'node:test';
import crypto from 'node:crypto';
import fs from 'node:fs';
import ts from 'typescript';

const source = fs.readFileSync(new URL('../src/services/webosControl.ts', import.meta.url), 'utf8');
const { outputText } = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2019, module: ts.ModuleKind.ES2020 } });
const linked = outputText.replace('"./subscriptionReport.mjs"', JSON.stringify(new URL('../src/services/subscriptionReport.mjs', import.meta.url).href));
const { createWebosControl, verifyWebosLease } = await import('data:text/javascript;base64,' + Buffer.from(linked).toString('base64'));
const pair = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const key = { ...pair.publicKey.export({ format: 'jwk' }), kid: 'webos-test' };
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
function lease(device, changes = {}) {
    const now = Math.floor(Date.now() / 1000);
    const payload = { iss: 'https://megastrem.megastation.uk', aud: 'megastream-webos', sub: device.installationId,
        credentialBinding: crypto.createHash('sha256').update(Buffer.from(device.credential, 'base64url')).digest('base64url'),
        iat: now, nbf: now, exp: now + 300, licenseStartsAt: now - 60, licenseEndsAt: now + 600, decision: 'allowed', ...changes };
    const input = encode({ alg: 'ES256', kid: key.kid }) + '.' + encode(payload);
    return input + '.' + crypto.sign('sha256', Buffer.from(input), { key: pair.privateKey, dsaEncoding: 'ieee-p1363' }).toString('base64url');
}
function storage() {
    const data = new Map();
    return { getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value), removeItem: key => data.delete(key) };
}

test('webOS signed licenses reject Android, other devices, expired or forged leases', async () => {
    const device = { installationId: crypto.randomUUID(), credential: crypto.randomBytes(32).toString('base64url') };
    assert.equal(await verifyWebosLease(lease(device), key, device), true);
    for (const changes of [{ aud: 'megastream-android' }, { exp: 1 }, { sub: crypto.randomUUID() }, { credentialBinding: 'wrong' }, { licenseEndsAt: 1 }]) {
        assert.equal(await verifyWebosLease(lease(device, changes), key, device), false);
    }
    assert.equal(await verifyWebosLease(lease(device) + 'corrupt', key, device), false);
});

test('TV upgrades reuse identity, receive admin licensing and remote profiles, then honor revocation', async () => {
    const saved = storage();
    saved.setItem('MegaStream.providers', JSON.stringify([{ id: 'local', name: 'Local', type: 'm3u', url: 'https://example.com/local.m3u' }]));
    let device, allowed = false, design = 'studio', redeemed = false, appearanceOffline = false;
    const calls = [], registrations = [];
    const fetcher = async (url, options) => {
        assert.equal(options.credentials, 'omit');
        assert.equal(options.redirect, 'error');
        assert.equal(new URL(url).origin, 'https://megastrem.megastation.uk');
        const route = url.split('/api/v1/')[1]; calls.push(route);
        const body = options.body ? JSON.parse(options.body) : null;
        let result = {};
        if (route === 'installations/register') { device = body; registrations.push(body); assert.equal(body.platform, 'webos'); }
        if (route === 'devices/heartbeat') result = { decision: { state: allowed ? 'allowed' : 'revoked' }, ...(allowed ? { lease: lease(device) } : {}) };
        if (route === 'public/signing-key') result = { keys: [key] };
        if (route.startsWith('devices/experience')) result = { allowSubscriptionDetails: false, notifications: [], uiStyle: design };
        if (route === 'devices/appearance') {
            if (appearanceOffline) throw new Error('Offline');
            if (body.expectedUiStyle !== design) return new Response('{}', { status: 409 });
            design = body.uiStyle;
        }
        if (route === 'providers/assignments') result = { assignments: redeemed ? [{ assignmentId: 'remote-fixture', displayName: 'Remote',
            profileRevision: 1, type: 'm3u', configuration: { m3uUrl: 'https://example.com/remote.m3u' } }] : [], tombstones: [] };
        if (route === 'providers/redeem') { assert.deepEqual(body, { code: 'SUBSCRIPTION-TEST' }); redeemed = true; }
        return new Response(JSON.stringify(result), { headers: { 'Content-Type': 'application/json' } });
    };
    const first = createWebosControl(saved, '3.0.7', fetcher);
    await Promise.all([first.sync(), first.sync()]);
    assert.equal(registrations.length, 1, 'Concurrent startup must not register twice');
    await assert.rejects(first.redeemSubscription('SUBSCRIPTION-TEST'));
    allowed = true;
    await first.sync();
    assert.equal((await first.state()).state, 'allowed');
    const before = calls.length;
    design = 'modern'; await first.syncExperience();
    assert.equal((await first.state()).uiStyle, 'modern');
    assert.deepEqual(calls.slice(before), ['devices/experience?version=2']);
    await first.setAppearance('classic');
    assert.equal(design, 'classic');
    design = 'studio';
    await first.setAppearance('modern');
    assert.equal((await first.state()).uiStyle, 'studio', 'A stale local choice must not overwrite a newer admin change');
    appearanceOffline = true;
    await first.setAppearance('classic');
    await first.setAppearance('modern');
    assert.equal((await first.state()).uiStyle, 'modern');
    assert.ok(saved.getItem('MegaStream.webos.pending-design.v1'));
    appearanceOffline = false;
    await first.syncExperience();
    assert.equal(design, 'modern');
    assert.equal(saved.getItem('MegaStream.webos.pending-design.v1'), null);
    await first.redeemSubscription('SUBSCRIPTION-TEST');
    assert.equal((await first.state()).providerCount, 2);
    assert.equal(JSON.parse(saved.getItem('MegaStream.providers'))[0].id, 'local');
    const upgrade = createWebosControl(saved, '3.0.8', fetcher);
    await upgrade.sync();
    assert.equal(registrations[1].installationId, registrations[0].installationId);
    assert.equal(registrations[1].credential, registrations[0].credential);
    allowed = false; await upgrade.sync();
    assert.equal((await upgrade.state()).state, 'revoked');
});

test('corrupt or unwritable identity storage never registers another device', async () => {
    let requests = 0;
    const fetcher = async () => { requests++; throw new Error('Unexpected network request'); };
    const saved = storage(); saved.setItem('MegaStream.webos.identity.v1', '{broken');
    assert.equal((await createWebosControl(saved, '3.0.7', fetcher).sync()).state, 'verification_required');
    const unwritable = { ...storage(), setItem() { throw new Error('Storage full'); } };
    assert.equal((await createWebosControl(unwritable, '3.0.7', fetcher).sync()).state, 'verification_required');
    assert.equal(requests, 0);
});

for (const mode of ['save', 'read', 'admin', 'offline']) {
    test(`webOS appearance queue preserves newer choices during ${mode} and keeps compare-and-set semantics`, async () => {
        const saved = storage();
        let release, entered;
        const gate = new Promise(resolve => { release = resolve; });
        const started = new Promise(resolve => { entered = resolve; });
        let server = 'classic', armed = false, paused = false, offline = false;
        const posts = [];
        const fetcher = async (url, options) => {
            const route = url.split('/api/v1/')[1];
            if (route === 'devices/appearance') {
                const body = JSON.parse(options.body); posts.push(body);
                if (!paused && mode !== 'read') { paused = true; entered(); await gate; }
                if (offline) throw new Error('Offline');
                if (body.expectedUiStyle !== server) return new Response('{}', { status: 409 });
                server = body.uiStyle;
                if (mode === 'admin' && posts.length === 1) server = 'classic';
                return new Response(null, { status: 204 });
            }
            if (route.startsWith('devices/experience')) {
                if (armed && !paused && mode === 'read') { paused = true; entered(); await gate; }
                if (offline) throw new Error('Offline');
                return new Response(JSON.stringify({ uiStyle: server, allowSubscriptionDetails: false, notifications: [] }));
            }
            if (route === 'devices/heartbeat') return new Response(JSON.stringify({ decision: { state: 'unlicensed' } }));
            assert.equal(route, 'installations/register');
            return new Response('{}');
        };
        const control = createWebosControl(saved, '3.0.8', fetcher);
        await control.sync(); armed = true;
        const first = mode === 'read' ? control.syncExperience() : control.setAppearance('modern');
        await started;
        const middle = mode === 'read' ? control.setAppearance('modern') : Promise.resolve();
        const last = control.setAppearance('studio');
        if (mode === 'offline') offline = true;
        release();
        await Promise.all([first, middle, last]);
        if (mode === 'offline') {
            assert.deepEqual(JSON.parse(saved.getItem('MegaStream.webos.pending-design.v1')), { uiStyle: 'studio', expectedUiStyle: 'classic' });
            assert.equal((await control.state()).uiStyle, 'studio');
            offline = false; await control.syncExperience();
        }
        assert.equal(server, mode === 'admin' ? 'classic' : 'studio');
        assert.equal((await control.state()).uiStyle, server);
        assert.equal(saved.getItem('MegaStream.webos.pending-design.v1'), null);
        if (mode === 'read') assert.deepEqual(posts, [{ uiStyle: 'studio', expectedUiStyle: 'classic' }]);
        else assert.deepEqual(posts, [{ uiStyle: 'modern', expectedUiStyle: 'classic' },
            { uiStyle: 'studio', expectedUiStyle: mode === 'offline' ? 'classic' : 'modern' }]);
        if (mode === 'admin') assert.equal((await control.state()).error, 'appearance_conflict');
    });
}
