#!/usr/bin/env python3
"""Milestone 1 conformance: does the product see what the system says is there? Standard library only.

Layer 3 of the test plan (DESIGN.md section 13). The product's debug build writes what it found, plus the raw
readings under it, when launched with --ez conformance true. This script puts an EMULATOR into a known state,
verifies that state with the system itself (lab defect D-004: never assume a setup worked), launches the app, pulls
its JSON and compares every field with an independent source:

  * which services are installed and enabled: `cmd package query-services` and `settings get secure`
  * declared abilities: the service's own XML resource, read from the APK with aapt2 (not through PackageManager)
  * app label: `aapt2 dump badging`
  * signing certificate SHA-256: `apksigner verify --print-certs` (parses the APK file, not PackageManager)
  * installer and initiating package: `dumpsys package`
  * permissions in the shipped APK: `aapt2 dump permissions`

Run it from a PowerShell where `. lab\\tools\\env.ps1` has been dot-sourced (apksigner needs JAVA_HOME).
It refuses to touch anything that is not an emulator.
"""
import argparse
import csv
import datetime as dt
import glob
import json
import os
import re
import subprocess
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
PRODUCT = os.path.dirname(HERE)
ROOT = os.path.dirname(PRODUCT)
LAB_TOOLS = os.path.join(ROOT, "lab", "tools")
sys.path.insert(0, LAB_TOOLS)
import runner  # noqa: E402  (reuses the lab's adb plumbing and its setup verification)

SDK = os.environ.get("ANDROID_HOME", r"C:\Users\lordhasib\AndroidLab\sdk")
BUILD_TOOLS = os.path.join(SDK, "build-tools", "36.0.0")
FIXTURE_APK = os.path.join(ROOT, "lab", "fixtures", "build", "outputs", "apk", "a11y", "debug", "fixtures-a11y-debug.apk")
FIXTURE_COMPONENT = runner.COMPONENTS["a11y"]
FIXTURE_PACKAGE = "lab.fixture.a11y"
RESULTS_CSV = os.path.join(PRODUCT, "conformance", "m1_results.csv")
RUN_ID = dt.datetime.now().isoformat(timespec="seconds")
SHOTS_DIR = os.path.join(PRODUCT, "conformance", "screenshots")

# The permissions the product may request (milestone 3 added the network and device sensors). Never INTERNET or location.
NORMAL_PERMISSIONS = ["android.permission.ACCESS_NETWORK_STATE", "android.permission.ACCESS_WIFI_STATE",
                      "android.permission.QUERY_ADVANCED_PROTECTION_MODE"]

# An independent copy of the platform's CAPABILITY_* constants and of the product's ability keys.
CAPABILITY_ATTRS = {
    "canRetrieveWindowContent": 0x1,
    "canRequestTouchExplorationMode": 0x2,
    "canRequestEnhancedWebAccessibility": 0x4,
    "canRequestFilterKeyEvents": 0x8,
    "canControlMagnification": 0x10,
    "canPerformGestures": 0x20,
    "canRequestFingerprintGestures": 0x40,
    "canTakeScreenshot": 0x80,
}
ABILITY_KEYS = {
    0x1: "window-content", 0x2: "touch-exploration", 0x8: "key-events", 0x10: "magnification",
    0x20: "gestures", 0x40: "fingerprint-gestures", 0x80: "screenshot",
}


# ---------------------------------------------------------------- pure helpers (unit tested)

def declared_capability_bits(xmltree_text):
    """Capability bitmask declared in an accessibility-service XML resource, from `aapt2 dump xmltree`."""
    bits = 0
    for m in re.finditer(r":(\w+)\(0x[0-9a-fA-F]+\)=(true|false)", xmltree_text or ""):
        if m.group(2) == "true":
            bits |= CAPABILITY_ATTRS.get(m.group(1), 0)
    return bits


def ability_keys(bits):
    return [ABILITY_KEYS[b] for b in sorted(ABILITY_KEYS) if bits & b]


