# Contributing

Declared is a glass-box tool. A change belongs here if it makes a finding easier to understand, more honest about what it cannot tell, or better tested. Read `CONCEPT.md` first, then `DESIGN.md`.

## Rules that do not bend

1. **No score, no "safe".** The app sorts by attention (note, look, look first) and says why. It never rates a phone or says a phone is clean or protected.
2. **A claim carries its evidence.** Each observation names the API it came from, how sure the app is, and what it cannot tell. A new sensor comes with the measurement that shows what a normal app can actually read.
3. **Unreadable is a result.** If Android refuses or the signal does not exist on this version, the reading is `Refused`, `Failed` or `Unavailable` with a reason, and it appears on the screen and in the blind-spot map. Never an empty list.
4. **Words are plain and never accuse.** An app "holds the accessibility power", it is never "malicious". `WordingTest` bans a list of words and checks every message the app can produce; if a new message breaks it, change the message.
5. **No new permission without a reason in the manifest comment and a change to the gate.** `verifyManifestPolicy` lists the permissions the product promised; adding one needs a discussion first. There is no internet permission and none is planned for Stage 1.
6. **The pure core stays pure.** `core` has no `android.*` import. Android access lives in `sensors-android`, behind the interfaces in `core/sensor/Sources.kt`, so everything above it is tested on the JVM with fakes.

## Build and test

See `product/README.md`. In short: the JDK 17 and the Android SDK, then from `product/`:

```bash
./gradlew :core:test :sensors-android:testDebugUnitTest
./gradlew :app:lintDebug :sensors-android:lintDebug :app:assembleDebug
```

Warnings are errors, in Kotlin and in lint.

## Adding a sensor

1. Measure first. Write the experiment into `OBSERVABILITY_PLAN.md`, run it on an emulator (and a real phone if you have one), and record the result in `FINDINGS.md`.
2. Add the source interface and fakes in `core`, the mapper that turns readings into observations, and the findings or facts.
3. Add the Android adapter in `sensors-android`, using `guarded { }` so platform exceptions become results.
4. Add tests with fakes for every outcome, including the refusals.
5. Add a conformance script under `product/tools/` that compares what the app reports with what the system itself says (`dumpsys`, `settings`, `appops`, `apksigner`, ...). A check that compares the app with its own output proves nothing.
6. Put anything you got wrong on the way into `lab/results/DEFECT_LOG.md`. The log is part of the evidence; a clean log looks like an untested project.

## Adding an attack-path rule

1. Write it in `core/paths/RuleBook.kt` as facts, ANDs and ORs, with the technique ids, the levers that cut it (each with a trade-off) and what the app cannot see on it. A fact the app could not read must be UNKNOWN with the reason, never FALSE.
2. Add a phone to `RuleBookTest` that raises it, one that must not, and one where the fact is unreadable.
3. Add the same rule, written again from the threat model and not copied from Kotlin, to `product/tools/paths_oracle.py`, and a state to `conformance_m7.py` that makes it true on the emulator. The two must agree.

## Tests that must be able to fail

A test that cannot fail is worse than none. When you add a guard (a scale limit, a banned word, a checksum), prove the test by breaking the guard on purpose once and watching it fail; `ScaleTest` and `WordingTest` show how.

## Style

Match the surrounding code. Comments say why, not what. No em or en dashes anywhere in the docs or in text the app shows.

## Licence

Apache-2.0. By contributing you agree your change is released under it. The reference data under `sensors-android/src/main/assets/attestation/` is Google's and is attributed in `NOTICE`.
