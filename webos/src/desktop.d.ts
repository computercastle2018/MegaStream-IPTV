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
  notifications?: { id: string; title: string; message: string; createdAt: string }[];
}
interface Window {
  megastreamWebos?: { control: NonNullable<Window["megastreamDesktop"]>["control"] };
  megastreamDesktop?: {
    storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;
    control: {
      state(): Promise<DesktopControlState>;
      sync(): Promise<DesktopControlState>;
      activate(key: string): Promise<DesktopControlState>;
      redeemSubscription?(code: string): Promise<DesktopControlState>;
      setAppearance?(uiStyle: "classic" | "modern" | "studio"): Promise<DesktopControlState>;
      requestCode(): Promise<{ code: string; expiresAt: string }>;
      subscribe(callback: (state: DesktopControlState) => void): () => void;
    };
  };
}
