#!/usr/bin/env python3
"""Milestone 3 conformance: device facts, network facts and the findings made from them. Standard library only.

Same method as conformance_m1.py and conformance_m2.py: an EMULATOR is put into a known state, the state is verified
with the system itself (lab defect D-004), the debug build is launched, and what it found is compared with independent
sources:

  * security patch date and the device's own date: `getprop`, `date`
  * screen lock: `locksettings get-disabled` (prints `true` only when no credential is set)
  * USB debugging, wireless debugging, developer options, web proxy, Private DNS mode: `settings get global`
  * connection, transports, validation, Private DNS in use: `dumpsys connectivity`
  * Wi-Fi security type: `cmd wifi status`

Two states are compared: the emulator as found, and a changed one (developer options on, a PIN set, a proxy set, Private
DNS pinned to a host). Every change is reverted and the revert is verified, also when the script fails.
Run from a PowerShell where `. lab\\tools\\env.ps1` has been dot-sourced. Refuses to touch anything but an emulator.
"""
import argparse
import csv
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import conformance_m1 as m1  # noqa: E402
import runner  # noqa: E402

PRODUCT = m1.PRODUCT
RESULTS_CSV = os.path.join(PRODUCT, "conformance", "m3_results.csv")
SHOTS_DIR = os.path.join(PRODUCT, "conformance", "screenshots")
RUN_ID = dt.datetime.now().isoformat(timespec="seconds")
PIN = "4321"
TEST_PROXY = "10.0.2.2:8888"
TEST_DNS_HOST = "dns.google"
LOOK_DAYS, LOOK_FIRST_DAYS = 180, 365  # DESIGN.md section 19: the documented patch-age thresholds
WIFI_NAMES = {0: "OPEN", 1: "WEP", 2: "WPA_PSK", 3: "ENTERPRISE", 4: "WPA3_PSK", 5: "OWE", 6: "WAPI", 7: "WAPI",
              8: "ENTERPRISE", 9: "ENTERPRISE", 10: "ENTERPRISE", 11: "ENTERPRISE", 12: "OTHER"}
FACT_KINDS = ["PATCH_LEVEL", "SCREEN_LOCK", "USB_DEBUGGING", "WIRELESS_DEBUGGING", "DEVELOPER_OPTIONS", "ADVANCED_PROTECTION",
              "CONNECTION", "VPN", "WIFI_SECURITY", "PRIVATE_DNS", "HTTP_PROXY", "USER_CAS",
              "KEY_ATTESTATION", "BOOT_STATE", "BOOTLOADER", "ATTESTED_PATCH"]


# Findings owned by conformance_m4.py (the integrity panel) and by the user-CA phase below; this script does not judge them.
OTHER_SCRIPTS_FINDINGS = {"condition:software-keys", "condition:attestation-failed", "condition:bootloader-unlocked", "condition:boot-state",
                          "condition:patch-mismatch", "condition:user-cas"}


# ---------------------------------------------------------------- pure helpers (unit tested)

def parse_global(text):
    """A `settings get` value: the text `null` or an empty answer means the setting does not exist."""
    t = (text or "").strip()
    return None if t in ("", "null") else t


def parse_int_setting(text):
    v = parse_global(text)
    return int(v) if v is not None and v.lstrip("-").isdigit() else None


def parse_proxy(host, port, pac, legacy):
    """The effective global proxy from the keys Android stores it in (`global_http_proxy_host` and friends).

    The older `http_proxy` setting is only a way in: deleting it does NOT clear a proxy that is already in effect
    (lab-style defect D-025), so it is consulted only as a fallback and never believed over the real keys.
    """
    h, p, u, old = parse_global(host), parse_global(port), parse_global(pac), parse_global(legacy)
    if h:
        return f"{h}:{p}" if p else h
    if u:
        return f"script {u}"
    if old and old != ":0":
        return old
    return None


def lock_set_from_get_disabled(text):
    """`locksettings get-disabled` prints `true` only when the lock screen is disabled, that is, no credential is set."""
    return (text or "").strip() != "true"


def parse_wifi_status(text):
    """(connected, security type number) from `cmd wifi status`."""
    connected = bool(re.search(r'Wifi is connected to "', text or ""))
    m = re.search(r"Security type: (-?\d+)", text or "")
    return connected, (int(m.group(1)) if m else None)


