// Isolated test entrypoint; excluded from packaged applications.
const crypto = require('node:crypto');
const pair = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const key = { ...pair.publicKey.export({ format: 'jwk' }), kid: 'ui-test' };
let identity, activated = false;
const realFetch = global.fetch;
global.fetch = async (url, options) => {
  if (!url.startsWith('https://megastrem.megastation.uk/api/v1/')) return realFetch(url, options);
  const route = url.split('/api/v1/')[1];
  let result = {};
  if (route === 'installations/register') identity = JSON.parse(options.body);
  if (route === 'licenses/activate') activated = true;
  if (route === 'public/signing-key') result = { keys: [key] };
  if (route === 'devices/experience?version=2') result = { allowSubscriptionDetails: process.env.TEST_ALLOW_DETAILS === '1', uiStyle: global.testUiStyle ?? null };
  if (route === 'providers/assignments') result = { assignments: activated ? [{ assignmentId: '41f81556-efb6-4136-a748-cc46e974906f',
    type: process.env.TEST_XTREAM_URL ? 'xtream_codes' : 'm3u', displayName: 'Desktop test', profileRevision: 1, policy: 'required',
    configuration: process.env.TEST_XTREAM_URL ? { serverUrl: process.env.TEST_XTREAM_URL, username: 'fixture', password: 'fixture' } : { m3uUrl: process.env.TEST_PLAYLIST_URL } }] : [], tombstones: [] };
  if (route === 'devices/heartbeat') {
    const now = Math.floor(Date.now() / 1000);
    const encode = object => Buffer.from(JSON.stringify(object)).toString('base64url');
    const input = encode({ alg: 'ES256', kid: key.kid }) + '.' + encode({ iss: 'https://megastrem.megastation.uk', aud: 'megastream-desktop',
      sub: identity.installationId, credentialBinding: crypto.createHash('sha256').update(Buffer.from(identity.credential, 'base64url')).digest('base64url'),
      decision: 'allowed', iat: now, nbf: now, exp: now + 300, licenseStartsAt: now - 60, licenseEndsAt: now + 600 });
    result = { decision: { state: activated ? 'allowed' : 'unlicensed', startsAt: new Date((now - 60) * 1000).toISOString(), endsAt: new Date((now + 600) * 1000).toISOString() },
      ...(activated ? { lease: input + '.' + crypto.sign('sha256', Buffer.from(input), { key: pair.privateKey, dsaEncoding: 'ieee-p1363' }).toString('base64url') } : {}) };
  }
  return new Response(JSON.stringify(result), { headers: { 'Content-Type': 'application/json' } });
};
require(process.env.TEST_APPLICATION_MAIN || './main.cjs');
