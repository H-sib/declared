#!/usr/bin/env python3
"""Stage 2 conformance: the attack paths, compared with an independent evaluation, on an EMULATOR. Standard library only.

The phone is put into a series of states with adb (benign lab fixtures that hold powers, a fixture with the shape of an
app made to watch someone, a proxy, Private DNS, a screen lock) and the person's side is driven through the real UI
(answers on the "About you" screen, answers in the review). After each step the debug build reports the paths it
computed, and `paths_oracle.py` computes the same thing from the system's own readings (`settings`, `dumpsys`, `appops`,
`cmd wifi`, `aapt2`) and from what was tapped, with its own rules, its own three-valued logic and its own brute-force
search for the smallest fix set. The two must agree on: which paths exist, which are held open, each path's severity, the
confidence of the phone-level paths, the smallest set of changes and how many paths it stops. The words on the screen (the
plan sentence and the what-if preview) are checked against what the oracle says they must be.

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
import conformance_m3 as m3  # noqa: E402
import conformance_m5 as m5  # noqa: E402
import paths_oracle as oracle  # noqa: E402
import runner  # noqa: E402

PRODUCT = m1.PRODUCT
RESULTS_CSV = os.path.join(PRODUCT, "conformance", "m7_results.csv")
SHOTS_DIR = os.path.join(PRODUCT, "conformance", "screenshots")
RUN_ID = dt.datetime.now().isoformat(timespec="seconds")
SHADOW = "lab.fixture.shadow"
SHADOW_A11Y = f"{SHADOW}/lab.fixture.ShadowA11yService"
SHADOW_ADMIN = f"{SHADOW}/lab.fixture.ShadowAdminReceiver"
SHADOW_APK = os.path.join(m1.ROOT, "lab", "fixtures", "build", "outputs", "apk", "shadow", "debug", "fixtures-shadow-debug.apk")
FIXTURE_PACKAGES = ["lab.fixture.a11y", "lab.fixture.notif", "lab.fixture.admin", "lab.fixture.ime", "lab.fixture.perms", SHADOW]
PERMS_PKG = "lab.fixture.perms"
HIGH_RISK_LABEL = "Someone who may be targeted, such as a journalist or an activist"
PIN = "4242"
TEST_DNS_HOST = "dns.google"
TEST_PROXY = "127.0.0.1:8899"
UNUSUAL = {"ADB", "UNKNOWN", "SIDELOAD"}
TIER_CONFIDENCE = {"ADB": "MEDIUM", "SIDELOAD": "MEDIUM", "UNKNOWN": "LOW"}
LEVER_WORDS = {  # what the plan row says for each lever, in the product's own sentences (core/paths/RuleBook.kt)
    "private-dns-on": "Turn on Private DNS", "clear-proxy": "Clear the web proxy", "set-lock": "Set a screen lock",
    "update-phone": "Install the latest system update", "remove-ca": "Remove the added certificate authority",
}
PHONE_LEVEL = ("p3-ca", "p3-proxy", "p3-wifi", "p4-no-lock", "p6-patch", "p6-advanced-protection")


# ---------------------------------------------------------------- pure helpers (unit tested)

def aggregate_answer(answers):
    """The oracle's one answer per package from the answers of its holders: any doubt wins; all recognized is recognized; else none."""
    if "NOT_RECOGNIZED" in answers:
        return "NOT_RECOGNIZED"
    if answers and all(a == "RECOGNIZED" for a in answers):
        return "RECOGNIZED"
    return None


def plan_sentence(levers, cut, total):
    """The sentence the plan card must show (core/paths/PathsWords.kt planSummary)."""
    things = "this 1 change" if levers == 1 else f"these {levers} changes"
    if total == 1:
        outcome = "stops the 1 path this app can see"
    elif cut == total:
        outcome = f"stops all {total} paths this app can see"
    else:
        outcome = f"stops {cut} of the {total} paths this app can see"
    return f"Making {things} {outcome}."


def remaining_sentence(n):
    if n == 0:
        return "With the changes you ticked, none of these paths would remain."
    if n == 1:
        return "With the changes you ticked, 1 path would remain."
    return f"With the changes you ticked, {n} paths would remain."


def path_count_sentence(n):
    return "1 path this app can see." if n == 1 else f"{n} paths this app can see."


