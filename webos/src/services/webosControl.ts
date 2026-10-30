import type { Provider } from "../types";
import { localSubscriptionReport } from "./subscriptionReport.mjs";

const origin = "https://megastrem.megastation.uk";
const packageName = "uk.megastation.megastream";
const versionCode = 11;
const identityKey = "MegaStream.webos.identity.v1";
type Identity = { installationId: string; credential: string };
type Fetcher = typeof fetch;
const encode = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes)).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
const decode = (text: string) => Uint8Array.from(atob(text.replace(/-/g, "+").replace(/_/g, "/")), character => character.charCodeAt(0));

function uuid(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function identity(storage: Pick<Storage, "getItem" | "setItem">): Identity {
  const saved = storage.getItem(identityKey);
  const value: Identity = saved ? JSON.parse(saved) : {
    installationId: uuid(), credential: encode(crypto.getRandomValues(new Uint8Array(32))),
  };
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value.installationId) ||
      !/^[\w-]{43}$/.test(value.credential) || encode(decode(value.credential)) !== value.credential) throw new Error("Invalid saved device identity");
  if (!saved) storage.setItem(identityKey, JSON.stringify(value));
  return value;
}

export async function verifyWebosLease(lease: string, key: JsonWebKey & { kid?: string }, device: Identity, now = Date.now()): Promise<boolean> {
  try {
    const parts = lease.split(".");
    if (parts.length !== 3) return false;
    const json = (part: string) => JSON.parse(new TextDecoder().decode(decode(part)));
    const header = json(parts[0]), claims = json(parts[1]), seconds = Math.floor(now / 1000);
    const binding = encode(new Uint8Array(await crypto.subtle.digest("SHA-256", decode(device.credential))));
    if (header.alg !== "ES256" || header.kid !== key.kid || key.kty !== "EC" || key.crv !== "P-256" ||
        claims.iss !== origin || claims.aud !== "megastream-webos" || claims.sub !== device.installationId ||
        claims.credentialBinding !== binding || claims.decision !== "allowed" ||
        !["iat", "nbf", "exp", "licenseStartsAt", "licenseEndsAt"].every(name => Number.isSafeInteger(claims[name])) ||
        claims.nbf > seconds || claims.iat > seconds + 60 || claims.exp <= seconds || claims.licenseStartsAt > seconds ||
        claims.licenseEndsAt <= seconds || claims.exp - claims.iat > 3 * 86400) return false;
    const publicKey = await crypto.subtle.importKey("jwk", key, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    return crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, publicKey, decode(parts[2]), new TextEncoder().encode(parts[0] + "." + parts[1]));
  } catch { return false; }
}

