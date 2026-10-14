#!/usr/bin/env python3
"""Observability lab runner. Standard library only.

Drives adb to install the lab apps, put a device into a known state, run the probe, pull its JSON,
read ground truth from the system itself (settings, getprop, dumpsys), compare the two, and append
rows to the results CSV. The pure functions near the top have unit tests in test_runner.py.
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
LAB = os.path.dirname(HERE)
RESULTS_DIR = os.path.join(LAB, "results")
RAW_DIR = os.path.join(RESULTS_DIR, "raw")
# LAB_CSV lets a physical-device session write to its own file while an emulator sweep appends to the main one.
CSV_PATH = os.environ.get("LAB_CSV") or os.path.join(RESULTS_DIR, "observability_results.csv")
ADB = os.environ.get("ADB", "adb")

PROBE_PACKAGES = {"q": "lab.probe.q", "a": "lab.probe.a"}
FIXTURE_ROLES = ["a11y", "notif", "admin", "ime", "perms", "hidden", "boot", "sms"]
COMPONENTS = {
    "a11y": "lab.fixture.a11y/lab.fixture.FixtureA11yService",
    "notif": "lab.fixture.notif/lab.fixture.FixtureNotifListener",
    "admin": "lab.fixture.admin/lab.fixture.FixtureAdminReceiver",
    "ime": "lab.fixture.ime/lab.fixture.FixtureIme",
}
PERMS_FIXTURE = "lab.fixture.perms"
FIXTURE_RUNTIME_PERMS = ["READ_SMS", "RECEIVE_SMS", "READ_CONTACTS", "RECORD_AUDIO", "ACCESS_FINE_LOCATION"]
SHA3_256_ABC = "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532"

CSV_FIELDS = [
    "signal_id", "api_level", "device_or_image", "oem_build", "flavor", "target_sdk",
    "permission_state", "setup_state", "result_code", "value_summary", "ground_truth_match", "notes", "date",
]


# ---------------------------------------------------------------- pure helpers (unit tested)

def norm_component(s):
    """Normalise 'pkg/.Cls' to 'pkg/pkg.Cls' so values from different sources compare equal."""
    s = (s or "").strip()
    if "/" not in s:
        return s
    pkg, cls = s.split("/", 1)
    if cls.startswith("."):
        cls = pkg + cls
    return f"{pkg}/{cls}"


def split_components(value):
    """Split a settings value such as 'a/b:c/d' into a set of normalised components."""
    if not value or value == "null":
        return set()
    return {norm_component(x) for x in value.split(":") if x.strip()}


def parse_granted(dumpsys_package_text):
    """Return {permission: bool} from 'permission: granted=true' lines of 'dumpsys package'."""
    return {m.group(1): m.group(2) == "true"
            for m in re.finditer(r"([A-Za-z0-9_.]+): granted=(true|false)", dumpsys_package_text or "")}


def parse_installer(dumpsys_package_text):
    m = re.search(r"installerPackageName=(\S+)", dumpsys_package_text or "")
    if not m:
        return None
    return None if m.group(1) == "null" else m.group(1)


def parse_admins(dumpsys_device_policy_text):
    """Best-effort parse of enabled device admins; the format varies by Android version."""
    admins = set()
    in_section = False
    for line in (dumpsys_device_policy_text or "").splitlines():
        if "Enabled Device Admins" in line:
            in_section = True
            continue
        if in_section:
            if line.strip() == "" or (not line.startswith(" ") and line.strip()):
                in_section = False
                continue
            m = re.search(r"ComponentInfo\{([^}]+)\}", line) or re.match(r"^\s+([\w.]+/[\w.$]+):?\s*$", line)
            if m:
                admins.add(norm_component(m.group(1)))
    return admins


def settings_diff(before, after):
    """Diff two {scope: {key: value}} dumps. Used by the D12 settings-discovery protocol."""
    out = []
    for scope in sorted(set(before) | set(after)):
        b, a = before.get(scope, {}), after.get(scope, {})
        for key in sorted(set(b) | set(a)):
            if b.get(key) != a.get(key):
                out.append({"scope": scope, "key": key, "before": b.get(key), "after": a.get(key)})
    return out


def parse_settings_list(text):
    d = {}
    for line in (text or "").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            d[k.strip()] = v.strip()
    return d


def enable_commands(role, current_a11y=""):
    """Shell commands that put a fixture role into its enabled state."""
    comp = COMPONENTS.get(role)
    if role == "a11y":
        comps = split_components(current_a11y) | {comp}
        return [f"settings put secure enabled_accessibility_services {':'.join(sorted(comps))}",
                "settings put secure accessibility_enabled 1"]
    if role == "notif":
        return [f"cmd notification allow_listener {comp}"]
    if role == "admin":
        return [f"dpm set-active-admin {comp}"]
    if role == "ime":
        return [f"ime enable {comp}"]
    if role == "perms":
        cmds = [f"pm grant {PERMS_FIXTURE} android.permission.{p}" for p in FIXTURE_RUNTIME_PERMS]
        cmds += [f"appops set {PERMS_FIXTURE} SYSTEM_ALERT_WINDOW allow",
                 f"appops set {PERMS_FIXTURE} REQUEST_INSTALL_PACKAGES allow",
                 f"appops set {PERMS_FIXTURE} GET_USAGE_STATS allow"]
        return cmds
    return []


def disable_commands(role, current_a11y=""):
    comp = COMPONENTS.get(role)
    if role == "a11y":
        remaining = split_components(current_a11y) - {comp}
        if remaining:
            return [f"settings put secure enabled_accessibility_services {':'.join(sorted(remaining))}"]
        return ["settings delete secure enabled_accessibility_services", "settings put secure accessibility_enabled 0"]
    if role == "notif":
        return [f"cmd notification disallow_listener {comp}"]
    if role == "admin":
        return [f"dpm remove-active-admin {comp}"]
    if role == "ime":
        return [f"ime disable {comp}"]
    if role == "perms":
        cmds = [f"pm revoke {PERMS_FIXTURE} android.permission.{p}" for p in FIXTURE_RUNTIME_PERMS]
        cmds += [f"appops set {PERMS_FIXTURE} SYSTEM_ALERT_WINDOW default",
                 f"appops set {PERMS_FIXTURE} REQUEST_INSTALL_PACKAGES default",
                 f"appops set {PERMS_FIXTURE} GET_USAGE_STATS default"]
        return cmds
    return []


ALL_ROLES = ["a11y", "notif", "admin", "ime", "perms"]
LABEL_EXPECTATIONS = {
    "all-enabled-adb": (ALL_ROLES, []),
    "baseline-empty": ([], ALL_ROLES),
}


def role_present(role, truth):
    """Whether a fixture role is really enabled according to the system itself (not the probe)."""
    comp = COMPONENTS.get(role)
    if role == "a11y":
        return comp in split_components(truth.get("secure.enabled_accessibility_services"))
    if role == "notif":
        return comp in split_components(truth.get("secure.enabled_notification_listeners"))
    if role == "ime":
        return comp in split_components(truth.get("secure.enabled_input_methods"))
    if role == "admin":
        return bool(re.search(r"lab\.fixture\.admin/", truth.get("dumpsys.device_policy") or ""))
    if role == "perms":
        return parse_granted(truth.get(f"dumpsys.package.{PERMS_FIXTURE}")).get("android.permission.READ_SMS", False)
    return None


def check_expectations(present_roles, absent_roles, truth):
    """Return [(role, expected_present, actually_present)] for every role whose real state differs from the expectation.

    A comparison between probe and truth is meaningless when the setup silently failed, because both sides can be
    empty and still 'match'. This turns that vacuous-match risk into an explicit failure.
    """
    failures = []
    for role in present_roles:
        if role_present(role, truth) is not True:
            failures.append((role, True, role_present(role, truth)))
    for role in absent_roles:
        if role_present(role, truth) is not False:
            failures.append((role, False, role_present(role, truth)))
    return failures


def expectation_rows(present_roles, absent_roles, truth, meta, setup_state, device_name):
    """CSV rows recording whether each expected fixture state was really in place for this run."""
    rows = []
    today = dt.datetime.now().isoformat(timespec="seconds")
    for roles, want in ((present_roles, True), (absent_roles, False)):
        for role in roles:
            actual = role_present(role, truth)
            rows.append({
                "signal_id": f"SETUP:{role}", "api_level": meta.get("sdk"), "device_or_image": device_name,
                "oem_build": meta.get("display"), "flavor": meta.get("applicationId"), "target_sdk": meta.get("targetSdk"),
                "permission_state": "", "setup_state": setup_state,
                "result_code": "OK" if actual is want else "FAILED",
                "value_summary": f"expected {'enabled' if want else 'absent'}, system says {actual}",
                "ground_truth_match": "true" if actual is want else "false", "notes": "", "date": today,
            })
    return rows


def _reading_value(reading):
    return (reading or {}).get("value") or {}


def _ok(reading):
    return reading is not None and reading.get("code") == "OK"


def cmp_d1(r, truth):
    if not _ok(r):
        return None, "reading not OK"
    got, want = _reading_value(r).get("securityPatch"), truth.get("getprop.security_patch")
    return got == want, f"probe={got} truth={want}"


def cmp_d2(r, truth):
    if not _ok(r):
        return None, "reading not OK"
    v = _reading_value(r)
    ok = str(v.get("sdk")) == truth.get("getprop.sdk") and v.get("release") == truth.get("getprop.release")
    return ok, f"probe sdk={v.get('sdk')} release={v.get('release')} truth sdk={truth.get('getprop.sdk')} release={truth.get('getprop.release')}"


def _cmp_setting(r, key_in_reading, truth_key, truth):
    if not _ok(r):
        return None, "reading not OK"
    got = _reading_value(r).get(key_in_reading)
    want = truth.get(truth_key)
    want_int = -1 if want in (None, "null") else int(want)
    return got == want_int, f"probe={got} truth={want}"


def cmp_d4(r, truth):
    return _cmp_setting(r, "adb_enabled", "global.adb_enabled", truth)


def cmp_d5(r, truth):
    return _cmp_setting(r, "adb_wifi_enabled", "global.adb_wifi_enabled", truth)


def cmp_d6(r, truth):
    return _cmp_setting(r, "development_settings_enabled", "global.development_settings_enabled", truth)


def cmp_p1(r, truth):
    if not _ok(r):
        return None, "reading not OK"
    probe = {norm_component(x.get("id")) for x in _reading_value(r).get("enabled", [])}
    want = split_components(truth.get("secure.enabled_accessibility_services"))
    return probe == want, f"missing={sorted(want - probe)} extra={sorted(probe - want)}"


def cmp_p3(r, truth):
    if not _ok(r):
        return None, "reading not OK"
    probe = split_components(_reading_value(r).get("secureSetting"))
    want = split_components(truth.get("secure.enabled_notification_listeners"))
    return probe == want, f"missing={sorted(want - probe)} extra={sorted(probe - want)}"


def cmp_p4(r, truth):
    if not _ok(r):
        return None, "reading not OK"
    admins = _reading_value(r).get("admins")
    probe = {norm_component(a) for a in admins} if admins else set()
    want = parse_admins(truth.get("dumpsys.device_policy"))
    return probe == want, f"missing={sorted(want - probe)} extra={sorted(probe - want)} (parser is best-effort)"


def cmp_p5(r, truth):
    if not _ok(r):
        return None, "reading not OK"
    v = _reading_value(r)
    raw = v.get("defaultInputMethod") or ""
    if raw.startswith("ERR:"):
        return None, f"default input method not readable: {raw}"
    got = norm_component(raw)
    want = norm_component(truth.get("secure.default_input_method") or "")
    return got == want, f"probe={got} truth={want}"


APPOPS_MODES = {"allow": 0, "ignore": 1, "deny": 2, "errored": 2, "default": 3, "foreground": 4}
PROBE_OP_TO_APPOPS = {
    "systemAlertWindow": "SYSTEM_ALERT_WINDOW",
    "getUsageStats": "GET_USAGE_STATS",
    "requestInstallPackages": "REQUEST_INSTALL_PACKAGES",
    "readSms": "READ_SMS",
}


OP_BACKING_PERMISSION = {"READ_SMS": "android.permission.READ_SMS"}


def parse_appops(text):
    """Parse 'appops get <pkg>' output into {OP_NAME: mode_int}. Ops that are not listed are at their default."""
    ops = {}
    for line in (text or "").splitlines():
        m = re.match(r"^\s*([A-Z_0-9]+):\s*([a-z]+)", line)
        if m and m.group(2) in APPOPS_MODES:
            ops[m.group(1)] = APPOPS_MODES[m.group(2)]
    return ops


def cmp_p7(r, truth):
    """Compare app-ops for the perms fixture against 'appops get'. Unlisted ops count as default (3)."""
    if not _ok(r):
        return None, "reading not OK"
    row = _reading_value(r).get("rows", {}).get(PERMS_FIXTURE)
    if row is None:
        return None, "perms fixture not among probed packages"
    unreadable = {k: row[k] for k in PROBE_OP_TO_APPOPS if isinstance(row.get(k), str) and row[k].startswith("ERR:")}
    if unreadable:
        # A refused read is a finding about observability (the signal is not available here), not a wrong value.
        return None, "cross-app app-ops not readable: " + ", ".join(f"{k}={v}" for k, v in unreadable.items())
    want = parse_appops(truth.get(f"appops.{PERMS_FIXTURE}"))
    granted = parse_granted(truth.get(f"dumpsys.package.{PERMS_FIXTURE}"))
    diffs = []
    for key, op in PROBE_OP_TO_APPOPS.items():
        got = row.get(key)
        if op in want:
            expected = want[op]
        elif granted.get(OP_BACKING_PERMISSION.get(op, ""), False):
            # Runtime-permission-backed ops are allowed once the permission is granted, but 'appops get'
            # does not list them until they are used. Treating them as default was a ground-truth defect.
            expected = 0
        else:
            expected = 3
        if got != expected:
            diffs.append(f"{op}: probe={got} truth={expected}")
    return not diffs, "; ".join(diffs) if diffs else "all four ops match appops"


def cmp_p9(r, truth):
    """Granted sensitive permissions of the perms fixture vs 'dumpsys package'."""
    if not _ok(r):
        return None, "reading not OK"
    rows = {x.get("package"): x for x in _reading_value(r).get("rows", [])}
    row = rows.get(PERMS_FIXTURE)
    if row is None:
        return None, "perms fixture not in rows"
    granted_truth = {p for p, g in parse_granted(truth.get(f"dumpsys.package.{PERMS_FIXTURE}")).items()
                     if g and p.startswith("android.permission.") and p.rsplit(".", 1)[1] in SENSITIVE_SHORT}
    got = {f"android.permission.{x}" for x in row.get("granted", [])}
    return got == granted_truth, f"missing={sorted(granted_truth - got)} extra={sorted(got - granted_truth)}"


SENSITIVE_SHORT = {
    "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_CONTACTS", "READ_CALL_LOG", "RECORD_AUDIO", "CAMERA",
    "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES",
    "PACKAGE_USAGE_STATS", "QUERY_ALL_PACKAGES",
}


def cmp_v2_installer(r, truth):
    """Installer of each fixture row vs 'dumpsys package installerPackageName'."""
    if not _ok(r):
        return None, "reading not OK"
    rows = {x.get("package"): x for x in _reading_value(r).get("rows", [])}
    notes, ok, seen = [], True, 0
    for role in FIXTURE_ROLES:
        pkg = f"lab.fixture.{role}"
        if pkg not in rows:
            continue
        seen += 1
        want = parse_installer(truth.get(f"dumpsys.package.{pkg}"))
        got = rows[pkg].get("installer")
        if got != want:
            ok = False
        notes.append(f"{role}: probe={got} truth={want} tier={rows[pkg].get('tier')}")
    if not seen:
        return None, "no fixture rows"
    return ok, "; ".join(notes)


def cmp_n2(r, truth):
    if not _ok(r):
        return None, "reading not OK"
    v = _reading_value(r)
    mode, spec = truth.get("global.private_dns_mode"), truth.get("global.private_dns_specifier")
    active, name = v.get("privateDnsActive"), v.get("privateDnsServerName")
    if mode == "hostname":
        return (active is True and name == spec), f"mode=hostname probe active={active} name={name} truth spec={spec}"
    if mode == "off":
        return active is False, f"mode=off probe active={active}"
    return None, f"mode={mode}: opportunistic state cannot be matched exactly (probe active={active})"


def cmp_k8(r, truth):
    if not _ok(r):
        return None, "reading not OK"
    sha3 = _reading_value(r).get("platformSha3", {})
    if not sha3.get("available"):
        return None, "platform SHA3-256 not available"
    return sha3.get("abcDigest") == SHA3_256_ABC, f"abc digest {'matches' if sha3.get('abcDigest') == SHA3_256_ABC else 'DIFFERS'} the known test vector"


COMPARATORS = {
    "D1": cmp_d1, "D2": cmp_d2, "D4": cmp_d4, "D5": cmp_d5, "D6": cmp_d6,
    "P1": cmp_p1, "P3": cmp_p3, "P4": cmp_p4, "P5": cmp_p5, "P7": cmp_p7, "P9": cmp_p9,
    "V2": cmp_v2_installer, "N2": cmp_n2, "K8": cmp_k8,
}


def compare(readings, truth):
    """readings: {id: reading dict}. Returns {id: (match True/False/None, note)}."""
    out = {}
    for sid, fn in COMPARATORS.items():
        out[sid] = fn(readings.get(sid), truth)
    return out


def summarize(value, limit=200):
    s = json.dumps(value, sort_keys=True, ensure_ascii=False)
    return s if len(s) <= limit else s[: limit - 3] + "..."


def permission_state(readings):
    v = _reading_value(readings.get("N6"))
    if not v:
        return "unknown"
    return f"fine={int(bool(v.get('permFine')))} nearby={int(bool(v.get('permNearby')))} location={int(bool(v.get('locationEnabled')))}"


def build_rows(doc, truth, setup_state, device_name):
    """Turn a probe document plus truth into CSV row dicts."""
    meta = doc["meta"]
    readings = {r["id"]: r for r in doc["readings"]}
    # A redacted run hashes identifiers, so comparing it with plaintext system truth would report false mismatches.
    if meta.get("redact"):
        matches = {sid: (None, "redacted run: identifiers are hashed, so not comparable with system truth") for sid in COMPARATORS}
    else:
        matches = compare(readings, truth)
    perm = permission_state(readings)
    today = dt.datetime.now().isoformat(timespec="seconds")
    rows = []
    for r in doc["readings"]:
        m, note = matches.get(r["id"], (None, ""))
        notes = "; ".join(x for x in [note, r.get("note") or ""] if x)
        rows.append({
            "signal_id": r["id"],
            "api_level": meta.get("sdk"),
            "device_or_image": device_name,
            "oem_build": meta.get("display"),
            "flavor": meta.get("applicationId"),
            "target_sdk": meta.get("targetSdk"),
            "permission_state": perm,
            "setup_state": setup_state,
            "result_code": r["code"],
            "value_summary": summarize(r.get("value")),
            "ground_truth_match": "n/a" if m is None else str(m).lower(),
            "notes": notes,
            "date": today,
        })
    return rows


def append_csv(rows, path=CSV_PATH):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    new = not os.path.exists(path)
    with open(path, "a", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        if new:
            w.writeheader()
        w.writerows(rows)


# ---------------------------------------------------------------- adb plumbing

def adb(serial, *args, timeout=120, check=True):
    cmd = [ADB] + (["-s", serial] if serial else []) + list(args)
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace")
    if check and p.returncode != 0:
        raise RuntimeError(f"adb failed ({' '.join(cmd)}): {p.stderr.strip() or p.stdout.strip()}")
    return p.stdout


def shell(serial, command, **kw):
    return adb(serial, "shell", command, **kw).strip()


def settings_get(serial, scope, key):
    v = shell(serial, f"settings get {scope} {key}", check=False)
    return None if v in ("", "null") else v


def collect_truth(serial, extra_packages=()):
    truth = {
        "getprop.security_patch": shell(serial, "getprop ro.build.version.security_patch"),
        "getprop.sdk": shell(serial, "getprop ro.build.version.sdk"),
        "getprop.release": shell(serial, "getprop ro.build.version.release"),
        "getprop.model": shell(serial, "getprop ro.product.model"),
    }
    for scope, key in [
        ("global", "adb_enabled"), ("global", "adb_wifi_enabled"), ("global", "development_settings_enabled"),
        ("global", "http_proxy"), ("global", "private_dns_mode"), ("global", "private_dns_specifier"),
        ("secure", "enabled_accessibility_services"), ("secure", "enabled_notification_listeners"),
        ("secure", "default_input_method"), ("secure", "enabled_input_methods"),
    ]:
        truth[f"{scope}.{key}"] = settings_get(serial, scope, key)
    truth["dumpsys.device_policy"] = adb(serial, "shell", "dumpsys device_policy", check=False)
    for pkg in extra_packages:
        truth[f"dumpsys.package.{pkg}"] = adb(serial, "shell", f"dumpsys package {pkg}", check=False)
    truth[f"appops.{PERMS_FIXTURE}"] = adb(serial, "shell", f"appops get {PERMS_FIXTURE}", check=False)
    return truth


def run_probe(serial, flavor, label, redact=False, keys=None, timeout=120):
    pkg = PROBE_PACKAGES[flavor]
    run_id = uuid.uuid4().hex[:12]
    shell(serial, "input keyevent KEYCODE_WAKEUP", check=False)
    cmd = (f"am start -n {pkg}/lab.probe.MainActivity --ez autorun true --es label '{label}' "
           f"--es run_id {run_id} --ez redact {str(bool(redact)).lower()}")
    if keys:
        cmd += f" --es keys '{keys}'"
    shell(serial, cmd)
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(2)
        raw = adb(serial, "exec-out", "run-as", pkg, "cat", "files/latest.json", check=False)
        try:
            doc = json.loads(raw)
        except ValueError:
            continue
        if doc.get("meta", {}).get("runId") == run_id:
            return doc
    raise TimeoutError(f"probe did not finish within {timeout}s (run_id={run_id})")


def apk_paths(kind):
    pattern = {"probe": "probe/build/outputs/apk/**/*-debug.apk", "fixtures": "fixtures/build/outputs/apk/**/*-debug.apk"}[kind]
    return sorted(glob.glob(os.path.join(LAB, pattern), recursive=True))


# ---------------------------------------------------------------- commands

def cmd_devices(_args):
    print(adb(None, "devices", "-l"))


def cmd_install(args):
    kinds = ["probe", "fixtures"] if args.what == "all" else [args.what]
    for kind in kinds:
        for apk in apk_paths(kind):
            print("installing", os.path.relpath(apk, LAB))
            print(adb(args.serial, "install", "-r", "-t", apk, timeout=300).strip())


def _a11y_current(serial):
    return settings_get(serial, "secure", "enabled_accessibility_services") or ""


def cmd_setup(args):
    for role in [r for r in (args.enable or "").split(",") if r]:
        for c in enable_commands(role, _a11y_current(args.serial)):
            print(">", c, "->", shell(args.serial, c, check=False))
    for role in [r for r in (args.disable or "").split(",") if r]:
        for c in disable_commands(role, _a11y_current(args.serial)):
            print(">", c, "->", shell(args.serial, c, check=False))


def cmd_run(args):
    os.makedirs(RAW_DIR, exist_ok=True)
    doc = run_probe(args.serial, args.flavor, args.label, args.redact, args.keys)
    fixtures = [f"lab.fixture.{r}" for r in FIXTURE_ROLES]
    truth = collect_truth(args.serial, fixtures)
    device = truth.get("getprop.model") or args.serial
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    with open(os.path.join(RAW_DIR, f"{device}-{args.flavor}-{stamp}.json"), "w", encoding="utf-8") as f:
        admin_lines = [ln.strip() for ln in (truth.get("dumpsys.device_policy") or "").splitlines() if "admin" in ln.lower()][:40]
        json.dump({"probe": doc,
                   "truth_keys": {k: v for k, v in truth.items() if not k.startswith("dumpsys.")},
                   "device_policy_admin_lines": admin_lines}, f, indent=2)
    present, absent = _expected_roles(args)
    rows = build_rows(doc, truth, args.state or args.label, device)
    setup_rows = expectation_rows(present, absent, truth, doc["meta"], args.state or args.label, device)
    append_csv(rows + setup_rows)
    bad = [r for r in rows if r["ground_truth_match"] == "false"]
    setup_bad = [r for r in setup_rows if r["result_code"] == "FAILED"]
    print(f"{len(rows)} rows (+{len(setup_rows)} setup checks) appended to {os.path.relpath(CSV_PATH, LAB)}; "
          f"{len(bad)} ground-truth mismatches; {len(setup_bad)} setup failures")
    for r in bad:
        print("  MISMATCH", r["signal_id"], r["notes"])
    for r in setup_bad:
        print("  SETUP FAILED", r["signal_id"], r["value_summary"])


def _expected_roles(args):
    """Roles that must be enabled / absent for this run: explicit flags, else inferred from the label."""
    if args.expect is not None or args.absent is not None:
        split = lambda s: [x for x in (s or "").split(",") if x]
        return split(args.expect), split(args.absent)
    return LABEL_EXPECTATIONS.get(args.label, ([], []))


IDENTIFYING_SETTINGS = {"android_id", "bluetooth_address", "bluetooth_name", "device_name"}


def redact_settings(dump):
    """Blank values of settings that identify the device or its owner. Keys are kept so diffs still work."""
    return {scope: {k: ("<redacted>" if k in IDENTIFYING_SETTINGS else v) for k, v in entries.items()}
            for scope, entries in dump.items()}


def cmd_settings_dump(args):
    dump = {s: parse_settings_list(shell(args.serial, f"settings list {s}", check=False)) for s in ("secure", "global", "system")}
    dump = redact_settings(dump)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(dump, f, indent=2, sort_keys=True)
    print({s: len(v) for s, v in dump.items()}, "->", args.out)


def cmd_settings_diff(args):
    with open(args.before, encoding="utf-8") as f:
        before = json.load(f)
    with open(args.after, encoding="utf-8") as f:
        after = json.load(f)
    for d in settings_diff(before, after):
        print(f"{d['scope']}:{d['key']}  {d['before']!r} -> {d['after']!r}")


def cmd_restore(args):
    for role in FIXTURE_ROLES:
        for c in disable_commands(role, _a11y_current(args.serial)):
            shell(args.serial, c, check=False)
    for pkg in [f"lab.fixture.{r}" for r in FIXTURE_ROLES] + list(PROBE_PACKAGES.values()):
        print("uninstall", pkg, "->", adb(args.serial, "uninstall", pkg, check=False).strip())


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("devices").set_defaults(fn=cmd_devices)

    s = sub.add_parser("install")
    s.add_argument("--serial")
    s.add_argument("--what", choices=["probe", "fixtures", "all"], default="all")
    s.set_defaults(fn=cmd_install)

    s = sub.add_parser("setup")
    s.add_argument("--serial")
    s.add_argument("--enable")
    s.add_argument("--disable")
    s.set_defaults(fn=cmd_setup)

    s = sub.add_parser("run")
    s.add_argument("--serial")
    s.add_argument("--flavor", choices=list(PROBE_PACKAGES), default="q")
    s.add_argument("--label", default="baseline")
    s.add_argument("--state", help="free-text setup state recorded in the CSV")
    s.add_argument("--redact", action="store_true")
    s.add_argument("--keys")
    s.add_argument("--expect", help="comma list of fixture roles that must really be enabled (default: inferred from --label)")
    s.add_argument("--absent", help="comma list of fixture roles that must really be absent (default: inferred from --label)")
    s.set_defaults(fn=cmd_run)

    s = sub.add_parser("settings-dump")
    s.add_argument("--serial")
    s.add_argument("--out", required=True)
    s.set_defaults(fn=cmd_settings_dump)

    s = sub.add_parser("settings-diff")
    s.add_argument("before")
    s.add_argument("after")
    s.set_defaults(fn=cmd_settings_diff)

    s = sub.add_parser("restore")
    s.add_argument("--serial")
    s.set_defaults(fn=cmd_restore)

    args = p.parse_args(argv)
    args.fn(args)


if __name__ == "__main__":
    main()
