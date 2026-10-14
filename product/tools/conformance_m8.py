#!/usr/bin/env python3
"""Stage 3 conformance: the history and the tripwires, on an EMULATOR. Python standard library, adb, OpenSSL.

The phone is changed with adb (a proxy, a fixture app, a screen lock, fingerprints). After each change the debug build writes
what it holds and what it read back from its own encrypted history file, and `ledger_oracle.py` (hashlib and OpenSSL, no
shared code) checks that file: chain, replay, every signature, and the key against the one the phone's Keystore holds. The
changes the history records are compared with what the system says. The History screen is driven through the real UI.

Run from a PowerShell where `. lab\\tools\\env.ps1` has been dot-sourced. Refuses to touch anything but an emulator.
"""
import argparse
import csv
import json
import os
import subprocess
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import conformance_m1 as m1  # noqa: E402
import conformance_m2 as m2  # noqa: E402
import conformance_m5 as m5  # noqa: E402
import ledger_oracle as oracle  # noqa: E402
import runner  # noqa: E402

sys.path.insert(0, m1.LAB_TOOLS)
import emu_fingerprint  # noqa: E402
import tripwire_emulator  # noqa: E402
import ui  # noqa: E402

RESULTS_CSV = os.path.join(m1.PRODUCT, "conformance", "m8_results.csv")
PIN = "4242"
PROXY = "127.0.0.1:8899"
PERMS = "lab.fixture.perms"
LEDGER_FILE = "files/declared-history.bin"
APP = {"id": ""}
RESULTS = []


# ---------------------------------------------------------------- pure helpers (unit tested)

def new_events(text, since_seq):
    """Every event code written in entries after `since_seq`."""
    return sorted({ev for seq, _, _, events, _ in oracle.events_of(text) if seq > since_seq for ev in events})


def new_changes(text, since_seq):
    """(kind, key) of every change recorded in entries after `since_seq`."""
    return {c for seq, _, _, _, changes in oracle.events_of(text) if seq > since_seq for c in changes}


def third_party(pm_output):
    return {line.split(":", 1)[1].strip() for line in pm_output.splitlines() if line.startswith("package:")}


def launcher_packages(query_output):
    """Package names from `cmd package query-activities --brief` (one 'package/Activity' line per activity)."""
    import re
    return {m.group(1) for m in (re.match(r"^([A-Za-z0-9_.]+)/", line.strip()) for line in query_output.splitlines()) if m}


def titles_added(text, key):
    """The titles of the finding `key` as the entries recorded them when it appeared."""
    doc = oracle.parse(text)
    return [c["a"]["title"] for e in doc["entries"] for c in e["changes"] if c["k"] == key and c["t"] == "A" and "title" in c["a"]]


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), str(detail)[:300]))
    print(("ok   " if ok else "FAIL ") + name + ("" if ok else f"   [{str(detail)[:200]}]"))
    return bool(ok)


# ---------------------------------------------------------------- the phone and the app

def sh(serial, command):
    return runner.adb(serial, "shell", command, check=False)


def pull_file(serial, path):
    return subprocess.run([runner.ADB, "-s", serial, "exec-out", "run-as", APP["id"], "cat", path], capture_output=True, timeout=60).stdout


def push_file(serial, path, data):
    p = subprocess.run([runner.ADB, "-s", serial, "exec-in", "run-as", APP["id"], "sh", "-c", f"cat > {path}"], input=data, capture_output=True, timeout=60)
    return p.returncode == 0


def start(serial, fresh=True):
    """Start the app (killing it first when `fresh`, which makes the step 'opened') and return the run id its debug file carries."""
    run_id = uuid.uuid4().hex[:10]
    m1.wake(serial)
    flags = "-S -W " if fresh else "-W "
    runner.shell(serial, f"am start {flags}-n {APP['id']}/io.github.h_sib.declared.app.MainActivity --ez conformance true --es runId {run_id} --ez allowCapture true --ez expandAll true")
    return run_id


def poll(serial, run_id, want, timeout=240):
    """Read the app's history file until `want(d)` holds; returns (last reading or None, whether it held)."""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        raw = runner.adb(serial, "exec-out", "run-as", APP["id"], "cat", f"files/conformance-{run_id}-history.json", check=False)
        try:
            last = json.loads(raw)
        except ValueError:
            time.sleep(2)
            continue
        if want(last):
            return last, True
        time.sleep(2)
    return last, False


