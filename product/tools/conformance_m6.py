#!/usr/bin/env python3
"""Milestone 6 conformance: the live view, the blind-spot map, the secure window and large text, on an EMULATOR. Standard library only.

The app is put in front on the first screen and the phone is then changed from the outside with adb, the way
something on the phone would change it. The debug build writes what its watcher heard and what its report said
afterwards to a small file after every change (`files/conformance-<id>-live.json`); this script compares that with
what it did and with what the system says:

  * every watched setting, an accessibility service, a notification listener, an app installed, updated and
    removed: the matching announcement must arrive (device clock before the change vs the event's own time) and the
    report must be read again and show the change
  * an app with no launcher icon and no special component must NOT be announced, and not counted
  * things no signal announces (a device administrator, a screen lock) must still show up, through the re-read
  * the app in the background hears nothing and catches up when it comes back
  * the window is secure by default (`dumpsys window` shows the SECURE flag), and not when a lab script asks for capture
  * the live view and the blind-spot map, on screen, say what the product says they say; also at large text, with
    every touch target at least 48dp (what an accessibility tree can show; a screen reader itself is not on this image)

Run from a PowerShell where `. lab\\tools\\env.ps1` has been dot-sourced. Refuses to touch anything but an emulator.
"""
import argparse
import csv
import datetime as dt
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import conformance_m1 as m1  # noqa: E402
import conformance_m2 as m2  # noqa: E402
import runner  # noqa: E402

PRODUCT = m1.PRODUCT
RESULTS_CSV = os.path.join(PRODUCT, "conformance", "m6_results.csv")
SHOTS_DIR = os.path.join(PRODUCT, "conformance", "screenshots")
RUN_ID = dt.datetime.now().isoformat(timespec="seconds")
LIVE_RUN = "m6live"
POLL_SECONDS = 30  # Watched.POLL_SECONDS in core
APP = {}

# The words the product uses for each announcement (core/live/Live.kt). A change there fails here on purpose.
WORDS = {
    "dns-mode": "The Private DNS mode changed",
    "dns-name": "The Private DNS server name changed",
    "proxy": "The web proxy setting changed",
    "developer": "Developer options were switched on or off",
    "a11y": "The list of accessibility services that are switched on changed",
    "notif": "The list of apps allowed to read notifications changed",
}
FIXTURE_PERMS = "lab.fixture.perms"
FIXTURE_HIDDEN = "lab.fixture.hidden"
PIN = "4242"
DP_PER_INCH = 160.0


# ---------------------------------------------------------------- pure helpers (unit tested)

def iso_to_ns(text):
    """An `Instant.toString()` value (3, 6 or 9 fractional digits, or none, always Z) as nanoseconds since the epoch."""
    m = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z", text or "")
    if not m:
        raise ValueError(f"not an instant: {text!r}")
    base = dt.datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=dt.timezone.utc)
    return int(base.timestamp()) * 1_000_000_000 + int((m.group(2) or "0").ljust(9, "0"))


def events_after(live, what, since_ns):
    """The announcements with these exact words made at or after `since_ns`, oldest first."""
    found = [e for e in (live or {}).get("events", []) if e["what"] == what and iso_to_ns(e["at"]) >= since_ns]
    return sorted(found, key=lambda e: iso_to_ns(e["at"]))


def latency_ms(live, what, since_ns):
    """Milliseconds from `since_ns` (the device clock just before the change) to the first matching announcement, or None."""
    found = events_after(live, what, since_ns)
    return (iso_to_ns(found[0]["at"]) - since_ns) / 1e6 if found else None


def events_matching(live, pattern, since_ns):
    rx = re.compile(pattern)
    return [e["what"] for e in (live or {}).get("events", []) if rx.search(e["what"]) and iso_to_ns(e["at"]) >= since_ns]


def read_again_after(live, since_ns):
    """True when the report on screen was taken after `since_ns`, so it can reflect the change."""
    taken = (live or {}).get("reportTakenAt")
    return bool(taken) and iso_to_ns(taken) >= since_ns


