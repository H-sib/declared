"""Watcher timing experiment (signal P11, question Q7). Emulator only. Standard library only.

Question: while the probe's live watcher is running, which security-relevant changes does it hear about, how fast,
and under which conditions (app in front, in the background, screen off, deep idle, process killed)?

For each event the harness reads the DEVICE clock just before and just after issuing the change, then reads the
probe's own watch log (timestamped on the same device clock) and records the first matching line. A change that
never shows up is recorded as a miss, which is the result that matters for deciding what needs periodic diffing.

    python tools/watcher_timing.py --serial emulator-5554
"""
import argparse
import csv
import datetime as dt
import os
import re
import statistics
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from runner import adb, shell, LAB  # noqa: E402

PROBE = "lab.probe.q"
CSV_PATH = os.path.join(LAB, "results", "watcher_timing.csv")
FIELDS = ["api", "condition", "event", "rep", "observed", "latency_ms_from_start", "latency_ms_from_end", "matched_line", "notes"]
APK_DIR = os.path.join(LAB, "fixtures", "build", "outputs", "apk")

A11Y = "lab.fixture.a11y/lab.fixture.FixtureA11yService"
NOTIF = "lab.fixture.notif/lab.fixture.FixtureNotifListener"
IME = "lab.fixture.ime/lab.fixture.FixtureIme"


def apk(role):
    return os.path.join(APK_DIR, role, "debug", f"fixtures-{role}-debug.apk")


# Each event: name, optional prepare steps, the change (do), the revert (undo), and a regex for the watch-log line that
# proves the watcher heard about it. A step is ("sh", "device shell command") or ("adb", [host adb args]).
EVENTS = [
    {"name": "accessibility service enabled", "pre": [], "expect": r"setting-changed secure:enabled_accessibility_services",
     "do": [("sh", f"settings put secure enabled_accessibility_services {A11Y}")],
     "undo": [("sh", "settings delete secure enabled_accessibility_services")]},
    {"name": "notification listener allowed", "pre": [], "expect": r"setting-changed secure:enabled_notification_listeners",
     "do": [("sh", f"cmd notification allow_listener {NOTIF}")],
     "undo": [("sh", f"cmd notification disallow_listener {NOTIF}")]},
    {"name": "default keyboard changed", "pre": [("sh", f"ime enable {IME}")], "expect": r"setting-changed secure:default_input_method",
     "do": [("sh", f"ime set {IME}")], "undo": [("sh", "ime reset"), ("sh", f"ime disable {IME}")]},
    {"name": "private DNS mode changed", "pre": [], "expect": r"setting-changed global:private_dns_mode",
     "do": [("sh", "settings put global private_dns_mode hostname")], "undo": [("sh", "settings put global private_dns_mode off")]},
    {"name": "global HTTP proxy set", "pre": [], "expect": r"setting-changed global:http_proxy",
     "do": [("sh", "settings put global http_proxy 10.0.2.2:8888")], "undo": [("sh", "settings put global http_proxy :0")]},
    {"name": "developer options toggled", "pre": [("sh", "settings put global development_settings_enabled 1")],
     "expect": r"setting-changed global:development_settings_enabled",
     "do": [("sh", "settings put global development_settings_enabled 0")], "undo": [("sh", "settings put global development_settings_enabled 1")]},
    {"name": "visible app installed (has launcher icon)", "pre": [("adb", ["uninstall", "lab.fixture.a11y"])], "expect": r"package PACKAGE_ADDED lab.fixture.a11y",
     "do": [("adb", ["install", "-r", "-t", apk("a11y")])], "undo": []},
    {"name": "visible app removed", "pre": [("adb", ["install", "-r", "-t", apk("a11y")])], "expect": r"package PACKAGE_REMOVED lab.fixture.a11y",
     "do": [("adb", ["uninstall", "lab.fixture.a11y"])], "undo": [("adb", ["install", "-r", "-t", apk("a11y")])]},
    {"name": "hidden-icon app installed (no launcher, no capability)", "pre": [("adb", ["uninstall", "lab.fixture.hidden"])],
     "expect": r"package PACKAGE_ADDED lab.fixture.hidden",
     "do": [("adb", ["install", "-r", "-t", apk("hidden")])], "undo": []},
    {"name": "app disabled", "pre": [], "expect": r"package PACKAGE_CHANGED lab.fixture.perms",
     "do": [("sh", "pm disable-user --user 0 lab.fixture.perms")], "undo": [("sh", "pm enable lab.fixture.perms")]},
    {"name": "Wi-Fi turned off", "pre": [("sh", "svc wifi enable")], "expect": r"network ",
     "do": [("sh", "svc wifi disable")], "undo": [("sh", "svc wifi enable")]},
]


# ---- pure helpers (unit tested) ----