def loaded(d, at_least=1):
    return d["state"] == "Ready" and d["entries"] >= at_least and d["onDisk"]["status"] == "Loaded"


def text_of(d):
    return d["onDisk"]["text"]


def verified(name, d):
    """The independent verifier on what the app has on disk, tied to the key the Keystore holds, and the app's own head against the file's."""
    text = text_of(d)
    r = oracle.verify(text, held=d["signerKey"])
    check(f"{name}: OpenSSL and hashlib say the history is authentic and tied to this phone's key", r.authentic, r.problems)
    head = oracle.head(text)
    check(f"{name}: the head the app shows is the head in the file", d["head"] and (d["head"]["seq"], d["head"]["hash"]) == tuple(head), (d["head"], head))
    return text


def lock_is_set(serial):
    """`locksettings get-disabled` prints `true` only when no credential is set: the reading conformance_m3.py uses on every version."""
    return sh(serial, "locksettings get-disabled").strip().lower() != "true"


def reset_phone(serial):
    for c in ("settings put global http_proxy :0", "settings delete global http_proxy", f"locksettings clear --old {PIN}", f"pm uninstall {PERMS}"):
        sh(serial, c)
    time.sleep(2)


def open_history_screen(serial):
    return m1.tap_tab(serial, ui, "History")


def tap_text(serial, pattern):
    node = m2.scroll_find(serial, ui, pattern)
    if not node:
        return False
    ui.tap_xy(serial, *ui.center(node["bounds"]))
    time.sleep(2)
    return True


# ---------------------------------------------------------------- the scenarios

def scenario_baseline(serial):
    run_id = start(serial)
    d, ok = poll(serial, run_id, lambda d: loaded(d) and d["tripwire"] is not None)
    if not check("baseline: a first entry is written and read back from the encrypted file", ok, d):
        return None
    text = verified("baseline", d)
    check("baseline: the first entry says it was taken when the app was opened", oracle.events_of(text)[0][2] == "OPENED")
    third = third_party(sh(serial, "pm list packages -3"))
    records = oracle.final_records(text)
    in_history = {k[4:] for k in records if k.startswith("app:")}
    launcher = launcher_packages(sh(serial, "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"))
    missing = sorted((third & launcher) - in_history)
    if launcher:
        check("baseline: every third-party app with a launcher icon that the system lists is in the history", third & launcher and not missing, missing)
    else:
        check("baseline: (skipped: this Android version cannot list launcher activities with cmd package)", True, "skipped")
    report_apps = None
    for _ in range(20):
        try:
            report_apps = {a["pkg"] for a in json.loads(runner.adb(serial, "exec-out", "run-as", APP["id"], "cat", f"files/conformance-{run_id}.json", check=False))["apps"]}
            break
        except (ValueError, KeyError):
            time.sleep(2)
    check("baseline: the apps in the history are the apps the app's own report lists (that report is checked against the system by m2 to m7)", report_apps == in_history, report_apps and sorted(report_apps ^ in_history))
    check("baseline: the app says where its key is held and the file says the same", d["keyHeld"] == oracle.parse(text)["header"]["held"], (d["keyHeld"], oracle.parse(text)["header"]["held"]))
    check("baseline: the tripwire has a reading (or says why it cannot)", d["tripwire"] is not None, d["tripwire"])
    return d


