import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { FocusContext, getCurrentFocusKey, setFocus, useFocusable } from "@noriginmedia/norigin-spatial-navigation";
import { Bell, X } from "lucide-react";
import { useLang } from "../i18n/LanguageContext";
import { FocusButton } from "./Focusable";
import "./Notifications.css";

type Notice = NonNullable<DesktopControlState["notifications"]>[number];
type Receipts = { seen: string[]; dismissed: string[] };
const receiptKey = (id: string) => `MegaStream.notifications.${id}`;

function readReceipts(id: string): Receipts {
  try {
    const saved = JSON.parse(localStorage.getItem(receiptKey(id)) || "null");
    const ids = (value: unknown): string[] => Array.isArray(value) ? value.filter((id): id is string => typeof id === "string") : [];
    return { seen: ids(saved?.seen), dismissed: ids(saved?.dismissed) };
  } catch { return { seen: [], dismissed: [] }; }
}

function uniqueNotices(items: Notice[]): Notice[] {
  const ids = new Set<string>();
  return items.slice(0, 50).filter(item => {
    if (!item || typeof item.id !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(item.id)
      || typeof item.title !== "string" || !item.title.trim() || item.title.length > 128
      || typeof item.message !== "string" || item.message.length > 2000
      || typeof item.createdAt !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?(?:Z|[+-]\d{2}:\d{2})$/.test(item.createdAt)
      || !Number.isFinite(Date.parse(item.createdAt)) || ids.has(item.id.toLowerCase())) return false;
    ids.add(item.id.toLowerCase());
    return true;
  });
}

const NotificationContext = createContext({
  unread: 0, openInbox: () => {},
  update: (_installationId: string, _items?: Notice[]) => {},
});
export const useNotifications = () => useContext(NotificationContext);

export function NotificationsProvider({ children }: { children: ReactNode }) {
  const { lang } = useLang();
  const ar = lang === "ar";
  const [data, setData] = useState<{ id: string; items: Notice[]; receipts: Receipts }>({ id: "", items: [], receipts: { seen: [], dismissed: [] } });
  const [inbox, setInbox] = useState(false);
  const sounded = useRef(new Set<string>());
  const update = useCallback((id: string, items?: Notice[]) => {
    if (!id) return;
    setData(previous => ({ id, items: Array.isArray(items) ? uniqueNotices(items) : previous.id === id ? previous.items : [],
      receipts: previous.id === id ? previous.receipts : readReceipts(id) }));
  }, []);
  useEffect(() => {
    if (!data.id) return;
    try { localStorage.setItem(receiptKey(data.id), JSON.stringify(data.receipts)); } catch { /* Storage may be unavailable on TV. */ }
  }, [data.id, data.receipts]);
  useEffect(() => {
    if (!inbox) return;
    setData(previous => {
      const unread = previous.items.filter(item => !previous.receipts.seen.includes(item.id));
      return unread.length ? { ...previous, receipts: { ...previous.receipts, seen: [...previous.receipts.seen, ...unread.map(item => item.id)] } } : previous;
    });
  }, [inbox, data.items]);
  const popups = data.items.filter(item => !data.receipts.dismissed.includes(item.id) && !data.receipts.seen.includes(item.id));
  useEffect(() => {
    const arrivals = popups.filter(item => !sounded.current.has(`${data.id}:${item.id}`));
    if (!arrivals.length) return;
    arrivals.forEach(item => sounded.current.add(`${data.id}:${item.id}`));
    let audio: AudioContext | undefined;
    try {
      audio = new AudioContext();
      const oscillator = audio.createOscillator();
      const gain = audio.createGain();
      oscillator.connect(gain); gain.connect(audio.destination);
      oscillator.frequency.value = 880;
      gain.gain.setValueAtTime(0.08, audio.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.001, audio.currentTime + 0.25);
      oscillator.start(); oscillator.stop(audio.currentTime + 0.25);
      oscillator.onended = () => { void audio?.close().catch(() => {}); };
      void audio.resume().catch(() => {});
    } catch { /* Arrival sound is best-effort; autoplay may be blocked. */ }
    const timer = setTimeout(() => { if (audio && audio.state !== "closed") void audio.close().catch(() => {}); }, 1000);
    return () => { clearTimeout(timer); if (audio && audio.state !== "closed") void audio.close().catch(() => {}); };
  }, [data.id, popups.map(item => item.id).join("\n")]);
  const dismiss = useCallback((id: string) => setData(previous => previous.receipts.dismissed.includes(id) ? previous : ({
    ...previous, receipts: { ...previous.receipts, dismissed: [...previous.receipts.dismissed, id] },
  })), []);
  const closeInbox = useCallback(() => setInbox(false), []);
  return <NotificationContext.Provider value={{ update, openInbox: () => setInbox(true),
    unread: data.items.filter(item => !data.receipts.seen.includes(item.id)).length }}>
    {children}
    <div className="notification-popups" aria-live="polite" aria-relevant="additions">
      {popups.map(item => <NotificationPopup key={`${data.id}:${item.id}`} item={item} dismiss={dismiss} />)}
    </div>
    {inbox && <NotificationInbox items={data.items} onClose={closeInbox} ar={ar} />}
  </NotificationContext.Provider>;
}

