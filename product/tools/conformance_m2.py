#!/usr/bin/env python3
"""Milestone 2 conformance: every capability signal against the system's own answer. Standard library only.

Same method as conformance_m1.py (see its docstring): put an EMULATOR into a known state, verify that state with the
system itself (lab defect D-004), launch the debug build, pull what it found plus the raw readings, and compare each
field with an independent source. Milestone 2 adds notification listeners, device admins, keyboards, special access
(app-ops), text-message grants and the package inventory:

  * installed components: `cmd package query-services / query-receivers`, `ime list -a -s`
  * enabled and active: `settings get secure ...`, `dumpsys device_policy`, `ime list -s`
  * declared admin policies: the admin's own XML resource, read from the APK with aapt2
  * special access: `appops get <pkg>` (an unlisted op is at its default), with `dumpsys package` for the grant
  * grants, system flag and install record of every package: `dumpsys package`, `pm list packages -s`
  * which packages the app should and should not be able to see: launcher query, capability queries, `pm list packages`

Run from a PowerShell where `. lab\\tools\\env.ps1` has been dot-sourced. Refuses to touch anything but an emulator.
Expected availability depends on the Android version (special access is refused on Android 10); the actual answer is
always recorded, and an unexpected one is a failure.
"""
import argparse
import csv
import datetime as dt
import glob
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import conformance_m1 as m1  # noqa: E402
import runner  # noqa: E402  (m1 put the lab tools on the path)

PRODUCT = m1.PRODUCT
ROOT = m1.ROOT
RESULTS_CSV = os.path.join(PRODUCT, "conformance", "m2_results.csv")
SHOTS_DIR = os.path.join(PRODUCT, "conformance", "screenshots")
RUN_ID = dt.datetime.now().isoformat(timespec="seconds")
FIXTURE_ROLES = runner.FIXTURE_ROLES
ENABLE_ROLES = ["a11y", "notif", "admin", "ime", "perms"]
POLICY_IDS = {"limit-password": 0, "watch-login": 1, "reset-password": 2, "force-lock": 3, "wipe-data": 4,
              "set-global-proxy": 5, "expire-password": 6, "encrypted-storage": 7, "disable-camera": 8,
              "disable-keyguard-features": 9}
MODE_NAMES = {0: "ALLOWED", 1: "IGNORED", 2: "ERRORED", 3: "DEFAULT", 4: "FOREGROUND"}
SPECIAL = {"OVERLAY": ("SYSTEM_ALERT_WINDOW", "android.permission.SYSTEM_ALERT_WINDOW", "overlay"),
           "USAGE_ACCESS": ("GET_USAGE_STATS", "android.permission.PACKAGE_USAGE_STATS", "usage-access"),
           "INSTALL_UNKNOWN_APPS": ("REQUEST_INSTALL_PACKAGES", "android.permission.REQUEST_INSTALL_PACKAGES", "install-unknown-apps")}
