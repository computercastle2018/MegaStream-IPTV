import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { FocusContext, getCurrentFocusKey, setFocus, useFocusable } from "@noriginmedia/norigin-spatial-navigation";
import { Columns3, LayoutGrid, Rows3, X } from "lucide-react";
import { useLang } from "../i18n/LanguageContext";
import { FocusButton } from "./Focusable";
import "./Appearance.css";

type Design = "classic" | "modern" | "studio";
const valid = (value: unknown): value is Design => value === "classic" || value === "modern" || value === "studio";
const AppearanceContext = createContext({ design: "classic" as Design, remote: null as Design | null,
  saving: false, select: (_value: Design) => {}, setRemote: (_value: unknown) => {} });

export function AppearanceProvider({ children }: { children: ReactNode }) {
  const [local, setLocal] = useState<Design>(() => {
    const value = localStorage.getItem("MegaStream.design");
    return valid(value) ? value : "classic";
  });
  const [remote, updateRemote] = useState<Design | null>(null);
  const [saving, setSaving] = useState(false);
  const setRemote = useCallback((value: unknown) => updateRemote(valid(value) ? value : null), []);
  const design = remote ?? local;
  useEffect(() => { document.documentElement.dataset.design = design; }, [design]);
  return <AppearanceContext.Provider value={{ design, remote, saving,
    select: async value => {
      if (saving) return;
      setLocal(value); localStorage.setItem("MegaStream.design", value);
      const api = window.megastreamDesktop?.control ?? window.megastreamWebos?.control;
      if (api?.setAppearance) {
        setSaving(true); updateRemote(value);
        try { await api.setAppearance(value); } catch { setRemote(remote); } finally { setSaving(false); }
      }
    },
    setRemote }}>{children}</AppearanceContext.Provider>;
}

export const useAppearance = () => useContext(AppearanceContext);

export function AppearanceDialog({ onClose, onAccount }: { onClose: () => void; onAccount: () => void }) {
  const { design, remote, saving, select } = useAppearance();
  const { lang } = useLang();
  const ar = lang === "ar";
  const managed = !!remote && !(window.megastreamDesktop?.control ?? window.megastreamWebos?.control)?.setAppearance;
  const { ref, focusKey } = useFocusable<HTMLDialogElement>({ isFocusBoundary: true });
  useEffect(() => {
    const previousKey = getCurrentFocusKey();
    const previous = document.activeElement;
    ref.current?.showModal();
    setFocus(focusKey);
    return () => {
      if (previousKey) setFocus(previousKey);
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
    };
  }, [focusKey, ref]);
  useEffect(() => {
    // Saving disables the focused radio, so Back must also work from the document body.
    const close = (event: KeyboardEvent) => {
      if (event.key !== "Escape" && event.keyCode !== 461 && event.keyCode !== 10009) return;
      event.preventDefault(); event.stopImmediatePropagation(); onClose();
    };
    window.addEventListener("keydown", close, true);
    return () => window.removeEventListener("keydown", close, true);
  }, [onClose]);
  const options = [
    { id: "classic" as const, label: ar ? "الحالي" : "Classic", Icon: LayoutGrid },
    { id: "modern" as const, label: ar ? "حديث" : "Modern", Icon: Rows3 },
    { id: "studio" as const, label: "Studio", Icon: Columns3 },
  ];
  return <FocusContext.Provider value={focusKey}><dialog ref={ref} className="appearance-dialog" onCancel={event => { event.preventDefault(); onClose(); }}
    aria-labelledby="appearance-title">
    <header><h2 id="appearance-title">{ar ? "القوالب والإعدادات" : "Appearance & settings"}</h2>
      <FocusButton className="appearance-close" onEnter={onClose} title={ar ? "إغلاق" : "Close"}><X size={22} /></FocusButton></header>
    {remote && <p role="status">{managed ? (ar ? "التصميم محدد بواسطة لوحة التحكم" : "Appearance is managed by the control panel")
      : (ar ? "يُحفظ التصميم لهذا الجهاز ويُزامن مع لوحة التحكم" : "Appearance is saved for this device and synced with the control panel")}</p>}
    <fieldset disabled={managed || saving}><legend>{ar ? "تصميم الواجهة" : "Interface design"}</legend>
      <div className="design-options">{options.map(({ id, label, Icon }) => <DesignOption key={id} id={id} label={label}
        selected={design === id} disabled={managed || saving} onSelect={() => select(id)}>
        <span className={`design-preview preview-${id}`} aria-hidden="true"><Icon size={36} /><span /><span /><span /></span>
      </DesignOption>)}</div>
    </fieldset>
    <FocusButton className="appearance-account" onEnter={() => { onClose(); onAccount(); }}>{ar ? "الاشتراك والترخيص" : "Subscription & license"}</FocusButton>
  </dialog></FocusContext.Provider>;
}

function DesignOption({ id, label, selected, disabled, onSelect, children }: {
  id: Design; label: string; selected: boolean; disabled: boolean; onSelect: () => void; children: ReactNode;
}) {
  const { ref, focused, focusSelf } = useFocusable<HTMLLabelElement>({ focusable: !disabled, onEnterPress: onSelect });
  useEffect(() => { if (focused) ref.current?.focus({ preventScroll: true }); }, [focused, ref]);
  return <label ref={ref} tabIndex={disabled ? -1 : 0} onFocus={() => focusSelf()}
    className={`design-option focusable ${selected ? "selected" : ""} ${focused ? "focused" : ""}`}>
    {children}<span><input tabIndex={-1} type="radio" name="design" value={id} checked={selected} disabled={disabled} onChange={onSelect} />{label}</span>
  </label>;
}
