# Observability Experiment Plan v0.2

Status: v0.2, 2026-10-08. Companion to `CONCEPT.md` and `THREAT_MODEL.md`. This plan produces evidence, not product code. The throwaway lab it describes (fixtures, probe app, runner) lives in `lab/` and is never shipped.

Changes in v0.2: reference device added, Samsung-specific protective-feature and background-execution tests, trust-tier signals, adb-based ground truth, pre-registered hypotheses, safety rules for the daily phone, and a phased plan with toolchain setup.

**Change in v0.3 (2026-10-08, user decision): the Samsung Galaxy S21 FE is removed from the plan.** All Samsung-specific work (Auto Blocker, Knox, Secure Folder, One UI power management, the settings-discovery protocol) is dropped, Q10 is closed as "not investigated", and Q9 is answered on one device only (the Xperia). The only OEM data point is Sony. No claim is made about Samsung or One UI, and the product's documentation will say "not verified on Samsung". The rows below are kept, marked, so the plan stays honest about what was and was not measured.

## 1. Purpose

Every claim in the threat model depends on one question: **can a normal, non-privileged Play Store app actually read this signal, on which Android versions and devices, and does the value match reality?** This plan answers that signal by signal, so the product is built only on readings that were measured.

## 2. Decisions this experiment must produce

| # | Decision | Driven by |
|---|---|---|
| Q1 | Minimum supported API level (candidate: 29) | Share of Stage 1 signals usable per API level |
| Q2 | Can dangerous-capability apps be enumerated through `<queries>` without `QUERY_ALL_PACKAGES`? If not, shrink scope or accept the Play declaration risk | V1, V2 |
| Q3 | Are Keystore tripwires reliable enough to ship, and what do they miss? | K6, K7 |
| Q4 | Do we need a software SHA-3 implementation, and what does the Keystore support? | K8 |
| Q5 | How much confidence can device-side attestation checking claim, and is a self-hostable verifier worth building in Stage 3? | K1 to K4 |
| Q6 | Which Wi-Fi permission path (location vs nearby-devices) gives usable results, and what degrades without location? | N6 to N8 |
| Q7 | What can the live event view watch in real time, and what needs periodic diffing? | P11 |
| Q8 | Are LAN features viable given API 37 gating and OEM behavior? | L1 to L6 |
| Q9 | Can periodic background snapshots be relied on under the phone's default power management? (one device: the Xperia) | B1 to B3 |
| Q10 | ~~Do OEM protective features (for example Samsung Auto Blocker) expose state a normal app can read?~~ Closed: not investigated (Samsung excluded from the plan). | D11, D12 (not pursued) |
| Q11 | How reliable is trust-tier classification, including install-source spoofing? | V8 |

## 3. Method

1. **Fixtures first.** Build a small set of benign "capability mimic" apps that declare an accessibility service, a notification listener, a device-admin receiver, an input method, SMS and overlay requests, and a hidden launcher entry. They ignore all events and do nothing harmful. They give every signal a known ground truth and are reused later for campaign replay.
2. **Probe app.** One throwaway app, one screen, "run all", plus automation extras so the runner can drive it. For each signal it records a result code and a value summary.
3. **Ground truth from adb, not memory.** Wherever possible the runner reads the true state independently over adb (`settings get`, `getprop`, `dumpsys`, `cmd`), so a reading is checked against the system's own answer. Where adb cannot supply truth, the operator declares the state in the run label.
4. **Setup states.** Before each run, put the device into a documented state (section 6) and record how it was set (adb or the Settings UI, since the UI path also measures restricted-settings gating).
5. **Sweep.** Run every signal across the test matrix (section 4), in each relevant permission state.
6. **Hypothesis log.** The Hypothesis column below is the pre-registration. After each phase every hypothesis is marked Confirmed, Refuted or Partly, with the evidence. Refutations are reported prominently, not buried.
7. **Analyze.** Classify each signal per cell using the decision rules in section 7, then write the findings and update `CONCEPT.md` and `THREAT_MODEL.md`.

