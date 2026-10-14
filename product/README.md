# Declared (the product)

Android app that shows which apps on your phone hold powerful abilities, where each came from, and what the app itself cannot see. Apache-2.0. See `../DESIGN.md` for the design and `../CONCEPT.md` for the decisions.

**Status: Stage 1 (milestones 1 to 6) and Stage 2 (the attack-path engine) done on one emulator image (API 34); version 0.1.0 is a release candidate and 0.2.0 is unreleased.** It reads accessibility services, notification access, device administrators, keyboards, draw-over-apps, usage access, install-other-apps and text-message access, lists every visible app by origin, and shows the phone's own settings (security update date, screen lock, debugging switches, Advanced Protection) its network (connection, VPN, Private DNS, web proxy, Wi-Fi security type, added certificate authorities), and a hardware integrity check of the phone's boot state (offline attestation chain verification against bundled, checksummed Google reference data). The first start is a review: you say which powerful apps you recognize, the answers are kept on the phone encrypted, and later changes are listed against them until you acknowledge them. A live view hears changes while the app is on screen, a map shows what the app cannot see on every phone and on this one, and the screens are kept out of screenshots. The attack paths screen combines what was read and what you said into the ways someone could get at what matters to you, says how sure it is of each link and what it cannot see, and gives the smallest set of changes that would stop them, with a preview that changes nothing. Not yet verified on other Android versions (the multi-version run waits for approval to download images), on a physical phone, or against a real third-party app. The application ID is a placeholder and the release build refuses to run until it is replaced.

## Layout

| Module | What it is |
|---|---|
| `core/` | Pure Kotlin (JVM). Model, trust-tier classifier, findings, report pipeline, attestation chain verifier, snapshot and diff, the blind-spot map, the live-view model, the attack-path engine (`paths`: graph, evaluator, rule book, smallest fix set), the navigation reducer. No `android.*` import anywhere. |
| `sensors-android/` | Android library. Thin adapters that read the phone (`AccessibilityManager`, `PackageManager`, settings, `ConnectivityManager`, the Keystore attestation) and the change watcher, turning platform exceptions into plain results. Owns the package-visibility `<queries>` block and the bundled reference data. |
| `app/` | Jetpack Compose (Material 3) app: one activity, a view model around the pure reducer, the screens, the encrypted file store. `src/debug` holds the conformance hook (absent from release), `src/androidTest` the accessibility pass. |
| `tools/` | Conformance scripts `conformance_m2.py` to `conformance_m9.py`, `paths_oracle.py`, `ledger_oracle.py` and their helper tests: each compares what the app reports with what the system itself says, on an emulator. `conformance_m1.py` is the shared helper library. |
| `conformance/` | Results (`m2_results.csv` to `m8_results.csv`; `conformance_m9.py` prints its verdict only, one row per check, appended per run) and screenshots. |

## Build, test, check

From the repository's `product/` folder (PowerShell, after `. ..\lab\tools\env.ps1`, which sets the JDK, SDK and a temp path that works around lab defect D-001):

```bash
gradle :core:test :sensors-android:testDebugUnitTest      # JVM tests: 354 in core, 9 in sensors-android
gradle :app:lintDebug :sensors-android:lintDebug          # lint, warnings are errors
gradle :app:assembleDebug                                 # debug APK, also runs the manifest policy gate
python -I tools/test_conformance_m1.py                    # tests of the conformance scripts' own helpers
python -I tools/test_conformance_m2.py
python -I tools/test_conformance_m3.py
python -I tools/test_conformance_m4.py
python -I tools/test_conformance_m5.py
python -I tools/test_conformance_m6.py
python -I tools/test_conformance_m7.py
python -I tools/test_paths_oracle.py                      # the independent evaluation of the attack-path rules
python -I tools/test_ledger_oracle.py                     # the independent verifier of the history file, against the signed sample the app wrote (needs OpenSSL)
python -I tools/test_conformance_m8.py
python -I tools/conformance_m2.py --serial emulator-5554  # all eight capability kinds and the inventory; needs the lab fixtures built (lab/) and a running emulator; refuses real phones
python -I tools/conformance_m3.py --serial emulator-5554  # device and network facts; changes and reverts emulator settings; places a test CA if the image is rootable and OpenSSL exists
python -I tools/conformance_m4.py --serial emulator-5554  # integrity panel against the JDK verifier and an independent DER reader; needs the JDK on the path
python -I tools/conformance_m5.py --serial emulator-5554  # first-run review, encrypted baseline, what-changed, through the real UI; clears the app's data, re-signs a fixture, reboots the emulator and damages the state file on purpose
python -I tools/conformance_m6.py --serial emulator-5554  # live view, map, secure window, large text; changes settings and apps from outside while the app is in front, reverts them; takes about 12 minutes
python -I tools/conformance_m7.py --serial emulator-5554  # attack paths against paths_oracle.py; needs the shadow fixture built (gradle :fixtures:assembleShadowDebug in lab/); answers the UI like a person; takes about 12 minutes
python -I tools/conformance_m8.py --serial emulator-5554  # history and tripwires: changes the phone from outside, checks the encrypted history with ledger_oracle.py and OpenSSL, damages the file, sets a screen lock and enrolls fingerprints on the emulator
python -I tools/conformance_m9.py --serial emulator-5554  # the tour, the simple view, the intro and the optional automatic checks: reads the scheduled job back from dumpsys jobscheduler, fires it, counts the notifications; it grants the debug app the notification permission itself
gradle :app:connectedDebugAndroidTest                      # the accessibility pass: every screen at normal and double text size with Google's accessibility checks (uninstalls the app afterwards)
```

A signed release (the bundle for Google Play): keep the upload key outside the repository, point `DECLARED_KEYSTORE_PROPERTIES` at its `keystore.properties` (keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`), then `gradle :app:bundleRelease`. Without the variable a release is built unsigned.

Other contributors can use `./gradlew` instead of `gradle` (the wrapper pins Gradle 9.6.0). The Android SDK is found through `ANDROID_HOME` or a git-ignored `local.properties` with `sdk.dir`; JDK 17 is required.

## Rules the build enforces

- No permission may be requested except `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `QUERY_ADVANCED_PROTECTION_MODE` (all normal, no prompt), `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED` (both only for the optional automatic checks, off by default) and the one private signature permission AndroidX adds by itself. In particular no `INTERNET`, no location, no `QUERY_ALL_PACKAGES`, no VPN service, no foreground service.
- The package-visibility entries the sensors depend on must stay in the merged manifest.
- `core` warnings are errors; lint warnings are errors.
- A release build fails while the application ID still contains `.placeholder.`.

## Versions (read from the publishers, 2026-10-09)

Android Gradle Plugin 9.4.0 with built-in Kotlin, Kotlin 2.4.21, Compose BOM 2026.09.00, activity-compose 1.13.0, lifecycle 2.11.0, compileSdk 37, targetSdk 36 (Google Play's requirement for new apps), minSdk 29.

## Known limits

- The integrity panel has not run on a real phone. `tools/conformance_m1.py` is retired; it remains the helper library the other scripts import.
- The installer allow-list has no carrier or OEM entries yet, so an app installed by a setup flow is shown as "not recognized" (never as sideloaded).
- Texts are English and built in `core`; moving them to string resources is planned before any translation.
- Every screen carries the secure-window flag; the blind-spot map says an accessibility service can still read the text.
- Launcher icon is a first draft of the H mark.
