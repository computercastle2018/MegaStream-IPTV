# ADR 0004: Managed kiosk policy and explicit exit

- Status: Accepted
- Date: 2026-09-30

## Context

MegaStream should not pause or appear to close merely because an Android TV activity is temporarily backgrounded while playback is active. A foreground media-playback service can raise process importance, but an ordinary Android application cannot suppress Home, Recents, force-stop, reboot, low-memory termination, or vendor task killers.

Android lock-task allowlisting is available only when the device is provisioned under an authorized management relationship such as Device Owner. Enabling it unconditionally could trap a user in the application or falsely claim protection on an unmanaged television.

## Decision

The control plane exposes a closed kiosk policy: `off`, `playback`, or `always`, plus an independent `allowLocalExit` flag.

- `off` never requests lock task.
- `playback` requests lock task only while playback is active and the application is foreground-visible.
- `always` requests lock task while the application is foreground-visible.
- Lock task is requested only after Android confirms that MegaStream is Device Owner and the package is allowlisted.
- An unmanaged installation reports the policy as unsupported; the dashboard never reports it as enforced.
- A local exit is available only through an explicit, confirmed Manual Exit when `allowLocalExit` is true. It releases an owned lock-task request before ending the task.
- Background transitions never call Manual Exit and never finish the application.
- A disabled installation receives the safe effective policy `off` with local exit allowed, regardless of its stored requested policy.

Foreground playback protection and kiosk enforcement remain separate: the foreground service mitigates process eviction during media playback; kiosk mode restricts ordinary user navigation on managed devices.

## Consequences

Managed deployments can choose playback-only or application-wide containment without changing the Installation identity. Ordinary installations retain standard Android navigation and can only receive best-effort foreground playback protection. Kiosk status must distinguish requested, applied, released, unsupported, denied, and failed states. No policy can guarantee survival from force-stop, reboot, OOM, native failure, or firmware termination.
