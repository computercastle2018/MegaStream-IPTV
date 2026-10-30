import { useEffect, useState, type ReactNode } from "react";
import { ShieldCheck, RefreshCw, X } from "lucide-react";
import { useLang } from "../i18n/LanguageContext";
import { FocusButton, FocusInput, LanguageToggle } from "./Focusable";
import "./DesktopControl.css";
import { useAppearance } from "./Appearance";
import { useNotifications } from "./Notifications";

export default function DesktopControl({ children }: { children: ReactNode }) {
  const api = window.megastreamDesktop?.control ?? window.megastreamWebos?.control;
  const { update: updateNotifications } = useNotifications();
  const { lang } = useLang();
  const ar = lang === "ar";
  const [state, setState] = useState<DesktopControlState | null>(null);
  const [open, setOpen] = useState(false);
  const [key, setKey] = useState("");
  const [code, setCode] = useState("");
  const [subscriptionCode, setSubscriptionCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  const { setRemote } = useAppearance();
  useEffect(() => { setRemote(state?.uiStyle); }, [state?.uiStyle, setRemote]);
  useEffect(() => {
    if (!api) return;
    const update = (next: DesktopControlState) => {
      setState(next);
      updateNotifications(next.installationId, next.notifications);
      if (next.providersChanged) window.dispatchEvent(new Event("megastream-provider-sync"));
    };
    const unsubscribe = api.subscribe(update);
    api.state().then(update).catch(() => setError(true));
    return unsubscribe;
  }, [api, updateNotifications]);
  useEffect(() => {
    const show = () => setOpen(true);
    window.addEventListener("megastream-license-open", show);
    return () => window.removeEventListener("megastream-license-open", show);
  }, []);
  if (!api) return children;
  const allowed = state?.state === "allowed";
  const message = state?.state === "checking" || !state ? (ar ? "جارٍ التحقق" : "Checking")
    : allowed ? (ar ? "مرخّص" : "Licensed") : (ar ? "غير متاح للتشغيل" : "Playback unavailable");
  const run = async (operation: () => Promise<unknown>) => {
    setBusy(true); setError(false);
    try { await operation(); } catch { setError(true); } finally { setBusy(false); }
  };
  const redeem = () => run(async () => {
    await api.redeemSubscription!(subscriptionCode);
    setSubscriptionCode("");
  });
  const activate = () => run(async () => { await api.activate(key); setKey(""); });
  const date = (value?: string) => value ? new Date(value).toLocaleDateString(ar ? "ar" : "en-GB") : "—";
  const panel = <section className="desktop-license" role={allowed ? "dialog" : undefined} aria-label={ar ? "الترخيص والجهاز" : "License and device"}>
    <header><ShieldCheck size={28} /><h1>{ar ? "الترخيص والجهاز" : "License and device"}</h1><LanguageToggle />
      {allowed && <FocusButton className="license-command" onEnter={() => setOpen(false)} title={ar ? "إغلاق" : "Close"}><X size={20} /></FocusButton>}</header>
    <div className={`license-state ${allowed ? "licensed" : ""}`}><strong>{message}</strong><span>{state?.online ? (ar ? "متصل بلوحة التحكم" : "Connected to control panel") : (ar ? "غير متصل بلوحة التحكم" : "Control panel offline")}</span></div>
    <dl><dt>{ar ? "معرّف الجهاز" : "Device ID"}</dt><dd dir="ltr">{state?.installationId || "—"}</dd>
      <dt>{ar ? "بداية الترخيص" : "License starts"}</dt><dd>{date(state?.startsAt)}</dd>
      <dt>{ar ? "انتهاء الترخيص" : "License ends"}</dt><dd>{date(state?.endsAt)}</dd>
      <dt>{ar ? "نوع الخدمة" : "Service type"}</dt><dd>{state?.providerTypes?.join(", ") || "—"}</dd></dl>
    {allowed && state?.providerCount === 0 && !state.allowSubscriptionDetails && <p>{ar ? "بانتظار إسناد الاشتراك من لوحة التحكم" : "Waiting for a subscription from the control panel"}</p>}
    <FocusButton className="license-command license-sync" autoFocus disabled={busy} onEnter={() => { void run(() => api.sync()); }}><RefreshCw size={18} />{ar ? "سحب الترخيص والاشتراكات من السيرفر" : "Sync license and subscriptions"}</FocusButton>
    {api.redeemSubscription && <form onSubmit={event => { event.preventDefault(); if (!busy && subscriptionCode.trim()) redeem(); }}>
      <FocusInput label={ar ? "كود الاشتراك" : "Subscription code"} type="password" value={subscriptionCode} onChange={setSubscriptionCode} autoComplete="off" maxLength={64} />
      <FocusButton className="license-subscription-load" disabled={busy || !subscriptionCode.trim()} onEnter={redeem} title={ar ? "جلب الاشتراك" : "Load subscription"}>
        <RefreshCw size={18} aria-hidden="true" />{ar ? "جلب الاشتراك" : "Load subscription"}
      </FocusButton>
    </form>}
    {!allowed && <><form onSubmit={event => { event.preventDefault(); if (!busy && key.trim()) void activate(); }}>
      <FocusInput label={ar ? "مفتاح الترخيص" : "License key"} type="password" value={key} onChange={setKey} maxLength={128} autoComplete="off" />
      <FocusButton className="license-command" disabled={busy || !key.trim()} onEnter={activate}>{ar ? "تفعيل" : "Activate"}</FocusButton></form>
      <FocusButton className="license-command" disabled={busy} onEnter={() => { void run(async () => { const result = await api.requestCode(); setCode(result.code); }); }}>{ar ? "إنشاء رمز للتفعيل من اللوحة" : "Create activation code"}</FocusButton>
      {code && <output className="activation-code" dir="ltr">{code}</output>}</>}
    {(error || state?.error) && <p role="alert">{ar ? "تعذرت المزامنة. تحقق من الاتصال وأعد المحاولة." : "Sync failed. Check the connection and retry."}</p>}
  </section>;
  if (!allowed || (state.providerCount === 0 && !state.allowSubscriptionDetails)) return <div className="desktop-license-screen">{panel}</div>;
  return <><div className={`desktop-app ${state.allowSubscriptionDetails ? "" : "desktop-details-hidden"}`}>{children}</div>
    <FocusButton className="desktop-license-trigger" title={ar ? "الترخيص والجهاز" : "License and device"} onEnter={() => setOpen(true)}><ShieldCheck size={22} /></FocusButton>
    {open && <div className="desktop-license-modal">{panel}</div>}</>;
}