class Check(m1.Check):
    def row(self, phase, api):
        return [RUN_ID, api, phase, self.name, json.dumps(self.expected), json.dumps(self.actual), "PASS" if self.ok else "FAIL"]


def compare_paths(app_paths, result, told, answers_by_pkg, skip=()):
    """The app's `paths` object against the oracle's analysis. Returns a list of checks."""
    checks = []
    app_true = {p["id"]: p for p in app_paths["paths"]}
    app_open = {p["id"] for p in app_paths["notRuledOut"]}
    want_true = {g for g in result["paths"] if g not in skip}
    got_true = {g for g in app_true if g not in skip}
    checks.append(Check("the paths that exist = the oracle's", sorted(want_true), sorted(got_true)))
    checks.append(Check("the paths held open = the oracle's", sorted(g for g in result["open"] if g not in skip), sorted(g for g in app_open if g not in skip)))
    checks.append(Check("the profile in use = the one chosen", told["profile"], app_paths["profile"]))
    checks.append(Check("severity of each path = THREAT_MODEL.md section 6 for that profile", {g: result["severity"][g] for g in sorted(want_true)},
                        {g: app_true[g]["severity"] for g in sorted(want_true & set(app_true))}))
    conf_want = {g: oracle.CONF_NAME[result["paths"][g][1]] for g in sorted(want_true) if g in PHONE_LEVEL}
    conf_got = {g: app_true[g]["confidence"] for g in sorted(want_true & set(app_true)) if g in PHONE_LEVEL}
    checks.append(Check("confidence of the phone-level paths = weakest link by the oracle", conf_want, conf_got))
    for g in sorted(want_true & set(app_true)):
        if g.startswith("p2:"):
            checks.append(Check(f"{g} says it rests on what you told this app", True, app_true[g]["restsOnWhatYouSaid"]))
        if g.startswith("p1:"):
            pkg = g.split(":", 1)[1]
            checks.append(Check(f"{g} rests on your word only if you said you do not recognize the app", answers_by_pkg.get(pkg) == "NOT_RECOGNIZED", app_true[g]["restsOnWhatYouSaid"]))
    plan = app_paths["plan"]
    want_plan = result["plan"]
    if not want_true and not result["paths"]:
        checks.append(Check("with no path there is no plan", None, plan))
    else:
        plan = plan or {}
        checks.append(Check("the smallest set of changes (ids) = the oracle's brute force", want_plan["levers"], plan.get("levers")))
        checks.append(Check("how many paths that set stops = the oracle's", len([g for g in want_plan["cut"] if g not in skip]), plan.get("cut")))
        checks.append(Check("paths nothing can stop = the oracle's", want_plan["unstoppable"], plan.get("unstoppable")))
        checks.append(Check("the answer is proven exact, not approximate", True, plan.get("exact")))
    return checks


# ---------------------------------------------------------------- device truth

def sh(serial, command):
    return runner.adb(serial, "shell", command, check=False)


def user_ca_listing(serial):
    """The certificate files a user or administrator added, read as root; None when root is not available (the check is then skipped)."""
    runner.adb(serial, "root", check=False)
    time.sleep(3)
    runner.adb(None, "wait-for-device", timeout=60, check=False)
    ok = "uid=0" in runner.adb(serial, "shell", "id", check=False)
    listing = None
    if ok:
        out = runner.adb(serial, "shell", f"ls {m3.USER_CA_DIR} 2>/dev/null", check=False)
        listing = [x for x in out.split() if x.endswith(".0")]
    runner.adb(serial, "unroot", check=False)
    time.sleep(3)
    runner.adb(None, "wait-for-device", timeout=60, check=False)
    return listing


def apk_window_content(apk, xml_path):
    xmltree = m1.tool("aapt2.exe", "dump", "xmltree", "--file", xml_path, apk)
    return bool(m1.declared_capability_bits(xmltree) & 0x1)


A11Y_XML = {"lab.fixture.a11y": ("a11y_config", "lab.fixture.a11y/lab.fixture.FixtureA11yService"), SHADOW: ("shadow_a11y_config", SHADOW_A11Y)}


def apk_of(pkg):
    return SHADOW_APK if pkg == SHADOW else m2.fixture_apk(pkg.rsplit(".", 1)[1])