def parse_watch_log(text):
    """Parse the watch log into [(timestamp_ms, event_text)]. Malformed lines are skipped."""
    out = []
    for line in (text or "").splitlines():
        if "\t" not in line:
            continue
        ts, event = line.split("\t", 1)
        if ts.strip().isdigit():
            out.append((int(ts), event.strip()))
    return out


def first_match(events, pattern, t0, slack_ms=50):
    """First event at or after t0 (minus clock slack) whose text matches the regex, or None."""
    rx = re.compile(pattern)
    for ts, text in events:
        if ts >= t0 - slack_ms and rx.search(text):
            return ts, text
    return None


def summarize(rows):
    """Group result rows by (condition, event): observed count, repetitions, median and max latency in ms."""
    groups = {}
    for r in rows:
        groups.setdefault((r["condition"], r["event"]), []).append(r)
    out = []
    for (cond, event), rs in groups.items():
        lat = [int(r["latency_ms_from_start"]) for r in rs if str(r["observed"]) == "True" and r["latency_ms_from_start"] not in ("", None)]
        out.append({"condition": cond, "event": event, "observed": sum(1 for r in rs if str(r["observed"]) == "True"), "reps": len(rs),
                    "median_ms": int(statistics.median(lat)) if lat else None, "max_ms": max(lat) if lat else None})
    return out


def to_markdown(summary):
    conds = []
    for s in summary:
        if s["condition"] not in conds:
            conds.append(s["condition"])
    events = []
    for s in summary:
        if s["event"] not in events:
            events.append(s["event"])
    by = {(s["condition"], s["event"]): s for s in summary}
    lines = ["| Event | " + " | ".join(conds) + " |", "|---|" + "---|" * len(conds)]
    for e in events:
        cells = []
        for c in conds:
            s = by.get((c, e))
            if not s:
                cells.append("n/a")
            elif s["observed"] == 0:
                cells.append(f"**missed** (0/{s['reps']})")
            else:
                cells.append(f"{s['observed']}/{s['reps']}, median {s['median_ms']} ms, max {s['max_ms']} ms")
        lines.append(f"| {e} | " + " | ".join(cells) + " |")
    return "\n".join(lines)


# ---- device helpers ----

def device_ms(serial):
    return int(shell(serial, "date +%s%3N", check=False).strip() or 0)


def run_step(serial, step):
    kind, arg = step
    if kind == "sh":
        shell(serial, arg, check=False, timeout=90)
    else:
        adb(serial, *arg, check=False, timeout=180)


def read_log(serial):
    return adb(serial, "exec-out", "run-as", PROBE, "cat", "files/watch.log", check=False)


def start_watcher(serial):
    shell(serial, f"run-as {PROBE} rm -f files/watch.log", check=False)
    shell(serial, "input keyevent KEYCODE_WAKEUP", check=False)
    shell(serial, f"am force-stop {PROBE}", check=False)
    time.sleep(1)
    shell(serial, f"am start -n {PROBE}/lab.probe.MainActivity --ez watch true", check=False)
    deadline = time.time() + 20
    while time.time() < deadline:
        if "watcher started" in read_log(serial):
            return True
        time.sleep(1)
    return False


def measure(serial, event, wait_s):
    for step in event["pre"]:
        run_step(serial, step)
    time.sleep(2.5)
    t0 = device_ms(serial)
    for step in event["do"]:
        run_step(serial, step)
    t1 = device_ms(serial)
    found = None
    deadline = time.time() + wait_s
    while time.time() < deadline:
        found = first_match(parse_watch_log(read_log(serial)), event["expect"], t0)
        if found:
            break
        time.sleep(1)
    for step in event["undo"]:
        run_step(serial, step)
    time.sleep(1.5)
    return t0, t1, found


def process_state(serial):
    """What the system says about the probe process: pid, importance bucket and whether it is frozen."""
    pid = shell(serial, f"pidof {PROBE}", check=False).strip() or "none"
    frag = shell(serial, f"dumpsys activity processes | grep -A14 'ProcessRecord.*{PROBE}'", check=False)
    tags = []
    for pattern, tag in ((r"\bfrozen=true|isFrozen=true|mFrozen=true", "FROZEN"), (r"\bcch\b|cached", "cached"),
                         (r"\bfg\b|top", "top/foreground-ish"), (r"\bsvc\b|service", "service")):
        if re.search(pattern, frag):
            tags.append(tag)
    return f"pid={pid} [{','.join(tags) or 'unknown'}]", frag