def scenario_live_changes(serial, d0):
    """Changes made while the app is on screen: each must arrive as a signed 'while on screen' entry that matches the system."""
    run_id = start(serial)
    d, ok = poll(serial, run_id, lambda d: loaded(d) and d["tripwire"] is not None)
    n = d["entries"]
    sh(serial, f"settings put global http_proxy {PROXY}")
    d, ok = poll(serial, run_id, lambda d: loaded(d, n + 1) and oracle.final_records(text_of(d)).get("fact:HTTP_PROXY", {}).get("value") == PROXY, timeout=100)
    if check("live: a proxy set while the app is open becomes a new entry", ok, d and d["entries"]):
        text = verified("live proxy", d)
        check("live: the system agrees about the proxy", sh(serial, "settings get global http_proxy").strip() == PROXY)
        check("live: the entry records the fact and the finding that follows from it", {("M", "fact:HTTP_PROXY"), ("A", "finding:condition:http-proxy")} <= new_changes(text, n), new_changes(text, n))
        last = oracle.events_of(text)[-1]
        check("live: it says it was taken while the app was on screen", any(e[2] == "ON_SCREEN" for e in oracle.events_of(text) if e[0] > n), last)
    n = d["entries"] if d else n
    runner.adb(serial, "install", "-t", "-r", m2.fixture_apk("perms"))
    d, ok = poll(serial, run_id, lambda d: loaded(d, n + 1) and "app:" + PERMS in oracle.final_records(text_of(d)), timeout=100)
    if check("live: an app installed while the app is open becomes an entry", ok, d and d["entries"]):
        text = verified("live install", d)
        check("live: the system lists it", f"package:{PERMS}" in sh(serial, "pm list packages"))
        check("live: the entry is an addition of that app", ("A", "app:" + PERMS) in new_changes(text, n), new_changes(text, n))
    n = d["entries"] if d else n
    sh(serial, f"pm uninstall {PERMS}")
    d, ok = poll(serial, run_id, lambda d: loaded(d, n + 1) and "app:" + PERMS not in oracle.final_records(text_of(d)), timeout=100)
    if check("live: an app removed while the app is open becomes an entry", ok, d and d["entries"]):
        text = verified("live removal", d)
        check("live: the entry is a removal of that app", ("R", "app:" + PERMS) in new_changes(text, n), new_changes(text, n))
    return d


def scenario_revert_and_screen(serial, d):
    """Revert the proxy, reopen (an 'opened' entry), then read the History screen through the real UI."""
    sh(serial, "settings put global http_proxy :0")
    sh(serial, "settings delete global http_proxy")
    n = d["entries"] if d else 0
    run_id = start(serial)
    d, ok = poll(serial, run_id, lambda d: loaded(d, n + 1) and oracle.final_records(text_of(d)).get("fact:HTTP_PROXY", {}).get("value") == "", timeout=120)
    if not check("revert: clearing the proxy is recorded as the opposite change", ok, d and d["entries"]):
        return d
    text = verified("revert", d)
    check("revert: a fresh start makes an 'opened' entry", any(e[2] == "OPENED" for e in oracle.events_of(text) if e[0] > n))
    # the screen
    if check("screen: the History tab opens", open_history_screen(serial)):
        check("screen: 'Check the history now' can be pressed", tap_text(serial, r"Check the history now"))
        time.sleep(3)
        found = m2.scroll_find(serial, ui, r"every entry carries a valid signature")
        check("screen: the check reports a valid, signed history", found)
        check("screen: the check says what it cannot see", m2.scroll_find(serial, ui, r"What this cannot see"))
        titles = titles_added(text, "finding:condition:http-proxy")
        shown = " | ".join(m1.scrolled_texts(serial))
        check("screen: the timeline shows the proxy finding exactly as the entry recorded it", titles and all(t in shown for t in titles), titles)
        runner.shell(serial, "input keyevent KEYCODE_BACK", check=False)
        time.sleep(1)
    return d