def advanced_protection_truth(serial, api):
    """Android 16 and later keep the mode in a secure setting (1 is on); before that there is no such mode. An unset value is the default, off."""
    if api < 36:
        return "unsupported"
    value = (runner.settings_get(serial, "secure", "advanced_protection_mode") or "").strip()
    return "on" if value == "1" else "off"


def gather(serial, api, apk_cache):
    """Everything the oracle needs, read from the system and never from the app."""
    t = m3.system_truth(serial)
    patch_age = None
    try:
        patch_age = (dt.date.fromisoformat(t["device_date"]) - dt.date.fromisoformat(t["patch"])).days
    except ValueError:
        pass
    cas = user_ca_listing(serial)
    network = t["network"]
    truth = {
        "lock_set": t["lock_set"],
        "patch_age_days": max(patch_age, 0) if patch_age is not None else None,
        "advanced_protection": advanced_protection_truth(serial, api),
        "network_present": network["present"],
        "proxy": bool(t["proxy"]),
        "private_dns_active": network.get("private_dns_active") if network["present"] else None,
        "wifi_connected": t["wifi_connected"],
        "wifi_open": (t["wifi_security"] == 0) if t["wifi_security"] is not None and t["wifi_security"] >= 0 else None,
        "user_cas": (len(cas) > 0) if cas is not None else None,
    }
    enabled_a11y = runner.split_components(runner.settings_get(serial, "secure", "enabled_accessibility_services"))
    enabled_notif = runner.split_components(runner.settings_get(serial, "secure", "enabled_notification_listeners"))
    admins = runner.parse_admins(sh(serial, "dumpsys device_policy"))
    launcher = m2.parse_component_packages(sh(serial, "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"))
    installed = set(re.findall(r"package:(\S+)", sh(serial, "pm list packages")))
    apps, keys = {}, {"a11y": {}, "notif": {}, "admin": {}}
    for pkg in FIXTURE_PACKAGES:
        if pkg not in installed:
            continue
        text = sh(serial, f"dumpsys package {pkg}")
        installer, initiating = m1.parse_install_source(text)
        tier = m1.expected_tier(installer, initiating)
        granted = runner.parse_granted(text)
        op = sh(serial, f"appops get {pkg} SYSTEM_ALERT_WINDOW")
        a11y_component = A11Y_XML.get(pkg, (None, None))[1]
        notif_component = f"{pkg}/lab.fixture.FixtureNotifListener" if pkg == "lab.fixture.notif" else None
        admin_component = SHADOW_ADMIN if pkg == SHADOW else (f"{pkg}/lab.fixture.FixtureAdminReceiver" if pkg == "lab.fixture.admin" else None)
        window_content = False
        if a11y_component:
            apk = apk_of(pkg)
            window_content = apk_cache.setdefault(pkg, apk_window_content(apk, f"res/xml/{A11Y_XML[pkg][0]}.xml"))
            keys["a11y"][pkg] = runner.norm_component(a11y_component)
        if notif_component:
            keys["notif"][pkg] = runner.norm_component(notif_component)
        if admin_component:
            keys["admin"][pkg] = runner.norm_component(admin_component)
        apps[pkg] = {
            "tier_unusual": tier in UNUSUAL, "tier_confidence": TIER_CONFIDENCE.get(tier, "MEDIUM"),
            "a11y_on": bool(a11y_component) and runner.norm_component(a11y_component) in enabled_a11y, "a11y_window_content": window_content,
            "notif_on": bool(notif_component) and runner.norm_component(notif_component) in enabled_notif,
            "admin_on": bool(admin_component) and runner.norm_component(admin_component) in admins,
            "sms": granted.get("android.permission.READ_SMS") is True or granted.get("android.permission.RECEIVE_SMS") is True,
            "overlay": "allow" in op.lower(),
            "hidden_icon": pkg not in launcher,
            "location": granted.get("android.permission.ACCESS_FINE_LOCATION") is True,
            "mic": granted.get("android.permission.RECORD_AUDIO") is True,
            "built_in": False,
        }
    return truth, apps, keys


# ---------------------------------------------------------------- device actions

