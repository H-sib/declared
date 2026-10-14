# Declared: app info for portfolio, CV and submissions

Paste-ready. Everything here is true of the project as of 2026-10-12. Numbers were counted from the files and test runs, not estimated. Edit the role statement to say what you want to claim, and keep it truthful.

## In one line

**Declared** is an offline Android app that shows which apps on your phone can read your screen, see your notifications or install other apps, in plain words, and where to switch each one off.

## In 50 words

Declared is an open-source Android app that reads which apps hold powerful abilities (screen reading, notification access, device administration, keyboards, installing other apps) and which risky settings are on, explains each in plain language, and links to the exact setting. It has no internet permission, no account and no ads, and it never gives a score.

## In 150 words

Most security apps answer with a score or a green tick. Declared answers with facts you can check. It lists which apps hold powerful abilities on your phone, where each came from, how sure it is, what it cannot tell, and what you can do about it. It joins facts into attack paths (for example, an unfamiliar app that can read the screen on a phone whose accounts send sign-in codes by text) and works out the smallest set of changes that would stop them, with a preview that changes nothing. It keeps a signed, tamper-evident history of your phone and can check automatically on a schedule you choose. It has a simple view for people new to this, a built-in tour and plain-words explanations for every piece of jargon. Everything runs on the phone. There is no internet permission, no account and no analytics.

## CV bullets (pick two or three)

- Designed and built Declared, an open-source Android security-posture app in Kotlin and Jetpack Compose (about 21,000 lines of Kotlin, 404 JVM tests, 8 emulator conformance scripts); offline by design, with the no-internet promise enforced by the build.
- Built an attack-path engine over three-valued logic with an exact bounded-search solver for the smallest set of fixes, and a hash-linked, ECDSA-signed history verified by a second implementation in another language.
- Wrote an offline X.509 key-attestation verifier on the JDK with a hand-written DER reader, and checked the app against independent sources (aapt2, apksigner, dumpsys, OpenSSL, a Python oracle) on emulators for seven Android versions.
- Led the UX through tester feedback rounds: grouped findings, plain-words glossary, a simple view for non-technical users, accessibility checks at double text size, and a premium logo system reusable across apps.
- Prepared the full Google Play release: signed app bundle, listing copy, graphics, data-safety and content-rating answers, privacy policy.

## What it does

- **Home:** a status card (counts of what needs a look, and how much was checked) and a lit phone whose six tiles are real readings. One button re-checks.
- **Check:** findings sorted by level, with tiles that filter, grouped when many apps hold the same ability, one tap from a plain-words explanation and a button to the right Android setting.
- **Paths:** how someone could reach what matters, with confidence for each link, what the app cannot see on that path, and a preview of the smallest set of changes.
- **History:** a signed, hash-linked record of what changed, tripwires for a fingerprint added or the screen lock removed, and a check of the record.
- **Simple view:** bigger text, three tabs, one card per issue, one big button to act.
- **Automatic checks (optional):** pick any interval from 15 minutes to a year; one notification that says how many changes need a look, never which app.
- **Honesty built in:** a screen that lists what the app cannot see, no score, no "safe", and findings that say "can", never "does", because a declared ability is not an observed act.

## Why it is different

It refuses to claim more than a reading can know. It is built to be checked: the findings carry their sources, the history can be verified by a separate program, the build fails if an internet permission appears, and the project keeps a defect log of what testing found. The name comes from how Android works: every app declares what it can do, almost nobody reads that part, and Declared reads it. The logo is an H cut in two along a stepped seam, two halves that only fit if nothing was altered.

## Languages and tools used

| Language | Used for | Size |
|---|---|---|
| **Kotlin** (primary) | The whole app: the pure `core` logic, the `sensors-android` adapters, the Jetpack Compose interface, the tests, and the lab fixture apps | 156 files, about 21,500 lines |
| **Python** | Eight emulator conformance scripts, independent oracles (attack paths, signed history), test harness and lab tooling | 39 files, about 9,000 lines |
| **Gradle (Kotlin DSL)** | Build scripts, signing, and the build gates (no internet permission, no placeholder ID) | 9 files |
| **PowerShell** | Emulator automation (start, stop, sweep Android versions) | 5 files |
| **Java** | A small standalone certificate-chain verifier used as an independent check | 2 files |
| **XML** | Android manifest and resources | 31 files |
| **HTML/CSS, SVG** | The privacy-policy page, the brand sheet, the logo masters | small |
| **Markdown** | Design record, threat model, privacy policy, store listing, changelog | 18 files |