def has_finding(live, fragment):
    return any(fragment in f for f in (live or {}).get("findingIds", []))


def window_secure(dumpsys_windows, package):
    """True/False for whether the package's main window carries the SECURE flag in `dumpsys window windows`; None if there is no such window."""
    lines = dumpsys_windows.splitlines()
    for i, line in enumerate(lines):
        if re.search(r"Window #\d+ Window\{[0-9a-f]+ u0 " + re.escape(package) + r"/", line):
            for follow in lines[i + 1:i + 12]:
                if follow.strip().startswith("fl="):
                    return "SECURE" in follow.split()
            return None
    return None


def dp_to_px(dp, density):
    return dp * density / DP_PER_INCH


def touch_target_problems(nodes, density, minimum_dp=48):
    """Clickable nodes smaller than `minimum_dp` in either direction. Tolerance: one pixel.

    A node that touches the top or bottom edge of the app's window is cut off by scrolling (a tall card half out of view reports its
    visible part, without its text), not small: those are skipped. The window's edges are the extremes of the app's own nodes."""
    minimum = dp_to_px(minimum_dp, density) - 1
    app_nodes = [n["bounds"] for n in nodes if n.get("bounds") and not (n.get("rid") or "").startswith("com.android.systemui")]
    window_top = min((b[1] for b in app_nodes), default=None)
    window_bottom = max((b[3] for b in app_nodes), default=None)
    # The bottom bar hides what is under it, so a card that runs below the bar is cut off there too: its visible part is not a small target.
    bar_tops = [b[1] for b in app_nodes if window_bottom is not None and b[3] >= window_bottom - 1 and (b[3] - b[1]) >= dp_to_px(56, density)]
    content_bottom = min(bar_tops, default=window_bottom)
    problems = []
    for n in nodes:
        b = n.get("bounds")
        if not b or not n.get("enabled", True):
            continue
        left, top, right, bottom = b
        if (bottom - top < minimum) and (bottom >= content_bottom - 1 or top <= window_top + 1) and (right - left) >= minimum:
            continue
        if n.get("clickable") and (right - left < minimum or bottom - top < minimum):
            near = [(m["text"] or m["desc"])[:30] for m in nodes if m.get("bounds") and (m["text"] or m["desc"]) and abs(m["bounds"][1] - top) < 160 and m is not n][:3]
            problems.append(f"{(n['text'] or n['desc'] or '<no label>')[:40]} is {right - left}x{bottom - top}px, below {minimum_dp}dp (id {n.get('rid') or 'none'}, top {top}, near {near})")
    return problems


def clickable_label_problems(nodes):
    """A clickable node must have words: its own text or description, or text or a description inside its bounds (a card made of several texts, an icon with its label)."""
    problems = []
    for n in nodes:
        b = n.get("bounds")
        if not n.get("clickable") or not b or not n.get("enabled", True):
            continue
        if b[3] - b[1] < 8 or b[2] - b[0] < 8:  # a sliver of a card that runs under the bottom bar
            continue
        own = (n["text"] or n["desc"]).strip()
        inside = any(
            m is not n and (m.get("text") or m.get("desc")) and m.get("bounds") and m["bounds"][0] >= b[0] and m["bounds"][1] >= b[1] and m["bounds"][2] <= b[2] and m["bounds"][3] <= b[3]
            for m in nodes
        )
        if not own and not inside:
            problems.append(f"clickable at {b} has no words")
    return problems


class Check(m1.Check):
    def row(self, phase, api):
        return [RUN_ID, api, phase, self.name, json.dumps(self.expected), json.dumps(self.actual), "PASS" if self.ok else "FAIL"]


class Within(Check):
    """Passes when a measured number (milliseconds) is present and below the limit; the number is the recorded `actual`."""

    def __init__(self, name, limit_ms, actual_ms):
        super().__init__(name, f"under {limit_ms} ms", None if actual_ms is None else round(actual_ms))
        self.ok = actual_ms is not None and actual_ms < limit_ms


# ---------------------------------------------------------------- device plumbing

def sh(serial, command):
    return runner.adb(serial, "shell", command, check=False)