def condition_setup(serial, name):
    """Put the device into a condition. Returns a note describing the state actually reached.

    The first version of this function forced the idle flag and put the screen to sleep without ever moving the app
    to the background, so 'screen off' and 'deep idle' were really 'app in front' variants (defect D-013). Each
    condition now starts from the app being sent to the background, and the state is read back from the system."""
    if name == "app in front":
        shell(serial, "svc power stayon true", check=False)
        shell(serial, "input keyevent KEYCODE_WAKEUP", check=False)
        shell(serial, f"am start -n {PROBE}/lab.probe.MainActivity", check=False)
        time.sleep(2)
        return "foreground; " + process_state(serial)[0]
    if name == "app in background":
        shell(serial, "input keyevent KEYCODE_HOME", check=False)
        time.sleep(30)  # let the process settle into the cached state before measuring
        return "home screen, settled 30 s; " + process_state(serial)[0]
    if name == "screen off, app in background":
        shell(serial, "input keyevent KEYCODE_HOME", check=False)
        shell(serial, "svc power stayon false", check=False)
        shell(serial, "input keyevent KEYCODE_SLEEP", check=False)
        time.sleep(20)
        wake = shell(serial, "dumpsys power | grep -m1 mWakefulness=", check=False).strip()
        return f"{wake}; " + process_state(serial)[0]
    if name == "deep idle (screen off, unplugged)":
        shell(serial, "input keyevent KEYCODE_HOME", check=False)
        shell(serial, "svc power stayon false", check=False)
        shell(serial, "input keyevent KEYCODE_SLEEP", check=False)
        time.sleep(5)
        shell(serial, "dumpsys battery unplug", check=False)
        shell(serial, "dumpsys deviceidle force-idle", check=False)
        time.sleep(8)
        idle = shell(serial, "dumpsys deviceidle get deep", check=False).strip()
        return f"deviceidle={idle}; " + process_state(serial)[0]
    if name == "process killed":
        shell(serial, f"am force-stop {PROBE}", check=False)
        time.sleep(2)
        return process_state(serial)[0]
    return ""


def condition_teardown(serial):
    shell(serial, "dumpsys deviceidle unforce", check=False)
    shell(serial, "dumpsys battery reset", check=False)
    shell(serial, "svc power stayon true", check=False)
    shell(serial, "input keyevent KEYCODE_WAKEUP", check=False)


CONDITIONS = [("app in front", 3, 10), ("app in background", 2, 20), ("screen off, app in background", 2, 20),
              ("deep idle (screen off, unplugged)", 2, 25), ("process killed", 1, 8)]


def slug(text):
    return re.sub(r"[^A-Za-z0-9]+", "-", text).strip("-").lower()


def save_raw(serial, api, cond):
    """Keep the complete watch log and the process state for each condition so odd results can be explained."""
    base = os.path.join(LAB, "results", "raw", f"watch-api{api}-{slug(cond)}")
    with open(base + ".log", "w", encoding="utf-8") as f:
        f.write(read_log(serial))
    with open(base + ".state.txt", "w", encoding="utf-8") as f:
        f.write(process_state(serial)[1])


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", default="emulator-5554")
    p.add_argument("--api", default=None)
    args = p.parse_args(argv)
    serial = args.serial
    if "emulator" not in serial:
        print("Refusing to run: this experiment is for emulators only.")
        return 2
    api = args.api or shell(serial, "getprop ro.build.version.sdk")
    rows = []
    os.makedirs(os.path.dirname(CSV_PATH), exist_ok=True)
    new = not os.path.exists(CSV_PATH)
    csv_file = open(CSV_PATH, "a", newline="", encoding="utf-8")
    writer = csv.DictWriter(csv_file, fieldnames=FIELDS)
    if new:
        writer.writeheader()

    print(f"== Watcher timing on {serial}, API {api} ==")
    for cond, reps, wait_s in CONDITIONS:
        condition_teardown(serial)
        if not start_watcher(serial):
            print(f"[{cond}] watcher did not start; skipping")
            continue
        note = condition_setup(serial, cond)
        print(f"-- condition: {cond} ({note})")
        for event in EVENTS:
            for rep in range(1, reps + 1):
                t0, t1, found = measure(serial, event, wait_s)
                if found:
                    row = {"api": api, "condition": cond, "event": event["name"], "rep": rep, "observed": True,
                           "latency_ms_from_start": found[0] - t0, "latency_ms_from_end": found[0] - t1,
                           "matched_line": found[1][:60], "notes": note}
                else:
                    row = {"api": api, "condition": cond, "event": event["name"], "rep": rep, "observed": False,
                           "latency_ms_from_start": "", "latency_ms_from_end": "", "matched_line": "", "notes": note}
                writer.writerow(row)
                csv_file.flush()
                rows.append(row)
                shown = f"{row['latency_ms_from_start']} ms" if found else "MISSED"
                print(f"   {event['name']:56s} rep {rep}: {shown}")
        save_raw(serial, api, cond)
        condition_teardown(serial)
    csv_file.close()
    print("\n" + to_markdown(summarize(rows)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
