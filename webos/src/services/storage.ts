import type { Provider } from "../types";

const PROVIDERS_KEY = "MegaStream.providers";
const ACTIVE_KEY = "MegaStream.activeProviderId";
const storage = () => window.megastreamDesktop?.storage ?? localStorage;

export function loadProviders(): Provider[] {
  try {
    const raw = storage().getItem(PROVIDERS_KEY);
    return raw ? (JSON.parse(raw) as Provider[]) : [];
  } catch (error) {
    if (window.megastreamDesktop) throw error;
    return [];
  }
}

export function saveProviders(providers: Provider[]): void {
  storage().setItem(PROVIDERS_KEY, JSON.stringify(providers));
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
  return storage().getItem(ACTIVE_KEY);
}

export function setActiveProviderId(id: string | null): void {
  if (id) storage().setItem(ACTIVE_KEY, id);
  else storage().removeItem(ACTIVE_KEY);
}

export function newId(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}