def device_ns_then(serial, command):
    """Runs `command` on the device and returns the device clock (ns since the epoch) read just before it."""
    out = sh(serial, f"date +%s%N; {command}").strip().splitlines()
    if not out or not out[0].strip().isdigit():
        raise SystemExit(f"could not read the device clock: {out[:1]!r}")
    return int(out[0].strip())


def read_live(serial):
    raw = runner.adb(serial, "exec-out", "run-as", APP["id"], "cat", f"files/conformance-{LIVE_RUN}-live.json", check=False)
    try:
        return json.loads(raw)
    except ValueError:
        return None


def wait_live(serial, predicate, timeout=10.0, interval=0.25):
    """Poll the live file until `predicate(live)` holds. Returns (live, True) or the last reading and False."""
    deadline = time.time() + timeout
    last = None
    while True:
        last = read_live(serial)
        if last is not None and predicate(last):
            return last, True
        if time.time() >= deadline:
            return last, False
        time.sleep(interval)


def start_app(serial, capture=True, report_after_ns=0):
    m1.wake(serial)
    flag = " --ez allowCapture true --ez expandAll true" if capture else ""
    runner.shell(serial, f"am start -S -W -n {APP['id']}/io.github.h_sib.declared.app.MainActivity --ez conformance true --es runId {LIVE_RUN}{flag}")
    live, ok = wait_live(serial, lambda L: bool(L.get("reportTakenAt")) and iso_to_ns(L["reportTakenAt"]) >= report_after_ns and L.get("watching"), timeout=60)
    if not ok:
        raise SystemExit("the app did not report a first reading and watching within 60 s")
    time.sleep(1.5)  # let the first burst of start-up announcements pass
    return read_live(serial)


def a11y_current(serial):
    return runner.settings_get(serial, "secure", "enabled_accessibility_services") or ""


def tap_text(serial, ui, pattern, scroll=False):
    node = m2.scroll_find(serial, ui, pattern) if scroll else ui.find(ui.dump(serial), pattern)
    if not node:
        return False
    ui.tap_xy(serial, *ui.center(node["bounds"]))
    time.sleep(1.5)
    return True


def screen_texts(serial, ui):
    return " | ".join(n["text"] for n in ui.dump(serial) if n["text"])


def tap_back(serial, ui):
    return tap_text(serial, ui, r"^Back$")


def window_density(serial):
    out = sh(serial, "wm density")
    m = re.findall(r"(\d+)", out.split("Override")[-1] if "Override" in out else out)
    return int(m[-1]) if m else 420


# ---------------------------------------------------------------- scenarios

def change_and_expect(serial, name, change_cmds, expected_words, settle, since_pad_ns=0, timeout=25):
    """Change the phone from outside; wait for the announcements and for a report that shows it. Returns checks and the final live reading."""
    cmd = "; ".join(change_cmds)
    t0 = device_ns_then(serial, cmd) - since_pad_ns
    live, ok = wait_live(serial, lambda L: all(events_after(L, w, t0) for w in expected_words.values()) and read_again_after(L, t0) and settle(L), timeout=timeout)
    checks = []
    for key, w in expected_words.items():
        checks.append(Check(f"{name}: announced '{w}'", True, bool(events_after(live, w, t0))))
        checks.append(Within(f"{name}: '{w}' arrived within 3 s of the change (device clock)", 3000, latency_ms(live, w, t0)))
    checks.append(Check(f"{name}: the phone was read again after the change", True, read_again_after(live, t0)))
    checks.append(Check(f"{name}: the new reading shows the change", True, bool(live) and settle(live)))
    return checks, live, t0


def facts_mention(fragment):
    return lambda L: any(fragment in v for v in (L.get("facts") or {}).values())