def set_shadow(serial, enabled):
    current = runner.settings_get(serial, "secure", "enabled_accessibility_services") or ""
    parts = [x for x in runner.split_components(current) if x != runner.norm_component(SHADOW_A11Y)]
    if enabled:
        sh(serial, f"settings put secure enabled_accessibility_services {':'.join(sorted(parts + [runner.norm_component(SHADOW_A11Y)]))}")
        sh(serial, "settings put secure accessibility_enabled 1")
        sh(serial, f"dpm set-active-admin {SHADOW_ADMIN}")
        for perm in ("READ_SMS", "RECEIVE_SMS", "ACCESS_FINE_LOCATION", "RECORD_AUDIO"):
            sh(serial, f"pm grant {SHADOW} android.permission.{perm}")
    else:
        sh(serial, f"settings put secure enabled_accessibility_services {':'.join(sorted(parts)) if parts else 'null'}" if parts else "settings delete secure enabled_accessibility_services")
        sh(serial, f"dpm remove-active-admin {SHADOW_ADMIN}")
        for perm in ("READ_SMS", "RECEIVE_SMS", "ACCESS_FINE_LOCATION", "RECORD_AUDIO"):
            sh(serial, f"pm revoke {SHADOW} android.permission.{perm}")
    time.sleep(2)


def tap_chip_scrolling(serial, ui, title_pattern, chip_text, max_swipes=12):
    """Tap a card's answer chip, scrolling until both the card's title and the chip are on screen (a review with several cards is longer than the screen)."""
    for _ in range(max_swipes + 1):
        nodes = ui.dump(serial)
        title = ui.find(nodes, title_pattern)
        if title:
            below = [n for n in nodes if n["text"] == chip_text and n["bounds"] and n["bounds"][1] >= title["bounds"][1]]
            if below:
                chip = min(below, key=lambda n: n["bounds"][1])
                ui.tap_xy(serial, *ui.center(chip["bounds"]))
                time.sleep(1)
                return True
        runner.shell(serial, "input swipe 540 1700 540 1100 400", check=False)
        time.sleep(1)
    return False


def tap_answer(serial, ui, question, label):
    return tap_chip_scrolling(serial, ui, question, label)


def open_paths(serial, ui):
    m5.fresh_launch(serial)
    return m1.tap_tab(serial, ui, "Paths")