## 4. Test matrix

| Axis | Values |
|---|---|
| API level | 29, 31, 33, 34, 35, 36, 37 (Android 10 to 17) |
| Emulators (free) | Stock Google APIs image for each level; one rootable image only for the system-store CA test |
| Excluded by user decision (2026-10-08) | Samsung Galaxy S21 FE, Android 14, One UI 6.1. Not tested; no Samsung claims are made. |
| Physical device (secondary phone, holds some data) | Sony Xperia 10 II, au/KDDI model SOV43, Android 12 (API 31), security patch 2022-07-01, verified boot green, bootloader locked (all confirmed on the device). The user first declared it a spare test phone, then clarified that it holds some data, so it is treated as "used sometimes": read-only sessions plus the background test. Fingerprint, lock-screen and certificate experiments need explicit per-step consent. |
| Permission states | Not requested, requested and denied, granted; location services on and off where relevant |

OEM coverage statement: one OEM data point, Sony on au/KDDI carrier firmware (API 31), with a near-stock interface. Findings never claim behavior on other manufacturers' skins, Samsung's One UI in particular; affected signals are marked "OEM-unverified" instead of "usable". Because Samsung is the largest Android manufacturer, this is a stated limitation of the project, not a footnote.

The Xperia is an end-of-support device with a probably frozen security patch, which makes it a real example for the unpatched-device path (P6), the patch-level signals (D1, K2) and the end-of-life logic (D2).

No paid device farms. The phone can be connected by USB or by wireless debugging, whichever works on this machine.

## 5. Signal catalogue

Result codes: OK (readable, matches ground truth), REDACTED (placeholder or null value), SECURITY_EXCEPTION, UNSUPPORTED, WRONG (readable but differs from ground truth). "Stage" is the first stage that needs the signal.

### 5.1 Device and system

| ID | Signal | How to read it | Hypothesis | Stage |
|---|---|---|---|---|
| D1 | Security patch level | `Build.VERSION.SECURITY_PATCH` | Readable; software-reported, so spoofable on a rooted device | 1 |
| D2 | OS version and end-of-life status | `Build.VERSION`, a bundled support-window table | Readable | 1 |
| D3 | Screen lock present | `KeyguardManager.isDeviceSecure` | Readable; lock type not exposed | 1 |
| D4 | USB debugging | `Settings.Global.ADB_ENABLED` | Readable | 1 |
| D5 | Wireless debugging | `Settings.Global` (Android 11+) | Unknown | 1 |
| D6 | Developer options on | `Settings.Global.DEVELOPMENT_SETTINGS_ENABLED` | Readable | 1 |
| D7 | Advanced Protection state | `AdvancedProtectionManager.isAdvancedProtectionEnabled` (API 36, needs `QUERY_ADVANCED_PROTECTION_MODE`) | Unknown whether a normal app can hold that permission | 1 |
| D8 | Play Protect enabled | No direct API expected; test the package-verifier settings keys | Not readable through an official API; confirm and document | 1 |
| D9 | Device managed by an MDM or admin | `DevicePolicyManager`, `UserManager` | Mostly limited to the app's own profile; test what leaks | 2 |
| D10 | Extra users, work profile, private space | `UserManager` | Partial | 2 |
| D11 | (Not pursued: Samsung excluded.) OEM protective features (Samsung Auto Blocker, Secure Folder presence, Knox state) | Read the settings keys found by D12 from a normal app | Unknown; keys, if any, are OEM-specific and may be unreadable | 1 |
| D12 | Settings discovery protocol (not a product signal) | `adb shell settings list secure/global/system` before and after toggling the OEM feature in Settings, then diff | Produces the candidate key list for D11 | 0 |

### 5.2 Key attestation and Keystore