def facts_dont_mention(fragment):
    return lambda L: not any(fragment in v for v in (L.get("facts") or {}).values())


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--skip-install", action="store_true")
    ap.add_argument("--skip-slow", action="store_true", help="skip the two checks that wait for the 30 second re-read")
    args = ap.parse_args(argv)
    serial = args.serial
    m1.require_emulator(serial)
    sys.path.insert(0, m1.LAB_TOOLS)
    import ui

    product_apk = m1.find_product_apk()
    APP["id"] = m1.parse_package_name(m1.tool("aapt2.exe", "dump", "badging", product_apk))
    api = runner.shell(serial, "getprop ro.build.version.sdk")
    if not args.skip_install:
        for role in m2.FIXTURE_ROLES:
            if os.path.exists(m2.fixture_apk(role)):
                runner.adb(serial, "install", "-t", "-r", m2.fixture_apk(role))
        runner.adb(serial, "install", "-r", product_apk)

    rows, failures = [], 0

    def record(phase, checks):
        nonlocal failures
        for c in checks:
            rows.append(c.row(phase, api))
            if not c.ok:
                failures += 1
                print(f"  FAIL [{phase}] {c.name}: expected {c.expected!r}, got {c.actual!r}")
        print(f"[{phase}] {sum(c.ok for c in checks)}/{len(checks)} checks passed")

    os.makedirs(SHOTS_DIR, exist_ok=True)
    stayed_on = runner.settings_get(serial, "global", "stay_on_while_plugged_in")
    font_before = runner.settings_get(serial, "system", "font_scale")
    sh(serial, "svc power stayon true")
    for role in m2.ENABLE_ROLES:  # a known start: every fixture role off, so each scenario below is the only change
        for c in runner.disable_commands(role, a11y_current(serial)):
            runner.shell(serial, c, check=False)
    for c in ("settings delete global private_dns_mode", "settings delete global private_dns_specifier", "settings put global http_proxy :0", "settings delete global http_proxy",
              "settings delete global development_settings_enabled"):
        sh(serial, c)
    time.sleep(2)

    try:
        m1.ensure_baseline(serial, APP["id"], ui)
        live = start_app(serial)

        # ------------------------------------------------------------ screenshots are allowed by default; Block screenshots in More blocks them
        sh(serial, f"am start -S -W -n {APP['id']}/io.github.h_sib.declared.app.MainActivity --ez conformance true --es runId {LIVE_RUN}")
        time.sleep(5)
        secure_default = window_secure(sh(serial, "dumpsys window windows"), APP["id"])
        m1.tap_text(serial, ui, r"^Skip$")  # without the lab flag a first launch shows the intro; skipping it is what a person does
        time.sleep(2)
        m1.open_tab(serial, ui, "More", r"^Look closer$|^Help$")
        switched_on = m1.tap_text(serial, ui, r"^Block screenshots$", scroll=True)
        time.sleep(2)
        secure_blocked = window_secure(sh(serial, "dumpsys window windows"), APP["id"])
        shot_blocked = ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m6-secure-window-api{api}.png"))
        switched_off = m1.tap_text(serial, ui, r"^Block screenshots$", scroll=True)
        time.sleep(2)
        secure_after = window_secure(sh(serial, "dumpsys window windows"), APP["id"])
        shot_open = ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m6-inventory-api{api}.png"))
        live = start_app(serial)
        record("secure-window", [
            Check("by default the app's window is not secure: screenshots are allowed (dumpsys window)", False, secure_default),
            Check("the Block screenshots switch is found and pressed", [True, True], [switched_on, switched_off]),
            Check("with Block screenshots on, the window carries the SECURE flag", True, secure_blocked),
            Check("a screenshot of the blocked window yields no usable image (empty, or far smaller than one without the flag)", True,
                  os.path.getsize(shot_blocked) * 3 < os.path.getsize(shot_open)),
            Check("with Block screenshots off again, the flag is gone", False, secure_after),
        ])

        # ------------------------------------------------------------ announcements for settings
        checks, live, _ = change_and_expect(
            serial, "Private DNS",
            ["settings put global private_dns_mode hostname", "settings put global private_dns_specifier dns.google"],
            {k: WORDS[k] for k in ("dns-mode", "dns-name")}, lambda L: True)
        record("announce-private-dns", checks)
        sh(serial, "settings delete global private_dns_mode; settings delete global private_dns_specifier")

        checks, live, _ = change_and_expect(serial, "web proxy", ["settings put global http_proxy 127.0.0.1:8888"], {"proxy": WORDS["proxy"]}, facts_mention("127.0.0.1"))
        record("announce-proxy", checks)
        checks, live, _ = change_and_expect(serial, "web proxy removed", ["settings put global http_proxy :0", "settings delete global http_proxy"], {"proxy": WORDS["proxy"]}, facts_dont_mention("127.0.0.1"))
        record("announce-proxy-removed", [c for c in checks if "arrived within" not in c.name])

        before_dev = (live or {}).get("facts", {}).get("DEVELOPER_OPTIONS")
        checks, live, _ = change_and_expect(
            serial, "developer options", ["settings put global development_settings_enabled 1"], {"developer": WORDS["developer"]},
            lambda L: L.get("facts", {}).get("DEVELOPER_OPTIONS", "").startswith("Developer options: on"))
        record("announce-developer-options", checks)
        checks, live, _ = change_and_expect(
            serial, "developer options removed", ["settings delete global development_settings_enabled"], {"developer": WORDS["developer"]},
            lambda L: L.get("facts", {}).get("DEVELOPER_OPTIONS") == before_dev)
        record("announce-developer-options-removed", [c for c in checks if "arrived within" not in c.name])

        # ------------------------------------------------------------ announcements for the powers that matter most
        current = a11y_current(serial)
        checks, live, _ = change_and_expect(serial, "accessibility service", runner.enable_commands("a11y", current), {"a11y": WORDS["a11y"]}, lambda L: has_finding(L, "lab.fixture.a11y"))
        record("announce-accessibility", checks)
        checks, live, _ = change_and_expect(serial, "accessibility service off", runner.disable_commands("a11y", a11y_current(serial)), {"a11y": WORDS["a11y"]}, lambda L: not has_finding(L, "lab.fixture.a11y"))
        record("announce-accessibility-off", [c for c in checks if "arrived within" not in c.name])

        checks, live, _ = change_and_expect(serial, "notification listener", runner.enable_commands("notif"), {"notif": WORDS["notif"]}, lambda L: has_finding(L, "lab.fixture.notif"))
        record("announce-notification-listener", checks)
        checks, live, _ = change_and_expect(serial, "notification listener off", runner.disable_commands("notif"), {"notif": WORDS["notif"]}, lambda L: not has_finding(L, "lab.fixture.notif"))
        record("announce-notification-listener-off", [c for c in checks if "arrived within" not in c.name])

        # ------------------------------------------------------------ apps
        runner.adb(serial, "uninstall", FIXTURE_PERMS, check=False)
        time.sleep(3)
        live = read_live(serial)
        count_before = live["appCount"]
        t0 = device_ns_then(serial, "true")
        runner.adb(serial, "install", "-t", m2.fixture_apk("perms"))
        live, ok = wait_live(serial, lambda L: bool(events_matching(L, f"installed: {re.escape(FIXTURE_PERMS)}$", t0)) and L["appCount"] == count_before + 1 and read_again_after(L, t0), timeout=25)
        if not live or live.get("appCount") != count_before + 1:
            # A rare slow read once left the count behind for the whole wait (a flake, D-042); say what the app thought it was doing.
            print(f"  info: after the install the app counted {live and live.get('appCount')} (wanted {count_before + 1}); refreshing={live and live.get('refreshing')}, refreshError={live and live.get('refreshError')}")
        record("announce-app-installed", [
            Check("an install is announced as installed, by name", [f"An app was installed: {FIXTURE_PERMS}"], events_matching(live, f"installed: {re.escape(FIXTURE_PERMS)}$", t0)),
            Check("the number of apps the report lists went up by one", count_before + 1, (live or {}).get("appCount")),
        ])
        count_before = live["appCount"]
        t1 = device_ns_then(serial, "true")
        runner.adb(serial, "install", "-t", "-r", m2.fixture_apk("perms"))
        live, ok = wait_live(serial, lambda L: bool(events_matching(L, f"updated: {re.escape(FIXTURE_PERMS)}$", t1)), timeout=25)
        time.sleep(2)
        live = read_live(serial)
        record("announce-app-updated", [
            Check("an update is announced as updated", True, bool(events_matching(live, f"updated: {re.escape(FIXTURE_PERMS)}$", t1))),
            Check("an update is not also announced as a removal and an installation", [], events_matching(live, f"(installed|removed): {re.escape(FIXTURE_PERMS)}$", t1)),
            Check("the number of apps did not change", count_before, live["appCount"]),
        ])
        t2 = device_ns_then(serial, "true")
        runner.adb(serial, "uninstall", FIXTURE_PERMS)
        live, ok = wait_live(serial, lambda L: bool(events_matching(L, f"removed: {re.escape(FIXTURE_PERMS)}$", t2)) and L["appCount"] == count_before - 1, timeout=25)
        record("announce-app-removed", [
            Check("a removal is announced as removed", True, bool(events_matching(live, f"removed: {re.escape(FIXTURE_PERMS)}$", t2))),
            Check("the number of apps went down by one", count_before - 1, live["appCount"]),
        ])
        runner.adb(serial, "install", "-t", m2.fixture_apk("perms"))
        time.sleep(3)

        # ------------------------------------------------------------ the limit: an app nothing announces
        runner.adb(serial, "uninstall", FIXTURE_HIDDEN, check=False)
        time.sleep(3)
        live = read_live(serial)
        count_before = live["appCount"]
        t3 = device_ns_then(serial, "true")
        runner.adb(serial, "install", "-t", m2.fixture_apk("hidden"))
        time.sleep(8)
        live = read_live(serial)
        installed_on_phone = f"package:{FIXTURE_HIDDEN}" in sh(serial, "pm list packages").split()
        record("hidden-app-limit", [
            Check("the system says the app with no icon and no special component is installed", True, installed_on_phone),
            *((Check("the watcher announced it: Android 10 has no package visibility rules", True, bool(events_matching(live, re.escape(FIXTURE_HIDDEN), t3))),
               Check("the report counts it", count_before + 1, live["appCount"]))
              if int(api) < 30 else
              (Check("the watcher did not announce it", [], events_matching(live, re.escape(FIXTURE_HIDDEN), t3)),
               Check("the report does not count it", count_before, live["appCount"]))),
        ])
        # the hidden fixture stays installed: it was there when this script started, and other scripts expect it

        # ------------------------------------------------------------ what no signal announces: the re-read
        if not args.skip_slow:
            t4 = device_ns_then(serial, " ; ".join(runner.enable_commands("admin")))
            window = POLL_SECONDS + 15
            live, ok = wait_live(serial, lambda L: has_finding(L, "lab.fixture.admin") and read_again_after(L, t4), timeout=window, interval=1.0)
            record("reread-device-admin", [
                Check(f"a device administrator switched on from outside shows within {window} s", True, ok),
                Check("the system says it is active", True, "lab.fixture.admin" in sh(serial, "dumpsys device_policy")),
            ])
            print(f"  info: announcements after the administrator change: {[e['what'] for e in live['events'] if iso_to_ns(e['at']) >= t4]}")
            for c in runner.disable_commands("admin"):
                runner.shell(serial, c, check=False)
            t4b = device_ns_then(serial, " ; ".join(runner.enable_commands("perms")))
            live, ok = wait_live(serial, lambda L: has_finding(L, "lab.fixture.perms") and read_again_after(L, t4b), timeout=window, interval=1.0)
            record("reread-special-access", [Check(f"special access (drawing over other apps, usage access, installing apps) given from outside shows within {window} s", True, ok)])
            print(f"  info: announcements after the special access change: {[e['what'] for e in live['events'] if iso_to_ns(e['at']) >= t4b]}")
            for c in runner.disable_commands("perms"):
                runner.shell(serial, c, check=False)
            t5 = device_ns_then(serial, f"locksettings set-pin {PIN}")
            before_lock = (live or {}).get("facts", {}).get("SCREEN_LOCK")
            live, ok = wait_live(serial, lambda L: L.get("facts", {}).get("SCREEN_LOCK") != before_lock and read_again_after(L, t5), timeout=window, interval=1.0)
            record("reread-screen-lock", [Check(f"a screen lock set from outside shows within {window} s", True, ok)])
            print(f"  info: announcements after the screen lock change: {[e['what'] for e in live['events'] if iso_to_ns(e['at']) >= t5]}")
            sh(serial, f"locksettings clear --old {PIN}")
            live, ok = wait_live(serial, lambda L: L.get("facts", {}).get("SCREEN_LOCK") == before_lock, timeout=window, interval=1.0)
            record("reread-screen-lock-removed", [Check("and it is gone from the report after it is removed", True, ok)])

        # ------------------------------------------------------------ in the background
        live = read_live(serial)
        sh(serial, "input keyevent KEYCODE_HOME")
        live, stopped = wait_live(serial, lambda L: L.get("watching") is False, timeout=6)
        away_events = len(live["events"])
        t6 = device_ns_then(serial, "settings put global http_proxy 127.0.0.1:8899")
        time.sleep(6)
        live = read_live(serial)
        silent = len(live["events"]) == away_events and not events_matching(live, "proxy", t6)
        runner.shell(serial, f"am start -n {APP['id']}/io.github.h_sib.declared.app.MainActivity")
        live, ok = wait_live(serial, lambda L: L.get("watching") is True and facts_mention("127.0.0.1")(L) and read_again_after(L, t6), timeout=15)
        record("background", [
            Check("leaving the screen stops the watcher", True, stopped),
            Check("a change made while away is not announced", True, silent),
            Check("coming back reads the phone again and shows the change made while away", True, ok),
            Check("the change made while away was not announced after coming back either (it is a difference, not news)", [], events_matching(live, "proxy", t6)),
        ])
        sh(serial, "settings put global http_proxy :0; settings delete global http_proxy")
        wait_live(serial, facts_dont_mention("127.0.0.1"), timeout=10)

        # ------------------------------------------------------------ screens
        live = start_app(serial)
        t7 = device_ns_then(serial, "settings put global http_proxy 127.0.0.1:8898")
        runner.adb(serial, "install", "-t", "-r", m2.fixture_apk("perms"))
        live, heard = wait_live(serial, lambda L: bool(events_after(L, WORDS["proxy"], t7)) and bool(events_matching(L, f"updated: {re.escape(FIXTURE_PERMS)}$", t7)), timeout=25)
        sh(serial, "settings put global http_proxy :0; settings delete global http_proxy")
        record("live-events-for-the-screen", [Check("this run of the app heard a setting change and an app update to show on screen", True, heard)])
        m1.tap_tab(serial, ui, "More")
        nodes = ui.dump(serial)
        record("live-card", [Check("the More tab has the live view row saying it is watching", True,
                                   ui.find(nodes, r"^Live view$") is not None and ui.find(nodes, r"^Watching\.") is not None)])
        record("live-open", [Check("the live view row opens the live view", True, tap_text(serial, ui, r"^Live view$"))])
        ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m6-live-view-api{api}.png"))
        text = " | ".join(m1.scrolled_texts(serial))
        record("live-view-ui", [
            Check("the live view says it is watching", True, "Watching while this app is on screen" in text),
            Check("it states the measurement with where it was measured", True, "Measured on one Android 14 emulator" in text and "0.06 to 0.3 seconds" in text),
            Check("it states the background limit", True, "nothing at all once the app was no longer running" in text),
            Check("it states that a hidden app is never announced", True, "never tell you that no app was installed" in text),
            Check("it states what is re-read and how often", True, f"read again every {POLL_SECONDS} seconds" in text),
            Check("it lists what was heard, in the product's words", True, WORDS["proxy"] in text and f"An app was updated: {FIXTURE_PERMS}" in text),
        ])
        tap_back(serial, ui)
        record("blind-spots-open", [Check("the full map opens from the More tab", True, tap_text(serial, ui, r"^What this app cannot see$", scroll=True))])
        ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m6-blind-spots-api{api}.png"))
        text = " | ".join(m1.scrolled_texts(serial))
        live = read_live(serial)
        record("blind-spots-ui", [
            Check("both halves are there", True, "On this phone" in text and "On every phone" in text),
            *((Check("the hidden-apps entry says Android 10 shows every installed package", True, "Android 10 shows a normal app all installed packages" in text),)
              if int(api) < 30 else
              (Check("the hidden-apps entry gives the number of apps this phone shows", True, f"{live['appCount']} apps are visible here" in text),
               Check("it states the measured recall limit", True, "apps without an icon and without those powers stayed invisible on every version tested" in text))),
            *((Check("the unreadable signals of this phone are named with their reasons", True, "Advanced Protection" in text and "Android 15 and older have no Advanced Protection mode" in text),)
              if int(api) < 36 else
              (Check("on Android 16 and later the map does not say there is no Advanced Protection mode", False, "Android 15 and older have no Advanced Protection mode" in text),)),
            Check("the background limit and Play Protect are there", True, "Changes while this app is closed" in text and "Google Play Protect" in text),
            Check("the screen-capture limit says an accessibility power can still read the screen", True, "does not stop an app that holds the accessibility power" in text),
            Check("nothing on the map says the phone is safe", False, any(w in text.lower() for w in ("is safe", "protects you", "no threats", "all clear"))),
        ])
        tap_back(serial, ui)

        # ------------------------------------------------------------ large text and touch targets
        density = window_density(serial)
        for scale in ("1.0", "2.0"):
            sh(serial, f"settings put system font_scale {scale}")
            time.sleep(2)
            live = start_app(serial)
            problems, unlabeled, reached = [], [], []
            for label, tab, enter in (("home", "Home", None), ("check tab", "Check", None), ("more tab", "More", None), ("live view", "More", r"^Live view$"),
                                      ("map of blind spots", "More", r"^What this app cannot see$")):
                m1.tap_tab(serial, ui, tab)
                if enter and not tap_text(serial, ui, enter, scroll=True):
                    reached.append(f"{label}: not reachable")
                    continue
                nodes = ui.dump(serial)
                reached.append(f"{label}: ok" if nodes else f"{label}: empty")
                problems += [f"{label}: {p}" for p in touch_target_problems(nodes, density)]
                unlabeled += [f"{label}: {p}" for p in clickable_label_problems(nodes)]
                if scale == "2.0":
                    ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m6-{label.replace(' ', '-')}-fontscale2-api{api}.png"))
                if enter:
                    tap_back(serial, ui)
            record(f"large-text-{scale}", [
                Check(f"font scale {scale}: every tab, the live view and the map are all reachable", ["home: ok", "check tab: ok", "more tab: ok", "live view: ok", "map of blind spots: ok"], reached),
                Check(f"font scale {scale}: every touch target is at least 48dp", [], problems),
                Check(f"font scale {scale}: every clickable has words (its own or inside it)", [], unlabeled),
            ])
    finally:
        for role in m2.ENABLE_ROLES:
            for c in runner.disable_commands(role, a11y_current(serial)):
                runner.shell(serial, c, check=False)
        for c in ("settings put global http_proxy :0", "settings delete global http_proxy", "settings delete global private_dns_mode",
                  "settings delete global private_dns_specifier", "settings delete global development_settings_enabled", f"locksettings clear --old {PIN}"):
            sh(serial, c)
        sh(serial, f"settings put system font_scale {font_before}" if font_before not in (None, "null") else "settings delete system font_scale")
        sh(serial, f"settings put global stay_on_while_plugged_in {stayed_on}" if stayed_on is not None else "svc power stayon false")

    os.makedirs(os.path.dirname(RESULTS_CSV), exist_ok=True)
    new = not os.path.exists(RESULTS_CSV)
    with open(RESULTS_CSV, "a", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        if new:
            w.writerow(["run", "api", "phase", "check", "expected", "actual", "result"])
        w.writerows(rows)
    total = len(rows)
    print(f"{total - failures}/{total} checks passed; rows appended to {RESULTS_CSV}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