def parse_active_network(dumpsys_text):
    """Transports, validation, captive portal and Private DNS of the default network, from `dumpsys connectivity`."""
    text = dumpsys_text or ""
    m = re.search(r"Active default network: (\d+)", text)
    if not m:
        return {"present": False}
    # Android 11 and later print "NetworkAgentInfo{network{100}"; Android 10 prints "NetworkAgentInfo{ ni{...}  network{100}".
    starts = [i.start() for i in re.finditer(r"NetworkAgentInfo\{", text)]
    block = None
    for n, start in enumerate(starts):
        end = starts[n + 1] if n + 1 < len(starts) else len(text)
        if re.search(r"network\{%s\}" % m.group(1), text[start:start + 400]):
            block = text[start:end]
            break
    if block is None:
        return {"present": False}
    nc = re.search(r"nc\{\[ Transports: ([A-Z_&]+) Capabilities: ([A-Z_&]+)", block)
    transports = set(nc.group(1).split("&")) if nc else set()
    capabilities = set(nc.group(2).split("&")) if nc else set()
    dns_active = re.search(r"UsePrivateDns: (true|false)", block)
    dns_name = re.search(r"PrivateDnsServerName: (\S+)", block)
    return {
        "present": True,
        "transports": sorted(t.lower() for t in transports),
        "validated": "VALIDATED" in capabilities,
        "captive_portal": "CAPTIVE_PORTAL" in capabilities,
        "internet": "INTERNET" in capabilities,
        "private_dns_active": dns_active.group(1) == "true" if dns_active else False,
        "private_dns_name": dns_name.group(1) if dns_name else None,
    }


def expected_patch_attention(patch, device_date):
    """The documented rule: 180 days or more is LOOK, 365 or more is LOOK_FIRST, anything else makes no finding."""
    try:
        days = (dt.date.fromisoformat(device_date) - dt.date.fromisoformat(patch)).days
    except (TypeError, ValueError):
        return None
    if days >= LOOK_FIRST_DAYS:
        return "LOOK_FIRST"
    if days >= LOOK_DAYS:
        return "LOOK"
    return None


def expected_conditions(truth, api):
    """{finding id: attention} that the documented rules give for this system state."""
    want = {}
    patch = expected_patch_attention(truth["patch"], truth["device_date"])
    if patch:
        want["condition:patch-level"] = patch
    if not truth["lock_set"]:
        want["condition:screen-lock"] = "LOOK_FIRST"
    if truth["usb"] == 1:
        want["condition:usb-debugging"] = "LOOK"
    if truth["wireless"] == 1:
        want["condition:wireless-debugging"] = "LOOK_FIRST"
    if truth["developer"] == 1:
        want["condition:developer-options"] = "NOTE"
    wifi = WIFI_NAMES.get(truth["wifi_security"]) if truth["wifi_connected"] and int(api) >= 31 else None
    if wifi == "OPEN":
        want["condition:wifi-security"] = "LOOK"
    if wifi == "WEP":
        want["condition:wifi-security"] = "LOOK_FIRST"
    if truth["proxy"]:
        want["condition:http-proxy"] = "LOOK"
    return want


def expected_signal(api, signal_id):
    if signal_id == "device.advanced-protection":
        return "UNAVAILABLE" if int(api) < 36 else None  # on 36 and later the permission decides; the answer is recorded, not predicted
    return "AVAILABLE"


class Check(m1.Check):
    def row(self, phase, api):
        return [RUN_ID, api, phase, self.name, json.dumps(self.expected), json.dumps(self.actual), "PASS" if self.ok else "FAIL"]