Python scripts also drove OpenSSL and the Android SDK tools (aapt2, apksigner, adb, dumpsys) as independent sources of truth. Images for the store were produced with Python and Pillow.

## Technical profile

- **Platform:** Android, minimum SDK 29 (Android 10), target SDK 36, compile SDK 37, Kotlin 2.4.21, Jetpack Compose with Material 3 (BOM 2026.09.00), Android Gradle Plugin 9.4.0, JDK 17.
- **Architecture:** three modules. `core` is pure Kotlin with no Android import, so all of its logic is tested on the JVM. `sensors-android` holds thin adapters behind interfaces. The `app` is a Compose view of a pure reducer `(state, event) -> state`.
- **Engineering that carries weight:** an AND/OR attack-path graph with Kleene three-valued logic; an exact hitting-set solver (bounded search tree with a lower bound, labelled approximate when it hits its budget); an offline X.509 attestation verifier with a hand-written DER reader; a snapshot diff with the property `apply(old, diff(old, new)) == new`; a hash-linked ECDSA-signed ledger under an Android Keystore key; Keystore tripwires; an authenticated-encryption file container; a glossary and sentence engine for plain-language output; a system-job scheduler for optional automatic checks.
- **Security and privacy:** 5 permissions in total (network state, Wi-Fi state, Advanced Protection query, and notifications and run-at-startup used only by the optional automatic checks); no internet permission, no `QUERY_ALL_PACKAGES`, no foreground service; local data encrypted; the merged manifest is checked by the build.

## Quality evidence

- 404 JVM tests pass, including property tests, an exhaustive oracle for the solver on thousands of random graphs, a random-sequence test of the reducer, and scale tests at 4,000 and 20,000 synthetic apps.
- Eight emulator conformance scripts compare what the app says with independent sources, for example 297 checks on the capability kinds, 111 on attack paths and 59 on the signed history.
- Instrumented accessibility checks (Google's framework) on every screen at normal and double text size.
- Earlier stages verified on emulators for Android 10 and 12 to 17; the newest screens and the automatic checks on Android 14.
- Lint and compiler warnings are errors. The Play release is signed, with native libraries checked for 16 KB page alignment.
- A defect log of more than 50 entries, including problems found only by using the running app.

Honest limits: not yet tested on a physical phone with secure hardware; one live-timing check in the emulator suite is intermittent on slow images.

## Role statement (edit to fit)

Designed, directed and shipped by H-sib, built with an AI coding assistant (Claude Code) that wrote and tested much of the code under H-sib's direction, from requirements, design decisions and review feedback to testing with real testers.

## Facts and links

- **Name:** Declared. **Maker:** H-sib (github.com/H-sib). **Licence:** Apache-2.0.
- **Application ID:** `io.github.h_sib.declared`. **Version:** 0.3.0. **Size:** 8.2 MB bundle, about 23 MB installed apk.
- **Repository:** https://github.com/H-sib/declared. **Site:** https://h-sib.github.io/declared/. **Play Store:** link after release.
- **Screenshots:** `play-store/graphics/phone-1.png` to `phone-8.png`. **Feature graphic:** `play-store/graphics/feature-graphic-1024x500.png`. **Logo:** `brand/`.
- **Longer write-ups:** `PROJECT_SUMMARY.md` (overview, complexity, future scope), `DESIGN.md`, `THREAT_MODEL.md`, `CHANGELOG.md`.

## Timeline

Built in stages: concept and threat model, Stage 1 (what holds which power), Stage 2 (attack paths), Stage 3 (signed history and tripwires), a redesign after the first phone test, a simple view, a tour, automatic checks, and the Play package. The stages are dated in `DESIGN.md` and `CHANGELOG.md`; the latest work is from October 2026.

## What it could become (future scope)

Let automatic checks write to the history; a Bengali version; checks across more Android versions and on real phones; a rules file that can be updated without a new release; more apps under the same H mark.
