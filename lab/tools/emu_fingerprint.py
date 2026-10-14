"""Enrolls a virtual fingerprint on an emulator by driving the Settings app. Emulator only. Standard library only.

    python tools/emu_fingerprint.py --serial emulator-5554 --pin 1234 --finger 1

The emulator exposes a virtual fingerprint sensor: 'adb emu finger touch <id>' simulates a finger. The enrollment
screens are navigated with ui.py. Success is judged by the system itself (the enrolled count reported by
'dumpsys fingerprint'), never by what the screens say.
"""
import argparse
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui  # noqa: E402
from runner import adb, shell  # noqa: E402

ADB = os.environ.get("ADB", "adb")

# Buttons to press, in priority order. Only clickable elements are tapped.
BUTTONS = [r"^(i )?agree$", r"^more$", r"^next$", r"^start$", r"^continue$", r"^done$", r"^ok$",
           # Newer Android versions open the fingerprint list when one is already enrolled; the second enrollment
           # then starts from an "Add fingerprint" button instead of going straight to the setup screens.
           r"^add (a )?fingerprint$"]
# These must match only the screens that are actually waiting for a finger. An earlier, looser pattern also matched
# the intro screen's description ("Just touch the fingerprint sensor to unlock your phone"), so on Android 10 the
# driver kept sending touches and never pressed NEXT (defect D-011).
SENSOR_PROMPT = r"touch the sensor|^lift\b|lift,? then touch|keep touching|put your finger|place your finger|touch the power button"
PIN_PROMPT = r"enter your pin|confirm your pin|re-?enter your pin|enter pin"


def parse_enrolled_count(dumpsys_text):
    """Return the enrolled-fingerprint count from 'dumpsys fingerprint', or None if it cannot be read."""
    m = re.search(r'"prints":\[\{"id":\d+,"count":(\d+)', dumpsys_text or "")
    return int(m.group(1)) if m else None


def enrolled_count(serial):
    return parse_enrolled_count(adb(serial, "shell", "dumpsys fingerprint", check=False))


def finger_touch(serial, finger_id):
    subprocess.run([ADB, "-s", serial, "emu", "finger", "touch", str(finger_id)], capture_output=True, timeout=30)


def classify_screen(nodes):
    """What the current screen needs: 'pin', 'sensor', 'button:<regex>' or 'unknown'. Pure, unit tested."""
    if ui.any_text(nodes, PIN_PROMPT) or any(n["rid"].endswith(("password_entry", "pinEntry", "pin_entry")) for n in nodes):
        return "pin"
    if ui.any_text(nodes, SENSOR_PROMPT):
        return "sensor"
    for pat in BUTTONS:
        if ui.find(nodes, pat, clickable_only=True):
            return "button:" + pat
    # Android 10 does not mark its setup buttons as clickable in the dump, so fall back to matching by text alone.
    for pat in BUTTONS:
        if ui.find(nodes, pat, clickable_only=False):
            return "button:" + pat
    return "unknown"


def enroll_launch_command():
    """The command that opens the enrollment screens in a FRESH task. Pure, unit tested.

    -S force-stops Settings first and the flags are NEW_TASK | CLEAR_TASK. Without them, Android 16 and 17 resurface the
    stale Settings task left behind by the previous enrollment ("Activity not started, its current task has been
    brought to the front") and the second enrollment screen never appears (defect D-012)."""
    return "am start -S -f 0x10008000 -a android.settings.FINGERPRINT_ENROLL"


def enroll(serial, pin="1234", finger_id=1, max_steps=90, verbose=True):
    """Enroll one fingerprint. Returns True when the system's enrolled count has increased."""
    before = enrolled_count(serial)
    shell(serial, "input keyevent KEYCODE_WAKEUP", check=False)
    shell(serial, "input keyevent KEYCODE_HOME", check=False)
    shell(serial, "am force-stop com.android.settings", check=False)
    time.sleep(1)
    shell(serial, enroll_launch_command(), check=False)
    for step in range(max_steps):
        time.sleep(1.0)
        now = enrolled_count(serial)
        if before is not None and now is not None and now > before:
            if verbose:
                print(f"  enrolled count {before} -> {now} after {step} steps")
            # Tidy up: dismiss any 'Done' screen and discard the Settings task so the next enrollment starts clean.
            ui.tap_text(serial, r"^done$", clickable_only=True)
            shell(serial, "input keyevent KEYCODE_HOME", check=False)
            shell(serial, "am force-stop com.android.settings", check=False)
            return True
        nodes = ui.dump(serial)
        kind = classify_screen(nodes)
        if verbose:
            shown = [(n["text"] or n["desc"])[:34] for n in nodes if (n["text"] or n["desc"])][:4]
            print(f"  step {step:2d} {kind:22s} {shown}")
        if kind == "pin":
            shell(serial, f"input text {pin}", check=False)
            shell(serial, "input keyevent 66", check=False)
        elif kind == "sensor":
            finger_touch(serial, finger_id)
        elif kind.startswith("button:"):
            pat = kind.split(":", 1)[1]
            if not ui.tap_text(serial, pat, nodes, clickable_only=True):
                ui.tap_text(serial, pat, nodes, clickable_only=False)
        else:
            finger_touch(serial, finger_id)  # some screens give no text; a touch is harmless and often needed
    return False


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", default="emulator-5554")
    p.add_argument("--pin", default="1234")
    p.add_argument("--finger", type=int, default=1)
    args = p.parse_args(argv)
    if "emulator" not in args.serial:
        print("Refusing to run: this script is for emulators only.")
        return 2
    ok = enroll(args.serial, args.pin, args.finger)
    print("ENROLLED" if ok else "FAILED to enroll")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
