import assert from 'node:assert/strict';
import test from 'node:test';
import { localSubscriptionReport } from '../src/services/subscriptionReport.mjs';

test('local snapshots have stable IDs, encrypted-store compatible credentials and no remote assignment echo', async () => {
  const providers = [{ id: 'm3u-local', type: 'm3u', name: 'Local playlist', url: 'https://example.com/list.m3u' },
    { id: 'xtream-local', type: 'xtream', name: 'Xtream', serverUrl: 'https://example.com', username: 'fixture', password: 'fixture' },
    { id: 'remote:assignment', type: 'm3u', name: 'Managed', url: 'https://example.com/remote.m3u' }];
  const first = await localSubscriptionReport(providers);
  assert.equal(first.subscriptions.length, 2);
  assert.ok(first.subscriptions.every(item => Number.isSafeInteger(item.localId) && item.localId > 0 && item.status === 'unknown'));
  assert.equal(first.subscriptions[0].credentials.m3u.m3uUrl, providers[0].url);
  assert.equal(first.subscriptions[1].credentials.xtream.password, 'fixture');
  assert.deepEqual((await localSubscriptionReport(providers)).subscriptions.map(item => item.localId), first.subscriptions.map(item => item.localId));
  await assert.rejects(localSubscriptionReport([providers[0], providers[0]]));
  assert.deepEqual(await localSubscriptionReport([]), { subscriptions: [] });
});
