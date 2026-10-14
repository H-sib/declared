# Concept and Decision Record

Status: v0.2, decisions locked 2026-10-08, reviewed and extended the same day. The full feasibility analysis lives in the conversation that produced it; this file records what was decided and why.

## What this is

An open-source Android app that models a personal device as an attack graph. It collects honest observations about the device and its network, labels each as Observed, Declared, Inferred or Invisible, finds realistic attack paths for a chosen persona, and reports the smallest set of changes that cuts the most paths. It keeps a tamper-evident history of what changed.

It is not an antivirus, cleaner, booster, or score-based scanner.

## Ranked priorities

1. Strong CV and experience showcase.
2. Not normal or low effort.
3. Genuinely helpful, and lets users see what is actually happening while they use it.
4. Publishable on Google Play.

Overriding rule: nothing generic. Every feature must pass the uniqueness filter below before it is built.

## Uniqueness filter (five properties)

1. Reasons over combinations of settings (attack paths), not flat lists.
2. States what it cannot see (blind-spot map).
3. Keeps verifiable history (hash-chained, hardware-signed snapshots, tripwires).
4. Uses cryptographic ground truth for network checks (DNSSEC, Certificate Transparency, hash-pinned content, cross-vantage comparison).
5. Persona-driven, offline-first, no telemetry, no score, no ads.

## Locked decisions

| Decision | Choice |
|---|---|
| Flagship | Direction C, Personal Attack-Path Analyzer, in 5 independently shippable stages |
| Personas | General user (default) plus one high-risk profile (journalist/activist). Partner-surveillance persona deferred. |
| Platform | Android-first; pure core module with no Android dependencies; desktop sensor is a stretch goal |
| Publishing | GitHub v0.1 early; Play production at v1.0; built to Play policy from day one |

## Reference devices

- **Samsung Galaxy S21 FE** (Android 14, One UI 6.1): removed from the plan by the user's decision on 2026-10-08. Nothing was run on it. No claim is made about Samsung or One UI, and the project's documentation says "not verified on Samsung". Samsung is the largest Android manufacturer, so this is a stated limitation, and the session can be added back later if the user changes their mind.
- **Sony Xperia 10 II (au/KDDI model SOV43)**, Android 12 (API 31): a secondary phone that holds some data, end-of-support with a security patch of 2022-07-01, verified boot green and a locked bootloader. It is the second OEM data point and the real-world unpatched-device example. It gets read-only sessions plus the background test; fingerprint, lock-screen and test-CA experiments need explicit per-step consent.

Everything else comes from stock emulator images, and no claim is made about other manufacturers' skins.

## Stages

1. **Glass-box foundation (GitHub v0.1):** sensors for special-access grants, app provenance, network configuration; trust tiers (platform, system, store, sideloaded); first-run baseline review; blind-spot map; live event view.
2. **Attack-path engine:** graph model including defensive controls as nodes, minimum fix set, what-if mode. Start the Play closed test here.
3. **Ledger and tripwires:** hash-chained posture history, Keystore tripwires, drift timeline. Depends on background snapshots surviving OEM power management.
4. **Network integrity prober:** DNS, TLS, CT and hash-pinned content probes. First stage to cut if time runs short.
5. **Evaluation and write-up:** campaign replay, benchmarks, optional small user study; Play v1.0.

## Glass-box principle

- Every finding states the API it came from, what it means in plain language, its confidence, and what the app cannot tell.
- Live event view while the app is open (network, DNS, proxy, special-access and app changes). No permanent foreground service.
- The app logs its own network requests and exposes that log to the user.
- "Live" means observable changes only; the app never claims to see other apps' hidden behavior.
- Advice is safe by construction: it never recommends rooting or flashing, never recommends a factory reset without a data-loss warning, and always states the trade-off when a setting may be one the user depends on (for example an accessibility service).

## Play constraints (verified 2026-10-08)