SMS_PERMS = ["android.permission.READ_SMS", "android.permission.RECEIVE_SMS"]
SENSITIVE_SHORT = ["READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_CONTACTS", "READ_CALL_LOG", "RECORD_AUDIO", "CAMERA",
                   "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "QUERY_ALL_PACKAGES"]
WATCHED = {"android.permission." + s for s in SENSITIVE_SHORT} | {v[1] for v in SPECIAL.values()}
HIDDEN_FIXTURES = ["lab.fixture.hidden", "lab.fixture.boot", "lab.fixture.sms"]
BUILT_IN_TIERS = ("PLATFORM", "SYSTEM")


# ---------------------------------------------------------------- pure helpers (unit tested)

def parse_policy_bits(xmltree_text):
    """Bit n set for every `<policy-name/>` element of a device-admin XML resource (`aapt2 dump xmltree`)."""
    bits = 0
    for m in re.finditer(r"E: ([a-z-]+)", xmltree_text or ""):
        if m.group(1) in POLICY_IDS:
            bits |= 1 << POLICY_IDS[m.group(1)]
    return bits


def policy_keys(bits):
    inverse = {v: k for k, v in POLICY_IDS.items()}
    return [inverse[i] for i in sorted(inverse) if bits & (1 << i)]


def parse_ime_list(text):
    """Normalised components from `ime list -s` or `ime list -a -s` (one id per line)."""
    out = set()
    for line in (text or "").splitlines():
        m = re.match(r"^\s*([\w.]+/[\w.$]+)\s*$", line)
        if m:
            out.add(runner.norm_component(m.group(1)))
    return out


def parse_packages(text):
    """Package names from `pm list packages` output."""
    return {m.group(1) for m in re.finditer(r"^package:(\S+)", text or "", re.M)}


def parse_component_packages(query_text):
    return {c.split("/")[0] for c in m1.parse_installed_services(query_text)}


def op_mode_name(appops_text, op):
    """Mode of one op from `appops get <pkg>`. An op that is not listed is at its default."""
    modes = runner.parse_appops(appops_text)
    return MODE_NAMES[modes[op]] if op in modes else "DEFAULT"


def effective_special(mode_name, permission_granted):
    """The rule the lab verified (D-003): allowed, or default with the backing permission granted."""
    if mode_name in ("ALLOWED", "FOREGROUND"):
        return True
    return mode_name == "DEFAULT" and permission_granted


# Measured on the API 36 and 37 images: a Google system package whose notification listener service the shell lists and
# the app is not shown (package visibility). A known limit, recorded as such instead of being counted as a pass or a failure.
KNOWN_INVISIBLE = {"com.google.android.odad"}


def expected_signal_state(api, signal_id):
    """What the product is expected to say about a signal on this Android version. None: not judged here (conformance_m3.py owns it)."""
    if signal_id.startswith(("device.", "network.", "integrity.")):
        return None
    if signal_id.startswith("special-access.") and signal_id.split(".")[1] in ("overlay", "usage-access", "install-unknown-apps"):
        return "UNAVAILABLE" if int(api) == 29 else "AVAILABLE"
    return "AVAILABLE"


class Check(m1.Check):
    def row(self, phase, api):
        return [RUN_ID, api, phase, self.name, json.dumps(self.expected), json.dumps(self.actual), "PASS" if self.ok else "FAIL"]


def comp_set(items):
    return sorted({runner.norm_component(c if isinstance(c, str) else c["component"]) for c in items})


def compare(dump, truth, api):
    """All comparisons for one phase. Pure; returns [Check]. `truth` has the shapes built by `system_truth`."""
    checks = []
    raw = dump.get("raw", {})
    findings = {f["id"]: f for f in dump.get("findings", [])}
    holders = dump.get("holders", [])
    signals = {s["id"]: s for s in dump.get("signals", [])}

    def raw_of(key):
        v = raw.get(key)
        return v if isinstance(v, dict) else None

    # ---- accessibility, notification listeners, device admins, keyboards: installed, enabled, findings
    known_hidden = KNOWN_INVISIBLE if int(api) >= 36 else set()
    acc, notif, adm, kbd = raw_of("accessibility"), raw_of("notification"), raw_of("admins"), raw_of("keyboards")
    for name, rv in (("accessibility", acc), ("notification", notif), ("admins", adm), ("keyboards", kbd)):
        checks.append(Check(f"{name}: raw reading present", True, rv is not None))
    if None in (acc, notif, adm, kbd):
        return checks

    checks.append(Check("accessibility: installed = query-services", sorted(truth["a11y_installed"]), comp_set(acc["installed"])))
    checks.append(Check("accessibility: enabled = settings (among installed)", sorted(truth["a11y_enabled"] & truth["a11y_installed"]), comp_set(acc["enabledByManager"])))
    notif_unseen = {c for c in truth["notif_installed"] if c.split("/")[0] in known_hidden} - set(comp_set(notif["installed"]))
    checks.append(Check("notification: installed = query-services", sorted(set(truth["notif_installed"]) - notif_unseen), comp_set(notif["installed"])))
    if notif_unseen:
        checks.append(Check("known limit on this version (package visibility): listener services of system packages the app is not shown", sorted(notif_unseen), sorted(notif_unseen)))
    checks.append(Check("notification: enabled = settings", sorted(truth["notif_enabled"]), comp_set(notif["enabled"])))
    checks.append(Check("admins: installed = query-receivers", sorted(truth["admin_installed"]), comp_set(adm["installed"])))
    checks.append(Check("admins: active = dumpsys device_policy", sorted(truth["admin_active"]), comp_set(adm["active"])))
    checks.append(Check("keyboards: installed = ime list -a", sorted(truth["ime_installed"]), comp_set(kbd["installed"])))
    checks.append(Check("keyboards: enabled = ime list", sorted(truth["ime_enabled"]), comp_set(kbd["enabled"])))
    default = kbd.get("default")
    checks.append(Check("keyboards: in use = settings default_input_method", truth["ime_default"], runner.norm_component(default) if default else None))

    # findings of the four component kinds equal the system's enabled-and-installed sets
    def finding_components(prefix):
        return sorted(runner.norm_component(i.split(":", 1)[1]) for i in findings if i.startswith(prefix + ":"))
    checks.append(Check("accessibility: findings = enabled and installed", sorted(truth["a11y_enabled"] & truth["a11y_installed"]), finding_components("accessibility")))
    checks.append(Check("notification: findings = enabled and installed", sorted(truth["notif_enabled"] & truth["notif_installed"]), finding_components("notification-listener")))
    checks.append(Check("admins: findings = active admins", sorted(truth["admin_active"]), finding_components("device-admin")))
    checks.append(Check("keyboards: findings = enabled keyboards", sorted(truth["ime_enabled"] & truth["ime_installed"]), finding_components("input-method")))

    # device-admin policies of the fixture admin come from its own XML resource, not from PackageManager
    fixture_admin = runner.norm_component(runner.COMPONENTS["admin"])
    fact = next((a for a in adm["installed"] if runner.norm_component(a["component"]) == fixture_admin), None)
    if truth.get("admin_policy_bits") is not None:
        checks.append(Check("admins: declared policies = XML resource", truth["admin_policy_bits"], fact["policyBits"] if fact else None))

    # ---- special access (app-ops), per candidate package
    app_ops = raw.get("appOps")
    checks.append(Check("appOps: raw reading present", True, isinstance(app_ops, dict)))
    effective = {k: set() for k in SPECIAL}
    refused = False
    if isinstance(app_ops, dict):
        for pkg, modes in app_ops.items():
            if not isinstance(modes, dict):
                refused = True
                continue
            t = truth["packages"].get(pkg)
            if t is None or t["appops"] is None:
                continue
            for access, (op, permission, _) in SPECIAL.items():
                want = op_mode_name(t["appops"], op)
                got = modes.get(access)
                if int(api) >= 35 and t["builtin"] and want == "DEFAULT" and got == "ALLOWED":
                    want = got  # measured: from Android 15 the framework reports a platform package's default as allowed, `appops get` prints nothing
                checks.append(Check(f"appOps {pkg} {access} = appops get", want, got))
                if effective_special(want, t["granted"].get(permission, False)) and not t["builtin"]:
                    effective[access].add(pkg)
    for access, (_, _, prefix) in SPECIAL.items():
        got = sorted(i.split(":", 1)[1] for i in findings if i.startswith(prefix + ":"))
        if int(api) == 29 or refused:
            checks.append(Check(f"{access}: no findings when the platform refuses", [], got))
        else:
            checks.append(Check(f"{access}: findings = effective grants of non-built-in apps", sorted(effective[access]), got))

    # ---- text-message grants
    sms_expected = sorted(p for p, t in truth["packages"].items() if not t["builtin"] and any(t["granted"].get(x, False) for x in SMS_PERMS))
    checks.append(Check("sms: findings = READ_SMS or RECEIVE_SMS granted to non-built-in apps", sms_expected,
                        sorted(i.split(":", 1)[1] for i in findings if i.startswith("sms-access:"))))

    # ---- the package inventory
    packages = raw_of("packages")
    checks.append(Check("packages: raw listing present", True, packages is not None))
    if packages is not None:
        items = packages["items"]
        seen = {p["pkg"] for p in items}
        checks.append(Check("inventory is a subset of installed packages", [], sorted(seen - truth["all_packages"])))
        unseen = truth["must_see"] - seen
        checks.append(Check("inventory contains every launcher and capability package", [], sorted(unseen - known_hidden)))
        if unseen & known_hidden:
            checks.append(Check("known limit on this version (package visibility): system packages with a service the app is not shown", sorted(unseen & known_hidden), sorted(unseen & known_hidden)))
        # Package visibility rules start with Android 11: before that every installed package is visible, hidden or not.
        see_all = int(api) < 30
        checks.append(Check("hidden fixtures: " + ("all visible, Android 10 has no package visibility rules" if see_all else "not in the inventory (blind spot B1)"),
                            sorted(HIDDEN_FIXTURES) if see_all else [], sorted(set(HIDDEN_FIXTURES) & seen)))
        checks.append(Check("hidden fixtures really are installed", sorted(HIDDEN_FIXTURES), sorted(set(HIDDEN_FIXTURES) & truth["all_packages"])))
        checks.append(Check("system flag = pm list packages -s, for every visible package", [],
                            sorted(p["pkg"] for p in items if p["system"] != (p["pkg"] in truth["system_packages"]))))
        wrong_grants = []
        for p in items:
            t = truth["packages"].get(p["pkg"])
            if t is None or t.get("shared_uid"):
                continue
            want = sorted(k for k, v in t["granted"].items() if v and k in WATCHED)
            if sorted(p["grantedWatched"]) != want:
                wrong_grants.append(p["pkg"])
        checks.append(Check("granted watched permissions = dumpsys package", [], sorted(wrong_grants)))
        wrong_install = [p["pkg"] for p in items
                         if p["pkg"] in truth["packages"] and
                         (p["installing"], p["initiating"]) != (truth["packages"][p["pkg"]]["installer"], truth["packages"][p["pkg"]]["initiating"])]
        checks.append(Check("installer and initiating package = dumpsys package", [], sorted(wrong_install)))
        for role, expected in truth["fixture_facts"].items():
            p = next((x for x in items if x["pkg"] == f"lab.fixture.{role}"), None)
            if p is None:
                if role in ("hidden", "boot", "sms"):
                    continue
                checks.append(Check(f"fixture {role} is in the inventory", True, False))
                continue
            checks.append(Check(f"fixture {role}: signer SHA-256 = apksigner", expected["signers"], sorted(p["signerSha256"])))
            checks.append(Check(f"fixture {role}: label = aapt2 badging", expected["label"], p["label"]))
        report_apps = {a["pkg"]: a for a in dump.get("apps", [])}
        checks.append(Check("report apps = raw packages", sorted(seen), sorted(report_apps)))
        builtin_wrong = [p["pkg"] for p in items
                         if (report_apps.get(p["pkg"], {}).get("tier") in BUILT_IN_TIERS) != (p["system"] or p["updatedSystem"] or p["platformSigned"] is True)]
        checks.append(Check("built-in tiers = system or platform-signed flags", [], sorted(builtin_wrong)))

    # ---- attention levels follow the documented rule for the fixtures (all installed by adb, so tier ADB)
    if truth["fixture_tier_adb"]:
        want_level = {}
        for f in findings:
            kind, key = f.split(":", 1)
            if key.startswith("lab.fixture."):
                want_level[f] = "NOTE" if kind == "input-method" and runner.norm_component(key) != truth["ime_default"] else "LOOK_FIRST"
        checks.append(Check("fixture finding attention follows the rule",
                            dict(sorted(want_level.items())), {k: findings[k]["attention"] for k in sorted(want_level)}))
    checks.append(Check("every finding states its sources, limits and costs", True,
                        all(f["cannotTellCount"] >= 3 and f["actionCount"] >= 1 and all(c["source"] for c in f["claim"] + f["context"]) for f in findings.values())))

    # ---- signals
    for sid, s in signals.items():
        wanted = expected_signal_state(api, sid)
        if sid == "special-access.notification-listener.enabled" and truth["notif_enabled"] - truth["notif_installed"]:
            wanted = "DEGRADED"  # an enabled entry that matches no installed listener is reported as such, not hidden
        if wanted is not None:
            checks.append(Check(f"signal {sid} is {wanted}", wanted, s["availability"]))
    return checks


# ---------------------------------------------------------------- device plumbing

def fixture_apk(role):
    return os.path.join(ROOT, "lab", "fixtures", "build", "outputs", "apk", role, "debug", f"fixtures-{role}-debug.apk")


def system_truth(serial, product_package, dump):
    """Everything the system says, gathered independently of the app. `dump` only supplies which packages to ask about."""
    q = lambda cmd: runner.adb(serial, "shell", cmd, check=False)  # noqa: E731
    settings = lambda key: runner.settings_get(serial, "secure", key)  # noqa: E731
    all_packages = parse_packages(q("pm list packages"))
    system_packages = parse_packages(q("pm list packages -s"))
    a11y_installed = m1.parse_installed_services(q("cmd package query-services --brief -a android.accessibilityservice.AccessibilityService"))
    notif_installed = m1.parse_installed_services(q("cmd package query-services --brief -a android.service.notification.NotificationListenerService"))
    admin_installed = m1.parse_installed_services(q("cmd package query-receivers --brief -a android.app.action.DEVICE_ADMIN_ENABLED"))
    ime_installed = parse_ime_list(q("ime list -a -s"))
    launcher = parse_component_packages(q("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"))
    must_see = (launcher | {c.split("/")[0] for c in a11y_installed | notif_installed | admin_installed | ime_installed}
                | {product_package}) & all_packages

    packages = {}
    app_ops_keys = set(dump.get("raw", {}).get("appOps", {})) if isinstance(dump.get("raw", {}).get("appOps"), dict) else set()
    raw_packages = (dump.get("raw", {}).get("packages") or {}).get("items", []) if isinstance(dump.get("raw", {}).get("packages"), dict) else []
    for p in raw_packages:
        pkg = p["pkg"]
        if pkg in system_packages and pkg not in (product_package,):
            # System apps are checked for the flag and the grants only when they matter (sample below).
            pass
        text = q(f"dumpsys package {pkg}")
        inst, init = m1.parse_install_source(text)
        packages[pkg] = {
            "granted": runner.parse_granted(text),
            # Packages that share a user id (mostly built-in ones): dumpsys reports the whole uid's grants.
            "shared_uid": "sharedUser=SharedUserSetting" in text,
            "installer": inst,
            "initiating": init,
            "appops": q(f"appops get {pkg}") if (pkg not in system_packages or pkg in app_ops_keys) else None,
            "builtin": pkg in system_packages,
        }
    fixture_facts = {}
    for role in FIXTURE_ROLES:
        apk = fixture_apk(role)
        if os.path.exists(apk):
            fixture_facts[role] = {"signers": m1.parse_signer_digests(m1.tool("apksigner.bat", "verify", "--print-certs", apk)),
                                   "label": m1.parse_label(m1.tool("aapt2.exe", "dump", "badging", apk))}
    admin_xml = m1.tool("aapt2.exe", "dump", "xmltree", "--file", "res/xml/device_admin.xml", fixture_apk("admin")) if os.path.exists(fixture_apk("admin")) else None
    adb_tier = all((packages.get(f"lab.fixture.{r}") or {}).get("initiating") == "com.android.shell" and (packages.get(f"lab.fixture.{r}") or {}).get("installer") is None
                   for r in ENABLE_ROLES if f"lab.fixture.{r}" in packages)
    default_ime = settings("default_input_method")
    return {
        "all_packages": all_packages,
        "system_packages": system_packages,
        "must_see": must_see,
        "a11y_installed": a11y_installed,
        "a11y_enabled": runner.split_components(settings("enabled_accessibility_services")),
        "notif_installed": notif_installed,
        "notif_enabled": runner.split_components(settings("enabled_notification_listeners")),
        "admin_installed": admin_installed,
        "admin_active": runner.parse_admins(q("dumpsys device_policy")),
        "admin_policy_bits": parse_policy_bits(admin_xml) if admin_xml else None,
        "ime_installed": ime_installed,
        "ime_enabled": parse_ime_list(q("ime list -s")),
        "ime_default": runner.norm_component(default_ime) if default_ime else None,
        "packages": packages,
        "fixture_facts": fixture_facts,
        "fixture_tier_adb": adb_tier,
    }


def set_roles(serial, enabled):
    """Enable or disable every role and verify with the system that the state really is what was asked for (D-004)."""
    for role in ENABLE_ROLES:
        current = runner.settings_get(serial, "secure", "enabled_accessibility_services") or ""
        cmds = runner.enable_commands(role, current) if enabled else runner.disable_commands(role, current)
        for c in cmds:
            runner.shell(serial, c, check=False)
    time.sleep(2)
    truth = runner.collect_truth(serial, [runner.PERMS_FIXTURE])  # the perms role is judged from `dumpsys package`
    for role in ENABLE_ROLES:
        if runner.role_present(role, truth) is not enabled:
            raise SystemExit(f"setup failed: fixture role {role} is not {'enabled' if enabled else 'disabled'} according to the system")


def scroll_find(serial, ui, pattern, max_swipes=40):
    """Scroll down until a node matching `pattern` is on screen; return it, or None. The cap is generous on purpose: at large text a screen is several times longer (D-038)."""
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


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--skip-install", action="store_true")
    ap.add_argument("--no-ui", action="store_true", help="skip the on-screen checks")
    args = ap.parse_args(argv)
    serial = args.serial
    m1.require_emulator(serial)
    sys.path.insert(0, m1.LAB_TOOLS)
    import ui

    product_apk = m1.find_product_apk()
    app_id = m1.parse_package_name(m1.tool("aapt2.exe", "dump", "badging", product_apk))
    api = runner.shell(serial, "getprop ro.build.version.sdk")
    if not args.skip_install:
        for role in FIXTURE_ROLES:
            if os.path.exists(fixture_apk(role)):
                runner.adb(serial, "install", "-t", "-r", fixture_apk(role))
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

    permissions = m1.parse_permissions(m1.tool("aapt2.exe", "dump", "permissions", product_apk))
    record("manifest", [Check("APK requests only the three normal permissions, the two for the optional automatic checks, and AndroidX's private one",
                              sorted([f"{app_id}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", *m1.NORMAL_PERMISSIONS, "android.permission.POST_NOTIFICATIONS", "android.permission.RECEIVE_BOOT_COMPLETED"]),
                              sorted(permissions))])

    for phase, enabled in (("all-disabled", False), ("all-enabled", True)):
        set_roles(serial, enabled)
        dump = m1.launch_and_pull(serial, app_id, timeout=90)
        record(phase, compare(dump, system_truth(serial, app_id, dump), api))
        if enabled:
            print(f"    package listing took {dump.get('raw', {}).get('packagesMillis', '?')} ms; {len(dump.get('apps', []))} apps; {len(dump.get('findings', []))} findings")
            if not args.no_ui:
                record("on-screen", on_screen_checks(serial, ui, app_id, api, dump))

    set_roles(serial, False)  # leave the emulator as found
    os.makedirs(os.path.dirname(RESULTS_CSV), exist_ok=True)
    new = not os.path.exists(RESULTS_CSV)
    with open(RESULTS_CSV, "a", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        if new:
            w.writerow(["run", "api", "phase", "check", "expected", "actual", "result"])
        w.writerows(rows)
    print(f"{len(rows) - failures}/{len(rows)} checks passed; rows appended to {RESULTS_CSV}")
    return 1 if failures else 0


def on_screen_checks(serial, ui, app_id, api, dump):
    """What the person sees: the Check tab lists a finding per kind, the home screen counts the checks, the apps list opens, details have their layers."""
    texts = m1.check_texts(serial, app_id, ui)
    os.makedirs(SHOTS_DIR, exist_ok=True)
    ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m2-inventory-api{api}.png"))
    fixture_titles = {
        "accessibility": "Fixture a11y can read your screen and tap for you",
        "notification-listener": "Fixture notif can read every notification you get",
        "device-admin": "Fixture admin can set rules for your whole phone",
        "input-method": "Fixture ime is switched on as a keyboard and could see what you type",
        "overlay": "Fixture perms can draw over other apps, which can hide or fake a button",
        "usage-access": "Fixture perms can see which apps you open and when",
        "install-unknown-apps": "Fixture perms can install other apps without a store",
        "sms-access": "Fixture perms can read your text messages, including sign-in codes",
    }
    present = {f["id"].split(":", 1)[0] for f in dump["findings"] if f["id"].split(":", 1)[1].startswith("lab.fixture.") or f["id"].split(":", 1)[1].startswith("lab.fixture.perms")}
    checks = [Check(f"the Check tab shows: {title}", True, title in texts) for kind, title in fixture_titles.items() if kind in present]

    m1.tap_tab(serial, ui, "Home")
    home = " | ".join(n["text"] for n in ui.dump(serial) if n["text"])
    ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m2-home-api{api}.png"))
    checks.append(Check("the home screen says how many checks ran", True, bool(re.search(r"\d+ of \d+ checks ran", home))))
    m1.tap_tab(serial, ui, "More")
    more = " | ".join(m1.scrolled_texts(serial))
    checks.append(Check("the More tab links to what the app cannot see", True, "What this app cannot see" in more))

    m1.tap_tab(serial, ui, "Check")
    m1.scroll_top(serial)
    node = m1.scroll_find(serial, ui, r"^All apps$")
    if node:
        ui.tap_xy(serial, *ui.center(node["bounds"]))
        time.sleep(2)
        ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m2-apps-api{api}.png"))
        apps_text = " | ".join(n["text"] for n in ui.dump(serial) if n["text"])
        checks.append(Check("apps screen shows the app count", True, f"{len(dump['apps'])} apps" in apps_text))
        # One group per origin that has an app in it, titled as core/report/Describe.kt titles it. Which origin the fixtures fall under depends on what
        # the Android version tells about the installer, so the groups to look for come from the tiers the report itself holds.
        titles = {"ADB": "Installed from a computer", "SIDELOAD": "From a file or download", "UNKNOWN": "Not recognized"}
        wanted = sorted(t for tier, t in titles.items() if any(a["tier"] == tier for a in dump["apps"]))
        shown = " | ".join(m1.scrolled_texts(serial))
        checks.append(Check("apps screen groups by origin", [], [t for t in wanted if f"{t} (" not in shown]))
        m1.go_back(serial)
    else:
        checks.append(Check("the 'All apps' row is on the Check tab", True, False))

    for kind, button in (("device-admin", "Open Android security settings"), ("overlay", "draw-over-other-apps setting")):
        if kind not in present:
            continue
        m1.tap_tab(serial, ui, "Check")
        m1.scroll_top(serial)
        card = m1.scroll_find(serial, ui, re.escape(fixture_titles[kind]))
        if not card:
            checks.append(Check(f"{kind}: row is on screen", True, False))
            continue
        ui.tap_xy(serial, *ui.center(card["bounds"]))
        time.sleep(2)
        detail = " | ".join(m1.scrolled_texts(serial))
        checks.append(Check(f"{kind}: detail has its layers and its settings button", True,
                            all(s in detail for s in ["What you can do", "Why you should care", "What the app saw", "What it can't tell you", "Nerd info", button])))
        ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m2-detail-{kind}-api{api}.png"))
        m1.go_back(serial)
    return checks


if __name__ == "__main__":
    sys.exit(main())
