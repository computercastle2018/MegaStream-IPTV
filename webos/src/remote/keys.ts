// webOS Magic Remote / standard remote key codes.
// Arrow keys + Enter are standard DOM keydown codes; the rest are webOS-specific.
export const RemoteKey = {
  Left: 37,
  Up: 38,
  Right: 39,
  Down: 40,
  Enter: 13,
  Back: 461, // webOS "Back" button
  Red: 403,
  Green: 404,
  Yellow: 405,
  Blue: 406,
  ChannelUp: 33,
  ChannelDown: 34,
  Play: 415,
  Pause: 19,
  Stop: 413,
} as const;

/**
 * Registers a handler for the webOS Back button. Returns an unsubscribe fn.
 * On a desktop browser (dev), the Backspace key is mapped to Back so the flow
 * is testable without a TV.
 */
export function onBack(handler: () => void): () => void {
  const listener = (e: KeyboardEvent) => {
    if (e.keyCode === RemoteKey.Back || e.key === "Backspace" || e.key === "Escape") {
      e.preventDefault();
      handler();
    }
  };
  window.addEventListener("keydown", listener);
  return () => window.removeEventListener("keydown", listener);
}
