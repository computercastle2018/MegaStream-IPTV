interface DesktopControlState {
  state: string;
  online: boolean;
  installationId: string;
  allowSubscriptionDetails: boolean;
  uiStyle?: "classic" | "modern" | "studio" | null;
  startsAt?: string;
  endsAt?: string;
  error?: string | null;
  providersChanged?: boolean;
  providerCount?: number;
  providerTypes?: string[];
}
interface Window {
  megastreamDesktop?: {
    storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;
    control: {
      state(): Promise<DesktopControlState>;
      sync(): Promise<DesktopControlState>;
      activate(key: string): Promise<DesktopControlState>;
      requestCode(): Promise<{ code: string; expiresAt: string }>;
      subscribe(callback: (state: DesktopControlState) => void): () => void;
    };
  };
}
