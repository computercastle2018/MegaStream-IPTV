/**
 * Full-screen overlay with an animated MegaStream logo, shown while the
 * library is syncing (e.g. after pressing Reload on the dashboard).
 */
export default function LoadingOverlay({ message }: { message: string }) {
  return (
    <div className="loading-overlay" role="status" aria-live="polite">
      <div className="loading-mark">
        <span className="loading-ring" aria-hidden="true" />
        <svg className="loading-logo" viewBox="0 0 48 48" aria-hidden="true">
          <defs>
            <linearGradient id="loading-grad" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0" stopColor="#a855f7" />
              <stop offset="1" stopColor="#6d28d9" />
            </linearGradient>
          </defs>
          <rect x="3" y="3" width="42" height="42" rx="12" fill="url(#loading-grad)" />
          <path d="M20 17l11 7-11 7z" fill="#fff" />
        </svg>
      </div>
      <div className="loading-text">{message}</div>
      <div className="loading-dots" aria-hidden="true">
        <span />
        <span />
        <span />
      </div>
    </div>
  );
}
