"""Tiny UI-automation helpers for an emulator, built on 'uiautomator dump' and 'input tap'. Standard library only.

Used by the tripwire experiment to drive the Settings app (enroll a fingerprint, set a PIN). The parsing and
matching functions are pure and unit tested; the functions that talk to adb are thin wrappers.
"""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "adb")


def parse_nodes(xml_text):
    """Return a list of dicts {text, desc, rid, bounds, clickable, enabled} for every element in a uiautomator dump."""
    start = xml_text.find("<?xml")
    if start < 0:
        start = xml_text.find("<hierarchy")
    if start < 0:
        return []
    end = xml_text.rfind("</hierarchy>")
    body = xml_text[start:end + len("</hierarchy>")] if end >= 0 else xml_text[start:]
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        return []
    nodes = []
    for el in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", el.get("bounds", ""))
        bounds = tuple(int(g) for g in m.groups()) if m else None
        nodes.append({
            "text": el.get("text", ""),
            "desc": el.get("content-desc", ""),
            "rid": el.get("resource-id", ""),
            "bounds": bounds,
            "clickable": el.get("clickable") == "true",
            "enabled": el.get("enabled") != "false",
        })
    return nodes


def center(bounds):
    left, top, right, bottom = bounds
    return (left + right) // 2, (top + bottom) // 2


def find(nodes, pattern, fields=("text", "desc"), clickable_only=False):
    """First node whose text or description matches the regex (case-insensitive), or None."""
    rx = re.compile(pattern, re.IGNORECASE)
    for n in nodes:
        if not n["bounds"] or not n["enabled"]:
            continue
        if clickable_only and not n["clickable"]:
            continue
        if any(rx.search(n[f] or "") for f in fields):
            return n
    return None


def any_text(nodes, pattern):
    rx = re.compile(pattern, re.IGNORECASE)
    return any(rx.search(n["text"] or "") or rx.search(n["desc"] or "") for n in nodes)


# ---- adb wrappers ----

def _run(serial, *args, binary=False, timeout=60):
    cmd = [ADB] + (["-s", serial] if serial else []) + list(args)
    p = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return p.stdout if binary else p.stdout.decode("utf-8", errors="replace")


def dump(serial):
    # Dumping to /dev/tty yields only the trailer line over adb, so write a file on the device and read it back.
    text = _run(serial, "shell", "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml")
    return parse_nodes(text)


def tap_xy(serial, x, y):
    _run(serial, "shell", "input", "tap", str(x), str(y))


def tap_text(serial, pattern, nodes=None, clickable_only=False):
    nodes = nodes if nodes is not None else dump(serial)
    n = find(nodes, pattern, clickable_only=clickable_only)
    if not n:
        return False
    tap_xy(serial, *center(n["bounds"]))
    return True


def screenshot(serial, path):
    data = _run(serial, "exec-out", "screencap", "-p", binary=True, timeout=60)
    with open(path, "wb") as f:
        f.write(data)
    return path


def wait_for(serial, pattern, timeout=20, interval=1.0):
    """Poll the screen until some element matches the pattern. Returns the nodes, or None on timeout."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = dump(serial)
        if any_text(nodes, pattern):
            return nodes
        time.sleep(interval)
    return None
