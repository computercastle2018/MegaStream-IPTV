import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { Columns3, LayoutGrid, Rows3, X } from "lucide-react";
import { useLang } from "../i18n/LanguageContext";
import "./Appearance.css";

type Design = "classic" | "modern" | "studio";
const valid = (value: unknown): value is Design => value === "classic" || value === "modern" || value === "studio";
const AppearanceContext = createContext({ design: "classic" as Design, remote: null as Design | null,
  select: (_value: Design) => {}, setRemote: (_value: unknown) => {} });

export function AppearanceProvider({ children }: { children: ReactNode }) {
  const [local, setLocal] = useState<Design>(() => {
    const value = localStorage.getItem("MegaStream.design");
    return valid(value) ? value : "classic";
  });
  const [remote, updateRemote] = useState<Design | null>(null);
  const setRemote = useCallback((value: unknown) => updateRemote(valid(value) ? value : null), []);
  const design = remote ?? local;
  useEffect(() => { document.documentElement.dataset.design = design; }, [design]);
  return <AppearanceContext.Provider value={{ design, remote,
    select: value => { setLocal(value); localStorage.setItem("MegaStream.design", value); },
    setRemote }}>{children}</AppearanceContext.Provider>;
}

export const useAppearance = () => useContext(AppearanceContext);

export function AppearanceDialog({ onClose, onAccount }: { onClose: () => void; onAccount: () => void }) {
  const { design, remote, select } = useAppearance();
  const { lang } = useLang();
  const ar = lang === "ar";
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const previous = document.activeElement;
    ref.current?.showModal();
    return () => { if (previous instanceof HTMLElement) previous.focus(); };
  }, []);
  const options = [
    { id: "classic" as const, label: ar ? "الحالي" : "Classic", Icon: LayoutGrid },
    { id: "modern" as const, label: ar ? "حديث" : "Modern", Icon: Rows3 },
    { id: "studio" as const, label: "Studio", Icon: Columns3 },
  ];
  return <dialog ref={ref} className="appearance-dialog" onCancel={event => { event.preventDefault(); onClose(); }}
    onKeyDown={event => event.stopPropagation()} aria-labelledby="appearance-title">
    <header><h2 id="appearance-title">{ar ? "القوالب والإعدادات" : "Appearance & settings"}</h2>
      <button autoFocus className="appearance-close" onClick={onClose} title={ar ? "إغلاق" : "Close"}><X size={22} /></button></header>
    {remote && <p role="status">{ar ? "التصميم محدد بواسطة لوحة التحكم" : "Appearance is managed by the control panel"}</p>}
    <fieldset disabled={!!remote}><legend>{ar ? "تصميم الواجهة" : "Interface design"}</legend>
      <div className="design-options">{options.map(({ id, label, Icon }) => <label key={id} className={`design-option ${design === id ? "selected" : ""}`}>
        <span className={`design-preview preview-${id}`} aria-hidden="true"><Icon size={36} /><span /><span /><span /></span>
        <span><input type="radio" name="design" value={id} checked={design === id} onChange={() => select(id)} />{label}</span>
      </label>)}</div>
    </fieldset>
    <button className="appearance-account" onClick={() => { onClose(); onAccount(); }}>{ar ? "الاشتراك والترخيص" : "Subscription & license"}</button>
  </dialog>;
}