- New apps must target API 36 (since 2026-08-31). `ACCESS_LOCAL_NETWORK` becomes mandatory only when targeting API 37 (expected around 2027), so build the LAN module to handle that prompt.
- New personal developer accounts (created after 2023-11-13) need a closed test with 12+ testers for 14 continuous days before production access. Plan for 15-20 testers.
- Foreground services need per-type Play declarations; avoid a permanent monitoring service.
- Avoid `QUERY_ALL_PACKAGES` and `VpnService` in v1.
- Developer verification starts 2026-09-30 in four countries, global in 2027. Register early.
- Marketing must be honest: no "protects you from hackers" claims.

## SHA-256 / SHA-3: where it is load-bearing

1. Tamper-evident posture log (hash chain or Merkle tree with signed checkpoints).
2. Network probes: TLS SPKI fingerprints across vantage points, hash-pinned content, CT Merkle inclusion proofs.
3. Signing-certificate SHA-256 as app identity.
4. Hash-prefix privacy lookups.
5. Signed rule packs with a hash manifest.
6. Published SHA-256 checksums for every release APK, and the project's own signing-certificate fingerprint, so users can detect clones.

Avoid decorative uses (file-hash "integrity monitoring" without a trusted reference). SHA-2 vs SHA-3 on mobile hardware is an engineering benchmark, not a security-strength claim.

## Claims that must be verified by experiment before relying on them

- Keystore tripwires (key invalidation on fingerprint enrollment or lock-screen removal): measured once on an API 34 emulator (see `FINDINGS.md`); not verified on hardware or other Android versions.
- Enumerating dangerous-capability apps through `<queries>` intents without `QUERY_ALL_PACKAGES`.
- Unprivileged ICMP "ping sockets" for hop-count probes.
- Whether Android's Keystore supports SHA-3 digests.
- Per-app special-access reads through app-ops APIs.
- Wi-Fi vs cellular network binding behavior.
- Whether periodic background snapshots survive the phone's power management (measured on one device, the Xperia, and only as an observation about that device).

See `OBSERVABILITY_PLAN.md` for how each is tested.

## Quality bar

- At least three test layers: engine unit tests, sensor contract tests with fakes, and emulator instrumentation or lab integration tests.
- A defect log: every real defect found by using the running app is recorded (what broke, why the first fix was insufficient if it was, how the fix was confirmed).
- A hypothesis log: every hypothesis in the observability plan ends as confirmed, refuted or partly confirmed, and refutations are reported.
- An accessible interface (screen-reader labels, large-text support), because explanation is the product.
- At least 15 external testers, which also satisfies Play's closed-test rule, with their feedback documented.
- Zero telemetry, and an encrypted-at-rest local store (see `THREAT_MODEL.md`, section 9).

## Decisions (updated 2026-10-08)

- **Open-source license:** decided, Apache-2.0 (permissive, patent grant, accepted by F-Droid and Play).
- **Project name:** decided, **Declared**. The application ID string is still open because it is permanent once published; it needs the user's GitHub username or a domain they own. Until then the build uses the placeholder `io.github.placeholder.declared` and refuses to build a release.
- **UI toolkit:** decided, Jetpack Compose with Material 3.
- **Security contact:** open. A `SECURITY.md` with a reporting address is needed before the first public release.

## Next step

Phases 0 and 1 of `OBSERVABILITY_PLAN.md` are done and the measured results are in `FINDINGS.md`. The Stage 1 design is in `DESIGN.md`; milestone M1 is built in `product/` (see `DESIGN.md` section 17). Name, license and UI toolkit are decided. The application ID is still a placeholder that blocks any release build. Next is M2. The Xperia background result only affects Stage 3, so it does not block Stage 1.

## Ethics guardrails

- Probe only networks the user marks as their own; public Wi-Fi gets passive and gateway-level checks only.
- No credential guessing, no exploit code, no attacks on third parties.
- Adversary-lab testing on the author's own equipment only.
