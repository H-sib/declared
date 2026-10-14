#!/usr/bin/env python3
"""Runs every check that needs the emulator, one after the other, and prints one line per step.

The accessibility pass first (Gradle, instrumented), then the conformance scripts m2 to m9. Each step's whole output goes to its
own log file in --logs; the console gets only a verdict line, so a long run costs a person (or an assistant) a few lines to read.
Run from a PowerShell where `. lab\\tools\\env.ps1` has been dot-sourced and the emulator is running. Standard library only.
"""
import argparse
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PRODUCT = os.path.dirname(HERE)
PY = [sys.executable, "-u", "-I"]

STEPS = [
    ("a11y", ["cmd", "/c", r".\gradlew.bat", ":app:connectedDebugAndroidTest", "--console=plain", "-q"], PRODUCT),
] + [(f"m{n}", PY + [os.path.join(HERE, f"conformance_m{n}.py"), "--serial", "{serial}"], HERE) for n in range(2, 10)]


def verdict(returncode, text):
    """One short line: the script's own summary when it printed one, else the last non-empty line."""
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    summary = next((l for l in reversed(lines) if re.match(r"^m\d: \d+ checks", l) or re.search(r"\b\d+ (checks|passed|failed)\b", l)), None)
    return summary or (lines[-1][:160] if lines else "(no output)")


def run(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--logs", default=os.path.join(PRODUCT, "conformance", "suite_logs"))
    ap.add_argument("--only", default="", help="comma-separated step names, e.g. m7,m8")
    ap.add_argument("--timeout", type=int, default=3600, help="seconds allowed per step")
    args = ap.parse_args(argv)
    os.makedirs(args.logs, exist_ok=True)
    wanted = {s for s in args.only.split(",") if s}
    failed = 0
    for name, cmd, cwd in STEPS:
        if wanted and name not in wanted:
            continue
        cmd = [c.format(serial=args.serial) for c in cmd]
        started = time.time()
        try:
            p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=args.timeout)
            code, out = p.returncode, p.stdout + p.stderr
        except subprocess.TimeoutExpired as ex:
            code, out = 124, (ex.stdout.decode("utf-8", "replace") if isinstance(ex.stdout, bytes) else (ex.stdout or "")) + "\nTIMED OUT"
        with open(os.path.join(args.logs, f"{name}.log"), "w", encoding="utf-8") as f:
            f.write(out)
        failed += code != 0
        print(f"{name}: {'PASS' if code == 0 else 'FAIL'} in {int(time.time() - started)}s: {verdict(code, out)}", flush=True)
    print(f"suite: {failed} step(s) failed", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(run())
