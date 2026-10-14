"""Emulator-only experiment for signals K6 and K7 (Keystore tripwires). Standard library only.

Question: if an app creates a Keystore key that requires user authentication, does the platform destroy or
invalidate that key when (K6) another fingerprint is enrolled, or (K7) the screen lock is changed or removed?
If so, the app gets a cryptographic tripwire for "someone added a fingerprint / removed the lock".

    python tools/tripwire_emulator.py --serial emulator-5554 --pin 1234

Refuses to run against anything that is not an emulator. Each step records the system's own view (enrolled
fingerprints, whether a secure lock exists) next to what the probe reported about the keys.
"""
import argparse
import csv
import datetime as dt
import json
import os
import re
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import emu_fingerprint as ef  # noqa: E402
from runner import adb, shell, LAB  # noqa: E402

PROBE = "lab.probe.q"
KEYS = ("lab_trip_bio", "lab_trip_any")
CSV_PATH = os.path.join(LAB, "results", "tripwire_emulator.csv")
FIELDS = ["time", "api", "step", "enrolled_fingerprints", "secure_lock", "key_biometric_only", "key_biometric_or_credential",
          "biometric_state", "notes"]


# ---- pure helpers (unit tested) ----

def key_states(reading_value):
    """Extract (bio_state, any_state, secure, biometric_state) from the tripwire reading's value dict."""
    keys = (reading_value or {}).get("keys", {}) or {}
    return keys.get(KEYS[0]), keys.get(KEYS[1]), (reading_value or {}).get("deviceSecure"), (reading_value or {}).get("biometricStrongState")


def classify(state):
    """Collapse a key-state string into one of VALID, INVALIDATED, MISSING, CREATED, FAILED, OTHER, None."""
    if state is None:
        return None
    if state in ("VALID", "INVALIDATED", "MISSING", "CREATED"):
        return state
    if state.startswith("CREATE_FAILED"):
        return "FAILED"
    return "OTHER"


def row(api, step, enrolled, secure_lock, bio, any_, biometric_state, notes=""):
    return {"time": dt.datetime.now().isoformat(timespec="seconds"), "api": api, "step": step,
            "enrolled_fingerprints": enrolled, "secure_lock": secure_lock, "key_biometric_only": bio,
            "key_biometric_or_credential": any_, "biometric_state": biometric_state, "notes": notes}