| ID | Signal | How to read it | Hypothesis | Stage |
|---|---|---|---|---|
| K1 | Attestation chain retrieval and security level (software / TEE / StrongBox) | Key generation with an attestation challenge, `KeyInfo.securityLevel` | Works on API 24+; emulators give software-only attestation | 1 |
| K2 | Attested boot state, lock state, OS patch level, vendor and boot patch levels | Parse the attestation extension | Matches ground truth on physical devices; meaningless on emulators | 1 |
| K3 | Chain validity against Google roots and revocation status | Roots from `android.googleapis.com/attestation/root`; revocations from `.../attestation/status`; note the new root signing from 2026-02-01 | Works with a network fetch and caching; device-side check is weaker than server-side | 1 |
| K4 | Chain shape on devices launched with Android 16 (RKP only) | Parse and validate | Parser must handle RKP chains | 2 |
| K5 | StrongBox availability | System feature flag, key generation with the StrongBox flag | Device-dependent | 3 |
| K6 | Tripwire: biometric enrollment invalidates a bound key | Create two auth-bound keys (biometric-only and biometric-or-credential), enroll a fingerprint, try to initialize a cipher with each | `KeyPermanentlyInvalidatedException` on next use for at least the biometric-only key; check reboot, app update, backup and restore behavior. Key creation needs an enrolled biometric. | 3 |
| K7 | Tripwire: removing or resetting the lock screen invalidates an auth-bound key | Create the key, remove the lock, try to use it | Key is lost; also test whether changing the PIN does or does not trigger it | 3 |
| K8 | Keystore digest support, and platform SHA-3 availability | Reflect over `KeyProperties.DIGEST_*`; try a SHA3-256 key; try `MessageDigest.getInstance("SHA3-256")` per API level | Keystore has no SHA-3 digest; platform SHA-3 varies by level (Bouncy Castle fallback if needed) | 3 |

### 5.3 Special access and provenance

| ID | Signal | How to read it | Hypothesis | Stage |
|---|---|---|---|---|
| P1 | Enabled accessibility services | `AccessibilityManager.getEnabledAccessibilityServiceList`, `Settings.Secure` | Readable | 1 |
| P2 | Accessibility service capability flags | `AccessibilityServiceInfo` capabilities (window content, gestures) | Readable via P1 | 1 |
| P3 | Enabled notification listeners | `Settings.Secure` enabled-listeners entry | Readable | 1 |
| P4 | Active device admins | `DevicePolicyManager.getActiveAdmins` | Unknown; may be null or filtered by package visibility | 1 |
| P5 | Enabled and default input methods | `InputMethodManager`, `Settings.Secure` | Readable | 1 |
| P6 | Default SMS, dialer, assistant, browser | `Telephony`, `TelecomManager`, `Settings.Secure` | Partial; test each | 1 |
| P7 | Overlay permission per app | `AppOpsManager` check by uid and package | Unknown; may need a permission a normal app cannot hold | 1 |
| P8 | Usage access, install-unknown-apps, other special app-ops per app | `AppOpsManager` | Unknown, same caveat | 1 |
| P9 | Granted runtime permissions per visible app | `PackageManager.getPackageInfo` with permissions; check granted flags | Readable for visible packages | 1 |
| P10 | Which app owns the active VPN | `ConnectivityManager` | Likely only "a VPN is up", not the owner | 2 |
| P11 | Change notification in real time | `ContentObserver` on the relevant settings entries; dynamic package-change receiver while the app is open | Fires while the app runs; nothing while it does not, so background diffing is needed | 1 |
| V1 | Capability-based package enumeration | `<queries><intent>` with one action each for accessibility service, notification listener, device-admin receiver, input method; compare with a `QUERY_ALL_PACKAGES` baseline (the probe has one flavor of each) | Android's docs show intent matching with an activity example only; whether service and receiver filters make packages visible is unconfirmed | 1 |
| V1b | Widening `<queries>` to reach hidden-icon apps (added after the sweep showed they are invisible) | Add `<intent>` entries for the boot-completed broadcast and SMS delivery; check whether the hidden-icon fixture (given a matching receiver) becomes visible | Unknown; each added intent costs a line in the Play review story | 1 |
| V2 | What becomes readable once a package is visible | Permissions, signing info, install source, flags | Full for visible packages | 1 |
| V3 | Install source details | `getInstallSourceInfo` (initiating, installing, update owner, package source) | Readable for visible packages; the originating package may be restricted | 1 |
| V4 | Signing certificate SHA-256 and signing scheme | `GET_SIGNING_CERTIFICATES`; reading the APK block for scheme version | Certificate readable; scheme version needs APK read access, test `sourceDir` readability | 1 |
| V5 | Target SDK, debuggable flag, cleartext flag, exported components, network-security-config presence | `ApplicationInfo`, `PackageInfo` | Readable | 2 |
| V6 | First install and last update time | `PackageInfo` | Readable; feeds drift | 1 |
| V7 | Hidden launcher icon | Compare launcher-intent query with the visible package set | Depends on V1 visibility | 2 |
| V8 | Trust tier of each app (platform-signed, system, OEM preinstalled, store-installed, sideloaded) | System flags, `checkSignatures("android", pkg)`, installer, initiating package, package source | Platform and system tiers reliable; store vs sideload depends on the installer field, which `adb install -i` can set to any value, so compare it with the initiating package and package source (API 33+) | 1 |