def scenario_damage(serial):
    """A damaged history file: left alone, reported, replaceable only on request, and the old file kept under another name."""
    original = pull_file(serial, LEDGER_FILE)
    if not check("damage: the history file exists and is not readable as text (it is encrypted)", original and b"fact:" not in original and b"tripwire" not in original):
        return
    bad = m5.flip_byte(original, len(original) // 2)
    runner.shell(serial, f"am force-stop {APP['id']}", check=False)
    push_file(serial, LEDGER_FILE, bad)
    run_id = start(serial)
    d, ok = poll(serial, run_id, lambda d: d["state"] == "Damaged", timeout=60)
    check("damage: a changed byte is reported as a damaged history, not read as empty or half read", ok, d and d["state"])
    check("damage: nothing was added to or rewritten in the damaged file", pull_file(serial, LEDGER_FILE) == bad)
    if ok and open_history_screen(serial) and check("damage: the screen offers a new history", tap_text(serial, r"^Start a new history$")):
        d, ok = poll(serial, run_id, lambda d: loaded(d), timeout=60)
        check("damage: a new history starts with a first entry", ok, d and d["state"])
        if ok:
            verified("new history", d)
        files = sh(serial, f"run-as {APP['id']} ls files")
        check("damage: the damaged file is kept aside under another name, byte for byte", "declared-history.bin.damaged" in files and pull_file(serial, LEDGER_FILE + ".damaged") == bad, files)
    runner.shell(serial, "input keyevent KEYCODE_HOME", check=False)


def scenario_tripwires(serial):
    """A screen lock, then a fingerprint, then another, then the lock removed. Each step is confirmed with the system before the app is asked."""
    run_id = start(serial)
    d, ok = poll(serial, run_id, lambda d: loaded(d) and d["tripwire"] is not None)
    seq = d["head"]["seq"] if d and d["head"] else 0

    def step(name, event, verdict=None, extra=None):
        nonlocal seq
        tripwire_emulator.unlock(serial, PIN) if lock_is_set(serial) else None
        rid = start(serial)
        dd, good = poll(serial, rid, lambda x: loaded(x) and event in new_events(text_of(x), seq) and (not verdict or (x.get("lastTripwire") or {}).get("code") == event), timeout=90)
        if check(f"tripwire {name}: the signed history records '{event}'", good, dd and (dd["tripwire"], new_events(text_of(dd), seq) if dd["onDisk"]["status"] == "Loaded" else None)):
            text = verified(f"tripwire {name}", dd)
            if verdict:
                check(f"tripwire {name}: the screen keeps it as the last tripwire event ({verdict})", dd["lastTripwire"] and dd["lastTripwire"]["code"] == event, dd["lastTripwire"])
            seq = oracle.head(text)[0]
        return dd

    check("tripwire: with no screen lock the app says a lock tripwire is not possible", d and d["tripwire"] and any("lock" in v.lower() for v in d["tripwire"]["notPossible"].values()), d and d["tripwire"])
    sh(serial, f"locksettings set-pin {PIN}")
    check("tripwire: the system confirms a screen lock is set", lock_is_set(serial))
    step("lock set", "tripwire.armed.credential")
    emu_fingerprint.enroll(serial, PIN, 1, verbose=False)
    check("tripwire: the system confirms a fingerprint is enrolled", (emu_fingerprint.enrolled_count(serial) or 0) >= 1)
    if int(runner.shell(serial, "getprop ro.build.version.sdk")) >= 31:
        step("first fingerprint", "tripwire.armed.biometric")
        emu_fingerprint.enroll(serial, PIN, 2, verbose=False)
        check("tripwire: the system confirms a second fingerprint", (emu_fingerprint.enrolled_count(serial) or 0) >= 2)
        step("fingerprint added", "tripwire.fingerprint-added", "FingerprintAdded")
    else:
        # Measured on the Android 10 emulator: a fingerprint-bound key survives another fingerprint being added, so the app must say this tripwire cannot work.
        tripwire_emulator.unlock(serial, PIN)
        dd, good = poll(serial, start(serial), lambda x: loaded(x) and x["tripwire"] is not None and "BIOMETRIC" in x["tripwire"]["notPossible"], timeout=90)
        check("tripwire: on Android 10 the app says the fingerprint tripwire cannot work, instead of reporting that nothing happened", good,
              dd and dd["tripwire"])
        check("tripwire: ... and its reason names Android 10", good and "Android 10" in dd["tripwire"]["notPossible"]["BIOMETRIC"], dd and dd["tripwire"])
    sh(serial, f"locksettings clear --old {PIN}")
    check("tripwire: the system confirms the lock is gone", not lock_is_set(serial))
    step("lock removed", "tripwire.lock-removed", "LockRemoved")


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--no-tripwires", action="store_true", help="skip the screen-lock and fingerprint steps (the slow ones)")
    args = ap.parse_args(argv)
    serial = args.serial
    m1.require_emulator(serial)
    apk = m1.find_product_apk()
    APP["id"] = m1.parse_package_name(m1.tool("aapt2.exe", "dump", "badging", apk))
    reset_phone(serial)
    runner.adb(serial, "install", "-r", apk)
    sh(serial, f"pm clear {APP['id']}")
    m1.ensure_baseline(serial, APP["id"], ui)
    try:
        d = scenario_baseline(serial)
        if d:
            d = scenario_live_changes(serial, d)
            d = scenario_revert_and_screen(serial, d)
            scenario_damage(serial)
            if not args.no_tripwires:
                scenario_tripwires(serial)
    finally:
        reset_phone(serial)
    os.makedirs(os.path.dirname(RESULTS_CSV), exist_ok=True)
    with open(RESULTS_CSV, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["check", "ok", "detail"])
        w.writerows(RESULTS)
    failed = [r for r in RESULTS if not r[1]]
    print(f"m8: {len(RESULTS)} checks, {len(failed)} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