def append_csv(r, path=CSV_PATH):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    new = not os.path.exists(path)
    with open(path, "a", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        if new:
            w.writeheader()
        w.writerow(r)


# ---- device helpers ----

def parse_keyguard_showing(dumpsys_window_policy):
    """True if 'dumpsys window policy' says the keyguard (lock screen) is showing. Pure, unit tested."""
    # The spelling differs between Android versions (older ones print mShowingLockscreen).
    return bool(re.search(r"\b(?:mIsShowing|showing|keyguardShowing|mShowingLockscreen|isKeyguardShowing)=true", dumpsys_window_policy or ""))


def keyguard_showing(serial):
    return parse_keyguard_showing(shell(serial, "dumpsys window policy", check=False))


def unlock(serial, pin, force=False):
    """Unlock ONLY if the lock screen is really showing. An earlier version typed the PIN and Enter into whatever
    app was in the foreground; the Enter key pressed the probe's first button and opened a permission dialog that
    then blocked every later request (defect D-009).

    force=True is for the moment right after a reboot with a lock set, when the lock screen is certainly up even if
    this Android version words the keyguard state differently."""
    shell(serial, "input keyevent KEYCODE_WAKEUP", check=False)
    time.sleep(1)
    if not force and not keyguard_showing(serial):
        return False
    # Retry and verify: on some versions the PIN pad appears late after the swipe, so the first keystrokes are lost
    # and the phone stays locked (seen on Android 15 right after a reboot).
    for attempt in range(4):
        shell(serial, "input swipe 540 2000 540 700 300", check=False)
        time.sleep(2 + attempt)
        shell(serial, f"input text {pin}", check=False)
        time.sleep(1)
        shell(serial, "input keyevent 66", check=False)
        time.sleep(2)
        if not keyguard_showing(serial):
            return True
    return True


def secure_lock_present(serial):
    out = shell(serial, "locksettings get-disabled", check=False)
    return "false" in out.lower()


def diagnose(serial, label):
    """Capture why a request got no answer: a screenshot and the foreground activity, saved under results/raw."""
    safe = re.sub(r"[^A-Za-z0-9_-]+", "_", label)
    shot = os.path.join(LAB, "results", "raw", f"tripwire-fail-{safe}.png")
    try:
        import ui
        ui.screenshot(serial, shot)
    except Exception as e:  # diagnostics must never break the experiment
        shot = f"(screenshot failed: {e})"
    top = shell(serial, "dumpsys activity activities | grep -m1 topResumedActivity", check=False).strip()
    return f"no answer; top={top[:90]}; screenshot={os.path.basename(shot)}"


CURRENT = {"pin": "1234"}  # the PIN currently set on the emulator; updated when the experiment changes it


def _probe_once(serial, mode, label, timeout):
    run_id = uuid.uuid4().hex[:10]
    if keyguard_showing(serial):
        unlock(serial, CURRENT["pin"], force=True)  # a locked phone cannot run the probe
    # Keep the screen awake, start from a neutral screen and a fresh process, so a sleeping display, a stray dialog
    # or a stale worker can never silently swallow the request.
    shell(serial, "input keyevent KEYCODE_WAKEUP", check=False)
    shell(serial, "input keyevent KEYCODE_HOME", check=False)
    shell(serial, f"am force-stop {PROBE}", check=False)
    time.sleep(1)
    shell(serial, f"am start -n {PROBE}/lab.probe.MainActivity --es tripwire {mode} --es label '{label}' --es run_id {run_id}", check=False)
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(1.5)
        raw = adb(serial, "exec-out", "run-as", PROBE, "cat", "files/latest.json", check=False)
        try:
            doc = json.loads(raw)
        except ValueError:
            continue
        if doc.get("meta", {}).get("runId") == run_id:
            reading = doc["readings"][0]
            return {"code": reading["code"], "note": reading.get("note"), **(reading.get("value") or {})}
    return None


def probe_tripwire(serial, mode, label, timeout=25):
    """Ask the probe to create or check the tripwire keys; return the reading value dict (or None after one retry)."""
    result = _probe_once(serial, mode, label, timeout)
    if result is None:
        print(f"    (no answer for '{label}': {diagnose(serial, label)}; retrying once)")
        result = _probe_once(serial, mode, label, timeout)
    return result


def wait_for_boot(serial, timeout=300):
    adb(serial, "wait-for-device", timeout=timeout, check=False)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if shell(serial, "getprop sys.boot_completed", check=False).strip() == "1":
            if "package:" in shell(serial, "pm path android", check=False) and "error" not in shell(serial, "cmd activity get-current-user", check=False).lower():
                time.sleep(10)
                return True
        time.sleep(4)
    return False


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", default="emulator-5554")
    p.add_argument("--pin", default="1234")
    p.add_argument("--api", default=None, help="API level label for the CSV (read from the device if omitted)")
    args = p.parse_args(argv)
    serial = args.serial
    if "emulator" not in serial:
        print("Refusing to run: this experiment is for emulators only.")
        return 2
    api = args.api or shell(serial, "getprop ro.build.version.sdk")
    pin = args.pin
    CURRENT["pin"] = pin
    results = []

    def record(step, tv, notes=""):
        bio, any_, secure, bstate = key_states(tv)
        # 'secure_lock' is KeyguardManager.isDeviceSecure() as the APP sees it (what a product would use). The system
        # tool 'locksettings get-disabled' was misparsed on Android 12 and 13 in earlier rows, so it is only a fallback.
        lock_value = secure if secure is not None else secure_lock_present(serial)
        r = row(api, step, ef.enrolled_count(serial), lock_value, bio, any_, bstate, notes)
        append_csv(r)
        results.append(r)
        print(f"{step:46s} fingerprints={r['enrolled_fingerprints']} lock={str(r['secure_lock']):5s} "
              f"biometric-only={r['key_biometric_only']}  biometric-or-credential={r['key_biometric_or_credential']}  {notes}")

    def create_and_check(step_prefix):
        record(f"{step_prefix}: create keys", probe_tripwire(serial, "create", step_prefix))
        record(f"{step_prefix}: check right after creating", probe_tripwire(serial, "check", step_prefix))

    print(f"== Tripwire experiment on {serial}, API {api} ==")
    # Emulator only: keep the display on for the whole run so a screen timeout cannot interfere with the steps.
    shell(serial, "svc power stayon true", check=False)
    shell(serial, "settings put system screen_off_timeout 1800000", check=False)
    shell(serial, f"locksettings set-pin {pin}", check=False)  # harmless if already set
    unlock(serial, pin)
    if (ef.enrolled_count(serial) or 0) < 1:
        print("-- enrolling fingerprint 1")
        if not ef.enroll(serial, pin, 1, verbose=False):
            print("could not enroll fingerprint 1; aborting. " + diagnose(serial, f"api{api}-enroll1"))
            return 1

    # K6: biometric enrollment
    create_and_check("A baseline (1 fingerprint)")
    record("A control: check again", probe_tripwire(serial, "check", "A-control"))
    print("-- enrolling fingerprint 2 (step trace follows, to explain any failure)")
    ok2 = ef.enroll(serial, pin, 2, verbose=True)
    if not ok2:
        print("    second enrollment failed: " + diagnose(serial, f"api{api}-enroll2"))
    record("K6 after enrolling fingerprint 2", probe_tripwire(serial, "check", "K6"), "" if ok2 else "(second enrollment did not register)")

    # fresh keys for the survival and lock tests
    create_and_check("B fresh keys (2 fingerprints)")

    # survival: app update
    out = adb(serial, "install", "-r", "-t", os.path.join(LAB, "probe", "build", "outputs", "apk", "queries", "debug", "probe-queries-debug.apk"), check=False)
    unlock(serial, pin)
    record("S after reinstalling the app (-r)", probe_tripwire(serial, "check", "S-update"), "install: " + out.strip()[:30])

    # survival: reboot
    print("-- rebooting the emulator")
    adb(serial, "reboot", check=False)
    time.sleep(15)
    booted = wait_for_boot(serial)
    unlock(serial, pin, force=True)
    record("S after reboot (unlocked)", probe_tripwire(serial, "check", "S-reboot"), "" if booted else "(boot wait timed out)")

    # K7: lock changes
    new_pin = "5678"
    shell(serial, f"locksettings set-pin --old {pin} {new_pin}", check=False)
    CURRENT["pin"] = new_pin
    unlock(serial, new_pin)
    record("K7a after CHANGING the PIN", probe_tripwire(serial, "check", "K7a"))
    create_and_check("C fresh keys (new PIN)")
    shell(serial, f"locksettings clear --old {new_pin}", check=False)
    time.sleep(2)
    record("K7b after REMOVING the lock", probe_tripwire(serial, "check", "K7b"))
    record("K7c create keys with no lock (can it?)", probe_tripwire(serial, "create", "K7c"))

    print(f"\nrows written to {os.path.relpath(CSV_PATH, LAB)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
