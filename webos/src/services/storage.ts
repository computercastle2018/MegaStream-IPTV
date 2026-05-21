import type { Provider } from "../types";

const PROVIDERS_KEY = "MegaStream.providers";
const ACTIVE_KEY = "MegaStream.activeProviderId";

export function loadProviders(): Provider[] {
  try {
    const raw = localStorage.getItem(PROVIDERS_KEY);
    return raw ? (JSON.parse(raw) as Provider[]) : [];
  } catch {
    return [];
  }
}

export function saveProviders(providers: Provider[]): void {
  localStorage.setItem(PROVIDERS_KEY, JSON.stringify(providers));
}

export function addProvider(provider: Provider): Provider[] {
  const providers = loadProviders().filter((p) => p.id !== provider.id);
  providers.push(provider);
  saveProviders(providers);
  setActiveProviderId(provider.id);
  return providers;
}

export function removeProvider(id: string): Provider[] {
  const providers = loadProviders().filter((p) => p.id !== id);
  saveProviders(providers);
  if (getActiveProviderId() === id) {
    setActiveProviderId(providers[0]?.id ?? null);
  }
  return providers;
}

export function getActiveProviderId(): string | null {
  return localStorage.getItem(ACTIVE_KEY);
}

export function setActiveProviderId(id: string | null): void {
  if (id) localStorage.setItem(ACTIVE_KEY, id);
  else localStorage.removeItem(ACTIVE_KEY);
}

export function newId(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}
