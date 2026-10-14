# Design v0.1: Stage 1, the glass-box foundation

Status: v0.2. First draft 2026-10-08; section 17 records what changed while milestone 1 was built (2026-10-09). Companion to `CONCEPT.md`, `THREAT_MODEL.md`, `OBSERVABILITY_PLAN.md` and `FINDINGS.md`. Every decision below cites the measured finding behind it (F: ...) so the design cannot drift into guesses. Name: **Declared** (decided 2026-10-08). The exact application ID string is still open (section 12).

## 1. What Stage 1 is

A first public release (GitHub v0.1) that answers three questions for one Android phone, honestly and with no network access:

1. **Who on this phone holds dangerous power?** A capability inventory: which apps can read the screen, read notifications, administer the device, replace the keyboard, and where each came from (trust tier).
2. **What changed, and can I see it happen?** A first-run baseline the user confirms, a "what changed since last time" diff, and a live view while the app is open.
3. **What can this app NOT see?** A blind-spot map that is specific to this phone and Android version.

It is not an antivirus, cleaner or scanner, it shows no score, and it makes no claim that a phone is "safe".

### Uniqueness check (the project's top-priority rule)

| Stage 1 feature | Which of the five properties it delivers | Why a generic app does not have it |
|---|---|---|
| Capability inventory with trust tiers | 1 (reasons over combinations, groundwork) and 2 | Generic apps list permissions; this classifies where each holder came from and how sure it is |
| Blind-spot map | 2 (states what it cannot see) | No consumer security app publishes its own limits |
| First-run baseline review and diff | 3 (verifiable history, groundwork) | Scanners are point-in-time |
| Device-integrity panel with offline attestation check | 4 (cryptographic ground truth) | Generic apps show "rooted: no" from guesswork |
| No INTERNET permission in Stage 1 | 5 (offline-first, no telemetry) | Verifiable from the manifest by anyone |

## 2. Principles that constrain every choice

1. **Glass box.** Every finding shows its source API, plain-language meaning, confidence and what it cannot tell. No finding without evidence.
2. **Honest negatives.** "Not readable on this Android version" is a first-class result, never an omitted row and never a quiet "OK".
3. **No score, no scare.** Ordinal confidence and plain explanations. Advice states its trade-off (some people depend on an accessibility service).
4. **Offline.** Stage 1 holds no INTERNET permission. Nothing leaves the phone.
5. **Verify state, never assume it.** (Lesson from defects D-004, D-006, D-009.) After any state change the app reads the state back from the system.
6. **Core logic knows nothing about Android.** Separately testable and reusable by a future desktop sensor.

## 3. Architecture

```
product/
  core/             pure Kotlin (JVM). No Android imports. Everything with logic lives here.
  sensors-android/  Android library. Real readers for settings, packages, network, attestation.
  app/              Android application. UI, navigation state machine, storage, wiring by hand.
lab/                (exists) fixtures, probe, runner. Becomes the sensors' conformance suite.
```

```
 Android framework                                      user
        |                                                 ^
        v                                                 |
 [sensors-android] --Observation--> [core] --Report--> [app: UI state machine]
   SettingsReader                    Snapshot             Home / Inventory /
   PackageSource                     TrustTiers           Finding detail /
   NetworkSource                     Diff, Rules          Blind spots / Live /
   AttestationSource                 BlindSpots           Baseline review
        ^                            Attestation verify
        |
   fakes in unit tests; real device checked by lab/ against adb ground truth
```

**Decision (F: lab results):** the sensors depend on small interfaces (`SettingsReader`, `PackageSource`, `NetworkSource`, `AttestationSource`), not on `Context`, so unit tests use fakes and the real implementations are verified on emulators. The lab runner's "compare the app's reading with what the system itself says" becomes the integration test of every sensor.

**UI:** Jetpack Compose with Material 3, unidirectional data flow, one explicit reducer for navigation and screen state (testable without rendering). Versions pinned at scaffold time. Recommendation, confirmed in section 12.

## 4. Data model (in `core`)

```
Observation(
  id          : SignalId          e.g. "special-access.accessibility"
  subject     : Device | App(pkg) | Network
  value       : typed value
  provenance  : OBSERVED | DECLARED | INFERRED | INVISIBLE
  confidence  : LOW | MEDIUM | HIGH
  source      : Source(api name, minApi, permissionsUsed)
  limits      : list of plain-language notes about what this reading cannot tell
  observedAt  : timestamp
)
Snapshot(observations, appRows, takenAt)          // what the phone looked like at one moment
AppRow(pkg, tier, installer, initiating, signerSha256, capabilities, ...)   // minimal, no names of other apps in exports
Change(kind, subject, before, after)              // Diff(old, new) -> list of Change
Finding(id, title, evidence: list of Observation, meaning, whatItCannotTell, confidence, whatYouCanDo + tradeOff)
Availability = AVAILABLE | DEGRADED(reason) | UNAVAILABLE(reason)   // per signal, per this device
```

**Provenance and confidence rules (F: P7, P5, D7 results):**
- `INVISIBLE` is produced by the blind-spot registry, not by a sensor.
- A reading the platform refuses (Android 10 cross-app app-ops, the input-methods setting from Android 14 for a target above 33) becomes `UNAVAILABLE(reason)` and appears in the blind-spot map, never as an empty or green row.
- Confidence is ordinal and a finding takes the weakest confidence among its evidence (decision already recorded in `THREAT_MODEL.md`).

## 5. Sensors for Stage 1

