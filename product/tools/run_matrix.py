#!/usr/bin/env python3
"""Runs the whole emulator suite on other Android versions, one version at a time, keeping disk use to one system image.

For each API level: download the image and start an emulator (lab/tools/emu.ps1), run `run_suite.py`, then stop the emulator and
delete its image again (kept only when something failed, so the failure can be looked at without downloading 4 GB again).
The console gets a verdict line per step and a summary file collects them; the failing lines of every step go to one file per
version, so a person reads a handful of lines instead of the logs. Run from a PowerShell where `. lab\\tools\\env.ps1` has
been dot-sourced. Standard library only.
"""
import argparse
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PRODUCT = os.path.dirname(HERE)
ROOT = os.path.dirname(PRODUCT)
EMU = os.path.join(ROOT, "lab", "tools", "emu.ps1")
READY = os.path.join(ROOT, "lab", "results", "raw", "emu-{api}.ready")
OUT = os.path.join(PRODUCT, "conformance")

IMAGES = {
    29: "system-images;android-29;google_apis;x86_64",
    31: "system-images;android-31;google_apis;x86_64",
    33: "system-images;android-33;google_apis;x86_64",
    34: "system-images;android-34;google_apis;x86_64",
    35: "system-images;android-35;google_apis;x86_64",
    36: "system-images;android-36;google_apis;x86_64",
    37: "system-images;android-37.0;google_apis;x86_64",
}
FAIL_LINE = re.compile(r"(^|\s)(FAIL|MISMATCH|ERROR)\b|\bfailed\b|Traceback", re.IGNORECASE)


def failing_lines(log_text, limit=14):
    """The lines of a step's log that say something failed, cut short: what a person needs to see first."""
    out = []
    for line in log_text.splitlines():
        if FAIL_LINE.search(line) and not re.search(r"\b0 failed\b", line):
            out.append(line.strip()[:230])
            if len(out) >= limit:
                break
    return out


def powershell(*args, **kw):
    return subprocess.Popen(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", EMU, *args], **kw)


def warm_up(serial="emulator-5554"):
    """Install the app and compile it ahead of time, then let the fresh image settle.

    A fresh image keeps compiling and updating things for minutes after boot, and the first start of a just-installed debug app is slow:
    UI scripts that begin at once meet a busy phone and an 'isn't responding' dialog, and fail on the first screen (seen on Android 13 and 16)."""
    import glob
    adb = os.path.join(os.environ["ANDROID_HOME"], "platform-tools", "adb.exe")
    apks = glob.glob(os.path.join(PRODUCT, "app", "build", "outputs", "apk", "debug", "*.apk"))
    if not apks:
        return
    run = lambda *a: subprocess.run([adb, "-s", serial, *a], capture_output=True, text=True, timeout=420).stdout  # noqa: E731
    run("shell", "settings", "put", "global", "verifier_verify_adb_installs", "0")
    run("shell", "settings", "put", "global", "package_verifier_enable", "0")
    run("install", "-r", apks[0])
    for line in run("shell", "pm", "list", "packages", "-3").splitlines():
        if line.startswith("package:"):
            run("shell", "cmd", "package", "compile", "-m", "speed", "-f", line.split(":", 1)[1].strip())
    time.sleep(60)


def run_one(api, boot_timeout, keep_failed, steps=""):
    image = IMAGES[api]
    os.makedirs(OUT, exist_ok=True)
    summary = os.path.join(OUT, "matrix_summary.txt")
    ready = READY.format(api=api)
    if os.path.exists(ready):
        os.remove(ready)
    started = time.time()
    log = open(os.path.join(OUT, f"emu_api{api}.log"), "w", encoding="utf-8")
    emu = None
    for attempt in range(3):
        emu = powershell("-Action", "start", "-Api", str(api), "-Image", image, stdout=log, stderr=subprocess.STDOUT)
        deadline = time.time() + boot_timeout
        while time.time() < deadline and not os.path.exists(ready) and emu.poll() is None:
            time.sleep(10)
        if os.path.exists(ready):
            break
        # The package list could not be fetched or the emulator died at once (the network was down, the PC was asleep): clean up, wait, try again.
        try:
            emu.kill()
        except OSError:
            pass
        powershell("-Action", "stop", "-Api", str(api), "-Image", image, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).wait()
        time.sleep(90)
    lines = []
    failed = False
    if not os.path.exists(ready):
        lines.append(f"api {api}: the emulator did not come up within {int(time.time() - started)}s (see {log.name})")
        failed = True
    else:
        try:
            warm_up()
        except (subprocess.TimeoutExpired, OSError, KeyError) as ex:
            print(f"api {api}: warm-up skipped ({type(ex).__name__})", flush=True)
        logs = os.path.join(OUT, "suite_logs", f"api{api}")
        p = subprocess.run([sys.executable, "-u", "-I", os.path.join(HERE, "run_suite.py"), "--serial", "emulator-5554", "--logs", logs] + (["--only", steps] if steps else []),
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
        verdicts = [l.lstrip("﻿") for l in p.stdout.splitlines() if l.strip()]
        lines += [f"api {api}: {l}" for l in verdicts]
        failed = p.returncode != 0
        details = []
        for name in sorted(os.listdir(logs)) if os.path.isdir(logs) else []:
            with open(os.path.join(logs, name), encoding="utf-8", errors="replace") as f:
                found = failing_lines(f.read())
            if found:
                details.append(f"--- api {api} {name}")
                details += found
        with open(os.path.join(OUT, f"matrix_failures_api{api}.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(details) + "\n")
    keep = failed and keep_failed
    stop = ["-Action", "stop", "-Api", str(api), "-Image", image] + (["-KeepImage"] if keep else [])
    powershell(*stop, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).wait()
    try:
        emu.wait(timeout=60)
    except subprocess.TimeoutExpired:
        emu.kill()
    log.close()
    lines.append(f"api {api}: finished in {int((time.time() - started) / 60)} min, image {'kept for a look' if keep else 'deleted'}")
    with open(summary, "a", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines), flush=True)
    return 1 if failed else 0


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="29,31,33,35,36,37", help="comma-separated API levels")
    ap.add_argument("--boot-timeout", type=int, default=2400, help="seconds for the download and the boot")
    ap.add_argument("--steps", default="", help="only these steps of the suite, e.g. m5,m6 (m6 needs m5 before it for the baseline)")
    ap.add_argument("--keep-failed", action="store_true", help="keep the system image of a version where something failed (about 4 GB each)")
    args = ap.parse_args(argv)
    bad = 0
    for api in [int(a) for a in args.api.split(",") if a]:
        bad += run_one(api, args.boot_timeout, args.keep_failed, args.steps)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