def compare(dump, truth, api):
    """Every comparison for one state. Pure; returns [Check]."""
    checks = []
    raw = dump.get("raw", {})
    dev, net, wifi = raw.get("device"), raw.get("network"), raw.get("wifi")
    for name, v in (("device", dev), ("network", net), ("wifi", wifi)):
        checks.append(Check(f"{name}: raw reading present", True, isinstance(v, dict)))
    if not all(isinstance(v, dict) for v in (dev, net, wifi)):
        return checks

    checks.append(Check("device: security patch = getprop", truth["patch"], dev["securityPatch"]))
    checks.append(Check("device: screen lock = locksettings", truth["lock_set"], dev["screenLock"]))
    # An absent setting is `null`; a refused or failed read is a text. Only numbers count as readings.
    num = lambda v: v if isinstance(v, int) and not isinstance(v, bool) else None  # noqa: E731
    checks.append(Check("device: USB debugging = settings", truth["usb"], num(dev["usbDebugging"])))
    checks.append(Check("device: wireless debugging = settings", truth["wireless"], num(dev["wirelessDebugging"])))
    checks.append(Check("device: developer options = settings", truth["developer"], num(dev["developerOptions"])))
    checks.append(Check("device: Android version and API", [truth["release"], int(api)], [dev["release"], dev["sdk"]]))

    net_truth = truth["network"]
    checks.append(Check("network: connected = dumpsys connectivity", net_truth["present"], net["present"]))
    if net_truth["present"] and net["present"]:
        checks.append(Check("network: transports = dumpsys", net_truth["transports"], sorted(t.lower() for t in net["transports"])))
        checks.append(Check("network: validated = dumpsys", net_truth["validated"], net["validated"]))
        checks.append(Check("network: captive portal = dumpsys", net_truth["captive_portal"], net["captivePortal"]))
        checks.append(Check("network: Private DNS in use = dumpsys", net_truth["private_dns_active"], bool(net["privateDnsActive"])))
        checks.append(Check("network: Private DNS server name = dumpsys", net_truth["private_dns_name"], net["privateDnsServerName"]))
        checks.append(Check("network: web proxy = settings", truth["proxy"], net["httpProxy"]))

    checks.append(Check("wifi: connected = cmd wifi status", truth["wifi_connected"], wifi["connected"]))
    if truth["wifi_connected"]:
        want = WIFI_NAMES.get(truth["wifi_security"], "UNKNOWN") if int(api) >= 31 else None
        checks.append(Check("wifi: security type = cmd wifi status", want, wifi["security"]))

    findings = {f["id"]: f for f in dump.get("findings", []) if f["id"].startswith("condition:")}
    want = expected_conditions(truth, api)
    checks.append(Check("condition findings = the documented rules applied to the system's state", dict(sorted(want.items())),
                        {k: findings[k]["attention"] for k in sorted(findings) if k not in OTHER_SCRIPTS_FINDINGS}))
    checks.append(Check("every condition finding states its limits and costs", True,
                        all(f["cannotTellCount"] >= 3 and f["actionCount"] >= 1 and f["claim"] and all(c["source"] for c in f["claim"]) for f in findings.values())))

    facts = [f["kind"] for f in dump.get("facts", [])]
    checks.append(Check("every fact row is present exactly once, in display order", FACT_KINDS, facts))
    signals = {s["id"]: s["availability"] for s in dump.get("signals", [])}
    for sid in ("device.patch-level", "device.screen-lock", "device.usb-debugging", "device.wireless-debugging", "device.developer-options",
                "network.active", "network.private-dns", "network.http-proxy", "network.user-cas"):
        checks.append(Check(f"signal {sid} is AVAILABLE", "AVAILABLE", signals.get(sid)))
    ap = expected_signal(api, "device.advanced-protection")
    if ap:
        checks.append(Check("signal device.advanced-protection is UNAVAILABLE below Android 16", ap, signals.get("device.advanced-protection")))
    if truth["wifi_connected"]:
        checks.append(Check("signal network.wifi-security matches the Android version", "AVAILABLE" if int(api) >= 31 else "UNAVAILABLE", signals.get("network.wifi-security")))
    return checks


def compare_ca(dump, fingerprint, expected_present):
    """The user-added certificate authority reading against the certificate the harness placed (or removed)."""
    raw = (dump.get("raw") or {}).get("userCas")
    checks = [Check("userCas: raw reading present", True, isinstance(raw, dict))]
    if not isinstance(raw, dict):
        return checks
    got = sorted(raw.get("sha256", []))
    checks.append(Check("userCas: SHA-256 list = OpenSSL's fingerprint of the placed certificate" if expected_present
                        else "userCas: nothing found once the certificate is removed", [fingerprint] if expected_present else [], got))
    finding = next((f for f in dump.get("findings", []) if f["id"] == "condition:user-cas"), None)
    checks.append(Check("condition:user-cas finding is present iff a certificate is present", expected_present, finding is not None))
    if expected_present and finding is not None:
        checks.append(Check("finding attention and confidence", ["LOOK", "MEDIUM"], [finding["attention"], finding["confidence"]]))
    fact = next((f for f in dump.get("facts", []) if f["kind"] == "USER_CAS"), {"summary": ""})
    want = "Added certificate authorities: 1 found" if expected_present else "Added certificate authorities: none found"
    checks.append(Check("USER_CAS fact row states the count", True, fact["summary"].startswith(want)))
    return checks


# ---------------------------------------------------------------- device plumbing

def sh(serial, command):
    return runner.adb(serial, "shell", command, check=False)


