import { useCallback, useEffect, useId, useState } from "react";
import { FocusContext, getCurrentFocusKey, setFocus, useFocusable } from "@noriginmedia/norigin-spatial-navigation";
import { Check, ChevronDown, X } from "lucide-react";
import { FocusButton } from "./Focusable";
import { useLang } from "../i18n/LanguageContext";
import "./RemoteSelect.css";

interface Props {
  label: string;
  value: string;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
}

export default function RemoteSelect(props: Props) {
  const [open, setOpen] = useState(false);
  const close = useCallback(() => setOpen(false), []);
  return <>
    <FocusButton className="remote-select-trigger" title={props.label} hasPopup="dialog" expanded={open} onEnter={() => setOpen(true)}>
      <span>{props.options.find(option => option.value === props.value)?.label}</span><ChevronDown size={20} aria-hidden="true" />
    </FocusButton>
    {open && <RemoteOptions {...props} onClose={close} />}
  </>;
}

function RemoteOptions({ label, value, options, onChange, onClose }: Props & { onClose: () => void }) {
  const { lang } = useLang();
  const id = useId();
  const { ref, focusKey } = useFocusable<HTMLDialogElement>({ isFocusBoundary: true });
  useEffect(() => {
    const previousKey = getCurrentFocusKey(), element = document.activeElement;
    ref.current?.showModal();
    setFocus(`${id}:${value}`);
    const back = (event: KeyboardEvent) => {
      if (!["Escape", "Backspace"].includes(event.key) && event.keyCode !== 461 && event.keyCode !== 10009) return;
      event.preventDefault(); event.stopImmediatePropagation(); onClose();
    };
    window.addEventListener("keydown", back, true);
    return () => {
      window.removeEventListener("keydown", back, true);
      if (previousKey) setFocus(previousKey);
      if (element instanceof HTMLElement && element.isConnected) element.focus();
    };
  }, [focusKey, id, onClose, ref, value]);
  return <FocusContext.Provider value={focusKey}><dialog ref={ref} className="remote-options" aria-labelledby={`${id}-title`}
    onCancel={event => { event.preventDefault(); onClose(); }}>
    <header><h2 id={`${id}-title`}>{label}</h2>
      <FocusButton className="remote-options-close" title={lang === "ar" ? "إغلاق" : "Close"} onEnter={onClose}><X size={24} /></FocusButton></header>
    <div className="remote-options-list">{options.map(option => <RemoteOption key={option.value} focusKey={`${id}:${option.value}`}
      label={option.label} selected={option.value === value} onSelect={() => { onChange(option.value); onClose(); }} />)}</div>
  </dialog></FocusContext.Provider>;
}

function RemoteOption({ focusKey, label, selected, onSelect }: { focusKey: string; label: string; selected: boolean; onSelect: () => void }) {
  const { ref, focused, focusSelf } = useFocusable<HTMLDivElement>({ focusKey, onEnterPress: onSelect,
    onFocus: ({ node }) => node.scrollIntoView({ block: "nearest" }) });
  return <div ref={ref} tabIndex={0} role="button" aria-pressed={selected} onFocus={() => focusSelf()}
    onClick={() => { focusSelf(); onSelect(); }} className={`remote-option ${selected ? "selected" : ""} ${focused ? "focused" : ""}`}>
    <span>{label}</span>{selected && <Check size={24} aria-hidden="true" />}
  </div>;
}
