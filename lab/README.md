# Observability lab (throwaway test tooling)

Not the product. This folder holds the tooling for `../OBSERVABILITY_PLAN.md`: benign fixture apps, a probe app, and a runner that checks what the probe reports against what the system says is true.

## Layout

| Path | What it is |
|---|---|
| `fixtures/` | Benign "capability mimic" apps (accessibility, notification listener, device admin, input method, sensitive permissions, hidden launcher), and a seventh, `shadow`, with the *shape* of an app made to watch someone: a screen-reading service, a device administrator, no launcher icon and text-message, location and microphone requests. They declare capabilities and ignore every event. |
| `probe/` | The probe app. Two flavors: `queries` (uses `<queries>` intents, no `QUERY_ALL_PACKAGES`) and `allpkgs` (holds `QUERY_ALL_PACKAGES`, the baseline). Pure DER parser for key attestation is unit tested on the JVM. |
| `tools/runner.py` | Drives adb: install, set states, run the probe, read ground truth, compare, write `results/observability_results.csv`. |
| `tools/test_runner.py` | Unit tests for the runner's pure functions (`python tools/test_runner.py`). |
| `results/` | CSV output. Raw per-run JSON goes in `results/raw/` (not committed). |

## Build (after the toolchain is installed)

```
gradle :probe:assembleQueriesDebug :probe:assembleAllpkgsDebug :fixtures:assembleDebug
gradle :probe:testQueriesDebugUnitTest
```

## Known environment quirk (Windows)

If Gradle fails with `Unable to establish loopback connection`, the cause is an 8.3 short `TEMP` path
(`C:\Users\NAME~1\...`): this JDK builds its selector pipe from a Unix-domain socket there and Windows rejects the
short form. `tools/env.ps1` sets `TEMP` and `TMP` to a normal long path for the session, which fixes it.

## Typical session

```
python tools/runner.py devices
python tools/runner.py install --serial <serial>
python tools/runner.py setup --serial <serial> --enable a11y,notif,admin,ime,perms
python tools/runner.py run --serial <serial> --flavor q --label "all-fixtures-adb"
python tools/runner.py restore --serial <serial>
```

## Safety

- Fixtures are benign and never read events, notifications or typed text.
- Do not run `restore`-less sessions on a daily phone: it removes fixtures, probe and fixture grants.
- Redacted mode (`--redact`) is for other people's devices: it hashes package names and identifiers.
- See section 10 of `../OBSERVABILITY_PLAN.md` before touching fingerprints or lock screens on a physical phone.
