# Android TV silent-exit investigation

## Status

The exact cause of the reported television exit is not yet proven because the released app records only uncaught managed exceptions. OOM/low-memory kills, ANRs, native crashes, signals, and system process removal can terminate the process without invoking that handler. The next instrumented build is designed to classify the next occurrence and upload only bounded, sanitized evidence.

## Confirmed instrumentation gaps in the previous build

- `CrashReportStore` installs `Thread.setDefaultUncaughtExceptionHandler`. It can persist a Java/Kotlin uncaught exception but cannot reliably run after low-memory kill, native termination, ANR kill, force-stop, or abrupt power/process loss.
- `RuntimeDiagnosticsManager.start()` and `writeSnapshot()` return when `BuildConfig.DEBUG` is false. Release televisions therefore produced no production memory-pressure timeline.
- No `ApplicationExitInfo` adapter recovered API 30+ process-exit reasons on the next launch.
- There was no durable session-open/session-clean-end marker for API 27–29 or for missing OS history.
- Active playback had no `mediaPlayback` foreground service, leaving the process at a lower importance when the Activity moved to the background.
- `PlayerViewModel.onAppBackgrounded()` paused active playback outside Picture-in-Picture. This was intentional lifecycle behavior but could look like playback stopped when the television launcher or an overlay backgrounded the Activity.

## Relevant intentional exits

The player supports stop-playback and idle-standby timers. Both defaults are zero (disabled). When a user configures one, expiration intentionally navigates out of the player. Telemetry classifies this as `sleep_timer`, not as a crash.

## Ranked hypotheses pending captured evidence

1. **System low-memory/process kill.** Android TV devices commonly have small memory classes. Playback, artwork, catalog state, preview, and up to four split-screen decoders can increase pressure. Existing split-screen policy already limits low-memory devices, but the previous release had no production exit-reason recovery.
2. **Media codec/native process termination.** Decoder or vendor codec failures may terminate below the Java uncaught-exception boundary.
3. **ANR followed by system termination.** The previous release did not recover OS-confirmed ANR evidence.
4. **Activity background lifecycle mistaken for closure.** The previous player paused itself on `ON_STOP` outside PiP and had no playback foreground service.
5. **Configured playback timer.** Possible only when the user changed the default from zero.

These are hypotheses, not a claimed root cause. The dashboard must show captured evidence before one is promoted to the diagnosis.

## Feedback loop

The deterministic local loop exercises the new classification seam rather than trying to synthesize an OEM process kill:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
./gradlew :domain:test --tests 'com.MegaStream.domain.diagnostics.*' \
  :data:testDebugUnitTest --tests 'com.MegaStream.data.diagnostics.*'
```

The tests feed every supported OS exit reason and fallback marker into `SessionTerminationClassifier`, assert that OOM/LMK/ANR/native/signal/user/system/unknown remain distinct, and verify that an already consumed marker is not emitted twice. Outbox tests assert bounded storage, event idempotency, sample-first eviction, and sanitization before persistence.

This loop validates classification and persistence but cannot reproduce the user's OEM-specific exit. Final diagnosis requires one of:

- a dashboard event from the instrumented build after the next occurrence;
- a redacted `adb bugreport` or `logcat` captured around the timestamp; or
- Android vitals/managed-device process-exit records.

## Mitigations in the instrumented build

- `mediaPlayback` foreground service while playback is active.
- Playback continues when the Activity is backgrounded only while the foreground-service request is active; terminal player state stops the service.
- Existing screen-on behavior remains.
- Typed sanitized diagnostic queue and session recovery classifier.
- API 30+ OS exit-reason mapping plus fallback marker classification.
- Exact error evidence distinguishes intentional timer exit, license block, Java crash, ANR, OOM, LMK, native crash, signal, and unknown termination.

A foreground service reduces process eviction risk but cannot guarantee survival after force-stop, unrecoverable OOM, device reboot, firmware failure, or an OEM task killer. Managed Device Owner/Kiosk policy is the only supported route for preventing ordinary user navigation away; it still cannot override every system termination.
