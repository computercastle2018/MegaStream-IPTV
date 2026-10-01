# MegaStream Domain Glossary

## Licensing and entitlement

**License**  
A grant that allows MegaStream application use for a bounded time and a bounded number of installations. A License belongs to the MegaStream control plane, not to an IPTV provider.

**License Key**  
A high-entropy enrollment secret that assigns an installation to a License. It is not the License identity and is displayed only when created.

**Activation Code**  
A short-lived, single-use code used to approve one installation from the administration dashboard. It is not a permanent credential.

**Installation**  
One lifetime of MegaStream app data and secure local storage. Reinstalling or clearing app data creates a new Installation even when the physical television is unchanged.

**Binding**  
The assignment of one Installation to one License. An Installation has at most one current Binding.

**Seat Reservation**  
A License capacity slot retained while an Installation may still possess a valid offline grant. Removing an offline Installation cannot immediately make that slot safe to reuse.

**Offline Lease**  
Signed, installation-bound evidence that MegaStream may continue operating until a fixed deadline without reaching the control plane. It is not a device authentication credential.

**Entitlement Decision**  
The current application-access result: allowed, not started, expired, suspended, revoked, installation disabled, or online verification required.

**Offline Grace**  
The maximum period after the last successful online validation during which an existing Offline Lease may remain usable. A failed request, reboot, or app relaunch never restarts this period.

## Device operations

**Presence**  
A recent authenticated observation from an Installation. Presence indicates online status only; it does not grant application access or prove playback.

**App Session**  
One execution of the MegaStream application process, from observed start until a reported or inferred end.

**Playback Session**  
One playback attempt for a channel or media item. Split-screen panes have independent Playback Sessions.

**Reported Session End**  
A graceful App Session end explicitly recorded by the application.

**Inferred Session End**  
An App Session end inferred on the next launch or by the control plane because no graceful end was recorded.

**Managed Device Policy**  
The control-plane policy requested for a Managed Installation. It describes intended managed behavior but is not evidence that Android enforced it.

**Kiosk Mode**  
A Managed Device Policy that restricts ordinary navigation away from MegaStream either during playback or while the application is in use. It cannot prevent operating-system termination, reboot, or hardware failure.

**Manual Exit**  
An explicit user action that ends MegaStream operation. Background transitions, temporary loss of focus, and screen overlays are not Manual Exits.

## Diagnostics

**Diagnostic Event**  
A typed, bounded, sanitized fact produced by an Installation. Diagnostic Events never contain provider URLs, playlist contents, usernames, passwords, tokens, or license secrets.

**Playback Problem**  
A classified Diagnostic Event describing buffering, decoder failure, source failure, retry exhaustion, or another playback condition.

**Source Type**  
The provider family supplying content: Xtream Codes, M3U, Stalker Portal, local, or unknown. Source Type never identifies a specific provider account.

**Stream Type**  
The media transport or container: HLS, DASH, MPEG-TS, progressive, RTSP, Smooth Streaming, or unknown.

## Upstream provider concepts

**Provider Subscription Expiration**  
An expiration reported by an IPTV provider. It is unrelated to a MegaStream License end date.

**Provider Maximum Connections**  
A connection limit reported by an IPTV provider. It is unrelated to a License installation limit.

## Administration

**Administrator**  
An authenticated operator who manages Licenses, Installations, activation, and diagnostics according to assigned permissions.

**Audit Entry**  
An immutable record of a security-sensitive administration action and its result.

**Retention Policy**  
The rules defining how long Diagnostic Events, App Sessions, and Audit Entries are kept before deletion or aggregation.

## Application updates

**Update Release**  
An immutable, published MegaStream APK version together with its version code, package identity, signing-certificate fingerprint, size, and cryptographic digest.

**Update Command**  
An administrator request asking one Installation to download and install a specific Update Release. A command reports delivery and installation states; it is not proof of success until the installed version is observed.

**Managed Installation**  
An Installation whose Android device is provisioned with MegaStream as Device Owner. Managed Installations may qualify for a silent package installation; ordinary Installations always require Android user confirmation.

## Managed IPTV providers

**Remote Provider Profile**  
An encrypted IPTV provider configuration created by an Administrator for delivery to assigned Installations. It is separate from a MegaStream License and never appears in Diagnostics.

**Provider Assignment**  
The assignment of one Remote Provider Profile to one Installation together with an activation policy. Provider Assignments never target a hardware MAC address.

**Provider Assignment Policy**  
The user-control rule for an assignment: optional, auto-enabled, or required. Locally created providers are outside this policy and cannot be changed remotely.

**Stalker Provider MAC**  
A virtual or account MAC required by a Stalker Portal. It is an encrypted provider credential, not the television's hardware identity.