### 5.4 Network configuration

| ID | Signal | How to read it | Hypothesis | Stage |
|---|---|---|---|---|
| N1 | Transport, VPN present, validated, captive portal | `NetworkCapabilities` | Readable with normal permissions | 1 |
| N2 | DNS servers, Private DNS active and name | `LinkProperties` | Readable | 1 |
| N3 | HTTP proxy | `LinkProperties.getHttpProxy` | Wi-Fi proxy visible; global setting to test | 1 |
| N4 | Gateway, routes, DHCP server address | `LinkProperties` | Partial | 1 |
| N5 | User-installed CA | `AndroidCAStore` aliases and `X509TrustManagerExtensions.isUserAddedCertificate`; the probe uses the default network security config, which is what a product would use | Detectable through the keystore; whether it lists user CAs under the default config is unknown; a CA injected into the system store (rootable image) should be invisible | 1 |
| N6 | Wi-Fi security type | `WifiInfo.getCurrentSecurityType` (API 31+), `ScanResult.capabilities` below that | Readable; WPA1 and WPA2 may both appear as PSK | 1 |
| N7 | SSID and BSSID access matrix | Permission × location services on/off × API level | Redacted without location | 1 |
| N8 | Scan results for same-SSID, different-security checks | `WifiManager.getScanResults` | Needs location; throttled; high false-positive risk (the probe reports only counts) | 4 |
| N9 | Captive-portal details | `LinkProperties.getCaptivePortalData` (API 30+) | Partial | 4 |
| N10 | Cellular generation and 2G state | Telephony APIs | Needs a phone-state permission; likely dropped from scope | none |

### 5.5 LAN and probes

| ID | Signal | How to read it | Hypothesis | Stage |
|---|---|---|---|---|
| L1 | mDNS discovery | `NsdManager` | Works to API 36; check API 37 behavior with and without `ACCESS_LOCAL_NETWORK`, and the Android 16 opt-in test via the `RESTRICT_LOCAL_NETWORK` compat flag | 4 |
| L2 | SSDP and UPnP port-mapping listing | Multicast UDP plus SOAP requests to the gateway | Works on common routers; needs a lab router | 4 |
| L3 | Gateway port check | TCP connects to a short fixed port list, own network only | Works; API 37 gating applies | 4 |
| L4 | Hop-count probing | Unprivileged ICMP "ping" sockets, TTL option | Unknown; may not be allowed on all devices | 4 |
| L5 | ARP / neighbor table | `/proc/net/arp` and similar | Blocked for current target levels; recorded as C4 evidence | none |
| L6 | Rogue DHCP detection | Bind the DHCP client port | Blocked; recorded as C4 evidence | none |
| X1 | DNS comparison: system vs DoH vs DNSSEC validation | Own resolver client code | Feasible with INTERNET only | 4 |
| X2 | TLS chain capture over Wi-Fi vs cellular | `ConnectivityManager.requestNetwork` and socket binding | Needs a normal network-state permission and mobile data; OEM power policies may interfere | 4 |
| X3 | CT checks: extract SCTs and verify inclusion | Own parser; log queries | CT log APIs are in transition between the older request-based interface and newer tile-based logs; confirm which logs are queryable before committing | 4 |
| X4 | Hash-pinned content probe | Fetch a static file from a project-controlled host, compare SHA-256 to a signed manifest | Feasible with free static hosting | 4 |

