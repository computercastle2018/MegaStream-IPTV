import { useEffect, useState } from "react";
import type { Provider, ProviderType } from "../types";
import { FocusButton, FocusInput, LanguageToggle } from "../components/Focusable";
import { addProvider, newId, removeProvider } from "../services/storage";
import { authenticate } from "../services/xtreamClient";
import { onBack } from "../remote/keys";
import { useLang } from "../i18n/LanguageContext";

interface Props {
  providers: Provider[];
  onChange: (providers: Provider[]) => void;
  onConnect: (id: string) => void;
  onDone: () => void;
  canGoBack: boolean;
}

export default function ProviderSetupScreen({ providers, onChange, onConnect, onDone, canGoBack }: Props) {
  const { t } = useLang();
  const [type, setType] = useState<ProviderType>("xtream");
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [server, setServer] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [status, setStatus] = useState<{ msg: string; error: boolean } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!canGoBack) return;
    return onBack(onDone);
  }, [canGoBack, onDone]);

  const reset = () => {
    setName("");
    setUrl("");
    setServer("");
    setUsername("");
    setPassword("");
  };

  const handleAdd = async () => {
    setStatus(null);
    try {
      if (type === "m3u") {
        if (!url.trim()) return setStatus({ msg: t("setup.errPlaylistUrl"), error: true });
        const provider: Provider = {
          id: newId(),
          type: "m3u",
          name: name.trim() || "M3U Playlist",
          url: url.trim(),
        };
        onChange(addProvider(provider));
        setStatus({ msg: t("setup.okPlaylistAdded"), error: false });
        reset();
      } else {
        if (!server.trim() || !username.trim() || !password.trim()) {
          return setStatus({ msg: t("setup.errXtreamFields"), error: true });
        }
        const provider: Provider = {
          id: newId(),
          type: "xtream",
          name: name.trim() || "Xtream Provider",
          serverUrl: server.trim(),
          username: username.trim(),
          password: password.trim(),
        };
        setBusy(true);
        await authenticate(provider);
        onChange(addProvider(provider));
        setStatus({ msg: t("setup.okConnected"), error: false });
        reset();
      }
    } catch (e) {
      setStatus({ msg: e instanceof Error ? e.message : t("setup.errAddFailed"), error: true });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="auth-screen">
      <div className="auth-grid">
        <aside className="brand-panel">
          <div className="brand-top">
            <div className="brand-mark" aria-hidden="true">
              <svg viewBox="0 0 48 48" width="100%" height="100%">
                <defs>
                  <linearGradient id="svg-grad" x1="0" y1="0" x2="1" y2="1">
                    <stop offset="0" stopColor="#14b8a6" />
                    <stop offset="1" stopColor="#0f766e" />
                  </linearGradient>
                </defs>
                <rect x="3" y="3" width="42" height="42" rx="12" fill="url(#svg-grad)" />
                <path d="M20 17l11 7-11 7z" fill="#03110f" />
              </svg>
            </div>
            <div className="brand-name">MegaStream</div>
          </div>
          <p className="brand-tagline">{t("brand.tagline")}</p>
          <ul className="brand-features">
            <li>
              <Check /> {t("brand.feat1")}
            </li>
            <li>
              <Check /> {t("brand.feat2")}
            </li>
            <li>
              <Check /> {t("brand.feat3")}
            </li>
          </ul>
          <div className="brand-lang">
            <LanguageToggle />
          </div>
        </aside>

        <main className="form-card">
          <header className="form-head">
            <h2>{t("setup.title")}</h2>
            <p>{t("setup.subtitle")}</p>
          </header>

          <div className="segmented" role="tablist">
            <FocusButton
              className={`seg-option ${type === "xtream" ? "active" : ""}`}
              autoFocus
              onEnter={() => setType("xtream")}
            >
              {t("setup.tabXtream")}
            </FocusButton>
            <FocusButton
              className={`seg-option ${type === "m3u" ? "active" : ""}`}
              onEnter={() => setType("m3u")}
            >
              {t("setup.tabM3u")}
            </FocusButton>
          </div>

          <div className="form-fields">
            <FocusInput
              label={t("setup.displayName")}
              value={name}
              onChange={setName}
              placeholder={t("setup.displayNamePh")}
            />

            {type === "m3u" ? (
              <FocusInput
                label={t("setup.playlistUrl")}
                value={url}
                onChange={setUrl}
                placeholder="http://host/playlist.m3u8"
              />
            ) : (
              <>
                <FocusInput
                  label={t("setup.serverUrl")}
                  value={server}
                  onChange={setServer}
                  placeholder="http://host:port"
                />
                <div className="field-row">
                  <FocusInput label={t("setup.username")} value={username} onChange={setUsername} />
                  <FocusInput label={t("setup.password")} value={password} onChange={setPassword} type="password" />
                </div>
              </>
            )}
          </div>

          <div className="form-actions">
            <FocusButton className="btn primary block" onEnter={busy ? () => {} : handleAdd}>
              {busy ? t("setup.connecting") : t("setup.add")}
            </FocusButton>
            {canGoBack && (
              <FocusButton className="btn ghost" onEnter={onDone}>
                {t("setup.done")}
              </FocusButton>
            )}
          </div>

          {status && (
            <div className={`status-banner ${status.error ? "error" : "ok"}`}>
              <span className="status-dot" />
              {status.msg}
            </div>
          )}

          {providers.length > 0 && (
            <section className="saved">
              <h3>{t("setup.savedProviders")}</h3>
              <div className="provider-list">
                {providers.map((p) => (
                  <div key={p.id} className="provider-row">
                    <div className="provider-info">
                      <span className="provider-badge">{p.type === "xtream" ? "XC" : "M3U"}</span>
                      <div className="provider-text">
                        <div className="provider-name">{p.name}</div>
                        <div className="provider-meta">
                          {p.type === "xtream" ? p.serverUrl : p.url}
                        </div>
                      </div>
                    </div>
                    <div className="provider-actions">
                      <FocusButton className="btn primary small" onEnter={() => onConnect(p.id)}>
                        {t("setup.connect")}
                      </FocusButton>
                      <FocusButton className="btn ghost small" onEnter={() => onChange(removeProvider(p.id))}>
                        {t("setup.remove")}
                      </FocusButton>
                    </div>
                  </div>
                ))}
              </div>
            </section>
          )}
        </main>
      </div>
    </div>
  );
}

function Check() {
  return (
    <svg className="feat-check" viewBox="0 0 20 20" width="1em" height="1em" aria-hidden="true">
      <circle cx="10" cy="10" r="10" fill="rgba(20,184,166,0.18)" />
      <path d="M6 10.5l2.5 2.5L14 7.5" fill="none" stroke="#14b8a6" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
