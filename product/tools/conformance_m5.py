#!/usr/bin/env python3
"""Milestone 5 conformance: the baseline review, the encrypted store and "what changed", driven through the real UI. Standard library only.

An EMULATOR is put into a known state with the lab fixtures, the app's data is cleared, and the app is then used the way
a person uses it: it shows the first-run screen, the review is answered by tapping, and the app is restarted. After every
step the debug build reports what it holds in memory AND what it reads back from the encrypted file on disk, and that is
compared with independent truth:

  * which holders must be up for review: the system's own state of each fixture role (verified with `dumpsys`/`settings`)
  * the signing certificate stored with each answer: `apksigner verify --print-certs` on the fixture APK
  * persistence: the file is read again after a process restart, an app update (`adb install -r`) and an emulator reboot
  * a changed signer: a fixture re-signed with a new key (apksigner), reinstalled, and found by the product
  * damage: one flipped byte, a truncated file and a wrong header must each be reported as Corrupt, never as an empty baseline

Run from a PowerShell where `. lab\\tools\\env.ps1` has been dot-sourced. Refuses to touch anything but an emulator.
"""
import argparse
import csv
import datetime as dt
import json
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import conformance_m1 as m1  # noqa: E402
import conformance_m2 as m2  # noqa: E402
import runner  # noqa: E402

PRODUCT = m1.PRODUCT
RESULTS_CSV = os.path.join(PRODUCT, "conformance", "m5_results.csv")
SHOTS_DIR = os.path.join(PRODUCT, "conformance", "screenshots")
RUN_ID = dt.datetime.now().isoformat(timespec="seconds")
STATE_FILE = "files/declared-state.bin"

HOLDER_KEYS = {
    "a11y": ["ACCESSIBILITY:lab.fixture.a11y/lab.fixture.FixtureA11yService"],
    "notif": ["NOTIFICATION_LISTENER:lab.fixture.notif/lab.fixture.FixtureNotifListener"],
    "admin": ["DEVICE_ADMIN:lab.fixture.admin/lab.fixture.FixtureAdminReceiver"],
    "ime": ["INPUT_METHOD:lab.fixture.ime/lab.fixture.FixtureIme"],
    "perms": ["OVERLAY:lab.fixture.perms", "USAGE_ACCESS:lab.fixture.perms", "INSTALL_UNKNOWN_APPS:lab.fixture.perms", "SMS_ACCESS:lab.fixture.perms"],
}
ROLE_PACKAGE = {"a11y": "lab.fixture.a11y", "notif": "lab.fixture.notif", "admin": "lab.fixture.admin", "ime": "lab.fixture.ime", "perms": "lab.fixture.perms"}


# ---------------------------------------------------------------- pure helpers (unit tested)

ANDROID_10_REFUSED = ("INSTALL_UNKNOWN_APPS:", "OVERLAY:", "USAGE_ACCESS:")


def expected_pending(enabled_roles, answered=(), api=None):
    """The holders that must be up for review: those of every enabled fixture role that have no answer yet.

    On Android 10 the system refuses to say which apps may draw over others, see app usage or install apps, and the product says so
    (those signals are UNAVAILABLE), so a holder of those powers cannot be up for review there."""
    keys = []
    for role in enabled_roles:
        keys += HOLDER_KEYS[role]
    if api is not None and int(api) == 29:
        keys = [k for k in keys if not k.startswith(ANDROID_10_REFUSED)]
    return sorted(k for k in keys if k not in set(answered))


def pending_check_keys(state):
    return sorted(state.get("pending", []))


def screens(state):
    # the Check screen is opened on top of the home screen by the scripts' launches (--es start check); it is not a different place to be
    return [s.split("(")[0] for s in state.get("stack", []) if s.split("(")[0] != "Check"]


def flip_byte(data, index):
    out = bytearray(data)
    out[index] ^= 0x01
    return bytes(out)