export function NotificationBell() {
  const { unread, openInbox } = useNotifications();
  const { lang } = useLang();
  return <FocusButton className="notification-bell" onEnter={openInbox}
    title={`${lang === "ar" ? "الإشعارات" : "Notifications"}${unread ? ` (${unread})` : ""}`}>
    <Bell size={20} aria-hidden="true" />
    {unread > 0 && <span className="notification-count" aria-hidden="true">{unread > 99 ? "99+" : unread}</span>}
  </FocusButton>;
}

function NotificationPopup({ item, dismiss }: { item: Notice; dismiss: (id: string) => void }) {
  const { lang } = useLang();
  useEffect(() => {
    const timer = setTimeout(() => dismiss(item.id), 60_000);
    return () => clearTimeout(timer);
  }, [item.id, dismiss]);
  return <article className="notification-card">
    <div><h2 dir="auto">{item.title}</h2><p dir="auto">{item.message}</p></div>
    <FocusButton className="notification-close" onEnter={() => dismiss(item.id)} title={lang === "ar" ? "إغلاق" : "Close"}><X size={22} aria-hidden="true" /></FocusButton>
  </article>;
}

function NotificationInbox({ items, onClose, ar }: { items: Notice[]; onClose: () => void; ar: boolean }) {
  const { ref, focusKey } = useFocusable<HTMLElement>({ isFocusBoundary: true });
  useEffect(() => {
    const previous = getCurrentFocusKey();
    const element = document.activeElement;
    setFocus(focusKey);
    const back = (event: KeyboardEvent) => {
      if (event.key === "Tab") {
        const root: HTMLElement | null = ref.current;
        const controls = Array.from(root?.querySelectorAll<HTMLElement>('[tabindex="0"]') ?? []);
        if (controls.length) {
          event.preventDefault();
          const index = controls.indexOf(document.activeElement as HTMLElement);
          controls[(index + (event.shiftKey ? -1 : 1) + controls.length) % controls.length].focus();
        }
      }
      if (event.key === "Escape" || event.key === "Backspace" || event.keyCode === 10009 || event.keyCode === 461) {
        event.preventDefault(); event.stopImmediatePropagation(); onClose();
      }
    };
    window.addEventListener("keydown", back, true);
    return () => {
      window.removeEventListener("keydown", back, true);
      if (previous) setFocus(previous);
      if (element instanceof HTMLElement && element.isConnected) element.focus();
    };
  }, [focusKey, onClose, ref]);
  return <FocusContext.Provider value={focusKey}><div className="notification-backdrop">
    <section ref={ref} className="notification-inbox" role="dialog" aria-modal="true" aria-labelledby="notification-title">
      <header><h2 id="notification-title">{ar ? "الإشعارات" : "Notifications"}</h2>
        <FocusButton className="notification-close" onEnter={onClose} title={ar ? "إغلاق" : "Close"}><X size={22} aria-hidden="true" /></FocusButton></header>
      {items.length ? <ul>{items.map(item => <NotificationRow key={item.id} item={item} ar={ar} />)}</ul> : <p>{ar ? "لا توجد إشعارات" : "No notifications"}</p>}
    </section>
  </div></FocusContext.Provider>;
}

function NotificationRow({ item, ar }: { item: Notice; ar: boolean }) {
  const { ref, focused, focusSelf } = useFocusable<HTMLLIElement>();
  useEffect(() => { if (focused) ref.current?.scrollIntoView({ block: "nearest" }); }, [focused, ref]);
  return <li ref={ref} tabIndex={0} onFocus={() => focusSelf()} className={focused ? "focused" : ""}>
    <h3 dir="auto">{item.title}</h3><p dir="auto">{item.message}</p>
    {!Number.isNaN(Date.parse(item.createdAt)) && <time dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleString(ar ? "ar" : "en-GB")}</time>}
  </li>;
}
