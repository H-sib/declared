#!/usr/bin/env python3
"""Milestone 9: automatic checks, the simple view, the tour and the intro, driven the way a person drives them.

Automatic checks are off by default; turned on in the app they schedule one repeating job (read back from `dumpsys jobscheduler`), the interval
can be a preset or typed, a change that needs a look makes one notification that says how many and never which app, and the same change is not
announced twice. The simple view swaps the screens and the bar, the tour opens from the home screen, and the story of the name opens from Help.
Needs the emulator (run it from a PowerShell where `. lab\\tools\\env.ps1` has been dot-sourced). Standard library only.
"""
import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import conformance_m1 as m1  # noqa: E402
from conformance_m1 import runner  # noqa: E402

sys.path.insert(0, m1.LAB_TOOLS)
import ui  # noqa: E402

JOB_ID = 4201
ACTIVITY = "io.github.h_sib.declared.app.MainActivity"
PROXY = "127.0.0.1:8899"
checks = []


def check(phase, name, expected, actual):
    ok = expected == actual
    checks.append(ok)
    if not ok:
        print(f"  FAIL [{phase}] {name}: expected {expected!r}, got {actual!r}")
    return ok


def sh(serial, command):
    return runner.shell(serial, command, check=False)


def nodes_text(serial):
    return " | ".join(n["text"] or n["desc"] for n in ui.dump(serial) if (n["text"] or n["desc"]))


def wait_text(serial, pattern, timeout=40):
    end = time.time() + timeout
    while time.time() < end:
        if ui.find(ui.dump(serial), pattern):
            return True
        time.sleep(1.5)
    return False


def start(serial, pkg):
    m1.wake(serial)
    sh(serial, f"am start -S -W -n {pkg}/{ACTIVITY} --ez allowCapture true")
    return wait_text(serial, r"^Works offline$|^Check my phone again$", 120)


def duration_ms(text):
    units = {"d": 86_400_000, "h": 3_600_000, "m": 60_000, "s": 1_000, "ms": 1}
    return sum(int(n) * units[u] for n, u in re.findall(r"(\d+)(ms|d|h|m|s)", text))


def job_interval(serial, pkg):
    """None when the app has no job, the interval in milliseconds when it has one, -1 when the job is there and the interval could not be read."""
    lines = runner.adb(serial, "shell", "dumpsys jobscheduler", check=False).splitlines()
    for i, line in enumerate(lines):
        if "JOB #" in line and pkg in line and "CheckJobService" in line and f"/{JOB_ID}" in line:
            for follow in lines[i + 1:i + 70]:
                if follow.lstrip().startswith("JOB #"):
                    break
                m = re.search(r"interval=\+?([0-9a-z]+)", follow)
                if m:
                    return duration_ms(m.group(1))
            return -1
    return None


def prefs(serial, pkg):
    xml = runner.adb(serial, "exec-out", "run-as", pkg, "cat", "shared_prefs/auto.xml", check=False)
    announced = re.search(r'name="announced" value="(\d+)"', xml)
    return {"enabled": 'name="enabled" value="true"' in xml, "announced": int(announced.group(1)) if announced else 0, "xml": xml}


def our_notification(serial, pkg):
    lines = runner.adb(serial, "shell", "dumpsys notification --noredact", check=False).splitlines()
    for i, line in enumerate(lines):
        if "NotificationRecord" in line and f"pkg={pkg}" in line:
            block = []
            for follow in lines[i:i + 80]:
                if block and "NotificationRecord" in follow:
                    break
                block.append(follow)
            return "\n".join(block)
    return None