def parse_label(badging_text):
    # Some APKs print only the `application: label=` line (seen for two lab fixtures), so accept either form.
    m = re.search(r"application-label:'([^']*)'", badging_text or "") or re.search(r"^application: label='([^']*)'", badging_text or "", re.M)
    return m.group(1) if m else None


def parse_service_label(manifest_xmltree, class_suffix):
    """The android:label of the <service> whose android:name ends with class_suffix, from `aapt2 dump xmltree`."""
    element, label, name, found = None, None, None, None
    for line in (manifest_xmltree or "").splitlines() + ["E: end"]:
        m = re.match(r"\s*E: (\S+)", line)
        if m:
            if element == "service" and name and name.endswith(class_suffix):
                found = label
            element, label, name = m.group(1), None, None
            continue
        if element == "service":
            a = re.search(r"android:(label|name)\(0x[0-9a-fA-F]+\)=\"([^\"]*)\"", line)
            if a:
                label, name = (a.group(2), name) if a.group(1) == "label" else (label, a.group(2))
    return found


def parse_package_name(badging_text):
    m = re.search(r"^package: name='([^']+)'", badging_text or "", re.M)
    return m.group(1) if m else None


def parse_signer_digests(apksigner_text):
    return sorted(m.group(1).lower() for m in re.finditer(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", apksigner_text or ""))


def parse_install_source(dumpsys_package_text):
    """(installer, initiating) from `dumpsys package`; the text 'null' becomes None."""
    def pick(key):
        m = re.search(rf"\b{key}=(\S+)", dumpsys_package_text or "")
        return None if not m or m.group(1) == "null" else m.group(1)
    return pick("installerPackageName"), pick("initiatingPackageName")


def parse_installed_services(query_services_text):
    """Normalised `package/class` of every service listed by `cmd package query-services --brief`."""
    out = set()
    for line in (query_services_text or "").splitlines():
        m = re.match(r"^\s+([\w.]+/[\w.$]+)\s*$", line)
        if m:
            out.add(runner.norm_component(m.group(1)))
    return out


def parse_permissions(aapt_permissions_text):
    return sorted(m.group(1) for m in re.finditer(r"^uses-permission: name='([^']+)'", aapt_permissions_text or "", re.M))


def expected_tier(installer, initiating):
    """What the documented rule (DESIGN.md section 6) gives for a non-system app installed by adb."""
    if installer == "com.android.shell" or initiating == "com.android.shell":
        return "ADB"
    if installer is None and initiating is None:
        return "UNKNOWN"
    return None


class Check:
    def __init__(self, name, expected, actual):
        self.name, self.expected, self.actual = name, expected, actual
        self.ok = expected == actual

    def row(self, phase, api):
        return [RUN_ID, api, phase, self.name, json.dumps(self.expected), json.dumps(self.actual), "PASS" if self.ok else "FAIL"]


def compare(dump, truth):
    """Every comparison between what the product reported and what the system says. Pure; returns [Check]."""
    comp = runner.norm_component(FIXTURE_COMPONENT)
    raw = dump.get("raw", {})
    accessibility = raw.get("accessibility", {})
    if not isinstance(accessibility, dict):
        return [Check("raw accessibility reading present", "a reading", accessibility)]
    holders = {runner.norm_component(h["component"]): h for h in dump.get("holders", [])}
    findings = {f["id"]: f for f in dump.get("findings", [])}
    finding_id = f"accessibility:{comp}"
    enabled_truth = truth["enabled"]
    installed_truth = truth["installed"]
    checks = []

    installed_raw = {runner.norm_component(s["component"]) for s in accessibility.get("installed", [])}
    checks.append(Check("installed services = system's list", sorted(installed_truth), sorted(installed_raw)))
    manager = {runner.norm_component(c) for c in accessibility.get("enabledByManager", [])}
    checks.append(Check("enabled (manager) = settings value, among installed", sorted(enabled_truth & installed_truth), sorted(manager)))
    setting = accessibility.get("enabledBySetting")
    checks.append(Check("enabled (cross-check setting) = settings value",
                        sorted(enabled_truth), sorted(runner.norm_component(c) for c in setting) if setting is not None else None))
    checks.append(Check("findings = enabled and installed services",
                        sorted(f"accessibility:{c}" for c in enabled_truth & installed_truth), sorted(findings)))

    fixture_enabled = comp in enabled_truth
    holder = holders.get(comp)
    checks.append(Check("fixture is listed as a holder", True, holder is not None))
    if holder is None:
        return checks
    checks.append(Check("fixture holder.enabled", fixture_enabled, holder["enabled"]))
    checks.append(Check("fixture finding present iff enabled", fixture_enabled, finding_id in findings))

    fact = next((s for s in accessibility["installed"] if runner.norm_component(s["component"]) == comp), None)
    checks.append(Check("declared ability bits = XML resource", truth["capability_bits"], fact["capabilityBits"] if fact else None))
    checks.append(Check("holder abilities = decoded bits", ability_keys(truth["capability_bits"]), holder["abilities"]))
    checks.append(Check("service label = manifest <service> label", truth["service_label"], holder["serviceLabel"]))
    checks.append(Check("no unrecognized ability bits", 0, holder["unrecognizedBits"]))

    package = next((p for p in raw.get("packages", []) if p["pkg"] == FIXTURE_PACKAGE), None)
    checks.append(Check("package facts read", True, package is not None))
    if package is not None:
        checks.append(Check("installer = dumpsys", truth["installer"], package["installing"]))
        checks.append(Check("initiating package = dumpsys", truth["initiating"], package["initiating"]))
        checks.append(Check("signer SHA-256 = apksigner", truth["signers"], sorted(package["signerSha256"])))
        checks.append(Check("app label = aapt2 badging", truth["label"], package["label"]))
        checks.append(Check("system flag = false (not in `pm list -s`)", truth["system"], package["system"]))
        checks.append(Check("install record readable", True, package["installRecordReadable"]))
    want_tier = expected_tier(truth["installer"], truth["initiating"])
    if want_tier:
        checks.append(Check("tier follows the documented rule", want_tier, holder["tier"]))
    if fixture_enabled and finding_id in findings:
        f = findings[finding_id]
        claim = {c["id"]: c for c in f["claim"]}
        checks.append(Check("finding attention (strong abilities, adb origin)", "LOOK_FIRST", f["attention"]))
        checks.append(Check("finding claim confidence (weakest of its evidence)", "HIGH", f["confidence"]))
        checks.append(Check("enabled observation is OBSERVED", "OBSERVED", claim["special-access.accessibility.enabled"]["provenance"]))
        checks.append(Check("capability observation is DECLARED", "DECLARED", claim["special-access.accessibility.capabilities"]["provenance"]))
        checks.append(Check("capability observation value", ability_keys(truth["capability_bits"]), claim["special-access.accessibility.capabilities"]["value"]))
        checks.append(Check("every observation names its source", True, all(c["source"] for c in f["claim"] + f["context"])))
        checks.append(Check("finding states what it cannot tell", True, f["cannotTellCount"] >= 3))
        checks.append(Check("finding offers actions with costs", True, f["actionCount"] >= 1))
    signals = {s["id"]: s for s in dump.get("signals", [])}
    checks.append(Check("accessibility signal available", "AVAILABLE", signals.get("special-access.accessibility.enabled", {}).get("availability")))
    return checks


# ---------------------------------------------------------------- device plumbing

def tool(name, *args):
    exe = os.path.join(BUILD_TOOLS, name)
    p = subprocess.run(["cmd", "/c", exe, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    return p.stdout + p.stderr


def find_product_apk():
    apks = glob.glob(os.path.join(PRODUCT, "app", "build", "outputs", "apk", "debug", "*.apk"))
    if not apks:
        raise SystemExit("No product debug APK. Run: gradle :app:assembleDebug")
    return apks[0]


def install_unstick():
    """A busy fresh image shows an "isn't responding" dialog over the app, and a script that looks at the screen then reads the dialog.
    Every screen dump the scripts take goes through here: if the dialog is up, press Wait and look again."""
    sys.path.insert(0, LAB_TOOLS)
    import ui
    if getattr(ui.dump, "unstick", False):
        return
    original = ui.dump

    def dump(serial):
        nodes = original(serial)
        for _ in range(8):
            if not any(re.search(r"isn.t responding", n["text"] or "", re.IGNORECASE) for n in nodes):
                break
            wait = [n for n in nodes if (n["text"] or "") == "Wait" and n["bounds"]]
            if wait:
                ui.tap_xy(serial, *ui.center(wait[0]["bounds"]))
            time.sleep(3)
            nodes = original(serial)
        return nodes

    dump.unstick = True
    ui.dump = dump


def require_emulator(serial):
    install_unstick()
    chars = runner.shell(serial, "getprop ro.kernel.qemu", check=False)
    product = runner.shell(serial, "getprop ro.product.model", check=False).lower()
    if chars != "1" and "sdk" not in product and "emulator" not in product:
        raise SystemExit(f"{serial} does not look like an emulator; this script changes settings and refuses to run on it.")
    # A fresh image can leave an install waiting for a package verifier that never answers (INSTALL_FAILED_VERIFICATION_FAILURE).
    runner.shell(serial, "settings put global verifier_verify_adb_installs 0", check=False)
    runner.shell(serial, "settings put global package_verifier_enable 0", check=False)


def wake(serial):
    runner.shell(serial, "input keyevent KEYCODE_WAKEUP", check=False)
    runner.shell(serial, "wm dismiss-keyguard", check=False)


def set_fixture_state(serial, enabled):
    current = runner.settings_get(serial, "secure", "enabled_accessibility_services") or ""
    cmds = runner.enable_commands("a11y", current) if enabled else runner.disable_commands("a11y", current)
    for c in cmds:
        runner.shell(serial, c, check=False)
    time.sleep(2)
    truth = runner.collect_truth(serial)
    if runner.role_present("a11y", truth) is not enabled:  # D-004: verify, never assume
        raise SystemExit(f"setup failed: fixture accessibility service is not {'enabled' if enabled else 'disabled'} according to the system")


def system_truth(serial, product_apk):
    fixture_xml = tool("aapt2.exe", "dump", "xmltree", "--file", "res/xml/a11y_config.xml", FIXTURE_APK)
    badging = tool("aapt2.exe", "dump", "badging", FIXTURE_APK)
    pkg = runner.adb(serial, "shell", f"dumpsys package {FIXTURE_PACKAGE}", check=False)
    installer, initiating = parse_install_source(pkg)
    system_list = runner.shell(serial, "pm list packages -s", check=False)
    return {
        "enabled": runner.split_components(runner.settings_get(serial, "secure", "enabled_accessibility_services")),
        "installed": parse_installed_services(runner.adb(serial, "shell", "cmd package query-services --brief -a android.accessibilityservice.AccessibilityService", check=False)),
        "capability_bits": declared_capability_bits(fixture_xml),
        "label": parse_label(badging),
        "service_label": parse_service_label(tool("aapt2.exe", "dump", "xmltree", "--file", "AndroidManifest.xml", FIXTURE_APK), "FixtureA11yService"),
        "signers": parse_signer_digests(tool("apksigner.bat", "verify", "--print-certs", FIXTURE_APK)),
        "installer": installer,
        "initiating": initiating,
        "system": f"package:{FIXTURE_PACKAGE}" in system_list.split(),
    }


def launch_and_pull(serial, app_id, timeout=240):
    run_id = uuid.uuid4().hex[:10]
    wake(serial)
    runner.shell(serial, f"am start -S -W -n {app_id}/io.github.h_sib.declared.app.MainActivity --ez conformance true --es runId {run_id} --ez allowCapture true --ez expandAll true --es start check")
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(2)
        raw = runner.adb(serial, "exec-out", "run-as", app_id, "cat", f"files/conformance-{run_id}.json", check=False)
        try:
            return json.loads(raw)
        except ValueError:
            continue
    raise TimeoutError(f"the app wrote no conformance file within {timeout}s (run {run_id})")


def scrolled_texts(serial, max_swipes=40):
    """Every text on a scrollable screen: a screen dump only holds what is on screen, so scroll and merge."""
    seen = []
    for i in range(max_swipes + 1):
        texts = [n["text"] for n in screen_text(serial) if n["text"]]
        new = [t for t in texts if t not in seen]
        seen += new
        if i > 0 and not new:
            break
        runner.shell(serial, "input swipe 540 1800 540 700 400", check=False)
        time.sleep(1)
    return seen


FIRST_SCREEN = re.compile(r"^(Next|Skip|Start the review|Finish the review|Check this phone|Check|Home)$")


def scroll_find(serial, ui, pattern, max_swipes=40):
    """Scroll down until a node matching `pattern` is on screen; return it, or None. The cap is generous: at large text a screen is several times longer."""
    for _ in range(max_swipes + 1):
        node = ui.find(ui.dump(serial), pattern)
        if node:
            return node
        runner.shell(serial, "input swipe 540 1800 540 700 400", check=False)
        time.sleep(1)
    return None


def scroll_top(serial):
    for _ in range(12):
        runner.shell(serial, "input swipe 540 600 540 2000 250", check=False)
    time.sleep(1)


def tap_text(serial, ui, pattern, scroll=False):
    node = scroll_find(serial, ui, pattern) if scroll else ui.find(ui.dump(serial), pattern)
    if not node:
        return False
    ui.tap_xy(serial, *ui.center(node["bounds"]))
    time.sleep(1.5)
    return True


def tap_tab(serial, ui, name):
    """Press an entry of the bottom bar (Home, Check, Paths, History, More). A tab's name is often also a screen's title, and the bar is the lowest of them."""
    rx = re.compile(rf"^{re.escape(name)}$", re.IGNORECASE)
    for _ in range(6):
        hits = [n for n in ui.dump(serial) if n["bounds"] and (rx.search(n["text"] or "") or rx.search(n["desc"] or ""))]
        if hits:
            ui.tap_xy(serial, *ui.center(max(hits, key=lambda n: n["bounds"][1])["bounds"]))
            time.sleep(2)
            return True
        time.sleep(1.5)
    return False


def open_tab(serial, ui, name, marker, attempts=4):
    """Press a tab until the screen it opens shows `marker`: the first press right after a start can land before the app takes touches."""
    for _ in range(attempts):
        tap_tab(serial, ui, name)
        time.sleep(1.5)
        if ui.find(ui.dump(serial), marker):
            return True
    return False


def go_back(serial):
    runner.shell(serial, "input keyevent KEYCODE_BACK", check=False)
    time.sleep(1.5)


def ensure_baseline(serial, app_id, ui):
    """Start the app normally and, if it asks for the first review, complete it (everything unanswered). Returns True if it had to.

    A fresh install shows the three intro pages and then the review before anything else, so every script that looks at the app
    on screen must first get past them, the way a person would: Next, Next, Start the review, Finish the review.
    """
    wake(serial)
    runner.shell(serial, f"am start -S -W -n {app_id}/io.github.h_sib.declared.app.MainActivity --ez allowCapture true --ez expandAll true --es start check")
    time.sleep(4)
    nodes = []
    for _ in range(30):  # a fresh image can take a while to draw the first screen
        nodes = ui.dump(serial)
        if any(FIRST_SCREEN.search(n["text"] or "") for n in nodes):
            break
        time.sleep(2)
    if not (ui.find(nodes, r"^Next$") or ui.find(nodes, r"^Start the review$") or ui.find(nodes, r"^Finish the review$")):
        return False
    for _ in range(3):
        if ui.find(ui.dump(serial), r"^Start the review$"):
            break
        tap_text(serial, ui, r"^Next$")
    tap_text(serial, ui, r"^Start the review$")
    tap_text(serial, ui, r"^Finish the review$", scroll=True)
    time.sleep(3)
    return True


def check_texts(serial, app_id, ui):
    """Everything the Check tab says, with every section open: baseline done, app started on the Check tab, then scrolled from the top."""
    ensure_baseline(serial, app_id, ui)
    wake(serial)
    runner.shell(serial, f"am start -S -W -n {app_id}/io.github.h_sib.declared.app.MainActivity --ez allowCapture true --ez expandAll true --es start check")
    for _ in range(45):  # the Check tab says when it was read; a slow image is still "Reading this phone..." for a while
        if ui.find(ui.dump(serial), r"^Read at "):
            break
        time.sleep(2)
    scroll_top(serial)
    return " | ".join(scrolled_texts(serial))


def screen_text(serial):
    sys.path.insert(0, LAB_TOOLS)
    import ui
    return ui.dump(serial)


def main(argv=None):
    print("conformance_m1.py is superseded: its runner was written for the accessibility-only report. Use conformance_m2.py"
          " (accessibility and the other capability kinds, with the same checks and more) and conformance_m3.py.")
    print("This file still provides the shared helpers (aapt2, apksigner, dumpsys parsers) that those scripts import.")
    return 2


def _legacy_main(argv=None):  # kept for reference; unreachable
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--skip-install", action="store_true")
    args = ap.parse_args(argv)
    serial = args.serial
    require_emulator(serial)

    product_apk = find_product_apk()
    app_id = parse_package_name(tool("aapt2.exe", "dump", "badging", product_apk))
    api = runner.shell(serial, "getprop ro.build.version.sdk")
    if not args.skip_install:
        runner.adb(serial, "install", "-t", "-r", FIXTURE_APK)
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

    permissions = parse_permissions(tool("aapt2.exe", "dump", "permissions", product_apk))
    record("manifest", [Check("APK requests only the three normal permissions and AndroidX's private one",
                              sorted([f"{app_id}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", *NORMAL_PERMISSIONS]), sorted(permissions))])

    set_fixture_state(serial, False)
    record("fixture-disabled", compare(launch_and_pull(serial, app_id), system_truth(serial, product_apk)))

    set_fixture_state(serial, True)
    dump = launch_and_pull(serial, app_id)
    record("fixture-enabled", compare(dump, system_truth(serial, product_apk)))

    # What the user actually sees: read the screen, not the app's own idea of it.
    wake(serial)
    runner.shell(serial, f"am start -S -W -n {app_id}/io.github.h_sib.declared.app.MainActivity --ez allowCapture true --ez expandAll true --es start check")
    time.sleep(4)
    texts = " | ".join(n["text"] for n in screen_text(serial) if n["text"])
    os.makedirs(SHOTS_DIR, exist_ok=True)
    import ui
    ui.screenshot(serial, os.path.join(SHOTS_DIR, f"inventory-api{api}.png"))
    record("on-screen", [
        Check("inventory shows the finding title", True, "Fixture a11y has an accessibility service switched on" in texts),
        Check("inventory shows 'Look at this first'", True, "Look at this first" in texts),
        Check("inventory states what it cannot see", True, "What this app cannot see" in texts),
    ])
    nodes = screen_text(serial)
    card = ui.find(nodes, r"Fixture a11y has an accessibility service switched on")
    if card:
        ui.tap_xy(serial, *ui.center(card["bounds"]))
        time.sleep(2)
        ui.screenshot(serial, os.path.join(SHOTS_DIR, f"detail-api{api}.png"))
        detail = " | ".join(scrolled_texts(serial))
        record("on-screen-detail", [
            Check(f"detail shows section '{s}'", True, s in detail)
            for s in ["What I saw", "Where I got it", "What it means", "How sure I am", "What I cannot tell", "What you can do, and what it costs"]
        ])
    else:
        record("on-screen-detail", [Check("inventory card is on screen and tappable", True, False)])

    set_fixture_state(serial, False)  # leave the emulator as found

    os.makedirs(os.path.dirname(RESULTS_CSV), exist_ok=True)
    new = not os.path.exists(RESULTS_CSV)
    with open(RESULTS_CSV, "a", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        if new:
            w.writerow(["run", "api", "phase", "check", "expected", "actual", "result"])
        w.writerows(rows)
    print(f"{len(rows) - failures}/{len(rows)} checks passed; rows appended to {RESULTS_CSV}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