def damaged_variants(data):
    """Three ways to ruin a sealed file, each with the reason the product is expected to give."""
    return {
        "one flipped byte in the ciphertext": (flip_byte(data, len(data) - 20), "integrity check"),
        "half of the file": (data[: len(data) // 2], "integrity check"),
        "cut inside the header": (data[:20], "truncated"),
        "wrong header": (bytes([0x7F]) + data[1:], "not a saved baseline"),
    }


def holder_changes(items, kind):
    return [i["title"] for i in items if i["kind"] == kind]


class Check(m1.Check):
    def row(self, phase, api):
        return [RUN_ID, api, phase, self.name, json.dumps(self.expected), json.dumps(self.actual), "PASS" if self.ok else "FAIL"]


def compare_persisted(state, expected_answers, expected_signers):
    """In-memory state and the file read back separately must agree with each other and with the independent truth."""
    checks = []
    persisted = state.get("persisted") or {}
    saved = state.get("saved") or {}
    checks.append(Check("the file on disk can be read back (decrypted and parsed)", "Loaded", persisted.get("status")))
    got = {k: v["answer"] for k, v in (persisted.get("answers") or {}).items()}
    checks.append(Check("answers on disk = what was tapped (UNSURE for the unanswered)", dict(sorted(expected_answers.items())), dict(sorted(got.items()))))
    checks.append(Check("answers in memory = answers on disk", got, {k: v["answer"] for k, v in (saved.get("answers") or {}).items()}))
    signers = {k: sorted(v["signers"]) for k, v in (persisted.get("answers") or {}).items()}
    checks.append(Check("signing certificate stored with each answer = apksigner's", dict(sorted(expected_signers.items())), dict(sorted(signers.items()))))
    checks.append(Check("baseline date and reviewed snapshot are stored", True, bool(persisted.get("baselineAt")) and persisted.get("recordCount", 0) > 20))
    return checks


# ---------------------------------------------------------------- device plumbing

APP = {}
API = {}


def sh(serial, command):
    return runner.adb(serial, "shell", command, check=False)


def run_as(serial, *args):
    return runner.adb(serial, "exec-out", "run-as", APP["id"], *args, check=False)


def pull_state_bytes(serial):
    p = subprocess.run([runner.ADB, "-s", serial, "exec-out", "run-as", APP["id"], "cat", STATE_FILE], capture_output=True, timeout=60)
    return p.stdout


def push_state_bytes(serial, data):
    p = subprocess.run([runner.ADB, "-s", serial, "exec-in", "run-as", APP["id"], "sh", "-c", f"cat > {STATE_FILE}"], input=data, capture_output=True, timeout=60)
    return p.returncode == 0


def set_role(serial, role, enabled):
    current = runner.settings_get(serial, "secure", "enabled_accessibility_services") or ""
    cmds = runner.enable_commands(role, current) if enabled else runner.disable_commands(role, current)
    for c in cmds:
        runner.shell(serial, c, check=False)
    time.sleep(1)
    truth = runner.collect_truth(serial, [runner.PERMS_FIXTURE])
    if runner.role_present(role, truth) is not enabled:  # D-004: verify, never assume
        raise SystemExit(f"setup failed: fixture role {role} is not {'enabled' if enabled else 'disabled'} according to the system")


def set_roles(serial, enabled_roles):
    for role in m2.ENABLE_ROLES:
        set_role(serial, role, role in enabled_roles)


def launch(serial, timeout=240):
    return m1.launch_and_pull(serial, APP["id"], timeout)


def fresh_launch(serial):
    m1.wake(serial)
    runner.shell(serial, f"am start -S -W -n {APP['id']}/io.github.h_sib.declared.app.MainActivity --ez allowCapture true --ez expandAll true")
    time.sleep(4)
    for _ in range(14):  # a fresh image can take much longer than four seconds to draw the first screen
        shown = " | ".join(n["text"] for n in m1.screen_text(serial) if n["text"])
        if any(m1.FIRST_SCREEN.search(n["text"] or "") for n in m1.screen_text(serial)):
            break
        time.sleep(2)


def tap_text(serial, ui, pattern, scroll=False):
    node = m2.scroll_find(serial, ui, pattern) if scroll else ui.find(ui.dump(serial), pattern)
    if not node:
        return False
    ui.tap_xy(serial, *ui.center(node["bounds"]))
    time.sleep(1.5)
    return True


def tap_chip(serial, ui, card_title, chip_text, max_swipes=12):
    """Tap one of a card's answer chips: the chip with that label nearest below the card's title. A card with its details open is taller than the
    screen, so the list is scrolled until the title and the chip are both on screen."""
    for _ in range(max_swipes + 1):
        nodes = ui.dump(serial)
        title = ui.find(nodes, card_title)
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


def screen_texts(serial, ui):
    return " | ".join(n["text"] for n in ui.dump(serial) if n["text"])


def wait_for_boot(serial, timeout=420):
    runner.adb(None, "wait-for-device", timeout=timeout, check=False)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if runner.shell(serial, "getprop sys.boot_completed", check=False) == "1":
            ok = "package:" in runner.adb(serial, "shell", "pm path android", check=False) and "Can't find" not in runner.adb(serial, "shell", "cmd activity get-current-user", check=False)
            if ok:
                time.sleep(10)
                return True
        time.sleep(5)
    return False


def make_resigned_apk(workdir):
    """A copy of the notif fixture signed with a brand-new key. Returns (path, its signer SHA-256 list)."""
    ks = os.path.join(workdir, "resign.jks")
    exe = os.path.join(os.environ["JAVA_HOME"], "bin", "keytool.exe") if os.environ.get("JAVA_HOME") else "keytool"
    subprocess.run([exe, "-genkeypair", "-keystore", ks, "-storepass", "declared-test", "-keypass", "declared-test", "-alias", "k", "-keyalg", "RSA",
                    "-keysize", "2048", "-validity", "30", "-dname", "CN=Declared Resign Test"], capture_output=True, check=True)
    out = os.path.join(workdir, "notif-resigned.apk")
    m1.tool("apksigner.bat", "sign", "--ks", ks, "--ks-pass", "pass:declared-test", "--key-pass", "pass:declared-test", "--out", out, m2.fixture_apk("notif"))
    return out, m1.parse_signer_digests(m1.tool("apksigner.bat", "verify", "--print-certs", out))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--skip-install", action="store_true")
    ap.add_argument("--skip-reboot", action="store_true")
    args = ap.parse_args(argv)
    serial = args.serial
    m1.require_emulator(serial)
    sys.path.insert(0, m1.LAB_TOOLS)
    import ui

    product_apk = m1.find_product_apk()
    APP["id"] = m1.parse_package_name(m1.tool("aapt2.exe", "dump", "badging", product_apk))
    api = runner.shell(serial, "getprop ro.build.version.sdk")
    API["level"] = api
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
    signer = {role: m1.parse_signer_digests(m1.tool("apksigner.bat", "verify", "--print-certs", m2.fixture_apk(role))) for role in ("a11y", "notif", "admin", "ime", "perms")}
    key_signers = lambda roles: {k: sorted(signer[r]) for r in roles for k in HOLDER_KEYS[r]}  # noqa: E731
    stayed_on = runner.settings_get(serial, "global", "stay_on_while_plugged_in")
    sh(serial, "svc power stayon true")

    try:
        # ---------------------------------------------------------------- first run, answered by tapping
        sh(serial, f"pm clear {APP['id']}")
        set_roles(serial, ["a11y", "notif", "admin"])
        d = launch(serial)
        st = d["state"]
        record("first-run", [
            Check("nothing is saved on a fresh install: the file reads as Empty", "Empty", st["persisted"]["status"]),
            Check("the first-run screen comes first and nothing else can be reached", ["FirstRun"], screens(st)),
            Check("the holders up for review = the fixture roles the system says are on", expected_pending(["a11y", "notif", "admin"]), pending_check_keys(st)),
        ])
        fresh_launch(serial)
        ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m5-first-run-api{api}.png"))
        page1 = screen_texts(serial, ui)
        tap_text(serial, ui, r"^Next$")
        page2 = screen_texts(serial, ui)
        tap_text(serial, ui, r"^Next$")
        page3 = screen_texts(serial, ui)
        record("first-run-ui", [Check("the intro comes first, in three pages: what it reads, that nothing leaves the phone, then the review", True,
                                      "See what your phone allows" in page1 and "Skip" in page1 and "Private by design" in page2 and "Nothing leaves this phone." in page2
                                      and "Start the review" in page3)])
        tap_text(serial, ui, r"Start the review")
        ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m5-review-api{api}.png"))
        tapped = [
            tap_chip(serial, ui, r"^Fixture a11y", "I recognize this"),
            tap_chip(serial, ui, r"^Fixture notif", "I do not recognize this"),
        ]
        record("review-taps", [Check("both taps landed on their cards' chips", [True, True], tapped)])
        m1.scroll_top(serial)  # the taps above scrolled the list down
        shown = " | ".join(m1.scrolled_texts(serial))  # the cards are taller than the screen: scroll to see them all
        record("review-ui", [Check("the review lists each holder with where it came from", True, all(s in shown for s in ("Fixture a11y", "Fixture notif", "Fixture admin", "CAME FROM")))])
        record("review-finish", [Check("the Finish button was found and tapped", True, tap_text(serial, ui, r"Finish the review", scroll=True))])

        d = launch(serial)  # a new process: whatever it shows must have come from the file
        st = d["state"]
        answers = {HOLDER_KEYS["a11y"][0]: "RECOGNIZED", HOLDER_KEYS["notif"][0]: "NOT_RECOGNIZED", HOLDER_KEYS["admin"][0]: "UNSURE"}
        record("after-first-review", compare_persisted(st, answers, key_signers(["a11y", "notif", "admin"])) + [
            Check("after the review the inventory is reachable: no first-run screen", ["Inventory"], screens(st)),
            Check("nothing is up for review any more", [], pending_check_keys(st)),
            Check("nothing has changed since the baseline", 0, st["changes"]["raw"] if st["changes"] else None),
            Check("'I do not recognize this' became a LOOK_FIRST finding in the report", True,
                  any(f["id"] == f"review:not-recognized:{HOLDER_KEYS['notif'][0]}" and f["attention"] == "LOOK_FIRST" for f in d["findings"])),
            Check("recognized and unsure answers made no finding of their own", [],
                  sorted(f["id"] for f in d["findings"] if f["id"].startswith("review:") and "not-recognized" not in f["id"])),
        ])

        # ---------------------------------------------------------------- something new appears
        set_role(serial, "perms", True)
        d = launch(serial)
        st = d["state"]
        titles = [i["title"] for i in (st["changes"] or {}).get("items", [])]
        record("new-since-baseline", [
            Check("the new holders are up for review, and only those", expected_pending(["perms"], api=API.get("level")), pending_check_keys(st)),
            Check("what changed lists them as new findings at LOOK_FIRST", True,
                  any(i["kind"] == "NEW_FINDING" and i["attention"] == "LOOK_FIRST" and "Fixture perms" in i["title"] for i in (st["changes"] or {"items": []})["items"])),
            Check("the answers already given are untouched", "RECOGNIZED", st["persisted"]["answers"][HOLDER_KEYS["a11y"][0]]["answer"]),
            Check("the changes are not cleared by opening the app again", True, len(titles) > 0),
        ])
        d = launch(serial)
        record("changes-persist", [Check("a second start still shows the same changes (they wait for an acknowledgement)", len(titles), len((d["state"]["changes"] or {}).get("items", [])))])

        fresh_launch(serial)
        m1.tap_tab(serial, ui, "Check")
        tap_text(serial, ui, r"changes since you last looked", scroll=True)
        ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m5-what-changed-api{api}.png"))
        shown = " | ".join(m1.scrolled_texts(serial))
        record("what-changed-ui", [Check("the what-changed screen names the new items and offers the acknowledgement", True,
                                         "What changed" in shown and "I have seen these changes" in shown and "Fixture perms" in shown)])
        tap_text(serial, ui, r"I have seen these changes", scroll=True)
        d = launch(serial)
        st = d["state"]
        record("acknowledged", [
            Check("after 'I have seen these changes' nothing is listed", 0, len((st["changes"] or {}).get("items", []))),
            Check("the acknowledgement was written to disk (reviewed date moved)", True, st["persisted"]["reviewedAt"] > st["persisted"]["baselineAt"]),
            Check("the answers survived the acknowledgement", 3, len(st["persisted"]["answers"])),
            Check("the new holders are still waiting for an answer: acknowledging is not answering", expected_pending(["perms"], api=API.get("level")), pending_check_keys(st)),
        ])
        fresh_launch(serial)
        m1.tap_tab(serial, ui, "Check")
        tap_text(serial, ui, r"new to review", scroll=True)
        tapped = tap_chip(serial, ui, r"^Fixture perms", "I recognize this")
        tap_text(serial, ui, r"Save my answers", scroll=True)
        d = launch(serial)
        st = d["state"]
        record("later-review", [
            Check("the later review answered what it showed and kept the earlier answers", True, len(st["persisted"]["answers"]) >= 4 and tapped),
            Check("the original baseline date is kept", True, st["persisted"]["baselineAt"] <= st["persisted"]["reviewedAt"]),
        ])

        # ---------------------------------------------------------------- a changed signing certificate
        with tempfile.TemporaryDirectory() as tmp:
            resigned, new_signers = make_resigned_apk(tmp)
            runner.adb(serial, "uninstall", ROLE_PACKAGE["notif"], check=False)
            runner.adb(serial, "install", "-t", resigned)
            set_role(serial, "notif", True)
            d = launch(serial)
            st = d["state"]
            pkg = next((p for p in d["raw"]["packages"]["items"] if p["pkg"] == ROLE_PACKAGE["notif"]), {})
            key = HOLDER_KEYS["notif"][0]
            record("signer-changed", [
                Check("the product reads the new signing certificate (apksigner on the re-signed APK)", sorted(new_signers), sorted(pkg.get("signerSha256", []))),
                Check("the new certificate differs from the one stored with the answer", True, sorted(new_signers) != sorted(st["persisted"]["answers"][key]["signers"])),
                Check("the report has a LOOK_FIRST signer-changed finding for it", True,
                      any(f["id"] == f"review:signer-changed:{key}" and f["attention"] == "LOOK_FIRST" for f in d["findings"])),
                Check("what changed says the app is now signed by a different certificate", True,
                      any(i["kind"] == "SIGNER_CHANGED" and i["attention"] == "LOOK_FIRST" for i in (st["changes"] or {}).get("items", []))),
            ])
            runner.adb(serial, "uninstall", ROLE_PACKAGE["notif"], check=False)
            runner.adb(serial, "install", "-t", m2.fixture_apk("notif"))
            set_role(serial, "notif", True)

        # ---------------------------------------------------------------- persistence across update and reboot
        before = launch(serial)["state"]["persisted"]
        runner.adb(serial, "install", "-r", product_apk)
        after_update = launch(serial)["state"]["persisted"]
        record("after-app-update", [
            Check("an app update (adb install -r) keeps the key: the file still decrypts", "Loaded", after_update["status"]),
            Check("the answers are the same after the update", before["answers"], after_update["answers"]),
        ])
        if not args.skip_reboot:
            runner.adb(serial, "reboot", check=False)
            booted = wait_for_boot(serial)
            sh(serial, "svc power stayon true")
            m1.wake(serial)
            after_reboot = launch(serial)["state"]["persisted"] if booted else {}
            record("after-reboot", [
                Check("the emulator came back", True, booted),
                Check("a reboot keeps the key and the file: it still decrypts", "Loaded", after_reboot.get("status")),
                Check("the answers are the same after the reboot", before["answers"], after_reboot.get("answers")),
            ])

        # ---------------------------------------------------------------- damage: reported, never an empty baseline
        good = pull_state_bytes(serial)
        record("file-on-disk", [Check("the file is binary, not readable text (encrypted at rest)", True, bool(good) and good[0] == 0xCA and b"RECOGNIZED" not in good and b"lab.fixture" not in good)])
        damaged = []
        for name, (data, reason) in damaged_variants(good).items():
            ok = push_state_bytes(serial, data)
            st = launch(serial)["state"]
            damaged.append((name, ok, st["persisted"]["status"], reason in st["persisted"].get("reason", ""), st["store"], screens(st)))
        record("damaged-file", [Check(f"{n}: reported as Corrupt with the right reason, first run offered, not silently empty", [True, "Corrupt", True, "Corrupt", ["FirstRun"]], [ok, status, why, store, scr])
                                for n, ok, status, why, store, scr in damaged])
        push_state_bytes(serial, good)
        restored = launch(serial)["state"]["persisted"]
        record("restored", [Check("the undamaged file reads again", "Loaded", restored["status"])])
    finally:
        set_roles(serial, [])
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
