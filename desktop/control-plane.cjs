const crypto = require('node:crypto');
const os = require('node:os');
const { verifyLease } = require('./lease.cjs');
const origin = 'https://megastrem.megastation.uk';
const packageName = 'uk.megastation.megastream.desktop';

class ControlPlane {
  constructor(store, version, notify, fetcher = fetch) {
    this.store = store; this.version = version; this.notify = notify; this.fetcher = fetcher;
    this.sessionId = crypto.randomUUID(); this.sequence = 0;
    this.state = { state: 'checking', online: false, installationId: '', allowSubscriptionDetails: false,
      uiStyle: this.store.get('desktop.uiStyle') };
  }
  async request(route, body, anonymous = false) {
    const response = await this.fetcher(origin + '/api/v1/' + route, {
      method: body === undefined ? 'GET' : 'POST', redirect: 'error', signal: AbortSignal.timeout(15000),
      headers: { 'Content-Type': 'application/json', ...(anonymous ? { 'Idempotency-Key': this.identity.installationId } : { Authorization: 'Bearer ' + this.identity.credential }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) })
    });
    if (!response.ok) {
      const error = new Error('Control plane request failed');
      error.status = response.status;
      throw error;
    }
    return response.status === 204 ? null : response.json();
  }
  async register() {
    this.identity = this.store.get('desktop.identity');
    if (!this.identity) {
      this.identity = { installationId: crypto.randomUUID(), credential: crypto.randomBytes(32).toString('base64url') };
      this.store.set('desktop.identity', this.identity);
    }
    this.state.installationId = this.identity.installationId;
    await this.request('installations/register', {
      ...this.identity, platform: process.platform === 'darwin' ? 'macos' : 'windows', appVersionCode: 56,
      packageName, channel: 'stable', appVersionName: this.version, manufacturer: process.platform === 'darwin' ? 'Apple' : 'PC',
      model: 'MegaStream Desktop', androidApi: 0, androidRelease: os.release().slice(0, 32),
      abi: process.arch === 'arm64' ? 'arm64_v8a' : 'x86_64', locale: Intl.DateTimeFormat().resolvedOptions().locale.slice(0, 16), managedDevice: false
    }, true);
  }
  async applyAssignments(batch) {
    if (!Array.isArray(batch.assignments) || !Array.isArray(batch.tombstones)) throw new Error('Invalid assignments');
    const providers = JSON.parse(this.store.get('MegaStream.providers') || '[]').filter(provider => !provider.id.startsWith('remote:'));
    const reports = [];
    for (const item of batch.assignments) {
      const c = item.configuration;
      const base = { id: 'remote:' + item.assignmentId, name: item.displayName || 'Subscription', remotePolicy: item.policy };
      let provider;
      if (!c.httpUserAgent && !Object.keys(c.httpHeaders || {}).length) {
        if (item.type === 'xtream_codes' && c.serverUrl && c.username && c.password) provider = { ...base, type: 'xtream', serverUrl: c.serverUrl, username: c.username, password: c.password };
        if (item.type === 'm3u' && c.m3uUrl) provider = { ...base, type: 'm3u', url: c.m3uUrl };
      }
      if (provider) providers.push(provider);
      reports.push({ item, provider });
    }
    const json = JSON.stringify(providers);
    const changed = json !== this.store.get('MegaStream.providers');
    this.state.providersChanged = changed;
    if (changed) this.store.set('MegaStream.providers', json);
    const active = this.store.get('MegaStream.activeProviderId');
    if (!providers.some(provider => provider.id === active)) this.store.set('MegaStream.activeProviderId', providers[0]?.id ?? null);
    this.state.providerCount = providers.length;
    this.state.providerTypes = [...new Set(providers.map(provider => provider.type === 'xtream' ? 'Xtream Codes' : 'M3U'))];
    for (const { item, provider } of reports) await this.request(`providers/assignments/${item.assignmentId}/status`, {
      profileRevision: item.profileRevision, state: provider ? 'applied' : 'error', safeErrorCode: provider ? null : 'unsupported'
    });
    return changed;
  }
  async sync() {
    if (this.pending) return this.pending;
    this.pending = this.refresh().finally(() => { this.pending = null; });
    return this.pending;
  }
  async syncExperience() {
    if (!this.registered || this.state.state === 'installation_disabled') return;
    if (this.pendingExperience) return this.pendingExperience;
    this.pendingExperience = (async () => {
      const experience = await this.request('devices/experience?version=2');
      this.state.allowSubscriptionDetails = experience.allowSubscriptionDetails;
      this.state.uiStyle = ['classic', 'modern', 'studio'].includes(experience.uiStyle) ? experience.uiStyle : null;
      this.store.set('desktop.uiStyle', this.state.uiStyle);
      this.notify({ ...this.state, providersChanged: false });
    })().finally(() => { this.pendingExperience = null; });
    return this.pendingExperience;
  }
  async refresh() {
    try {
      if (!this.registered) { await this.register(); this.registered = true; }
      const result = await this.request('devices/heartbeat', {
        appSessionId: this.sessionId, sequence: this.sequence++, mode: 'foreground', appVersionCode: 56,
        packageName, channel: 'stable', appVersionName: this.version, managedDevice: false
      });
      this.state = { ...this.state, state: result.decision.state, online: true, error: null,
        startsAt: result.decision.startsAt, endsAt: result.decision.endsAt };
      if (result.decision.state === 'allowed') {
        const keys = await this.request('public/signing-key', undefined, true);
        const key = keys.keys.find(jwk => verifyLease(result.lease, jwk, this.identity));
        if (!key) { const error = new Error('Invalid signed entitlement'); error.status = 403; throw error; }
        this.store.set('desktop.lease', { lease: result.lease, key });
      } else this.store.set('desktop.lease', null);
      if (result.decision.state !== 'installation_disabled') {
        await this.syncExperience();
        this.state.providersChanged = await this.applyAssignments(await this.request('providers/assignments'));
      }
    } catch (error) {
      const cached = this.store.get('desktop.lease');
      const claims = error.status !== 401 && error.status !== 403 && cached && this.identity && verifyLease(cached.lease, cached.key, this.identity);
      this.state = { ...this.state, state: claims ? 'allowed' : 'verification_required', online: false,
        error: 'sync_failed', ...(claims ? { startsAt: new Date(claims.licenseStartsAt * 1000).toISOString(), endsAt: new Date(claims.licenseEndsAt * 1000).toISOString() } : {}) };
    }
    this.notify(this.state);
    return this.state;
  }
  async activate(key) {
    if (typeof key !== 'string' || !key.trim() || key.length > 128) throw new Error('Invalid license key');
    await this.sync();
    await this.request('licenses/activate', { licenseKey: key.trim() });
    return this.sync();
  }
  async requestCode() {
    await this.sync();
    const result = await this.request('activation-codes/request', {});
    this.code = result;
    return { code: result.code, expiresAt: result.expiresAt };
  }
  async pollCode() {
    if (!this.code) return;
    if (new Date(this.code.expiresAt).getTime() <= Date.now()) { this.code = null; return this.sync(); }
    const result = await this.request('activation-codes/status', { code: this.code.code, pollToken: this.code.pollToken });
    if (result.decision) this.code = null;
    return this.sync();
  }
}
module.exports = { ControlPlane };
