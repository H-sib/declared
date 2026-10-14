# Findings (interim): what a normal Android app can actually observe

Status: interim, 2026-10-08. Covers Phase 1 (emulator sweep) and the first physical-device session. Aggregated results only; nothing in this file comes from a person's app list or account.

## Evidence base

- **Emulators:** stock Google APIs images for Android 10, 12, 13, 14, 15, 16 and 17 (API 29, 31, 33, 34, 35, 36, 37). Per level: two probe builds (one using `<queries>` intents only, one holding `QUERY_ALL_PACKAGES` as the baseline) in two states (no fixtures enabled, five fixtures enabled). 28 runs. In every run the fixture state was verified against the system itself (0 setup failures) and every automatic ground-truth comparison matched (0 mismatches).
- **Real hardware:** Sony Xperia 10 II (au/KDDI SOV43), Android 12, security patch 2022-07-01, verified boot green, bootloader locked. Read-only measurements only.
- **Tripwire experiment (separate from the 28 runs above):** one complete run per Android version on emulators with a software Keystore and a virtual fingerprint (API 29, 31, 33 to 37), after four rounds of harness fixes.
- **Not measured, by decision or sequence:** anything that would alter a physical device (test CA, fingerprint, lock removal); any Samsung or One UI behavior (the Galaxy S21 FE was removed from the plan by the user's decision; Sony is the only OEM data point); the 24 to 48 hour background test (running); LAN and network probes (Stage 4).
- **Caveat that applies to everything:** one run per (device, API level) cell. A result is "observed on that cell", not a law about an Android version. The emulators are Google's own images, so they say nothing about other manufacturers' skins.
- **Data validity:** a first version of the sweep silently ran with nothing enabled and produced meaningless "matches". It was detected, archived and rerun with state verification (defect D-004). The numbers below come from the verified rerun.

## Answers to the planned questions

| # | Question | Status | Answer so far |
|---|---|---|---|
| Q1 | Minimum API level | Answered (candidate 29 holds) | At API 29, 24 of 26 Stage 1 signals returned usable data (Advanced Protection needs API 36; cross-app app-ops are refused on Android 10). 25 of 26 on API 31 to 35, 26 of 26 on API 36 and 37. That clears the 90% rule. Android 10 has no package-visibility filtering at all (both builds saw all 184 packages). |
| Q2 | Capability enumeration without `QUERY_ALL_PACKAGES` | Answered, with a measured limit | Recall of the four capability lists (accessibility, notification listener, input method, device-admin receiver): 100% on API 31, 33, 34, 35; 17 of 18 on API 36; 18 of 19 on API 37, always missing the same Google system package (`com.google.android.odad`, a notification-listener holder). The reason is unexplained. On the real phone the counts matched in both builds. The queries-only app sees about 45 to 50% of installed packages. See "What queries cannot see" below. |
| Q3 | Keystore tripwires | Answered on emulators for Android 12 to 17 (identical), Android 10 differs, Android 11 untested, **not verified on hardware** | Enrolling a second fingerprint invalidates a key that requires a biometric, but not a key that also accepts the PIN. Removing the screen lock destroys both keys. Changing the PIN trips neither. Keys survive an app reinstall and a reboot. Creating tripwire keys requires a secure lock screen and an enrolled biometric. On Android 10 the fingerprint tripwire did not fire and lock removal left unusable keys. Full table in the next section. |
| Q4 | Software SHA-3 needed? | Answered: yes | `SHA3-256` is unavailable from the platform providers, from the bundled `BC` provider, and as a Keystore key digest on every level from 29 to 37. The Keystore offers only MD5, SHA-1 and the SHA-2 family. Any SHA-3 use needs a bundled implementation. |
| Q5 | Confidence in device-side attestation | Answered | Feasible offline. The real Xperia chain verifies against Google's published roots with no revoked certificate, and shows hardware-backed key, verified boot, locked bootloader and attested patch level 2022-07. Pre-2021 phones carry expired but valid factory certificates and must be accepted unless revoked, or every old phone is falsely accused (defect D-008). Limits: the check runs on the same device as the data, and leaked attestation keys can fool it until revoked. A separate verifier remains the stronger option. |
| Q6 | Wi-Fi permission path | Partly | `getCurrentSecurityType` works with no location permission (real phone). SSID, BSSID and nearby-network scans appear only after `ACCESS_FINE_LOCATION` is granted (location services on). The Android 13+ nearby-devices path is not measured on hardware yet. |
| Q7 | What the live view can watch | Answered on one emulator (API 34), not measured on hardware | While the app is on screen the watcher hears about settings changes within about 60 to 320 ms, app installs, removals and changes within about 0.1 to 0.8 s, and Wi-Fi changes within about 0.35 s. Once the app is in the background, almost nothing is delivered (and what arrives is 6 to 10 s late), and nothing is delivered when the process is gone. An app with no launcher icon and no capability component is never seen, even live. Details in the next section. |
| Q8 | LAN features | Not measured | Stage 4. |
| Q9 | Background snapshots under OEM power management | Answered (one phone) | A 15-minute job was delivered 9 times out of about 229 expected (3.9%) across 57.8 hours of clean time (unplugged, screen off): 4 in the first hour of the first stretch, none in the 21 h 36 min after deep Doze began, then a few at deep-Doze maintenance windows once the app's bucket was RARE. See `lab/results/BACKGROUND_TEST.md`. |
| Q10 | OEM protective-feature state | Closed: not investigated | Samsung was excluded from the plan, so there is no OEM protective-feature data. The product will not model OEM-specific controls and will say "not verified on Samsung". |
| Q11 | Trust-tier reliability | Partly | Platform-signed and system classification works (209 platform-signed packages classified on the real phone). Store versus sideload is ambiguous: adb installs show no installer, and from Android 14 the initiating package `com.android.shell`. A real false positive: an app installed by the carrier's own setup flow was classified "sideload". The tier model needs carrier and OEM installer awareness. Install-source spoofing is not tested yet. |

## Hypothesis log (pre-registered in the observability plan)

| ID | Hypothesis | Outcome | Evidence |
|---|---|---|---|
| D1 | Patch level readable | Confirmed | Matches the system property on 7 emulators and the real phone |
| D3 | Screen-lock presence readable | Confirmed | Differs correctly between emulators (none) and the real phone (set) |
| D4, D5, D6 | Debugging and developer-options flags readable | Confirmed | Match the system; D6 is absent (-1) on emulators |
| D7 | Advanced Protection readable by a normal app | Confirmed on API 36 and 37, with a caveat | Permission was granted to a sideloaded debug build; behavior for a Play-installed app is untested |
| D8 | Play Protect state not readable | Confirmed | Only absent keys (-1) |
| K1, K2 | Attestation retrieval and parsing | Confirmed | Software level on emulators; TEE-backed, verified, locked on the real phone |
| K3 | Offline chain validation | Confirmed, refined | Legacy-root rule required |
| K5 | StrongBox availability device-dependent | Confirmed | Absent on emulators and the Xperia |
| K8 | SHA-3 availability varies; Bouncy Castle fallback | **Refuted** | The platform's `BC` provider has no SHA3-256 either |
| K6 | Enrolling a biometric invalidates an auth-bound key | Confirmed on Android 12 to 17 (emulators), with a nuance; **not observed on Android 10** | The biometric-only key was invalidated; the biometric-or-credential key stayed valid. On Android 10 neither was invalidated (cause unresolved) |
| K6b | Tripwire keys survive app update and reboot | Confirmed on Android 10 and 12 to 17 (emulators) | Both keys valid after `adb install -r` and after a reboot |
| K7 | Removing or resetting the lock destroys an auth-bound key | Partly: removal confirmed (missing on 12 to 17, unusable on 10), **PIN change refuted** | Removal destroys both keys. Changing the PIN left both keys valid on every version |
| P1, P2, P3 | Accessibility services, capability flags, listeners readable | Confirmed | Match the system on every level |
| P4 | Active device admins readable | Confirmed | Fixture admin seen on API 29 and 31 to 37 and on the real phone |
| P5 | Input-method data readable | **Partly refuted** | The enabled-input-methods setting is blocked from API 34 for apps targeting above 33; the input-method manager list and the default input method still work |
| P7, P8 | Cross-app app-ops readable | Partly | Readable on API 31 to 37, refused (SecurityException) on API 29 |
| P9 | Other apps' granted permissions readable | Confirmed | Match the system |
| P11 | The live watcher fires while the app runs, nothing while it does not | Confirmed, with sharper edges (API 34 emulator) | Fast and complete only with the app in front; in the background it was mostly missed or 6 to 10 s late; never fires for a hidden-icon app; nothing when the process is killed |
| V1 | Capability enumeration via `<queries>` | Confirmed with limits | See Q2 and the next section |
| V1b | Wider `<queries>` (boot and SMS receivers) reaches hidden-icon apps | **Refuted** (API 34) | Fixtures with only those receivers stayed invisible; visible count unchanged at 107 |
| V2, V8 | Provenance and tiers readable | Confirmed | Signing hashes, flags and platform-signature tiers work; store versus sideload is ambiguous (Q11) |
| V4 | Other apps' APKs and signing blocks readable | Confirmed | A normal app can read other apps' APK files and see which signing schemes they use (API 34 emulator and the real phone) |
| V7 | Hidden launcher icon detectable | Confirmed where visible | Only apps the build can see; see below |
| N1 to N4 | Network configuration readable | Confirmed | Match the system where a truth source exists |
| N5 | User CAs detectable | **Confirmed on one emulator (API 34), 2026-10-09** | A test CA placed in the user store was read back with the exact SHA-256 OpenSSL gives, and the reading was empty before and after (see `DESIGN.md` section 19). Not tried on hardware, other versions, or the Settings install route |
| N6, N7 | Security type readable; SSID needs location | Confirmed | See Q6 |
| N8 | Scan results need location | Confirmed | 16 nearby access points visible after the grant on the real phone, counts only |

## What `<queries>` cannot see (the central trade-off of avoiding `QUERY_ALL_PACKAGES`)

The queries-only build sees apps that have a launcher icon plus apps that expose one of the four capability components. Verified on API 31 to 37 and on the real phone: **an app with no launcher icon and none of those components is invisible to it**, while the `QUERY_ALL_PACKAGES` build sees it. Such an app cannot hold accessibility, notification-listener, input-method or device-admin power, but it can still hold ordinary sensitive permissions (SMS, microphone, location). This is exactly the shape stalkerware tries to take once it hides its icon, so it goes in the blind-spot map as a stated limit.

**Tested mitigation (V1b), refuted on API 34:** adding `<queries>` entries for the boot-completed broadcast and SMS delivery did not bring hidden apps into view. Two new hidden-icon fixtures, one with only a boot-completed receiver and one with only an SMS-received receiver, stayed invisible to the queries-only build and visible to the `QUERY_ALL_PACKAGES` build. The visible package count did not change at all (107 before and after). The experiment was checked for flaws: the built APK really contained the new `<queries>` intents, and the fixture APKs really contained exported receivers with those filters. A device-admin receiver, by contrast, is found reliably, so receivers in general do match. A plausible explanation (unconfirmed) is that the platform ignores protected system broadcasts such as these when computing visibility. The practical conclusion: no cheap `<queries>` trick closes this blind spot, so the blind-spot map must state it, and seeing every package would need `QUERY_ALL_PACKAGES` with a Play justification.

## The Keystore tripwire experiment (Q3, emulators for Android 10 and 12 to 17)

Setup: a PIN, one virtual fingerprint, and two Keystore keys that require user authentication for every use: one accepts a strong biometric only, the other a biometric or the device credential (on Android 10 the builder cannot express that difference). Both are created with "invalidate on biometric enrollment" on. Every row was recorded next to the system's own fingerprint count and lock state, not the screens' claims. The tool `tools/tripwire_summary.py` takes the latest complete run per version and marks a cell "NOT EXERCISED" when the event it tests did not really happen.

| Android (API) | Fingerprint added: biometric-only key | Fingerprint added: biometric-or-PIN key | App reinstalled | Rebooted | PIN changed | **Lock removed** | Create keys with no lock |
|---|---|---|---|---|---|---|---|
| 10 (29) | **valid (not invalidated)** | valid | valid | valid | valid | **unusable** (`UnrecoverableKeyException`) | fails (stale key, `KeyStoreException`) |
| 12 (31) | **invalidated** | valid | valid | valid | valid | **missing** | refused (needs lock) |
| 13 (33) | **invalidated** | valid | valid | valid | valid | **missing** | refused (needs lock) |
| 14 (34) | **invalidated** | valid | valid | valid | valid | **missing** | refused (needs lock) |
| 15 (35) | **invalidated** | valid | valid | valid | valid | **missing** | refused (needs lock) |
| 16 (36) | **invalidated** | valid | valid | valid | valid | **missing** | refused (needs lock) |
| 17 (37) | **invalidated** | valid | valid | valid | valid | **missing** | refused (needs lock) |

Android 11 (API 30) was not tested. On every version from 12 to 17 the result is identical. Android 10 differs in two ways: the fingerprint tripwire did not fire, and lock removal left the keys unusable instead of missing (and the emulator's enrolled-fingerprint count did not drop to 0). I do not trust the Android 10 fingerprint result as a statement about real Android 10 phones: the key may accept the PIN too (the builder cannot ask for biometric-only before Android 11), or this emulator's fingerprint simulation may not change the fingerprint identity the way hardware does. Both explanations fit the data, so it is recorded as "not invalidated on this emulator, unverified on hardware".

The table is the fourth attempt at this experiment: the first three had harness defects (D-009, D-010, D-011, D-012 in the defect log) that left cells empty or untested. Every empty or untested cell was redone, and the summary tool refuses to report a fingerprint-test cell as valid when the second fingerprint never registered.

What this means for a product tripwire:
- It is a cryptographic, tamper-evident signal for "a fingerprint was added" (biometric-only key) and "the lock was removed" (either key). It detects after the fact and says nothing about who did it.
- A PIN change goes unnoticed, which matters because a stolen-PIN scenario is the realistic one for the physical-access path (P4).
- A missing key is ambiguous: it can mean lock removal, but also an app reinstall, cleared app data or a restore. The product must combine it with the lock-screen reading (D3) and the app's install time before drawing a conclusion, and say "cannot tell" otherwise.
- The user's own legitimate fingerprint enrollment trips it as well, so it must be framed as "something changed, was that you?", never as an accusation.
- It only works on phones that have a lock screen and a fingerprint, and the keys must exist before the event. Phones without a lock get no tripwire, and the app can say so (that absence is itself a finding).
- On Android 10, treat `UnrecoverableKeyException` or a failed key deletion as "key destroyed" for the lock-removal case, and do not rely on the fingerprint tripwire at all until it is measured on a real Android 10 device.
- Limits of the evidence: one run per version on emulators whose Keystore is software-backed, with a virtual fingerprint. Real hardware may differ (the Xperia's attestation chain shows a hardware-backed Keystore exists there, but no tripwire test was run on it, by decision). Android 11 was not tested. Each version stays "observed on this cell" until measured elsewhere.

## The live-view watcher timing experiment (Q7, API 34 emulator)

Setup: the probe's watcher registers observers on security-relevant settings, a package-change receiver and a network callback. A harness issued 11 kinds of change (2 or 3 repetitions each, 110 in all), read the device clock before and after each one, and looked for the matching line in the watcher's own timestamped log. The conditions were: app in front; app sent home and left 30 s to settle; screen off with the app in the background; deep idle (screen off, unplugged, forced idle); and process killed. Every complete event log is saved in `results/raw/watch-*.log`.

Median delivery time, in milliseconds, with the app in front (3 of 3 delivered unless stated):

| Event | App in front | In any background condition | Process killed |
|---|---|---|---|
| Accessibility service enabled | 59 | 2 of 2 only after 6 to 10 s | missed |
| Notification listener allowed | 311 | 1 to 2 of 2, after 6 to 10 s | missed |
| Default keyboard changed | 80 | delivered after 6 to 10 s only when the screen was off or in deep idle; missed with the screen on | missed |
| Private DNS mode, global HTTP proxy, developer options | 64 to 85 | **missed** | missed |
| App installed (has a launcher icon) | 750 | **missed** | missed |
| App removed | 349 | **missed** | missed |
| App disabled | 113 | **missed** | missed |
| Wi-Fi turned off | 341 | **missed** | missed |
| App installed with no launcher icon and no capability component | **missed (0 of 3)** | missed | missed |

Reading the raw logs: in the background the system delivered only a handful of settings events (accessibility, notification-listener and, with the screen off, keyboard settings), each several seconds late, and then nothing at all for the rest of the condition, including events that were still being produced minutes later. The emulator image has Android's cached-app freezer enabled with a 10-second debounce (`use_freezer=true`), which is consistent with a backgrounded process being frozen and its notifications being lost or deferred. That is an explanation that fits the data, not a proven mechanism.

What this means for the product:
- **The live view is only live while it is on screen.** It must say "watching while this screen is open" and never imply continuous monitoring.
- **Anything that matters in the background must come from periodic diffing, not live callbacks.** That means a scheduled snapshot comparison (whose own reliability is the Xperia background test, Q9) plus a fresh diff every time the app is opened.
- **Even the live view has the same blind spot as the inventory:** an app with no launcher icon and no capability component is never announced, so "no app was installed" can never be claimed.
- The settings-based signals (accessibility, listeners, keyboard, DNS, proxy, developer options) are the fastest and most reliable live signals; package and network events are slower but still sub-second in front.
- Limits of the evidence: one emulator image (Android 14, software-backed), one run of 110 changes, and process management on real phones differs by manufacturer, so the background results are "observed on this emulator", not a rule. A first version of this experiment mis-specified two of its conditions (defect D-013) and was redone; its archived rows were not used.

## Observations from the real phone (Xperia 10 II, Android 12)

- Patch level 2022-07-01, more than four years old: a genuine end-of-life device for the unpatched-device path.
- Hardware attestation: trusted-environment security level, verified boot state "Verified", device locked, patch levels attested and equal to the software value.
- No StrongBox. No Advanced Protection API (Android 12).
- The number of enrolled fingerprints came from the system's own diagnostic tool. A normal app can ask only whether a biometric is enrolled (this needs the `USE_BIOMETRIC` permission), not how many.

## Design consequences so far

1. Use `<queries>` intents plus the launcher query, and state the hidden-app blind spot in every report. Widening the list with boot or SMS intents does not help (V1b), so the only way to close the blind spot is `QUERY_ALL_PACKAGES` with a Play justification, which is a deliberate later decision, not a v1 default.
2. Report Wi-Fi security type without any location permission; make location an optional feature that unlocks network identity and scan-based heuristics.
3. Bundle an own SHA-3 implementation if SHA-3 is used at all; SHA-256 is available everywhere. Treat the SHA-2 versus SHA-3 comparison as a benchmark, as planned.
4. Device-side attestation verification must implement the legacy-root rule, label itself as device-side, and enforce revocation.
5. Read Advanced Protection state on API 36 and later for the high-risk profile.
6. Treat cross-app app-ops as available from API 31; provide a degraded path at API 29 and 30.
7. Build the tripwire on two keys (a biometric-only one for fingerprint enrollment, either one for lock removal), pair every "key missing" result with the lock-screen reading and the install time, and degrade to "no tripwire possible" on phones without a lock screen or a fingerprint.
8. Label the live view "while this screen is open", run a diff on every app open, and rely on scheduled snapshot comparison for anything that must be noticed in the background (see the Xperia background test for how reliable that is).
9. Give the trust tiers an installer allow-list that includes OEM and carrier setup apps, or the false-alarm rate on real phones will be unacceptable.

## Next

Rebuild is done, the extra `<queries>` experiment is done (refuted) and the emulator tripwire experiment is done for Android 10 and 12 to 17. The watcher timing test is done for API 34. Remaining measurement work: the 24 to 48 hour background result on the Xperia (which also tells whether scheduled snapshots are a workable substitute for the live view), and optionally Android 11 for the tripwire and other Android versions for the watcher. The Samsung session is cancelled.

## Differences between Android versions found by running the suite on seven images

- Android 10: every installed package is visible to an app (no package visibility rules); the three special-access signals are refused; Wi-Fi security is not given without location; a fingerprint-bound key survives another fingerprint being added; the emulator's fixed software attestation certificates have expired.
- Android 11 and later: packages without an icon and without the four powers are hidden from the app, as `BlindSpots` says.
- Android 15 and later: `appops` shows nothing for platform packages whose default the framework reports as allowed.
- Android 16 and later: Advanced Protection can be read; a Google system package, `com.google.android.odad`, provides a notification listener service the app is not shown.
- Android 17: `getActiveNetwork()` is null for an app without internet permission; the AndroidX Test framework calls a removed method.