### 5.6 Background execution

| ID | Signal | How to read it | Hypothesis | Stage |
|---|---|---|---|---|
| B1 | Delivery rate of a 15-minute periodic job under the phone's default battery settings (Xperia only) | A heartbeat job writes a timestamped line each run; the runner computes the delivered fraction over 24 to 48 hours | The standby-bucket system will delay a meaningful share of runs for an app that is rarely opened (a first sign: the bucket moved from active to frequent within about 80 minutes) | 3 |
| B2 | Same, with the app set to unrestricted battery use | Same | Delivery improves, but needs a user action the product can only request, not force | 3 |
| B3 | Standby bucket, background restriction and battery-optimization state seen by the app | `UsageStatsManager.getAppStandbyBucket`, `ActivityManager.isBackgroundRestricted`, `PowerManager.isIgnoringBatteryOptimizations` | Readable for the app's own package | 3 |

## 6. Setup states (ground truth protocol)

For each device, record and set these before a run. Log the state and how it was set in the result row.

- Lock screen: none, PIN set; fingerprint count 0, 1, 2.
- Fixtures: accessibility fixture enabled and disabled; notification-listener fixture enabled and disabled; device-admin fixture active and inactive; input-method fixture enabled; a fixture holding SMS-read, overlay, and install-unknown-apps grants; a fixture with no launcher icon.
- Installer: one fixture installed by `adb`, one with the installer name set by `adb install -i`, one by the file picker (sideload).
- Network: Wi-Fi with and without a manual proxy; Private DNS off, automatic and hostname; a user CA installed and not installed; a VPN up and down; wireless debugging on and off.
- Permissions: location on/off; nearby-devices granted/denied; local-network granted/denied on API 37.

## 7. Decision rules

For each (signal × device-or-image × API level) cell:

- **Usable:** OK in every relevant permission state the product can reasonably request.
- **Degraded:** OK only with an extra permission or a fallback; the product must show the lower confidence.
- **OEM-unverified:** OK on emulators and one device, but not tested on enough OEMs.
- **Dropped:** REDACTED, SECURITY_EXCEPTION, UNSUPPORTED or WRONG in a way that cannot be worked around.

A cell with a single run is reported as "observed on that cell", not as a property of an API level. Minimum API level (Q1): the lowest API level at which at least 90% of Stage 1 signals are Usable or Degraded. If V1 fails (Q2), the Stage 1 plan is re-scoped before any engine work starts.

## 8. Result record

One row per signal per run, kept as CSV plus a short written findings document:

`signal_id, api_level, device_or_image, oem_build, flavor, target_sdk, permission_state, setup_state, result_code, value_summary, ground_truth_match, notes, date`

Also record collection time and battery impact for the Stage 1 signal set on the physical device, since the live view and background diffing depend on them.

## 9. Phases and exit criteria