Each sensor declares its minimum API, what it needs, how it degrades, and the evidence for it. API range: minSdk 29, targetSdk 36 (Play's requirement for new apps), compileSdk 37.

| Sensor | Reads | Needs | Degrades when | Evidence |
|---|---|---|---|---|
| Accessibility holders and capability flags | enabled and installed accessibility services, capability bits | none | none (readable on every tested version) | F: P1, P2 matched system truth on 29 to 37 |
| Notification-listener holders | enabled listeners | none | none | F: P3 |
| Device-admin holders | active admins | package visibility | none | F: P4 seen on 29, 31 to 37 |
| Input-method holders | enabled and default keyboards through `InputMethodManager` | none | the enabled-input-methods settings key is blocked from Android 14 for a target above 33, so use the manager list only | F: P5 refuted for the setting, manager list works |
| Cross-app special grants (overlay, usage access, install-unknown-apps, read SMS) | app-ops per visible app | none | **UNAVAILABLE on Android 10** (SecurityException); AVAILABLE from Android 12; Android 11 untested | F: P7 |
| Runtime grants per app | granted sensitive permissions | package visibility | none | F: P9 |
| Package inventory with trust tiers | provenance, signing hash, platform-signature match | `<queries>` intents | see blind spot B1 | F: V1, V2, V8, Q11 |
| Device facts | patch level, lock present, debugging flags, developer options | none | developer-options flag is absent (-1) on some images; report "not set" | F: D1 to D6 |
| Advanced Protection | `AdvancedProtectionManager` | API 36 and its permission | UNAVAILABLE below Android 16 | F: D7 |
| Device integrity | key attestation, offline chain check, patch cross-check | none | software attestation on emulators is reported as "not hardware-backed"; no StrongBox is fine | F: K1 to K3, Q5 |
| Network configuration | transport, VPN, validated, captive portal, DNS, Private DNS, proxy, routes | `ACCESS_NETWORK_STATE` | none | F: N1 to N4 |
| Wi-Fi security type | `getCurrentSecurityType` | `ACCESS_WIFI_STATE` | works **without** location; below API 31 falls back to scan capabilities, which needs location | F: Q6, N6 |
| Wi-Fi identity (optional feature) | SSID, BSSID, nearby-network counts | `ACCESS_FINE_LOCATION`, opt-in | off by default; the app works fully without it | F: N7, N8 |
| User-installed CAs | `AndroidCAStore` aliases and trust-manager check | none | **implemented but reported LOW confidence until verified with a real user CA on an emulator** (section 11) | F: N5 not yet measured with a CA |

**Not in Stage 1, deliberately:** the tripwire (Stage 3, needs `USE_BIOMETRIC` and has the Android 10 caveat), network probes (Stage 4), anything that needs INTERNET, `QUERY_ALL_PACKAGES`, `VpnService` or a foreground service.

### Device-integrity panel (the cryptographic piece of Stage 1)

- Generates an attested key with a random challenge, parses the extension (the DER parser from the lab, promoted to `core` with its tests), and verifies the chain offline against Google's attestation roots **bundled as an app asset**.
- Revocation: Stage 1 has no network, so the revocation list is **bundled at build time** and the panel shows its date ("revocation list from 2026-10-08"). A stale list lowers confidence instead of hiding the problem. An opt-in refresh arrives with the first network-enabled stage.
- **Legacy-root rule (F: D-008):** chains from the legacy root (pre-2021 phones) may contain expired certificates and are accepted unless revoked; other chains must be in their validity period. Four regression tests already exist in the lab.
- **Cross-check (new, cheap):** if the hardware-attested patch level is older than the software-reported `SECURITY_PATCH`, report an inconsistency. Only valid when the key is hardware-backed.
- Always labeled "checked on this phone, not by an independent server" (trust assumption 4).

## 6. Trust tiers (F: Q11, V8)

Classification inputs: system flag, platform-signature match (`checkSignatures("android", pkg)`), installing and initiating packages, package source (API 33+), update owner (API 34+), signing hash.

| Tier | Rule | Notes |
|---|---|---|
| PLATFORM | signed with the platform key | 209 packages on the real phone; shown, never flagged |
| SYSTEM | system flag or updated system app | |
| STORE | installer is a known store | allow-list is data, not code |
| OEM_OR_CARRIER_SETUP | installer is a known OEM or carrier setup app | **new, from the false positive on the real phone:** a carrier's setup flow installed an ordinary app and the naive rule called it "sideloaded" |
| SIDELOAD | installer or initiating package is a browser, file manager, package-installer UI or messenger | confidence MEDIUM |
| ADB | initiating package is the shell | |
| UNKNOWN | anything else | confidence LOW, never worded as "sideloaded" |

The installer allow-lists live in a versioned data file (signed and hash-listed when the rule-pack mechanism arrives in Stage 2). Wording rule: the UI says "installed by X" and the tier, never "suspicious".

## 7. Blind-spot map

Two halves, both on one screen:

**Static limits (always true):** other apps' behavior and traffic; zero-click and kernel compromise; cellular interception; account-level risk; anything a rooted attacker hides.

**This phone, this Android version (computed):**
- **B1, hidden apps (F: V1, V1b):** the inventory uses `<queries>` intents, which see apps with a launcher icon plus holders of the four capability components. On Android 11 and later, an app with no icon and none of those components is invisible, and widening the intents does not help. Shown with the measured recall (100% on Android 12 to 15, about 94% on 16 and 17, where one Google system package was missed).
- **B2, unreadable signals:** the `UNAVAILABLE` and `DEGRADED` rows from section 5 for this device.
- **B3, background changes (F: Q7):** the live view sees nothing once the app is not on screen, so changes in the background are found only by the next diff.
- **B4, device-side attestation:** not independently verified.

## 8. Screens and flow (reducer states)

```
FirstRun --> BaselineReview --> Home <--> Inventory
                                  |-----> FindingDetail
                                  |-----> WhatChanged
                                  |-----> LiveView        ("watching while this screen is open")
                                  |-----> BlindSpots
                                  |-----> DeviceIntegrity
                                  '-----> About / privacy (shows: no INTERNET permission)
```

- **Home:** no score. Three blocks: "Worth a look" (findings, each with its confidence), "What I can't see on this phone" (top blind spots), "Changed since last time" (count, link).
- **Inventory:** apps grouped by capability and tier; tapping shows the evidence (API, confidence, limits).
- **Finding detail (the glass box):** What I saw, where I got it, what it means, how sure I am, what I cannot tell, what you can do and what it costs. Wording never states that an accessibility service is malicious.
- **BaselineReview:** for every holder outside PLATFORM and SYSTEM, "I recognize this / I don't recognize this / not sure". Stored with the signing hash, so a later signer change is itself a finding. This replaces the unsafe assumption that the first snapshot is clean.
- **WhatChanged:** diff against the last snapshot, computed every time the app opens (the background answer from B3).
- **LiveView:** the watcher while the screen is open, with measured expectations ("settings changes appear in about a tenth of a second") and the explicit limit.
- **Accessibility of the app itself:** screen-reader labels, large-text support, no color-only meaning. Tested.

State machine rule: the reducer is a pure function `(state, event) -> state`, tested without a device.

## 9. Storage and privacy

- Local store: baseline, last snapshot and the user's baseline answers, encrypted with a Keystore-wrapped AES-GCM key. Plain framework APIs, no extra storage library.
- Secure-window flag on screens that list the inventory; stated as defeatable by a malicious accessibility service (blind spot).
- Exports (if any) redact app names by default.
- No network permission, no analytics, no advertising identifier, no crash reporter that phones home. Data-safety answer for Play: no data collected or shared.

## 10. Manifest and Play compliance (F: policy research, Q2)

- Permissions: `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, optionally `ACCESS_FINE_LOCATION` (opt-in feature), `QUERY_ADVANCED_PROTECTION_MODE` if it proves grantable to a Play-installed app (unverified). **No `INTERNET`, no `QUERY_ALL_PACKAGES`, no `VpnService`, no foreground service.**
- `<queries>`: the four capability intents and the launcher intent.
- Target API 36. `ACCESS_LOCAL_NETWORK` is irrelevant until Stage 4.
- Honest store text: no "protects you" claims; the listing states the blind spots in plain words.

## 11. Verification still owed before or during build

| # | Item | How | Blocks |
|---|---|---|---|
| 1 | User-CA detection (N5) with a real user CA | emulator only: install a test CA, read `AndroidCAStore` | **Done 2026-10-09 on API 34**: found exactly the placed certificate; confidence raised from LOW to MEDIUM (section 19) |
| 2 | Android 11 behavior of cross-app app-ops | emulator image for API 30 | the minSdk 29 degrade note |
| 3 | `QUERY_ADVANCED_PROTECTION_MODE` for a Play-installed app | internal test track | the Advanced Protection panel |
| 4 | Compose UI on Android 10 to 17 | instrumentation on the emulator matrix | UI sign-off |
| 5 | Why one Google system package is missed on Android 16 and 17 (optional) | inspect its manifest on the emulator | only the wording of B1 |

## 12. Decisions

| Decision | Choice | Status |
|---|---|---|
| Project name | **Declared** | decided 2026-10-08 |
| Open-source license | **Apache-2.0** | decided 2026-10-08. `LICENSE` (the Apache-2.0 terms, copied from the copy shipped inside the Gradle distribution already on this machine, so nothing was downloaded) and `NOTICE` are in the repository root |
| UI toolkit | **Jetpack Compose with Material 3** | decided 2026-10-08. Versions pinned at scaffold time (see `product/README.md`) |
| Application ID | decided 2026-10-11, not yet published | `io.github.h_sib.declared`, from the maintainer's GitHub name H-sib (an application ID cannot hold a hyphen, so it is written with an underscore). Permanent once the app is published. The release build still refuses any ID that contains `.placeholder.`. |
| Security contact for `SECURITY.md` | decided 2026-10-11 | GitHub private vulnerability reporting on the `H-sib/declared` repository; it must be switched on in the repository's settings when it is published |

## 13. Testing plan (four layers, per the quality bar)

1. **Core unit tests:** classifier, diff, rules, attestation parser and verifier (including the legacy-root cases), blind-spot computation, reducer. Property-style tests for the diff (applying a diff to the old snapshot gives the new one) and for the tier classifier (every input maps to exactly one tier).
2. **Sensor contract tests with fakes:** each sensor against scripted fake readers, including the refusal paths (`UNAVAILABLE`, `DEGRADED`).
3. **Conformance on emulators (reuses the lab):** the product's sensors read the lab fixtures on Android 10 and 12 to 17, and the runner compares their output with adb ground truth, exactly as the probe was checked. The state-verification checks from D-004 stay mandatory.
4. **UI tests:** reducer tests, Compose tests, an accessibility pass, and screenshots on a few Android versions.

Targets: well above the quality bar's three layers; a running defect log; every new hypothesis added to the hypothesis log.

## 14. Build order for Stage 1 (vertical slice first, then scale)

| Milestone | Content | Done when |
|---|---|---|
| M1 | `core` model, tier classifier, accessibility sensor, minimal inventory screen with a finding detail | one real finding, with its evidence, shown on an emulator and verified against adb. **Done 2026-10-09 on an API 34 emulator** (conformance results in `product/conformance/`); other Android versions and physical hardware not yet |
| M2 | remaining capability sensors, the full inventory and trust tiers, synthetic 1,000-app scale test | all capability signals pass conformance on the emulator matrix. **Built and verified 2026-10-09 on an API 34 emulator (297 of 297 checks); the other Android versions of the matrix are still to run** (section 18) |
| M3 | device facts, network configuration, Wi-Fi security type | conformance for those signals. **Built and verified 2026-10-09 on an API 34 emulator (84 of 84 checks), including the user-CA reading (section 19)**; other Android versions still to run |
| M4 | device-integrity panel with the offline chain check | real chain verified on the emulator and rejected when tampered. **Done 2026-10-09 on an API 34 emulator (63 of 63 checks against independent verifiers)**; hardware chains on real phones not yet run through the product (section 20) |
| M5 | baseline review, snapshot store, diff, "what changed" | diff property tests pass; baseline survives reboot and update. **Done 2026-10-09 on an API 34 emulator (44 of 44 checks, driven through the real UI, including a reboot, an app update, a re-signed app and three kinds of damaged file)** (section 21); other Android versions and physical hardware not yet |
| M6 | blind-spot map, live view, polish, accessibility pass | GitHub v0.1 release candidate; start the Play closed test. **Built and verified 2026-10-09 on an API 34 emulator (79 of 79 checks, plus an instrumented accessibility pass at two text sizes)** (section 22); the release candidate documents exist, and publishing waits on the application ID, the security contact and the multi-version run |

Every milestone so far was verified on the API 34 emulator only; the multi-version matrix and a physical phone are the open verification items.

## 15. How this meets the complexity checklist

- **Algorithmic depth:** attestation chain verification with the legacy-root rule, diff with a correctness property, trust-tier classification, and (Stage 2) graph reachability with a minimum fix set.
- **Core separated from interface:** `core` has no Android imports; the reducer is pure.
- **Scale:** synthetic 1,000-app snapshots in M2.
- **Layered testing:** section 13.
- **Documented real problem-solving:** `lab/results/DEFECT_LOG.md` already holds 13 entries and continues into the product.
- **Grounded in real standards and data:** every sensor cites a measurement; MITRE ATT&CK for Mobile in Stage 2.
- **Extensibility:** `core` accepts observations from any sensor, so a desktop sensor can be added without changing it.

## 16. Risks

- **Scope.** Stage 1 is large; the milestones are independently shippable, and M5 and M6 can slip without invalidating M1 to M4.
- **Android fragmentation.** Findings come from emulators and one Sony phone; the product states "not verified on Samsung" everywhere it matters.
- **Play review.** Mitigated by requesting no restricted permissions in Stage 1.
- **Trust.** A security app that overclaims is worse than none; the wording rules in sections 2 and 8 are part of the acceptance criteria.


## 17. Changes made while building milestone 1 (2026-10-09)

Each change says what the first draft assumed and why it was changed.

1. **The navigation reducer lives in `core`, not in `app`.** Section 3 put it in the app. It has no Android type in it, so it moved to `core`, where a property test runs 2,000 random event sequences on the JVM (stack never empty, bottom always the inventory, no screen for a finding that no longer exists).
2. **The sensor interfaces and the logic on top of them live in `core`; `sensors-android` holds only the thin adapters.** Section 3 had the interfaces in the sensor module. Putting the sealed `ReadResult` (Ok, Refused, Failed) and the source interfaces in `core` lets every refusal path be tested with fakes on the JVM. The Android adapters are verified by conformance against adb instead. Layers 1 and 2 of section 13 therefore both run without a device.
3. **A finding's confidence belongs to its claim, not to its context.** Section 4 said a finding takes the weakest confidence among its evidence. Applied literally, "this service is enabled", which Android states outright, would read MEDIUM only because the installer's name is unverified. A finding now has `claim` observations (they decide the confidence) and `context` observations (for example where the app came from, shown with their own confidence). Tests pin both halves.
4. **An ordinal attention level, with one stated rule.** `NOTE` (built-in, or only minor abilities), `LOOK` (strong abilities from a store or setup flow), `LOOK_FIRST` (strong abilities from an origin the app cannot vouch for: file install, adb, or unknown). It is an ordering for the screen, not a score, and it is explained in words. Strong abilities are reading the screen, taking over touch, seeing key presses, tapping for you and screenshots. A service's own claim to be an accessibility tool (Android 14 and later) is shown as context and does not lower the level, because it is self-declared.
5. **Findings carry a `subject` and holders a `serviceLabel`.** Found on the real screen (defect D-016): one app can ship several services.
6. **The installer allow-list starts without carrier or OEM entries.** The false positive that motivated the tier was recorded without the installer's name, so there is nothing verified to add. Unlisted installers are "not recognized" at LOW confidence, never "sideloaded".
7. **Package visibility `<queries>` moved to the sensors library manifest**, and the app build fails if an entry is missing (defect D-018).
8. **Build-time policy gate.** The merged manifest of every variant must request no permission except AndroidX's private signature permission, must contain no `QUERY_ALL_PACKAGES`, VPN service or foreground-service type, and must keep the visibility entries. "No INTERNET permission" is a headline claim, so the build enforces it. Lint warnings are errors.
9. **A debug-only conformance hook.** The debug build writes what it found plus the raw readings when launched with an intent extra; the release build contains a no-op with the same name. `product/tools/conformance_m1.py` compares that file with independent system sources (settings, `cmd package`, `dumpsys package`, the APK's own XML and signature through aapt2 and apksigner) and also reads the rendered screen.
10. **Not done yet, on purpose:** the secure-window flag (M6), string resources and translation, baseline review, snapshots and the diff, and every sensor other than accessibility.

### What milestone 1 does and does not show

It shows that on one emulator image (Android 14, API 34) the product reports the same enabled and installed accessibility services, declared abilities, install record, signing certificate hash and app and service labels as the system, both with a lab fixture service switched off and on, and that the finding and its glass-box detail appear on screen. It does not show behavior on other Android versions, on physical hardware, with apps other than the lab fixtures and the emulator's built-in services, or under screen readers or large text.


## 18. Milestone 2 (2026-10-09): the other capability sensors and the full inventory

### What was built

| Part | Source of truth | Notes |
|---|---|---|
| Notification listeners | `enabled_notification_listeners` setting, installed via `<queries>` | lab P3 |
| Device administrators | `DevicePolicyManager.getActiveAdmins`; declared policies from the admin's metadata | an active admin in a package this app cannot see is still listed, at LOW confidence; lab P4; defect D-022 |
| Keyboards | `InputMethodManager` lists, in-use keyboard from `default_input_method` | the raw enabled-keyboards setting is not used (lab P5). A keyboard that is on but not in use is a NOTE: it receives no typing until chosen |
| Special access (draw over apps, usage access, install other apps) | `AppOpsManager.unsafeCheckOpNoThrow` for apps that declare the permission | lab P7. An explicit allow is HIGH confidence; "default plus permission granted" is MEDIUM and says so (the rule is the one verified in lab D-003). Built-in apps are left out on purpose |
| Text-message access | `PackageInfo.requestedPermissionsFlags` | the only runtime permission that makes a finding by itself (account-code interception, THREAT_MODEL P2). Microphone, camera, location, contacts and call history are shown per app in the inventory and are left to Stage 2, which reasons over combinations |
| Full inventory | `getInstalledPackages` limited by `<queries>`, install record, signing hash | every visible app, sorted by origin (less usual first), with what it holds and what it is allowed to use |

Model changes: every capability is a list of plain-language `Power`s, so a keyboard, an admin policy and an accessibility ability share one description and one attention rule; `CapabilityKind` orders the sections; findings carry a `SettingsLink` (core names the place, the app builds the intent); `Holder` and `AppRow` replace the accessibility-only rows.

Decisions made here: a device administrator's meaning text says that Android will not allow uninstalling it until it is switched off (measured in the lab, D-006); an Android 10 refusal of app-ops reads as UNAVAILABLE for those three signals, never as "nothing granted"; no per-package request is made when the bulk listing already has the package (one launcher query for all packages instead of one per package).

### Evidence

- 95 JVM tests (89 in `core`): a sensor contract test for every kind including every refusal and degraded path; wording tests over several hundred generated findings covering all eight kinds, every tier and every policy shape; a scale test (below); reducer property test including the apps screen.
- **Scale (synthetic phone):** 1,000 apps produce exactly the expected holders and findings, app-op reads happen once per candidate app and nothing else, and 4,000 to 20,000 apps scale linearly (27 ms to 81 ms). The guard was proven to fail by injecting a quadratic scan (defect D-020).
- **Conformance on an API 34 emulator, 297 of 297 checks** (`product/conformance/m2_results.csv`), with the five lab fixtures off and then on, and three hidden-icon fixtures installed throughout: installed, enabled and active sets against `cmd package`, `settings`, `dumpsys device_policy` and `ime list`; admin policies against the admin's XML; every special-access mode against `appops get`; grants, system flag and install record of all 106 visible packages against `dumpsys package` and `pm list packages -s`; the three hidden-icon fixtures installed but absent from the inventory (blind spot B1 confirmed by the product itself); the on-screen inventory, apps list and two detail pages.
- Timing: reading all 106 visible packages took about 0.8 s on the emulator.

### Not verified yet

- Android versions other than 14. The multi-version matrix needs emulator images that are not on disk (about 4 GB each, one at a time within the 15 GB cap) and is waiting for your go-ahead.
- Android 11 app-ops behavior (unmeasured, treated as whatever Android answers and reported as such).
- Physical hardware, apps other than lab fixtures and built-in ones, and the `GET_APP_OPS_STATS` message text on Android 10 (the expectation that all three signals are UNAVAILABLE there comes from lab P7).
- Packages that share a user id (28 built-in ones on this emulator) show only their own manifest's permissions.


## 19. Milestone 3 (2026-10-09): device facts, network facts and the first condition findings

### What was built

Two kinds of output come from this milestone. **Facts** are plain rows shown whether or not they are a problem (every row also says when it could not be read). **Findings** exist only where one reading is unambiguous enough to say something about on its own; reasoning over combinations (open Wi-Fi without Private DNS, a proxy plus an added certificate authority) is Stage 2.

| Reading | Source | Finding rule |
|---|---|---|
| Security update date | `Build.VERSION.SECURITY_PATCH` | 180 days or older: LOOK; 365 or older: LOOK_FIRST; a date in the future or unreadable: no finding, stated |
| Screen lock | `KeyguardManager.isDeviceSecure` | none: LOOK_FIRST |
| USB debugging, wireless debugging, developer options | `Settings.Global` (absent means "not set", never "off") | on: LOOK, LOOK_FIRST, NOTE |
| Advanced Protection | `AdvancedProtectionManager` (Android 16 and later, needs a permission) | a fact only; whether it matters depends on who the user is |
| Connection, VPN, Private DNS, web proxy | `ConnectivityManager` (capabilities, link properties, default proxy) | a proxy: LOOK; the rest are facts |
| Wi-Fi security type | `WifiInfo.currentSecurityType` (Android 12 and later, no location needed, lab Q6) | open: LOOK; WEP: LOOK_FIRST; below Android 12: unavailable with the reason |
| Added certificate authorities | `AndroidCAStore` user entries, cross-checked with `isUserAddedCertificate` | any: LOOK, at MEDIUM confidence (below) |

Decisions: every device setting is read on its own so one refusal cannot hide the others (lab D-002); the thresholds are named constants that the conformance script re-states independently; Play Protect, which Android does not expose (lab D8), is left for the blind-spot screen. The app now asks for three **normal** permissions, no prompt and no data: `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and `QUERY_ADVANCED_PROTECTION_MODE`. The manifest gate allows exactly these and still rejects `INTERNET`, location, `QUERY_ALL_PACKAGES`, VPN and foreground-service declarations.

### The user-CA question (section 11, item 1) is answered on one emulator

A throwaway CA made with OpenSSL was placed in the emulator's user certificate store (a rootable test image; nothing on any phone). The product found exactly that certificate, with the SHA-256 that OpenSSL computes independently, and found nothing before placing it and nothing after removing it (removal verified in the system). The reading therefore moves from LOW to **MEDIUM** confidence, with the limits written into the finding: one emulator image, the certificate placed in the store directly, not tried on hardware, on other Android versions or through the Settings install route. The check is a repeatable phase of `product/tools/conformance_m3.py`.

### Evidence

- 119 JVM tests (113 in `core`): every threshold at its boundaries (179, 180, 364, 365 days), one refused part not erasing the others, every Advanced Protection state, every Wi-Fi security type, absent versus off versus unexpected setting values, and the wording rules over every condition shape.
- **Conformance on an API 34 emulator, 84 of 84 checks** (`product/conformance/m3_results.csv`): the emulator as found, then with developer options on, a PIN set, a proxy set and Private DNS pinned to a host; each reading compared with `getprop`, `settings`, `locksettings`, `dumpsys connectivity` and `cmd wifi status`; every change reverted and the revert verified; the CA phase above; the on-screen rows. As found, the emulator's own state produced three findings from the rules alone (a security update dated 2023-09-05, no screen lock, USB debugging on) plus the open emulated Wi-Fi.

### Not verified yet

- Other Android versions: the Wi-Fi security expectation for Android 10 and 11, Advanced Protection on Android 16 and 17 (and whether a Play-installed app is granted the permission), and the rest of the matrix.
- Real hardware, a real VPN, a captive portal, mobile data as the default network, WPA networks (the emulator's Wi-Fi is open), pattern or password locks (only a PIN was set).
- Install-source and attestation panels (milestone 4), baseline review and diff (milestone 5), the blind-spot screen (milestone 6).


## 20. Milestone 4 (2026-10-09): the device-integrity panel

### What was built

The phone is asked for a freshly attested key (a new random 32-byte challenge every time, the key deleted straight after), and the certificate chain it returns is judged **offline**, inside the app, with `java.security` only. The reference data it is judged against ships in the app as assets: Google's published attestation roots (2) and certificate status list (1,759 entries), fetched on 2026-10-08, with a manifest that lists the SHA-256 of each file and its date. Every load checks the checksums, so a corrupted or replaced file makes the panel *unavailable* with a reason instead of quietly changing its answer.

What the verifier checks, in the order of the lab's verifier (`lab/tools/verify_attestation.py`, which it ports): each certificate is signed by the next; the last one is a trusted root or is signed by one; no certificate is on the revocation list (decimal and hexadecimal serial spellings); every certificate is within its validity dates, except that a chain hanging off Google's legacy root may carry expired certificates and stays trusted unless revoked (lab defect D-008); the record answers this app's own challenge. The attestation record is read from the certificate nearest the root that carries one, because a leaf can carry a forged copy.

What the panel then says depends on what the record can honestly support:

| Situation | Facts | Findings |
|---|---|---|
| Keys held in software (emulators, a rare real phone) | "not possible"; boot state, bootloader and patch "not established" | NOTE: this phone keeps its keys in software. The record's own flags are **never believed** here (an emulator's record says "unlocked" and "unverified"; reporting that would be reporting a harness fact as a phone fact) |
| Hardware-backed, chain trusted | passed, with the reference date; boot state; bootloader; attested patch compared with the phone's own report | bootloader unlocked: LOOK_FIRST; custom-signed software: LOOK; failed start-up check: LOOK_FIRST; attested patch older than reported (compared by month): LOOK |
| Hardware-backed, chain not trusted (broken link, unknown anchor, revoked, expired outside the legacy rule, wrong challenge) | "did not pass" with the reasons; the three dependent rows "not established" | LOOK_FIRST, worded as a problem with the check or the hardware keys, not as proof of tampering |
| Cannot read the phone's answer or the bundled data | every row "not readable" with the reason | none |

Every integrity observation is MEDIUM confidence at best and says so in its limits: the check runs on the phone, not on an independent server (THREAT_MODEL trust assumption 4). If the bundled revocation list is more than 180 days old the confidence drops to LOW and the signal reads "partly readable"; with no network in Stage 1 the list cannot refresh itself, so this is the honest way to age. An opt-in refresh arrives with the first network-enabled stage. For this bundle the 180 days end on 2027-04-06.

New code in `core`: a DER reader and attestation parser (ported from the lab with its tests), a strict JSON parser for the two reference files (so `core` needs no library), the chain verifier, the bundle loader, and the assessment. The assessment is split from the verifier so tests can hand it any chain report.

### Evidence

- 173 JVM tests (164 in `core`): the lab's eight parser tests plus malformed-input paths; a real attestation chain captured from the API 34 emulator is the fixture (a software chain rooted in Google's *test* root, so it holds no personal data), and the verifier tests use its real signatures: trusted when anchored at its own top, trusted when the second certificate is signed by the anchor, rejected for a wrong or missing anchor, rejected when any single bit of any certificate is flipped, expired and not-yet-valid, the legacy rule accepting expiry but nothing else, revoked by either serial spelling, garbage input; and every assessment outcome above, including the 180-day boundary. The shipped reference files are tested against their own manifest, and a flipped byte is refused.
- **Conformance on an API 34 emulator, 63 of 63 checks** (`product/conformance/m4_results.csv`): the chain is judged again by the JDK's X.509 verifier through the lab's `ChainVerify.java` and by the lab's verdict, legacy-root and revocation logic, and the attestation record is read by a separate Python DER parser fed with the JDK's own extension bytes. Four situations are compared check by check (signature of each link, validity of each certificate, serials, root, legacy flag, revoked certificates, overall verdict): the real chain (correctly **not** trusted as hardware: its root is not a Google hardware root), the chain with one signature bit flipped (link 0 broken, found by both), the chain anchored at its own top (trusted) and that chain flipped (rejected). The record's security level, boot state, lock flag, patch levels and challenge agree between the two parsers, and the challenge is the one the app made.