def system_truth(serial):
    status_text = sh(serial, "cmd wifi status")
    wifi_connected, wifi_security = parse_wifi_status(status_text)
    if not status_text.strip():  # Android 10 prints nothing for this; the default network's transport says the same thing
        wifi_connected = "wifi" in parse_active_network(sh(serial, "dumpsys connectivity")).get("transports", [])
    return {
        "release": sh(serial, "getprop ro.build.version.release").strip(),
        "patch": sh(serial, "getprop ro.build.version.security_patch").strip(),
        "device_date": sh(serial, "date +%F").strip(),
        "lock_set": lock_set_from_get_disabled(sh(serial, "locksettings get-disabled")),
        "usb": parse_int_setting(sh(serial, "settings get global adb_enabled")),
        "wireless": parse_int_setting(sh(serial, "settings get global adb_wifi_enabled")),
        "developer": parse_int_setting(sh(serial, "settings get global development_settings_enabled")),
        "proxy": parse_proxy(sh(serial, "settings get global global_http_proxy_host"), sh(serial, "settings get global global_http_proxy_port"),
                             sh(serial, "settings get global global_proxy_pac_url"), sh(serial, "settings get global http_proxy")),
        "wifi_connected": wifi_connected,
        "wifi_security": wifi_security,
        "network": parse_active_network(sh(serial, "dumpsys connectivity")),
    }


def find_openssl():
    git_openssl = os.path.join("C:" + os.sep, "Program Files", "Git", "mingw64", "bin", "openssl.exe")
    for candidate in (shutil.which("openssl"), git_openssl):
        if candidate and os.path.exists(candidate):
            return candidate
    return None


def make_test_ca(openssl, directory):
    """A throwaway self-signed CA. Returns (pem path, OpenSSL's old subject hash, lowercase SHA-256 hex)."""
    os.makedirs(directory, exist_ok=True)
    pem, key = os.path.join(directory, "ca.pem"), os.path.join(directory, "key.pem")
    subprocess.run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", pem, "-days", "30",
                    "-subj", "/CN=Declared Test CA", "-addext", "basicConstraints=critical,CA:TRUE"], capture_output=True, check=True)
    old_hash = subprocess.run([openssl, "x509", "-in", pem, "-subject_hash_old", "-noout"], capture_output=True, text=True, check=True).stdout.strip()
    fp = subprocess.run([openssl, "x509", "-in", pem, "-fingerprint", "-sha256", "-noout"], capture_output=True, text=True, check=True).stdout.strip()
    return pem, old_hash, fp.split("=", 1)[1].replace(":", "").lower()


USER_CA_DIR = "/data/misc/user/0/cacerts-added"


def install_test_ca(serial, pem, old_hash):
    """Place the certificate in the emulator's user store. Needs a rootable (userdebug) emulator image; returns False if root is refused."""
    out = runner.adb(serial, "root", check=False) + runner.adb(serial, "shell", "id", check=False)
    time.sleep(3)
    if "uid=0" not in runner.adb(serial, "shell", "id", check=False):
        return False
    target = f"{USER_CA_DIR}/{old_hash}.0"
    sh(serial, f"mkdir -p {USER_CA_DIR}")
    runner.adb(serial, "push", pem, target, check=False)
    sh(serial, f"chmod 644 {target}; chown system:system {target}; chown system:system {USER_CA_DIR}; chmod 775 {USER_CA_DIR}; restorecon -R {USER_CA_DIR}")
    listed = sh(serial, f"ls {USER_CA_DIR}")
    return f"{old_hash}.0" in listed


def remove_test_ca(serial, old_hash):
    sh(serial, f"rm -f {USER_CA_DIR}/{old_hash}.0")
    gone = f"{old_hash}.0" not in sh(serial, f"ls {USER_CA_DIR} 2>/dev/null")
    runner.adb(serial, "unroot", check=False)
    time.sleep(3)
    return gone


def apply_changes(serial):
    sh(serial, "settings put global development_settings_enabled 1")
    sh(serial, f"locksettings set-pin {PIN}")
    sh(serial, f"settings put global http_proxy {TEST_PROXY}")
    sh(serial, "settings put global private_dns_mode hostname")
    sh(serial, f"settings put global private_dns_specifier {TEST_DNS_HOST}")
    time.sleep(6)  # let Android re-evaluate the network
    t = system_truth(serial)
    problems = []
    if t["developer"] != 1:
        problems.append("developer options were not switched on")
    if not t["lock_set"]:
        problems.append("the PIN was not set")
    if t["proxy"] != TEST_PROXY:
        problems.append("the proxy was not set")
    if parse_global(sh(serial, "settings get global private_dns_mode")) != "hostname":
        problems.append("Private DNS mode was not set")
    if problems:
        raise SystemExit("setup failed according to the system: " + "; ".join(problems))


