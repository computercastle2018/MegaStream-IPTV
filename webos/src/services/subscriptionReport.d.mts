import type { Provider } from "../types";
export function localSubscriptionReport(providers: Provider[]): Promise<{ subscriptions: {
  localId: number; name: string; type: string; enabled: boolean; status: string; maxConnections: number;
  credentials: { serverUrl?: string; xtream?: { username: string; password: string }; m3u?: { m3uUrl: string } };
}[] }>;