1. **Phase 0a, toolchain.** JDK, Android command-line tools, platform tools, Gradle, one emulator image. Exit: a trivial app builds, installs and runs on an emulator.
2. **Phase 0b, fixtures and probe.** Exit: every Stage 1 signal has a fixture state that proves its ground truth, the attestation parser has unit tests, and the probe runs end to end on an emulator.
3. **Phase 0c, runner and ground truth.** Exit: the runner installs, sets states, runs the probe, pulls results, compares with adb truth, and writes the CSV; its pure functions have unit tests.
4. **Phase 1, emulator sweep (API 29 to 37).** Exit: complete matrix for all signals except those needing real hardware (K2 to K4, K6, K7 on physical devices, L2, B1 to B3).
5. **Phase 2a, physical device session (Xperia only).** Done as read-only. Exit met: attestation, Wi-Fi, trust-tier and end-of-life results. Tripwires K6 and K7, the user-CA signal and OEM-feature signals were skipped (user decisions), so they stay "not verified on hardware".
6. **Phase 2b, background test.** Exit: 24 to 48 hours of heartbeat data on the Xperia under default battery settings. One device, so the answer to Q9 is "observed on this phone and firmware", not a rule about Android.
7. **Phase 3, findings.** Exit: written report; `CONCEPT.md` unverified list replaced by confirmed or refuted; `THREAT_MODEL.md` signals marked Observed, Degraded or Invisible; hypothesis log closed; Q1 to Q11 answered.

## 10. Safety and ethics

- Fixtures are benign: they declare capabilities and ignore every event. No real malware is used for any test.
- The author's daily phone is never rooted. System-store CA injection is tested only on a disposable rootable emulator image.
- **Tripwire tests (K6, K7), test CA installation and lock changes** run only with explicit per-step consent, and the later decision below skips them entirely. The user said the Xperia holds some data, so by default it gets read-only sessions plus the background test; K7 (removing the lock) is skipped unless the user decides the data is backed up or disposable. Each risky step is announced before it runs, and the user performs the on-screen parts (enrolling a fingerprint, installing a CA, changing the lock) themselves.
- **Decision (user, 2026-10-08): skip anything that could be harmful to a physical device.** No test CA installation, no fingerprint enrollment (K6), no lock-screen removal (K7), and no other change beyond the lab apps and their temporary, verified-and-reverted grants, on either phone. K6, K7 and the user-CA signal (N5) are therefore measured on emulators only. The physical-hardware behavior of those three is recorded as **not verified**, not assumed to match the emulators, and the product design treats it that way (the tripwire stage ships only if the emulator-level behavior is later confirmed in another way).
- **Least access between sessions:** every fixture capability (accessibility, notification listener, device admin, input method) and every fixture permission grant is switched off as soon as a measurement is taken, and the system is asked to confirm it. On production firmware the shell cannot remove a non-test device admin, so fixtures are built `testOnly`; until then the user deactivates it by hand.
- **Identifiers:** settings dumps are redacted by default (android_id, Bluetooth address and name, device name).
- **Why those tests are risky on any phone with real data:** enrolling a fingerprint invalidates every biometric-bound key on a phone, including other apps' keys, so banking apps may ask the user to re-enable biometric login, and removing the lock screen can disable biometrics, wallet or pass items.
- **Before K7 on the Xperia:** confirm that no account on it depends on the lock, and that a factory reset would lose nothing the user cares about.
- Restore checklist after every phone session: uninstall fixtures and probe, remove any test CA, remove any added fingerprint, turn off USB and wireless debugging, revoke USB debugging authorizations, restore Private DNS, proxy, default accessibility, notification-listener and input-method settings.
- Tests that touch networks run only on equipment the author owns. No scanning of third-party networks.
- When a friend's device is used, they give explicit consent and the probe runs in redacted mode: the exported file contains only signal codes and counts, never package names, SSIDs, BSSIDs or identifiers.
- No data leaves any device except what the researcher exports by hand.

## 11. Known risks to the plan

- Emulators can give misleading results for hardware-backed features, so K-signals are decided on physical devices only.
- A single OEM point means every OEM-sensitive result is provisional.
- API 37 behavior may still change in point releases; re-run the L-series when the final behavior is documented.
- Some "unknown" signals may need privileged permissions; in that case the signal is Dropped and the product states the blind spot instead of working around the sandbox.
- Scripted setup over adb bypasses the restricted-settings gating that a real user hits, so setups that matter for threat realism are repeated once through the Settings UI.