def revert_changes(serial):
    """Put the emulator back and prove it with the system. Runs also after a failure."""
    sh(serial, f"locksettings clear --old {PIN}")
    sh(serial, "settings put global http_proxy :0")  # the way Android's own settings say "no proxy"; deleting the key does not clear it
    time.sleep(2)
    sh(serial, "settings delete global http_proxy")
    sh(serial, "settings delete global private_dns_mode")
    sh(serial, "settings delete global private_dns_specifier")
    sh(serial, "settings delete global development_settings_enabled")
    time.sleep(3)
    t = system_truth(serial)
    left = []
    if t["lock_set"]:
        left.append("a lock is still set")
    if t["proxy"]:
        left.append("a proxy is still set")
    if t["developer"] is not None:
        left.append("developer options setting still exists")
    if parse_global(sh(serial, "settings get global private_dns_mode")) is not None:
        left.append("Private DNS mode is still set")
    if left:
        print("WARNING: the emulator was not fully restored: " + "; ".join(left))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--skip-install", action="store_true")
    ap.add_argument("--no-ui", action="store_true")
    args = ap.parse_args(argv)
    serial = args.serial
    m1.require_emulator(serial)
    sys.path.insert(0, m1.LAB_TOOLS)
    import ui

    product_apk = m1.find_product_apk()
    app_id = m1.parse_package_name(m1.tool("aapt2.exe", "dump", "badging", product_apk))
    api = runner.shell(serial, "getprop ro.build.version.sdk")
    if not args.skip_install:
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
    expected_permissions = sorted([f"{app_id}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", "android.permission.ACCESS_NETWORK_STATE",
                                   "android.permission.ACCESS_WIFI_STATE", "android.permission.QUERY_ADVANCED_PROTECTION_MODE",
                                   "android.permission.POST_NOTIFICATIONS", "android.permission.RECEIVE_BOOT_COMPLETED"])
    record("manifest", [Check("APK requests exactly the three normal permissions, the two for the optional automatic checks (and AndroidX's private one): no INTERNET, no location",
                              expected_permissions, sorted(permissions))])

    stay_on = parse_global(sh(serial, "settings get global stay_on_while_plugged_in"))
    sh(serial, "svc power stayon true")  # a PIN locks the screen when it sleeps; keep it awake for the run
    try:
        record("as-found", compare(m1.launch_and_pull(serial, app_id, 90), system_truth(serial), api))
        apply_changes(serial)
        dump = m1.launch_and_pull(serial, app_id, 90)
        record("changed", compare(dump, system_truth(serial), api))
        openssl = find_openssl()
        if openssl:
            pem, old_hash, fingerprint = make_test_ca(openssl, os.path.join(os.environ.get("TEMP", "."), "declared-test-ca"))
            if install_test_ca(serial, pem, old_hash):
                try:
                    record("user-ca", compare_ca(m1.launch_and_pull(serial, app_id, 90), fingerprint, True))
                finally:
                    removed = remove_test_ca(serial, old_hash)
                record("user-ca-removed", [Check("the test certificate was really removed (verified in the system)", True, removed)]
                       + compare_ca(m1.launch_and_pull(serial, app_id, 90), fingerprint, False))
            else:
                print("NOT EXERCISED: this emulator image refused root, so no certificate could be placed in the user store.")
        else:
            print("NOT EXERCISED: OpenSSL was not found, so no test certificate could be made.")
    finally:
        revert_changes(serial)
        sh(serial, f"settings put global stay_on_while_plugged_in {stay_on}" if stay_on is not None else "svc power stayon false")

    if not args.no_ui:
        texts = m1.check_texts(serial, app_id, ui)
        os.makedirs(SHOTS_DIR, exist_ok=True)
        ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m3-inventory-api{api}.png"))
        record("on-screen", [Check(f"screen shows: {s}", True, s in texts) for s in (
            "Last security update:", "Screen lock:", "USB debugging:", "Connection:", "Wi-Fi security:", "Private DNS:", "Web proxy:",
        )] + [Check(f"screen has the heading: {h}", True, f" | {h} | " in f" | {texts} | ") for h in ("Settings", "Network")])

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
