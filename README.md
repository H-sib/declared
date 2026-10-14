# Ethical lite hacking: personal device attack-path analyzer

Working name: **Declared** (Apache-2.0). An open-source Android project that models a personal device as an attack graph, shows which realistic attack paths exist, says what it cannot see, and keeps a tamper-evident history of what changed. Not an antivirus, cleaner or score-based scanner.

## Documents

| File | Purpose |
|---|---|
| `CONCEPT.md` | Locked decisions, stages, Play constraints, quality bar |
| `THREAT_MODEL.md` | Assets, adversaries, attack paths, false-alarm and harm controls |
| `OBSERVABILITY_PLAN.md` | Signal-by-signal experiment plan: what a normal app can actually read |
| `FINDINGS.md` | Interim measured results: what a normal app can observe, hypothesis log, design consequences |
| `DESIGN.md` | Stage 1 design: architecture, data model, sensors with their evidence, screens, tests, build order; the record of each milestone and of Stage 2 (sections 17 to 23) |
| `STAGE2_DESIGN.md` | Stage 2 design, the attack-path engine: the three-valued AND/OR graph, the smallest set of changes, the rule book, and where the build differs from it |
| `STAGE3_DESIGN.md` | Stage 3 proposal, history and tripwires: a hash-chained ledger, a drift timeline, Keystore tripwires, and the three decisions it waits on. Nothing of it is built |
| `product/` | The app itself (Stages 1 and 2 built): pure Kotlin core, Android sensor adapters, Compose UI, conformance tooling. See `product/README.md` |
| `LICENSE`, `NOTICE` | Apache-2.0 |
| `PRIVACY.md`, `SECURITY.md`, `CONTRIBUTING.md`, `CHANGELOG.md` | What the app keeps and sends (nothing leaves the phone), how to report a problem, how to contribute, release notes |
| `STORE_LISTING.md` | Draft store text and Data safety answers, written to what the app can back; not submitted |
| `lab/` | Throwaway test tooling for the plan (benign fixture apps, probe app, runner, attestation verifier). Not the product. |

## Status

Concept, threat model, observability plan and Stage 1 design written. Phase 0 and Phase 1 of the observability plan done (emulators API 29 to 37, one physical phone). Product milestone M1 done on an API 34 emulator (2026-10-09): accessibility services read, classified by origin, shown with a full glass-box detail screen, and checked against adb. M2 and M3 built and verified on API 34 (2026-10-09): eight capability kinds, full inventory sorted by origin, scale-tested (297 of 297 conformance checks), then device facts and network facts including the user-added certificate reading (84 of 84). The multi-version matrix is waiting for approval to download emulator images. M4 built and verified on API 34 (63 of 63 checks): the device-integrity panel judges the phone's key attestation chain offline against bundled, checksummed Google reference data. M5 built and verified on API 34 (44 of 44 checks through the real UI): a first-run review, an encrypted on-phone baseline that survives reboot and update, and a "what changed" list that waits for an acknowledgement. M6 built and verified on API 34 (79 of 79 checks, plus an instrumented accessibility pass at normal and double text size): the map of what the app cannot see, a live view that says it hears changes only while on screen, screens kept out of screenshots. Stage 1 is complete as a release candidate (0.1.0, not published). **Stage 2, the attack-path engine, is built and verified on API 34** (2026-10-09): an AND/OR graph over three-valued facts, six rules from the threat model, the smallest set of changes found exactly when it is small and labelled approximate when it is not, a what-if preview, and two questions only the person can answer; the engine's answers equal an independent evaluation on an emulator (111 of 111 checks). Next are the multi-version run, the application ID and security contact, and Stage 3 (history and tripwires). Stage 3 (history and tripwires) is built and checked on the same emulator (2026-10-09, `DESIGN.md` section 24); the Xperia background test (Q9) is still running.
