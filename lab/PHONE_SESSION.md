# Physical phone session (Xperia only)

Status 2026-10-08: the Samsung Galaxy S21 FE was removed from the plan by the user's decision, so there is one physical phone: the Sony Xperia 10 II (au SOV43, Android 12). Read `../OBSERVABILITY_PLAN.md` section 10 for the safety rules. Nothing here reads personal data: the probe reads settings and package metadata only, and the fixtures ignore every event.

## Rules for this phone

It holds some of the user's data, so only read-only measurements and the background test run on it. The test certificate, the fingerprint test and lock removal were skipped by the user's decision ("skip anything harmful to the device").

## What has been done

Wireless debugging paired; lab apps installed; baseline and fixtures-enabled runs (verified, 0 mismatches); Wi-Fi visibility with location granted to the probe only, then revoked; hardware attestation chain verified offline; the fixtures and second probe uninstalled after the user deactivated the fixture device admin; the heartbeat job (15-minute, persisted) left running for the background test in `lab.probe.q`.

## What is left on this phone

1. **Leave it alone** for 24 to 48 hours: unplugged, screen off, lying still, Wi-Fi on, probe never opened or force-stopped. Protocol and timeline: `results/BACKGROUND_TEST.md`.
2. **Read-out** (DONE 2026-10-09 about 17:55, result in `results/BACKGROUND_TEST.md`; was planned for no earlier than 2026-10-09 about 17:30): the user turns Wireless debugging back on (the PC stays paired), then `adb exec-out run-as lab.probe.q cat files/heartbeat.log` and the battery history are read.
3. **Cleanup:** uninstall `lab.probe.q`, turn Wireless debugging off, revoke USB debugging authorizations, optionally turn Developer options off.

## The session history (what was originally considered, for the record)

| Step | Outcome |
|---|---|
| Settings snapshot, install, baseline runs | Done |
| Scripted fixture enablement, runs | Done, verified |
| Real-UI accessibility enablement (restricted-settings gating) | Not applicable on Android 12 (that gating starts with Android 13) |
| Background test | Running |
| Test certificate, fingerprint tripwire, lock-removal tripwire | **Skipped by the user's decision** |
| Samsung-specific measurements | **Cancelled: Samsung excluded from the plan** |