export function createWebosControl(storage: Storage, version: string, fetcher: Fetcher = fetch) {
  let device: Identity, registered = false, sequence = 0;
  const session = uuid(), listeners = new Set<(state: DesktopControlState) => void>();
  let state: DesktopControlState = { state: "checking", online: false, installationId: "", allowSubscriptionDetails: false };
  let pending: Promise<DesktopControlState> | null = null;
  let experiencePending: Promise<void> | null = null;
  const publish = () => listeners.forEach(listener => listener({ ...state }));

  async function request(route: string, body?: unknown, anonymous = false) {
    const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await fetcher(origin + "/api/v1/" + route, {
        method: body === undefined ? "GET" : "POST", credentials: "omit", redirect: "error", signal: controller.signal,
        headers: { "Content-Type": "application/json", ...(anonymous ? { "Idempotency-Key": device.installationId } : { Authorization: "Bearer " + device.credential }) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      if (!response.ok) throw Object.assign(new Error("Control panel unavailable"), { status: response.status });
      return response.status === 204 ? null : response.json();
    } finally { clearTimeout(timeout); }
  }

  async function syncExperience() {
    if (!registered || state.state === "installation_disabled") return;
    if (experiencePending) return experiencePending;
    experiencePending = (async () => {
      let conflict = false, pendingFailed = false;
      do {
        const pendingStyle = storage.getItem("MegaStream.webos.pending-design.v1");
        if (pendingStyle) {
          const sent = JSON.parse(pendingStyle);
          try { await request("devices/appearance", sent); }
          catch (error) {
            if ((error as { status?: number }).status === 409) conflict = true;
            else pendingFailed = true;
          }
          if (!conflict && !pendingFailed) {
            const queued = storage.getItem("MegaStream.webos.pending-design.v1");
            if (queued === pendingStyle) storage.removeItem("MegaStream.webos.pending-design.v1");
            else if (queued) storage.setItem("MegaStream.webos.pending-design.v1", JSON.stringify({ ...JSON.parse(queued), expectedUiStyle: sent.uiStyle }));
            if (!storage.getItem("MegaStream.webos.pending-design.v1")) state = { ...state, uiStyle: sent.uiStyle };
          }
        }
        const result = await request("devices/experience?version=2");
        if (typeof result.allowSubscriptionDetails !== "boolean" || !Array.isArray(result.notifications) || result.notifications.length > 50) throw new Error("Invalid device policy");
        if (conflict) storage.removeItem("MegaStream.webos.pending-design.v1");
        const queued = storage.getItem("MegaStream.webos.pending-design.v1");
        state = { ...state, allowSubscriptionDetails: result.allowSubscriptionDetails,
          uiStyle: queued ? JSON.parse(queued).uiStyle : ["classic", "modern", "studio"].includes(result.uiStyle) ? result.uiStyle : null,
          notifications: result.notifications, providersChanged: false,
          error: conflict ? "appearance_conflict" : pendingFailed ? "appearance_sync_pending" : state.error === "appearance_sync_pending" ? null : state.error };
        publish();
      } while (storage.getItem("MegaStream.webos.pending-design.v1") && !conflict && !pendingFailed);
    })().finally(() => { experiencePending = null; });
    return experiencePending;
  }

  async function subscriptions() {
    const batch = await request("providers/assignments");
    if (!Array.isArray(batch.assignments) || !Array.isArray(batch.tombstones)) throw new Error("Invalid subscriptions");
    const before = storage.getItem("MegaStream.providers") || "[]";
    const providers: Provider[] = JSON.parse(before).filter((provider: Provider) => !provider.id.startsWith("remote:"));
    for (const item of batch.assignments) {
      const config = item.configuration;
      const base = { id: "remote:" + item.assignmentId, name: item.displayName || "Subscription" };
      let provider: Provider | undefined;
      if (!config.httpUserAgent && !Object.keys(config.httpHeaders || {}).length) {
        if (item.type === "m3u" && config.m3uUrl) provider = { ...base, type: "m3u", url: config.m3uUrl };
        if (item.type === "xtream_codes" && config.serverUrl && config.username && config.password)
          provider = { ...base, type: "xtream", serverUrl: config.serverUrl, username: config.username, password: config.password };
      }
      if (provider) providers.push(provider);
      await request(`providers/assignments/${encodeURIComponent(item.assignmentId)}/status`, {
        profileRevision: item.profileRevision, state: provider ? "applied" : "error", safeErrorCode: provider ? null : "unsupported",
      });
    }
    const json = JSON.stringify(providers);
    if (json !== before) storage.setItem("MegaStream.providers", json);
    if (!providers.some(provider => provider.id === storage.getItem("MegaStream.activeProviderId"))) {
      if (providers[0]) storage.setItem("MegaStream.activeProviderId", providers[0].id);
      else storage.removeItem("MegaStream.activeProviderId");
    }
    state = { ...state, providersChanged: json !== before, providerCount: providers.length,
      providerTypes: [...new Set(providers.map(provider => provider.type === "xtream" ? "Xtream Codes" : "M3U"))] };
  }

  async function refresh(): Promise<DesktopControlState> {
    try {
      device = identity(storage);
      state = { ...state, installationId: device.installationId };
      publish();
      if (!registered) {
        await request("installations/register", { ...device, platform: "webos", appVersionCode: versionCode,
          packageName, channel: "stable", appVersionName: version, manufacturer: "LG", model: "LG webOS TV",
          androidApi: 0, androidRelease: "webOS", abi: "other", locale: navigator.language.slice(0, 16), managedDevice: false }, true);
        registered = true;
      }
      const result = await request("devices/heartbeat", { appSessionId: session, sequence: sequence++, mode: "foreground",
        appVersionCode: versionCode, packageName, channel: "stable", appVersionName: version, managedDevice: false });
      if (result.decision.state === "allowed") {
        const keys = await request("public/signing-key", undefined, true);
        if (!Array.isArray(keys.keys) || !(await Promise.all(keys.keys.map((key: JsonWebKey) => verifyWebosLease(result.lease, key, device)))).some(Boolean))
          throw new Error("Invalid signed license");
      }
      state = { ...state, state: result.decision.state, online: true, error: null,
        startsAt: result.decision.startsAt, endsAt: result.decision.endsAt };
      await syncExperience();
      if (state.state === "allowed") {
        await subscriptions();
        try {
          await request("devices/subscriptions", await localSubscriptionReport(JSON.parse(storage.getItem("MegaStream.providers") || "[]")));
        } catch { state = { ...state, error: "subscription_report_failed" }; }
      }
    } catch {
      state = { ...state, state: "verification_required", online: false, error: "sync_failed" };
    }
    publish();
    return { ...state };
  }

  const sync = () => {
    if (!pending) pending = refresh().finally(() => { pending = null; });
    return pending;
  };
  return { state: async () => ({ ...state }), sync, syncExperience,
    subscribe(listener: (state: DesktopControlState) => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
    async activate(key: string) {
      if (!key.trim() || key.length > 128) throw new Error("Invalid license key");
      await sync(); await request("licenses/activate", { licenseKey: key.trim() }); return sync();
    },
    async requestCode() {
      await sync();
      const result = await request("activation-codes/request", {});
      return { code: result.code, expiresAt: result.expiresAt };
    },
    async redeemSubscription(code: string) {
      if (!code.trim() || code.length > 64) throw new Error("Invalid subscription code");
      await sync();
      if (state.state !== "allowed") throw new Error("A valid device license is required");
      await request("providers/redeem", { code: code.trim() });
      return sync();
    },
    async setAppearance(uiStyle: "classic" | "modern" | "studio") {
      if (!["classic", "modern", "studio"].includes(uiStyle)) throw new Error("Invalid design");
      const queued = storage.getItem("MegaStream.webos.pending-design.v1");
      const expectedUiStyle = queued ? JSON.parse(queued).expectedUiStyle : state.uiStyle ?? null;
      storage.setItem("MegaStream.webos.pending-design.v1", JSON.stringify({ uiStyle, expectedUiStyle }));
      state = { ...state, uiStyle }; publish();
      try { await syncExperience(); }
      catch { state = { ...state, error: "appearance_sync_pending" }; publish(); }
      return { ...state };
    },
  };
}

export function startWebosControl(version: string) {
  if (window.megastreamDesktop || !("PalmSystem" in window || "webOSSystem" in window)) return;
  document.documentElement.dataset.platform = "webos";
  const control = createWebosControl(localStorage, version);
  window.megastreamWebos = { control };
  void control.sync();
  window.setInterval(() => { void control.sync(); }, 30000);
  window.setInterval(() => { void control.syncExperience().catch(() => control.sync()); }, 5000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) void control.sync(); });
}