def set_declarations(serial, ui, sms=None, wifi=None, profile=None):
    ok = open_paths(serial, ui) and m5.tap_text(serial, ui, r"^Answer the questions about you$", scroll=True)
    labels = {"YES": "Yes", "NO": "No", "UNSURE": "Not sure"}
    if sms:
        ok = tap_answer(serial, ui, r"^Do your accounts send you sign-in codes", labels[sms]) and ok
    if wifi:
        ok = tap_answer(serial, ui, r"^Do you often use public Wi-Fi", labels[wifi]) and ok
    if profile:
        want = "Most people" if profile == "GENERAL" else HIGH_RISK_LABEL
        ok = tap_answer(serial, ui, r"^How much weight should the paths", want) and ok
    return ok


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--skip-install", action="store_true")
    args = ap.parse_args(argv)
    serial = args.serial
    m1.require_emulator(serial)
    sys.path.insert(0, m1.LAB_TOOLS)
    import ui

    product_apk = m1.find_product_apk()
    m5.APP["id"] = m1.parse_package_name(m1.tool("aapt2.exe", "dump", "badging", product_apk))
    app_id = m5.APP["id"]
    api = runner.shell(serial, "getprop ro.build.version.sdk")
    if not os.path.exists(SHADOW_APK):
        raise SystemExit(f"build the shadow fixture first: gradle :fixtures:assembleShadowDebug in lab/ ({SHADOW_APK})")
    if not args.skip_install:
        for role in m2.FIXTURE_ROLES:
            if os.path.exists(m2.fixture_apk(role)):
                runner.adb(serial, "install", "-t", "-r", m2.fixture_apk(role))
        runner.adb(serial, "install", "-r", product_apk)
    runner.adb(serial, "install", "-t", "-r", SHADOW_APK)

    rows, failures = [], 0
    apk_cache = {}

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
    sh(serial, "svc power stayon true")
    told = {"sms_codes": "UNSURE", "public_wifi": "UNSURE", "profile": "GENERAL"}
    answers_by_pkg = {}

    def reset_phone():
        m5.set_roles(serial, [])
        set_shadow(serial, False)
        for c in ("settings put global http_proxy :0", "settings delete global http_proxy", "settings delete global private_dns_mode",
                  "settings delete global private_dns_specifier", f"locksettings clear --old {PIN}"):
            sh(serial, c)
        time.sleep(2)

    def settled_launch():
        """Launch the app; if it could not yet read the network or Wi-Fi security while the system can (a re-validation of the network
        after a setting changed leaves them unreadable for a while, D-041), wait and try again. The app's honest answer in that window
        is "not ruled out", which is correct, so it is not what this script is here to judge."""
        d = None
        for attempt in range(8):
            d = m5.launch(serial)
            raw = d["raw"]
            net = raw["network"] if isinstance(raw["network"], dict) else {}
            wifi = raw["wifi"] if isinstance(raw["wifi"], dict) else {}
            unreadable = net.get("privateDnsActive") is None or (wifi.get("connected") and wifi.get("security") in (None, "UNKNOWN"))
            truth_now = m3.system_truth(serial)
            system_knows = truth_now["network"].get("present") and truth_now["network"].get("private_dns_active") is not None and (
                not truth_now["wifi_connected"] or (truth_now["wifi_security"] is not None and truth_now["wifi_security"] >= 0))
            if not (unreadable and system_knows):
                return d
            print(f"  info: the app could not yet read the network (attempt {attempt + 1}); waiting for it to settle")
            time.sleep(20)
        return d

    def step(phase, extra_skip=()):
        """Launch the app in a new process, gather the system's truth, and compare the paths."""
        d = settled_launch()
        st = d["state"]
        truth, apps, keys = gather(serial, int(api), apk_cache)
        skip = set(extra_skip) | ({"p3-ca"} if truth["user_cas"] is None else set())
        if truth["user_cas"] is None:
            truth["user_cas"] = False
        result = oracle.analyse(truth, told, apps, answers=answers_by_pkg, keys=keys)
        checks = compare_paths(st["paths"], result, told, answers_by_pkg, skip=skip)
        checks.append(Check("the declarations in memory = the ones tapped", told, {"sms_codes": st["paths"]["declarations"]["smsCodes"], "public_wifi": st["paths"]["declarations"]["publicWifi"], "profile": st["paths"]["declarations"]["profile"]}))
        persisted = st["persisted"].get("declarations") or {}
        checks.append(Check("the declarations read back from the encrypted file = the ones tapped", told, {"sms_codes": persisted.get("smsCodes"), "public_wifi": persisted.get("publicWifi"), "profile": persisted.get("profile")}))
        record(phase, checks)
        return st, result

    try:
        reset_phone()
        runner.adb(serial, "shell", f"pm clear {app_id}", check=False)
        m1.ensure_baseline(serial, app_id, ui)
        user_ca = user_ca_listing(serial)
        print(f"  info: user certificate authorities on the emulator: {user_ca}")

        # ------------------------------------------------------------ the phone as found, nothing told
        step("as-found")

        # ------------------------------------------------------------ the person answers, through the real UI
        ok = set_declarations(serial, ui, sms="YES", wifi="YES")
        told.update(sms_codes="YES", public_wifi="YES")
        record("about-you-taps", [Check("the About you screen was reached and every chip tapped", True, ok)])
        step("told-yes-yes")

        # ------------------------------------------------------------ fixtures hold powers
        m5.set_roles(serial, ["a11y", "notif", "admin", "perms"])
        set_shadow(serial, True)
        step("fixtures-on-unanswered")

        # ------------------------------------------------------------ the person reviews what is new
        m5.fresh_launch(serial)
        m1.tap_tab(serial, ui, "Check")
        opened = m5.tap_text(serial, ui, r"new to review", scroll=True)
        # The a11y fixture is "recognized" (so its paths go away although it came by adb); the shadow fixture is "not recognized"
        # (so its paths rest on the person's own word). Everything else is left unanswered and is saved as "not sure".
        t1 = tap_chip_scrolling(serial, ui, r"^Fixture a11y", "I recognize this")
        t2 = tap_chip_scrolling(serial, ui, r"^Fixture shadow", "I do not recognize this")
        saved = m5.tap_text(serial, ui, r"Save my answers", scroll=True)
        answers_by_pkg.update({"lab.fixture.a11y": aggregate_answer(["RECOGNIZED"]), SHADOW: aggregate_answer(["NOT_RECOGNIZED"])})
        record("review-taps", [Check("the review opened, two cards answered, and saved", [True, True, True, True], [opened, t1, t2, saved])])
        step("fixtures-answered")

        # ------------------------------------------------------------ the phone changes: proxy, Private DNS, a lock
        sh(serial, f"settings put global http_proxy {TEST_PROXY}")
        sh(serial, "settings put global private_dns_mode hostname")
        sh(serial, f"settings put global private_dns_specifier {TEST_DNS_HOST}")
        sh(serial, f"locksettings set-pin {PIN}")
        time.sleep(6)
        st, result = step("phone-changed")

        # what the screen says: the plan sentence and the what-if preview, compared with the oracle
        m5.fresh_launch(serial)
        reached = open_paths(serial, ui)
        text = " | ".join(m1.scrolled_texts(serial))
        plan = result["plan"]
        total = len(result["paths"])
        screen_checks = [Check("the paths screen opened from the first screen", True, reached)]
        if total:
            screen_checks.append(Check("the first screen's card and the paths screen state the count the oracle gives", True, path_count_sentence(total) in text))
            screen_checks.append(Check("the plan sentence = the oracle's counts, in the product's words", True, plan_sentence(len(plan["levers"]), len(plan["cut"]), total) in text))
            screen_checks.append(Check("the plan says whether it is proven smallest", True, "This is the smallest set that stops every path above" in text))
            for lever in plan["levers"]:
                if lever in LEVER_WORDS:
                    screen_checks.append(Check(f"the plan lists '{LEVER_WORDS[lever]}' with what it costs", True, LEVER_WORDS[lever] in text and "Cost:" in text))
        else:
            screen_checks.append(Check("with no path the screen says none was found and does not say the phone is clean", True, "No attack path was found" in text and "is safe" not in text.lower()))
        ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m7-paths-api{api}.png"))
        record("paths-screen", screen_checks)

        if plan["levers"]:
            m5.fresh_launch(serial)
            open_paths(serial, ui)
            tapped = m5.tap_text(serial, ui, r"^Preview this change$", scroll=True)
            # The plan card lists its levers by id and the first "Preview this change" chip on the screen belongs to the first one.
            after = oracle.with_flipped(result["values"], result["levers"][plan["levers"][0]][1])
            remaining = sum(1 for g in result["paths"] if oracle.evaluate(result["goals"][g], after)[0] == oracle.T)
            shown = " | ".join(m1.scrolled_texts(serial))
            record("what-if-preview", [
                Check("a change was ticked on the paths screen", True, tapped),
                Check("the preview says how many paths would remain = the oracle with that one change applied", True, remaining_sentence(remaining) in shown),
                Check("the preview says nothing was changed on the phone", True, "Nothing is changed on your phone." in shown),
            ])

        # ------------------------------------------------------------ the higher-risk profile
        ok = set_declarations(serial, ui, profile="HIGH_RISK")
        told["profile"] = "HIGH_RISK"
        record("profile-tap", [Check("the profile chip was tapped", True, ok)])
        step("high-risk")

        # ------------------------------------------------------------ not sure keeps paths open; no clears them
        ok = set_declarations(serial, ui, sms="UNSURE", wifi="NO", profile="GENERAL")
        told.update(sms_codes="UNSURE", public_wifi="NO", profile="GENERAL")
        record("unsure-taps", [Check("the chips were tapped", True, ok)])
        st, result = step("not-sure-and-no")
        record("not-ruled-out", [
            Check("with 'not sure' the code path of the doubted app is held open, not dropped", True, "p2:lab.fixture.notif" in result["open"] and "p2:lab.fixture.notif" in {p["id"] for p in st["paths"]["notRuledOut"]}),
            Check("and it says it needs an answer", True, any(p["id"] == "p2:lab.fixture.notif" and p["needsAnAnswer"] for p in st["paths"]["notRuledOut"])),
        ])
    finally:
        reset_phone()
        runner.adb(serial, "uninstall", SHADOW, check=False)
        sh(serial, f"settings put global stay_on_while_plugged_in {stayed_on}" if stayed_on is not None else "svc power stayon false")

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
