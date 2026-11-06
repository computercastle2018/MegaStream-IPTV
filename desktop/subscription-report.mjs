export async function localSubscriptionReport(providers) {
  const locals = providers.filter(provider => !provider.id.startsWith('remote:'));
  if (locals.length > 100) throw new Error('Too many local subscriptions');
  const subscriptions = await Promise.all(locals.map(async provider => {
    const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(provider.id)));
    const localId = bytes.slice(0, 6).reduce((value, byte) => value * 256 + byte, 0) || 1;
    const credentials = provider.type === 'xtream'
      ? { serverUrl: provider.serverUrl, xtream: { username: provider.username, password: provider.password } }
      : { m3u: { m3uUrl: provider.url } };
    return { localId, name: provider.name.slice(0, 128), type: provider.type === 'xtream' ? 'xtream_codes' : 'm3u',
      enabled: true, status: 'unknown', maxConnections: 1, credentials };
  }));
  if (new Set(subscriptions.map(item => item.localId)).size !== subscriptions.length) throw new Error('Ambiguous subscription identity');
  return { subscriptions };
}