def ime_shown(serial):
    return "mInputShown=true" in runner.adb(serial, "shell", "dumpsys input_method", check=False)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--serial", default="emulator-5554")
    args = ap.parse_args(argv)
    serial = args.serial
    m1.require_emulator(serial)
    apk = m1.find_product_apk()
    pkg = m1.parse_package_name(m1.tool("aapt2.exe", "dump", "badging", apk))
    runner.adb(serial, "install", "-r", apk)
    sh(serial, f"pm clear {pkg}")
    sh(serial, f"pm grant {pkg} android.permission.POST_NOTIFICATIONS")
    sh(serial, "settings delete global http_proxy")
    sh(serial, "settings put global stay_on_while_plugged_in 7")
    m1.ensure_baseline(serial, pkg, ui)

    try:
        # ---------------------------------------------------------------- the tour opens from the home screen
        check("setup", "the app reaches the home screen after the first review", True, start(serial, pkg))
        home = nodes_text(serial)
        check("tour", "a new person is offered the tour on the home screen", True, "New here?" in home and "Take the tour" in home)
        check("tour", "the tour card is tapped", True, m1.tap_text(serial, ui, r"^Take the tour$", scroll=True))
        check("tour", "the tour opens on its first page", True, wait_text(serial, r"^Declared checks your phone$", 10))
        for _ in range(6):
            m1.tap_text(serial, ui, r"^Next$")
        last_page = nodes_text(serial)
        check("tour", "the last page is about the simple view and ends in Done", True, "Prefer it simple?" in last_page and "Done" in last_page)
        check("tour", "Done is tapped", True, m1.tap_text(serial, ui, r"^Done$"))
        check("tour", "after the tour the offer is gone from the home screen", True, "New here?" not in nodes_text(serial))

        # ---------------------------------------------------------------- automatic checks: off by default
        check("auto-default", "no job is scheduled before the person turns it on", None, job_interval(serial, pkg))
        m1.tap_tab(serial, ui, "More")
        check("auto-default", "More says it is off and that nothing runs", True, m1.scroll_find(serial, ui, r"^Check automatically$") is not None
              and "Off. Nothing runs in the background." in " | ".join(m1.scrolled_texts(serial, max_swipes=6)))

        # ---------------------------------------------------------------- turn it on: a job with the default interval
        m1.scroll_top(serial)
        check("auto-on", "the switch is tapped", True, m1.tap_text(serial, ui, r"^Check automatically$", scroll=True))
        time.sleep(3)
        check("auto-on", "one day is the first interval", 24 * 3_600_000, job_interval(serial, pkg))
        check("auto-on", "the choice is stored as on", True, prefs(serial, pkg)["enabled"])

        # a preset
        check("auto-preset", "the 6 hours choice is tapped", True, m1.tap_text(serial, ui, r"^6 hours$", scroll=True))
        time.sleep(2)
        check("auto-preset", "the job is rescheduled to 6 hours", 6 * 3_600_000, job_interval(serial, pkg))

        # a typed time: 90 minutes
        field = m1.scroll_find(serial, ui, r"Custom time, number")
        check("auto-custom", "the number box is on screen", True, field is not None)
        if field:
            ui.tap_xy(serial, *ui.center(field["bounds"]))
            time.sleep(1)
            sh(serial, "input keyevent KEYCODE_MOVE_END" + " KEYCODE_DEL" * 7)
            sh(serial, "input text 90")
            time.sleep(1)
            if ime_shown(serial):
                m1.go_back(serial)
            check("auto-custom", "the minutes unit is chosen", True, m1.tap_text(serial, ui, r"^minutes$", scroll=True))
            check("auto-custom", "Use this time is tapped", True, m1.tap_text(serial, ui, r"^Use this time$", scroll=True))
            time.sleep(2)
            check("auto-custom", "any number of minutes works: the job is 90 minutes", 90 * 60_000, job_interval(serial, pkg))
            ui_text = " | ".join(m1.scrolled_texts(serial, max_swipes=6))
            check("auto-custom", "the card says what is set, in words", True, "On, every 90 minutes" in ui_text)

            # too short is explained and changes nothing
            field = m1.scroll_find(serial, ui, r"Custom time, number")
            if field:
                ui.tap_xy(serial, *ui.center(field["bounds"]))
                time.sleep(1)
                sh(serial, "input keyevent KEYCODE_MOVE_END" + " KEYCODE_DEL" * 7)
                sh(serial, "input text 5")
                time.sleep(1)
                if ime_shown(serial):
                    m1.go_back(serial)
                m1.tap_text(serial, ui, r"^Use this time$", scroll=True)
                time.sleep(1)
                shown = " | ".join(m1.scrolled_texts(serial, max_swipes=6))
                check("auto-custom", "5 minutes is refused with the reason", True, "Android runs this at most every 15 minutes." in shown)
                check("auto-custom", "and the job is still 90 minutes", 90 * 60_000, job_interval(serial, pkg))

        # ---------------------------------------------------------------- a change that needs a look: one notification, never repeated
        before = prefs(serial, pkg)["announced"]
        sh(serial, f"settings put global http_proxy {PROXY}")
        time.sleep(3)
        sh(serial, f"cmd jobscheduler run -f {pkg} {JOB_ID}")
        end = time.time() + 90
        while time.time() < end and prefs(serial, pkg)["announced"] <= before:
            time.sleep(3)
        check("notify", "the check announced the change once", before + 1, prefs(serial, pkg)["announced"])
        note = our_notification(serial, pkg) or ""
        check("notify", "the notification is there, with the app's own title", True, "Declared noticed a change" in note)
        check("notify", "it says how many", True, bool(re.search(r"\d+ changes? (is|are) worth a look\.", note)))
        check("notify", "it never says which change, so a locked screen shows nothing about the phone", False, PROXY.split(":")[0] in note or "proxy" in note.lower())
        sh(serial, f"cmd jobscheduler run -f {pkg} {JOB_ID}")
        time.sleep(30)
        check("notify", "the same change is not announced a second time", before + 1, prefs(serial, pkg)["announced"])

        # ---------------------------------------------------------------- turn it off: the job is gone, what was remembered is forgotten
        sh(serial, "settings delete global http_proxy")
        m1.scroll_top(serial)
        check("auto-off", "the switch is tapped again", True, m1.tap_text(serial, ui, r"^Check automatically$", scroll=True))
        time.sleep(3)
        check("auto-off", "the job is gone", None, job_interval(serial, pkg))
        state = prefs(serial, pkg)
        check("auto-off", "the choice is stored as off, and nothing about what was told is kept", (False, False), (state["enabled"], 'name="told"' in state["xml"]))

        # ---------------------------------------------------------------- the simple view
        m1.scroll_top(serial)
        check("simple", "Simple view is switched on from More", True, m1.tap_text(serial, ui, r"^Simple view$", scroll=True))
        time.sleep(2)
        bar = nodes_text(serial)
        check("simple", "the bar has three places: Home, To do, Help", True, all(w in bar for w in ("Home", "To do", "Help")) and "Paths" not in bar)
        m1.tap_tab(serial, ui, "Home")
        simple_home = nodes_text(serial)
        check("simple", "the home screen states what needs attention in plain words", True,
              bool(re.search(r"(\d+ things? needs? your attention|Nothing needs your attention right now)\.", simple_home)) and bool(re.search(r"Check my phone again|Checking\.\.\.", simple_home)))
        m1.tap_tab(serial, ui, "To do")
        todo = nodes_text(serial)
        check("simple", "the to-do list groups what was found", True, "Do these first" in todo or "Worth a look" in todo or "Good to know" in todo or "Nothing found by these checks." in todo)
        m1.tap_tab(serial, ui, "Help")
        check("simple", "Help has the tour and the way back", True, all(w in nodes_text(serial) for w in ("How to use Declared", "Simple view")))

        # ---------------------------------------------------------------- the story of the name
        check("intro", "the story opens from Help", True, m1.tap_text(serial, ui, r"^Why it is called Declared$", scroll=True))
        check("intro", "it ends in a button", True, wait_text(serial, r"^Continue$", 30))
        story = nodes_text(serial)
        check("intro", "it says why the app is called Declared: apps declare, nobody reads it, Declared does", True,
              "Declared reads the declarations." in story and "Do you read this part? Never." in story)
        check("intro", "Continue is tapped and Help is back", True, m1.tap_text(serial, ui, r"^Continue$") and wait_text(serial, r"^How to use Declared$", 10))

        # ---------------------------------------------------------------- back to the full view
        check("simple", "Simple view is switched off again", True, m1.tap_text(serial, ui, r"^Simple view$", scroll=True))
        time.sleep(2)
        check("simple", "the five places are back", True, all(w in nodes_text(serial) for w in ("Paths", "History")))
    finally:
        sh(serial, "settings delete global http_proxy")
        runner.shell(serial, f"cmd jobscheduler cancel {pkg} {JOB_ID}", check=False)

    passed = sum(checks)
    print(f"{passed}/{len(checks)} checks passed")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
