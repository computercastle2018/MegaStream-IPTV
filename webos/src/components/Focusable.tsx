import { useEffect } from "react";
import { useFocusable } from "@noriginmedia/norigin-spatial-navigation";
import { useLang } from "../i18n/LanguageContext";
import { LANGUAGES } from "../i18n/translations";

interface FocusButtonProps {
  className?: string;
  onEnter: () => void;
  focusKey?: string;
  autoFocus?: boolean;
  children: React.ReactNode;
}

/** A D-pad-focusable button. Enter/OK triggers onEnter. */
export function FocusButton({ className = "", onEnter, focusKey, autoFocus, children }: FocusButtonProps) {
  const { ref, focused, focusSelf } = useFocusable({ focusKey, onEnterPress: onEnter });

  useEffect(() => {
    if (autoFocus) focusSelf();
  }, [autoFocus, focusSelf]);

  return (
    <div
      ref={ref}
      className={`focusable ${className} ${focused ? "focused" : ""}`}
      onClick={onEnter}
      role="button"
      tabIndex={0}
    >
      {children}
    </div>
  );
}

/** A focusable button that cycles the UI language (EN ⇄ العربية). */
export function LanguageToggle() {
  const { lang, setLang } = useLang();
  const { ref, focused } = useFocusable({
    onEnterPress: () => setLang(lang === "ar" ? "en" : "ar"),
  });
  const next = LANGUAGES.find((l) => l.code !== lang);
  return (
    <div
      ref={ref}
      className={`focusable lang-toggle ${focused ? "focused" : ""}`}
      onClick={() => setLang(lang === "ar" ? "en" : "ar")}
      role="button"
      tabIndex={0}
      aria-label={`Switch to ${next?.label}`}
    >
      <svg viewBox="0 0 24 24" width="1em" height="1em" aria-hidden="true">
        <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="1.8" />
        <path
          d="M3 12h18M12 3c2.5 2.5 2.5 15 0 18M12 3c-2.5 2.5-2.5 15 0 18"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.6"
        />
      </svg>
      <span>{next?.label}</span>
    </div>
  );
}

interface BackButtonProps {
  onEnter: () => void;
  label?: string;
  autoFocus?: boolean;
}

/** A focusable, visible Back affordance with an arrow icon. */
export function BackButton({ onEnter, label, autoFocus }: BackButtonProps) {
  const { t } = useLang();
  const text = label ?? t("common.back");
  const { ref, focused, focusSelf } = useFocusable({ onEnterPress: onEnter });

  useEffect(() => {
    if (autoFocus) focusSelf();
  }, [autoFocus, focusSelf]);

  return (
    <div
      ref={ref}
      className={`focusable back-btn ${focused ? "focused" : ""}`}
      onClick={onEnter}
      role="button"
      tabIndex={0}
      aria-label={text}
    >
      <svg viewBox="0 0 24 24" width="1em" height="1em" aria-hidden="true">
        <path
          d="M15 5l-7 7 7 7"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.4"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
      <span className="back-label">{text}</span>
    </div>
  );
}

interface SearchInputProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  autoFocus?: boolean;
}

/** A focusable search box with an icon, plus backspace + clear buttons. */
export function SearchInput({ value, onChange, placeholder = "Search", autoFocus }: SearchInputProps) {
  const { ref, focused, focusSelf } = useFocusable<HTMLInputElement>({
    onEnterPress: () => ref.current?.focus(),
  });

  // When revealed, take D-pad focus so the box is immediately usable.
  useEffect(() => {
    if (autoFocus) focusSelf();
  }, [autoFocus, focusSelf]);

  // External-keyboard support: when the box gains D-pad focus, give the native
  // <input> real DOM focus so a USB/Bluetooth keyboard types straight into it
  // (and the webOS on-screen keyboard opens for remote-only users).
  useEffect(() => {
    if (focused) ref.current?.focus();
  }, [focused, ref]);

  return (
    <div className={`search-box ${focused ? "focused" : ""}`}>
      <svg className="search-glyph" viewBox="0 0 24 24" width="1em" height="1em" aria-hidden="true">
        <circle cx="11" cy="11" r="7" fill="none" stroke="currentColor" strokeWidth="2" />
        <path d="M16.5 16.5L21 21" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      </svg>
      <input
        ref={ref}
        type="search"
        value={value}
        placeholder={placeholder}
        dir="auto"
        autoComplete="off"
        onChange={(e) => onChange(e.target.value)}
      />
      {value && (
        <>
          <FocusButton
            className="search-icon-btn"
            focusKey="search-backspace"
            onEnter={() => onChange(value.slice(0, -1))}
          >
            <span aria-label="Backspace">⌫</span>
          </FocusButton>
          <FocusButton className="search-icon-btn" onEnter={() => onChange("")}>
            <span aria-label="Clear">✕</span>
          </FocusButton>
        </>
      )}
    </div>
  );
}

interface FocusInputProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  placeholder?: string;
}

/**
 * A focusable text field. On webOS, focusing the underlying <input> brings up
 * the on-screen keyboard; Enter/OK from the remote focuses it for typing.
 */
export function FocusInput({ label, value, onChange, type = "text", placeholder }: FocusInputProps) {
  const { ref, focused } = useFocusable<HTMLInputElement>({
    focusable: true,
    onEnterPress: () => ref.current?.focus(),
  });

  // External-keyboard support: DOM-focus the field once it gains D-pad focus so
  // a hardware keyboard types directly (the webOS on-screen keyboard still opens
  // for remote-only users).
  useEffect(() => {
    if (focused) ref.current?.focus();
  }, [focused, ref]);

  return (
    <div className="field">
      <label>{label}</label>
      <input
        ref={ref}
        className={focused ? "focused" : ""}
        type={type}
        value={value}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
      />
    </div>
  );
}