### Not verified yet

- A hardware-backed chain on a real phone with the product's own verifier. The lab verified the Xperia's chain with the Java and Python verifiers (2026-10-08), and the product's verifier ports exactly those rules, but it has not run on that phone: the phone holds data, the user was away, and wireless debugging is off.
- StrongBox, Android versions other than 14, the legacy-root path on a real expired factory chain (covered by the mechanism test and the lab's unit tests, not by a real chain in the product).
- The bundled data is a snapshot. Google's list changes; the date is shown and the confidence drops when it ages.


## 21. Milestone 5 (2026-10-09): baseline review, encrypted state and "what changed"

### What was built

The app no longer assumes the phone is clean the first time it looks. A fresh install shows a **first-run screen** and nothing else can be opened until the review is done; the review lists every holder of a powerful ability that did not come with the phone's own software, with where it came from and what it is allowed to do, and the person answers "I recognize this", "I do not recognize this" or "Not sure" (unanswered cards are saved as "not sure"). The answers, a snapshot of the phone and the signing certificate of each answered app are then saved on the phone, encrypted, and every later start is measured against them.

- **Snapshot and diff** (`core/state`). A snapshot is a flat list of records (the facts and findings that matter, the powers each holder has, the signing certificates). `Diff.of(old, new)` produces changes that can be applied (`apply(old, diff(old, new)) == new`) and inverted, which is the property tested.
- **Change summary.** The raw diff is turned into plain items with an attention level (eleven kinds: new, worse and vanished findings, a changed signing certificate, apps added and removed, new sensitive grants, a different installer, an ability switched off, a setting or network change). A new finding carries the attention of the finding itself, so this screen never rates something differently from the screen that explains it. A changed signing certificate is LOOK_FIRST: an update signed by someone else is exactly what the app should shout about. Built-in apps are counted ("N records differ underneath") but not itemized, because an operating-system update rewrites dozens of them.
- **Acknowledgement.** Changes stay on the list **across restarts** until the person presses "I have seen these changes"; opening the app does not clear them. Acknowledging is not answering: new holders keep waiting for an answer.
- **Answers feed the report.** "I do not recognize this" becomes a LOOK_FIRST finding in the report; "I recognize this" and "Not sure" make no finding of their own.
- **Encrypted store.** `SealedFile` is a small container (magic byte, format version, 12-byte nonce, AES-256-GCM ciphertext) with additional authenticated data `declared-state-v1`; `EncryptedFileStore` keeps the AES key in the Android Keystore, writes atomically (Android's AtomicFile) and reads back as `Empty`, `Loaded` or `Corrupt(reason)`. A damaged or foreign file is **reported**, and the person is offered a new review; it is never read as an empty baseline, which would hide that something happened.
- **Reducer.** The navigation reducer stays pure: first-run gate, review draft, pending list, saves only on `FinishReview` and `AcknowledgeChanges`, the save running on a conflated channel in the view model.

### Evidence

- 219 JVM tests at the end of the milestone: applying a diff to the old snapshot gives the new one for thousands of random pairs and the inverse goes back; the change rules one by one; the saved-state codec (any text, a 5,000-app state in reasonable time and size, every proper prefix of a valid state refused, wrong versions and missing fields refused with a reason); the sealed container (seven kinds of wrong file refused with a reason, 5,000 random byte strings never crash the reader or pass for a file); the reducer with a **property test of 3,000 random sequences of 80 events** that checks the first-run gate, the stack bottom and the pruning of draft answers after every step.
- **Conformance on an API 34 emulator, 44 of 44 checks** (`product/conformance/m5_results.csv`), driven through the real UI with `uiautomator`: the fresh install shows only the first-run screen, the holders up for review equal the roles the system reports on (`dumpsys`, `settings`), the review is answered by tapping, and the next process reads the file back. The file is read **separately from memory** and compared with independent truth: the answers the taps should have produced and the signing certificate of each fixture from `apksigner verify --print-certs`. A new holder appears as a LOOK_FIRST new finding, the changes survive a second start, and acknowledging clears them and moves the reviewed date. A fixture is **re-signed with a brand-new key**, reinstalled, and the product reports the changed signer; the original is put back afterwards. The file survives an app update (`adb install -r`) and an emulator reboot (the Keystore key is still there). It contains neither "RECOGNIZED" nor a fixture name in clear text. One flipped byte (the authentication tag refuses it), a truncated file and a wrong header are each reported as `Corrupt` with the right reason and a first-run screen, not as an empty baseline.
- The older scripts were rerun on the final build after `pm clear` and still pass (M2 297, M3 84, M4 63).

### Defects found on the way

D-031 (the random-sequence test found two reducer bugs) and D-032 (the conformance script stalled on a button below the fold, same shape as D-017, and aliased test data), in `lab/results/DEFECT_LOG.md`.

### Not verified yet

- Other Android versions (the file store uses the Keystore the same way on all of API 29 and later, but only 34 was run); a physical phone; a Keystore key in StrongBox; restore from backup (the file is excluded from backup and device transfer by rule, so the app asks for a new review on a new phone, which is intended but was not run on a second device).
- A person other than the author finishing the review: the wording was checked by banned-word tests and by reading the screenshots, not by a user study.


## 22. Milestone 6 (2026-10-09): the map of what the app cannot see, the live view, the secure window and the accessibility pass

### What was built

- **The blind-spot map** (`core/blindspots`, screen `BlindSpotsScreen`). Two halves on one screen, opened from the first screen. *On this phone* is computed from the report: the apps this phone hides from the app (with the count it does show, and the measured recall of the inventory by Android version), what Android refuses to let the app read here with each reason, the background limit, what built-in apps' grants leave out, and what the hardware check could or could not establish. *On every phone* is the static half: what other apps do, what leaves the phone, attacks on the system itself, an altered phone, what is not on the phone at all, who can see the app's own screens, and Play Protect (which Android does not let apps read). Entries that rest on a measurement say what was measured and where. Every unreadable signal is listed with its reason; nothing is dropped to look better.
- **The live view** (`core/live`, `AndroidChangeWatcher`, screen `LiveViewScreen`). While the app is on screen it listens to content observers on 13 settings keys (accessibility, notification listeners, keyboards, USB and wireless debugging, developer options, web proxy, Private DNS), a receiver for app installs, updates, removals and switches, and a default-network callback, and shows each announcement in plain words with its time. After a burst of announcements the phone is read again once, shortly after the last (and once more if a read was already running), so the first screen follows the phone. The watcher stops when the app leaves the screen, because the measurement (FINDINGS.md Q7) shows nothing is delivered in the background and no service is run to change that; the screen says so, with the measured delivery times, as its first lines. The network callback stays silent unless something the report reads changed (network, transports, internet, validation, VPN, captive portal), so signal strength does not look like news.
- **A re-read every 30 seconds while on screen**, because a device administrator, the screen lock and special access (drawing over other apps, usage access, installing apps) sent no announcement in the same test; the live view says so.
- **The secure window.** Every screen is kept out of screenshots, recordings and the recent-apps preview with `FLAG_SECURE`, because they list what is installed. The map says this does not stop an app that holds the accessibility power from reading the words. A lab script can ask the debug build for capture (`--ez allowCapture true`); the release build cannot be asked.
- **Accessibility pass** (`app/src/androidTest`). The real activity is walked as a person walks it (first run, review, first screen, live view, map, list of apps, what changed), once at normal text size and once at twice the size, with Google's Accessibility Test Framework checks on every screen (its default set, which includes touch target size, text contrast and missing labels), and it refuses to click a control that is in the tree but off the screen. A screen reader itself is not run (no TalkBack on the emulator image); the test checks what a screen reader is given.
- Release-candidate documents: `PRIVACY.md`, `SECURITY.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, `STORE_LISTING.md` (draft, not submitted), `PROJECT_SUMMARY.md`.

### Evidence

- 238 JVM tests (229 in `core`): the map's entries and boundaries (Android 10 versus 11, every unreadable signal present with its reason, no entry claims safety), the live log (bounded, newest first, repeats within a second collapsed), the words of every watched setting, the update-is-one-announcement rule, the reducer with the new screens and events inside the existing 3,000 by 80 random-sequence property test (stack bottom, first-run gate, bounded log).
- **Conformance on an API 34 emulator, 79 of 79 checks** (`product/conformance/m6_results.csv`), with the app in front and the phone changed from outside with adb, timed against the device's own clock and compared with the system: each setting and power announced (Private DNS mode and name, web proxy set and cleared, developer options, an accessibility service and a notification listener switched on and off) in 0.02 to 0.3 seconds, and the report read again afterwards and showing the change; an app installed, updated (announced once, as an update, not as a removal plus an install) and removed, with the app count of the report moving by one; **an app with no launcher icon and no special component was installed and neither announced nor counted** (the limit the screen states); a device administrator, a special-access grant and a screen lock set from outside produced no announcement at all and were still shown by the 30-second re-read; the watcher stops when the app leaves the screen, a change made while away is not announced, and coming back shows it; the window carries the SECURE flag in `dumpsys window` by default and not when capture is asked for, and a screenshot of the secure window yields no image at all; the live view and the map say on screen what the code says they say (measurement and its place, limits, count of visible apps, reasons); and at font scale 1.0 and 2.0 the three screens are reachable, every clickable has words and every clickable is at least 48dp.
- **Instrumented accessibility pass**, two tests (normal and double text size), both passing on the emulator. The checks are not vacuous: a tiny unlabeled clickable added on purpose to the first screen was rejected by the framework (`SpeakableTextPresentCheck`) and then removed.
- The older conformance scripts were rerun on the final build after `pm clear` and pass: **M2 297, M3 84, M4 63, M5 44, M6 79** (the M5 script is the one that found D-037).

### Defects found on the way

D-033 to D-038 and D-042 in `lab/results/DEFECT_LOG.md`. The two that matter: the first-run screen could not be scrolled, so at large text the button to start was off the screen (found by the instrumented test, not by the script; D-033); and **a service that the settings said was switched on could vanish from the report** (D-037): the report trusted Android's list of running services, a test tool attached to the screen suspends every service while it runs, and with a read every 30 seconds the overlap stopped being rare. Found only because the milestone 5 script, rerun on the final build, started failing. A service listed by either source is now shown as switched on, with lower confidence when the two disagree. The others: the first watcher reported the network's starting state as a change, a raw machine timestamp on the first screen, a map entry saying "measured" twice, and a fifth scroll cap in a conformance script.

### Not verified yet

- Everything above ran on one Android 14 emulator. The watcher's timing on real phones and other versions, the background behaviour on a manufacturer's power management (the Xperia read-out), and the instrumented checks on other screen sizes.
- A real screen reader. The accessibility framework checks what is exposed, not how it sounds.
- That a person finds the wording clear (no user study).
- `FLAG_SECURE` against a real accessibility-based capture: stated on the map as not stopping a service that reads the screen, not measured.


## 23. Stage 2 (2026-10-09): the attack-path engine

Design: `STAGE2_DESIGN.md`. This section records what was built, what was measured and what is still open.

### What was built

- **The engine** (`core/paths`, no Android type anywhere). An AND/OR graph over Kleene's three values (`Graph.kt`): a fact is TRUE, FALSE or UNKNOWN, and an unreadable signal makes its fact UNKNOWN, so a path that rests on it is *not ruled out* and never *absent*. An AND is the minimum and an OR the maximum; a path's confidence is the weakest link of an AND and the best-supported way of an OR (THREAT_MODEL.md section 6); a path says when it rests on the person's own word. The evaluator returns, for every TRUE goal, a witness (one derivation, shared sub-derivations stored once) so the screen can show the chain, and for every UNKNOWN goal the unreadable facts that keep it open. Graphs are validated on build (unique ids, resolvable references, no cycles, levers only on facts) and evaluated without recursion, so a chain of 20,000 nodes is fine.
- **The typed state** (`PhoneState`, carried in `Report.phone`). One place decides what "no screen lock" or "Private DNS off" means and when it is unreadable; Advanced Protection on a version that has none is *not applicable*, not unknown.
- **The rule book** (`RuleBook.kt`): THREAT_MODEL.md section 5 as code. P1 an unfamiliar app that can read the screen; P2 one-time codes taken through text messages, notifications or the screen (needs the answer about text-message codes); P3 an added certificate authority, a web proxy, and open or public Wi-Fi without Private DNS; P4 no screen lock; P5 the shape of an app made to watch someone (administrator or screen reader, no launcher icon, location or microphone); P6 an old security update (a year, or six months for the higher-risk profile) and Advanced Protection off where it exists. Each rule names its technique ids, what it needs, the levers that cut it and what this app cannot see on it. Packages that came with the phone get no nodes (they can never be unfamiliar); nothing that could be UNKNOWN or fixable is ever left out.
- **Levers and the smallest fix set** (`Fixes.kt`). Every lever states its trade-off and a cost. The smallest set that stops every stoppable path is a hitting-set problem (NP-hard), so the search is exact while it stays small (a bounded search tree on witnesses with a lower bound from witnesses that share no usable lever, deepened one lever at a time, cheapest then first by id among the smallest) and **labelled approximate when it reaches its budget of 20,000 steps**, when the greedy answer (most paths cut, then the most steps removed, then cheapest, redundant levers dropped) is returned instead. Paths no lever can stop are reported, not searched. Both searches work on arrays and repair only the part of the graph above a flipped fact. The what-if preview is the same evaluation with a different set of levers.
- **Declarations and the saved state, version 2.** Two questions only the person can answer (text-message codes, public Wi-Fi) and a profile, stored in the encrypted file beside the baseline; "not sure" is an answer and keeps dependent paths open. A version 1 file is still read, with every question unanswered; a damaged or unknown version is still reported, never read as empty.
- **Screens.** Attack paths (the chain of each path with where each link came from and how sure the app is, what it cannot see on that path, what stops it alone, the smallest set of changes with a preview that changes nothing on the phone, and the not-ruled-out list with what keeps each open), About you, and a card on the first screen. The reducer gained the two screens, the declarations and the preview (forgotten when the screen is left), inside the existing random-sequence property test.
- **A new provenance, TOLD** (D-039): the person's own answer is no longer labeled "the app's own statement".

### Evidence

- **310 JVM tests** (301 in `core`). The engine: Kleene tables, malformed graphs refused, a 20,000-node chain, and property tests on random graphs (applying more levers never makes anything more true; a witness is sufficient and every leaf in it is true; every unknown goal names its cause). The solver: hand-built cases; **exact search against an exhaustive-subset oracle on more than 1,500 random graphs and on up to 60 hard hitting-set instances, all identical**; the lower bound changes no answer on 40 instances; greedy is always a valid fix, never smaller than the minimum, and never has a redundant lever. The rule book: 22 tests over phones from a clean one that must raise nothing to a worst case with nine paths, banned-word and dash checks on every sentence the engine can emit. Saved state: version 1 migration, every declaration round-trips, hostile files refused.
- **Solver at scale** (`HardFixesTest`, instances built so that every goal is an OR of several ANDs over a shared pool of facts, the hard shape; the first scale test passed instantly because its random graphs were nearly all FALSE, which is why it was replaced, D-040). At the default budget: 90 nodes and 10 true paths are solved exactly (8 to 9 levers, 564 to 1,847 steps, under 10 ms); at 270 nodes and 20 paths the proof does not finish in 20,000 steps and the answer is labelled approximate (23 to 29 levers, about 215 ms); 750 nodes and 50 paths take about 600 ms (74 to 82 levers); 1,800 nodes and 100 paths take about 1.3 s (184 to 196 levers). On 30 mid-size hard instances where the minimum can be proven, **greedy used 203 levers against 202 (worst ratio 1.14)**. A phone with 1,000 apps and 112 paths is analysed and planned in about 0.6 s.
- **Conformance on an API 34 emulator, 111 of 111 checks against an independent evaluation** (`product/conformance/m7_results.csv`). `paths_oracle.py` evaluates the same rules from the system's own readings (`settings`, `dumpsys`, `appops`, `cmd wifi`, `aapt2`, the installer) and from what was tapped, with its own formulas, its own three-valued logic and a brute-force search for the smallest fix set, and its answer for the worst-case phone equals the Kotlin test's (two implementations, one answer; the oracle's tie-break is stated and the two agree on it). A benign lab fixture with the *shape* of an app made to watch someone (a screen-reading service, a device administrator, no launcher icon, text-message, location and microphone grants, doing nothing) was added to the lab. Driven through the real UI: answers on the About you screen, the review answered by tapping (one app recognized, one not recognized, the rest not sure), a proxy, a Private DNS host and a screen lock set from outside, the higher-risk profile. After each state the app's paths, severities, the phone-level confidences, what is held open, the smallest set of changes and how many paths it stops equal the oracle's, the declarations equal what was tapped both in memory and as read back from the encrypted file, and the words on screen (the plan sentence and the what-if preview's remaining count) equal what the oracle says they must be. The Stage 1 scripts and the accessibility pass were rerun on the same final build after `pm clear` and pass: **M2 297, M3 84, M4 63, M5 44, M6 79, M7 111**, and both accessibility tests.
- **Accessibility pass** extended to the paths and About you screens at normal and double text size, with the framework's checks and the preview chip ticked; both pass.

### Defects found on the way

D-039 (the person's answer labeled "the app's own statement"), D-040 (a solver test that could not fail, then a solver that was too slow when it could), D-041 (the network briefly unreadable after a setting changed, which the paths correctly kept open and the conformance script had to learn to wait out). `lab/results/DEFECT_LOG.md`.

### Not verified yet

- Everything ran on one Android 14 emulator. P6's Advanced Protection rule has only been exercised through its not-applicable branch on a real image; on Android 16 it needs a device.
- The shadow fixture has the shape and none of the behavior; no real stalkerware, and no claim about detecting any.
- P7 (a malicious app scanning the local network) waits for the Stage 4 sensors; P8 (SIM swap) is declared-only and has no node yet.
- The oracle and the engine have the same author and the same source (THREAT_MODEL.md section 5): they agree on what the rules say, not that the rules are right. The severity weights are the judgement of THREAT_MODEL.md section 6, not a measurement. Whether the paths make a person act better is a question for the user study.
- Wording of the higher-risk profile has not been read by anyone it is meant for.

## 24. Stage 3 (2026-10-09): history and tripwires

Design: `STAGE3_DESIGN.md`. Built with the proposal's defaults: the history and the timeline first, then the Keystore tripwires; no scheduled background work (it needs a permission the build gate forbids, and is the user's decision); the latest entry's fingerprint is shown on screen, with no export.

### What was built

- **The ledger** (`core/ledger`): one entry per look, holding the time, how it was taken (when the app was opened, or while it was on screen), the app and Android versions, the SHA-256 of the snapshot it leads to, the differences from the entry before, and the events (tripwires armed or fired, signing key changed). `hash = SHA-256(canonical text)`, where the text includes the previous hash; entry 1 names the hash of the header (start time and public key), so the key is part of the chain. The text is written by hand with a fixed order (keys and records sorted by UTF-16 code unit, the way Java compares strings), so another program can rebuild it.
- **Signatures**: ECDSA P-256 over the raw bytes of each hash, by a Keystore key made without any user authentication so a look never needs a prompt. Whether the key is in secure hardware is read from the key and recorded in the header, never assumed (on the emulator: software).
- **The check** (`LedgerVerifier`): numbering, links, hashes worked out again, the changes replayed to the snapshot each entry recorded, every signature, the clock running forward, and, when the app asks, the history's key against the key the phone holds now. `authentic` needs the chain to hold, every entry to be signed and the key to be tied. Weak findings do not break a chain: an unsigned entry stops it being called authentic, a clock set back is only noted. The result always lists what it did not check.
- **The timeline** (`Timeline`): the entries as rows, an entry that records nothing shown as "looked, nothing changed", and any stretch of more than 48 hours with no entry shown as a gap, because the app only looks while it is open.
- **Tripwires** (`Tripwire`, `AndroidTripwireKeys`): one Keystore key that Android destroys when a fingerprint is added (needs an enrolled fingerprint to exist) and one that dies when the secure lock is removed. The reading turns a destroyed key into a verdict by rules: a changed PIN trips neither; a missing key alone, with a lock present, is "cannot tell". Where a tripwire is impossible (no lock, no fingerprint, no hardware) the screen says why instead of showing nothing.
- **Orchestration** (`HistoryKeeper`, `ReportViewModel`): one history thread; a step is taken at the first review, when the saved state is read, and at each re-read while the app is on screen. A damaged or unreadable file blocks writing: it is left as it is and replaced only when the person asks, and then it is kept aside as `declared-history.bin.damaged`.
- **Screens**: a card on the first screen, a History screen (what is recorded, tripwires, a check of the history, the timeline) and, in the state file, nothing new: the history has its own encrypted file under its own key and purpose, so a damaged history never touches the baseline.

### How it was verified

- **JVM**: 354 tests in `core`. The ledger: every kind of alteration (time, kind, versions, snapshot fingerprint, a change, dropped changes, events, links, hash, number, signature, a removed signature, an entry removed, swapped or repeated, the first entry removed, each header field) is detected; 4,000 random one-character changes to the stored text are never accepted as authentic; the documented blind spot (the newest entries removed) is asserted as undetected so the limit cannot be forgotten; a history of 1,000 entries over 2,000 apps is built, checked and stored in the time the test records; the reducer's random 3,000-sequence test now includes the history events. The tripwire rules and the wording are tested, the wording against the same banned words as everything else.
- **An independent verifier** (`tools/ledger_oracle.py`): hashlib and the OpenSSL command line, no shared code. It accepts the sample history that the app's code wrote (`core/src/test/resources/ledger/sample-ledger.json`, with text that sorts differently by UTF-16 unit than by code point, a negative control that fails if the rule is wrong), rejects every kind of alteration, and survives a scan of every character position of the sample. It also builds histories of its own with OpenSSL keys to test what the sample cannot hold: a signature made elsewhere, signatures stripped, and a whole history rewritten under another key. 24 tests.
- **On the emulator** (`conformance_m8.py`, 59 checks): the first entry, the app's head against the file's, every third-party package the system lists present in the history; a proxy set while the app is open becomes an entry taken "while on screen" with the change and the finding it brings, the system agreeing about the proxy; an app installed and removed while it is open each become entries; the opposite change after a fresh start is an "opened" entry; the History screen's check, read through the real UI, reports a valid signed history and shows the finding text exactly as recorded; a damaged file is reported, not rewritten, replaced only on request, and kept aside byte for byte; a screen lock set, a first fingerprint enrolled, a second one, and the lock removed each produce the matching event in a signed entry, with the system confirming each step first (`locksettings`, `dumpsys fingerprint`) and the screen's verdict naming it. Every look is checked by `ledger_oracle.py` with OpenSSL, tied to the Keystore's public key.
- **Accessibility**: the History screen, and the result of a check, join the instrumented pass at normal and double text size; it passes.
- **The earlier stages again, on the final build**: conformance m2 to m7 pass with the same counts as before this stage (297, 84, 63, 44, 79, 111), so the new card on the first screen broke nothing. `tools/run_suite.py` runs the accessibility pass and m2 to m8 and prints one line per step.

### Defects found on the way

D-043 to D-048 in `lab/results/DEFECT_LOG.md`. The one that changed the design most: D-046, a history rewritten from scratch under another key was called authentic, because the check trusted the key the file named. The check is now tied to the key the phone holds.

### Not verified yet

- **Everything ran on emulators (Android 14, then the matrix of section 25)** with a software Keystore and a virtual fingerprint. That a destroyed key means what the rules say on a phone with secure hardware and a real sensor rests on the earlier emulator measurements for Android 10 and 12 to 17 (`FINDINGS.md`) and on Android's documentation; no real phone has run it (the user skipped the fingerprint and lock tests on the Xperia).
- **What the history proves, and for whom.** It stops anything that can write the app's file but cannot use its Keystore key, and it shows a history rewritten under another key. It does not stop code running as the app itself or an attacker with root, and it cannot see the newest entries being cut off. It also does not say the phone was clean when an entry was written.
- **Background**: nothing is recorded while the app is closed. Scheduled snapshots were measured on the Xperia (Q9): 9 of about 229 expected runs across 57.8 hours of clean time (unplugged, screen off), none for 21 h 36 min once deep Doze began in the first stretch. That is one phone and one firmware, and it is the reason scheduled work stays off and the timeline shows gaps as gaps.
- The verifier and the app have the same author. They agree on what the format says, not that the format is the best one.
- Other Android versions of this stage's checks (the matrix), a physical phone, and the wording of the tripwire sentences read by someone they are meant for.

## 25. The version matrix (2026-10-09 to 2026-10-11)

The whole emulator suite (`tools/run_suite.py`: the accessibility pass and conformance m2 to m8) was run on Android 10 and 12 to 17, one image at a time (`tools/run_matrix.py`). Best result per step, each from the last pass in which the step ran on that version after the app fixes below:

| Android | a11y | m2 | m3 | m4 | m5 | m6 | m7 | m8 |
|---|---|---|---|---|---|---|---|---|
| 10 (29) | pass | 135/135 | 84/84 | 63/63 | 44/44 | 78/78 | 111/111 | 53/53 |
| 12 (31) | pass | 259/259 | 84/84 | 63/63 | 44/44 | 79/79 | 111/111 | 59/59 |
| 13 (33) | pass | 289/289 | 78/84 (1) | 63/63 | 44/44 | 78/79 (2) | 111/111 | 59/59 |
| 14 (34) | pass | 297/297 | 84/84 | 63/63 | 44/44 | 78/79 (3) | 111/111 | 59/59 |
| 15 (35) | pass | 291/291 | 84/84 | 63/63 | 44/44 | 78/79 (2) | 111/111 | 59/59 |
| 16 (36) | pass | 307/307 | 82/82 | 63/63 | 44/44 | 77/79 (4) | 111/111 | 59/59 |
| 17 (37) | cannot run (5) | 313/313 | 82/82 | 63/63 | 44/44 | 74/79 (6) | 110/110 | 59/59 |

1. Wi-Fi read as disconnected once in a stretch where the system was re-validating the network (D-041).
2. A touch-target measurement artifact in the script, since fixed and confirmed on Android 10, 12 and 14 (D-054).
3. The number of apps did not go up after an install within the retry window, on a slow host (D-042).
4. The same artifact, plus a text check that was right only for Android 15 and older, since fixed.
5. AndroidX Test (Espresso) calls a method Android 17 removed, so the instrumented accessibility pass cannot start there (D-053).
6. Five text checks on the Live view screen come back incomplete on fresh Android 16 and 17 images. Timing in the script, not seen on Android 14.

The final build after the rename to Declared was run in full on Android 14 (accessibility pass, m2 to m4 in one pass, m5 to m8 in the next) and again on Android 17 (m5, m6, m8). Android 10 to 13, 15 and 16 were last run on builds from before the rename, which changed names and nothing else; the JVM tests (365) and a clean rebuild pass.

**What the matrix found in the app** (all fixed): Android 17 gives `activeNetwork` as null to an app without internet permission, so the app saw no network, no proxy and no Wi-Fi security (D-049); on Android 10 a key that requires a fingerprint is not destroyed when another is added, so that tripwire cannot fire there and the app now says so, and the lock tripwire key could not be created (D-050); the tripwire headline vanished at the next look, so the last tripwire event now stays on the History screen (D-051).

**What it found in the scripts** (all fixed): they assumed Android 14 in how packages are hidden, how old commands print, which attestation certificates are valid, what `appops` returns, whether Advanced Protection can be read, and how long a fresh image takes to draw its first screen (D-052, D-055).

**Not covered:** a physical phone with secure hardware and a real fingerprint sensor; the instrumented accessibility pass on Android 17; Wi-Fi security on Android 10 and 11, which Android does not give to an app without the location permission.

## 26. After the first phone test (2026-10-12): grouping, a simple view, a tour, automatic checks, the intro and the logo kit

**What the first testers said, and what changed.** The Check list was long because the same ability held by several apps was one row per app: findings of one kind and one level are now one card that opens in place (`core/home/Grouping.kt`, never merging different levels, never merging conditions). Pressing back from a finding lost the place in the list: the scroll and open groups of each tab are kept above the screens. A long button label was cut off by a fixed height: buttons now have a minimum height. The app read as a document: the home screen is a status card (a bar of the real counts and a progress bar of checks that ran), the Check tab is a console (level tiles that filter, icons for the kind, groups), Paths has readouts and a preview switch, and long text is one sentence per line with the jargon explained (`core/words/Glossary.kt`).

**Simple view.** For a person new to this: the theme scales type by a fifth, the bar has Home, To do and Help, the home screen counts what needs attention and shows at most three cards, each with one sentence of why (`core/home/Easy.kt`) and a big button to the right setting. Nothing is removed: the full layers are behind "Technical details", and the full view is one switch away. The choice is a preference, kept apart from the encrypted baseline.

**Automatic checks.** Opt-in, off by default, because the project's measurement (FINDINGS.md, Q7) found nothing is delivered to a background app and so far no service ran. A repeating `JobScheduler` job (no new dependency, no service that stays running) reads the phone with the same sources as the screen (`ReportSources`), compares with the saved baseline using the same reducer, and posts one notification when something that needs a look is new (`core/schedule/AutoCheck.kt`: only Look first and Look count, a change is announced once and again only if it comes back, what was announced is kept as hashes). The notification says how many and never which app, and shows nothing but the app name on a locked screen. It writes neither the baseline nor the history, so the history does not show automatic checks; a later stage may record them. Cost: two permissions, notifications and run at startup, allowed by the manifest gate and said in PRIVACY.md and the store listing. Android's minimum for a repeating job is 15 minutes and it may run later; both are said in the interface. Verified on the emulator by `tools/conformance_m9.py`: no job by default, the job's interval read back from `dumpsys jobscheduler` for the default, a preset and a typed value, a refused value, one notification for a real change (a web proxy set from outside), no second notification, and the job gone when turned off.

**Intro and logo.** A short animation (the two halves of the H fit together; why the app is called Declared) shown once and under Help, skipped when Android has animations off and by lab scripts. The H-sib logo template and the premium Declared artwork are in `brand/` with a brand sheet: the H never changes, each app adds one small thing and one accent color.

**Verification of this round (Android 14 emulator, 2026-10-12, final build).** Core tests 401 pass; lint clean for debug and release; the accessibility pass (every screen, the tour, the intro and the simple view, at normal and double text size) passes; `conformance_m2.py` 297/297, `m3` 84/84, `m4` 63/63, `m5` 44/44, `m7` 111/111, `m8` 59/59, `m9` 40/40, `m6` 78/79. The one `m6` check that still fails now and then is the known app-count race (D-042): after an install the report sometimes lists the new app one read later than the check allows. The scripts needed these changes for the new screens: tabs and accordions are read through a debug-only lab flag (`--ez expandAll true`), a dialog that says the system is not responding is waited out, and a card cut off by the bottom bar is not counted as a small touch target. Not covered: a physical phone, other Android versions for the newest screens, and how long Android really waits before running the scheduled job on different phone makers.

**Titles, the review and screenshots (2026-10-12, after more testers).** A title is the first thing read, so it now says what is at stake and who it is about, in the form "<app> can <consequence>" or "<fact>: <consequence>" (`holders/HolderFindings.kt`, `conditions/Conditions.kt`, `conditions/Integrity.kt`, `state/Review.kt`); it says "can", never "does", because a declared ability is not an observed act. The first review groups holders by app (`Review.groupByApp`): one card, one answer applied to all of that app's holders, answers still stored per holder. Screenshot blocking moved from always on to a setting that is off by default in every build (`UiPrefs.blockScreenshots`, `FLAG_SECURE` applied from `MainActivity`); the blind-spot map says so, and `conformance_m6.py` now checks the default (not secure), the switch (secure, and a screenshot of it is empty) and the switch off again. The intro is a single clock-driven timeline (`ui/Intro.kt`): the phone outline draws, tiles type what apps declare, the halves fly in and snap with a ring and a light sweep, a manifest types itself and ends in "you read this? never" and then "Declared reads it? every check".
