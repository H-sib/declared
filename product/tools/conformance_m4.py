#!/usr/bin/env python3
"""Milestone 4 conformance: the device-integrity panel against independent verifiers. Standard library only.

The debug build dumps the attestation chain it asked the phone for and what its own verifier says about that chain in four
situations: as it is, with one signature bit flipped, anchored at the chain's own top certificate, and the flipped chain
against that same anchor. This script judges the same chains again with code that shares nothing with the product:

  * the JDK's X.509 verifier through the lab's ChainVerify.java (signatures link by link, validity, root match)
  * the lab's verdict, legacy-root and revocation logic (lab/tools/verify_attestation.py, itself unit tested)
  * a separate Python DER reader for the attestation record, fed by the JDK's own extension bytes (AttestExt.java)
  * the bundled reference files checked against their manifest with hashlib

On the emulator the keys are software-backed and rooted in Google's test root, so the real chain must NOT be trusted as a
hardware chain, and the product must say so instead of passing it. The trusted path is exercised by anchoring the chain at
its own top, and the rejection path by the flipped bit. Run from a PowerShell where `. lab\\tools\\env.ps1` has been dot-sourced.
Refuses to touch anything but an emulator.
"""
import argparse
import base64
import csv
import datetime as dt
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import conformance_m1 as m1  # noqa: E402
import runner  # noqa: E402

sys.path.insert(0, os.path.join(m1.ROOT, "lab", "tools"))
import verify_attestation as va  # noqa: E402  (the lab's verifier: independent of the product)

PRODUCT = m1.PRODUCT
ASSETS = os.path.join(PRODUCT, "sensors-android", "src", "main", "assets", "attestation")
RESULTS_CSV = os.path.join(PRODUCT, "conformance", "m4_results.csv")
SHOTS_DIR = os.path.join(PRODUCT, "conformance", "screenshots")
RUN_ID = dt.datetime.now().isoformat(timespec="seconds")
VARIANTS = ("real", "tampered", "selfAnchored", "selfAnchoredTampered")


# ---------------------------------------------------------------- pure helpers (unit tested)

def der_read(data, off=0):
    """One DER element at `off`: (tag_class, constructed, tag, content bytes, next offset). Raises ValueError on bad input."""
    if off >= len(data):
        raise ValueError("past the end")
    first = data[off]
    off += 1
    cls, constructed, tag = first >> 6, bool(first & 0x20), first & 0x1F
    if tag == 0x1F:
        tag = 0
        while True:
            if off >= len(data):
                raise ValueError("tag runs past the end")
            b = data[off]
            off += 1
            tag = (tag << 7) | (b & 0x7F)
            if not b & 0x80:
                break
    if off >= len(data):
        raise ValueError("length missing")
    n = data[off]
    off += 1
    if n & 0x80:
        k = n & 0x7F
        if not 1 <= k <= 4 or off + k > len(data):
            raise ValueError("bad length")
        n = int.from_bytes(data[off:off + k], "big")
        off += k
    if off + n > len(data):
        raise ValueError("length out of range")
    return cls, constructed, tag, data[off:off + n], off + n


def der_children(content):
    out, off = [], 0
    while off < len(content):
        cls, constructed, tag, body, off = der_read(content, off)
        out.append((cls, constructed, tag, body))
    return out


def der_int(content):
    return int.from_bytes(content, "big", signed=True) if content else 0


def parse_key_description(extension_value_hex):
    """The attestation record from the hex of getExtensionValue (an OCTET STRING wrapping the KeyDescription)."""
    raw = bytes.fromhex(extension_value_hex)
    cls, _, tag, body, end = der_read(raw)
    if (cls, tag) != (0, 4) or end != len(raw):
        raise ValueError("expected exactly one OCTET STRING")
    cls, _, tag, seq, end = der_read(body)
    if (cls, tag) != (0, 16) or end != len(body):
        raise ValueError("expected exactly one SEQUENCE")
    f = der_children(seq)
    if len(f) < 8:
        raise ValueError("KeyDescription too short")
    allowed = der_children(f[7][3]) + der_children(f[6][3])  # hardware-enforced first, as the platform defines precedence

    def explicit(tag_no):
        for cls_, _, t, b in allowed:
            if cls_ == 2 and t == tag_no:
                kids = der_children(b)
                return kids[0] if len(kids) == 1 else None
        return None

    def explicit_int(tag_no):
        e = explicit(tag_no)
        return der_int(e[3]) if e else None

    rot = explicit(704)
    rot_kids = der_children(rot[3]) if rot else None
    return {
        "attestationVersion": der_int(f[0][3]), "securityLevel": der_int(f[1][3]), "keyMintSecurityLevel": der_int(f[3][3]),
        "challengeHex": f[4][3].hex(),
        "bootState": der_int(rot_kids[2][3]) if rot_kids and len(rot_kids) > 2 else None,
        "locked": (bool(rot_kids[1][3]) and rot_kids[1][3][0] != 0) if rot_kids and len(rot_kids) > 1 else None,
        "osPatchLevel": explicit_int(706), "vendorPatchLevel": explicit_int(718),
    }


def read_json(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def read_bytes(path):
    with open(path, "rb") as fh:
        return fh.read()


def pem_of(b64):
    return va.der_to_pem(b64)


def variant_chain(integrity, variant):
    chain = list(integrity["chainBase64"])
    if variant in ("tampered", "selfAnchoredTampered"):
        chain[0] = integrity["tamperedLeafBase64"]
    return chain


def variant_roots_pem(integrity, variant, google_roots):
    if variant in ("selfAnchored", "selfAnchoredTampered"):
        return [pem_of(integrity["chainBase64"][-1])]
    return [r if r.endswith(chr(10)) else r + chr(10) for r in google_roots]


def reference_checks(asset_dir, product_integrity):
    """The bundled files against their manifest (hashlib), and the product's counts against the files."""
    manifest = {}
    with open(os.path.join(asset_dir, "MANIFEST.txt"), encoding="utf-8") as fh:
        manifest_lines = fh.read().splitlines()
    for line in manifest_lines:
        if line and not line.startswith("#"):
            name, sha, fetched, _ = line.split(chr(9))
            manifest[name] = (sha, fetched)
    ok_hashes = all(hashlib.sha256(read_bytes(os.path.join(asset_dir, n))).hexdigest() == manifest[n][0] for n in manifest)
    roots = read_json(os.path.join(asset_dir, "google-attestation-roots.json"))
    status = read_json(os.path.join(asset_dir, "google-attestation-status.json"))
    return ok_hashes, len(roots), len(status["entries"]), min(v[1] for v in manifest.values())


class Check(m1.Check):
    def row(self, phase, api):
        return [RUN_ID, api, phase, self.name, json.dumps(self.expected), json.dumps(self.actual), "PASS" if self.ok else "FAIL"]


def compare_variant(name, product, lab_certs, lab_root, lab_verdict, lab_revoked_indexes):
    """The product's verdict on one chain against the lab verifier's. Pure."""
    checks = []
    checks.append(Check(f"{name}: chain length", len(lab_certs), len(product["links"])))
    checks.append(Check(f"{name}: each link's signature (JDK) = the product's", [c["signature"] == "ok" if c["signature"] != "n/a" else None for c in lab_certs],
                        [l["signatureOk"] for l in product["links"]]))
    checks.append(Check(f"{name}: each certificate's validity (JDK) = the product's", [c["validity"] == "valid" for c in lab_certs], [l["validNow"] for l in product["links"]]))
    checks.append(Check(f"{name}: serial numbers (JDK) = the product's", [c["serial_dec"] for c in lab_certs], [l["serial"] for l in product["links"]]))
    checks.append(Check(f"{name}: ends at a trusted root (JDK) = the product's", bool(lab_root and lab_root["trusted"]), product["endsAtTrustedRoot"]))
    checks.append(Check(f"{name}: legacy root rule = the lab's", va.is_legacy_chain(lab_certs), product["legacyRoot"]))
    checks.append(Check(f"{name}: revoked certificates = the lab's", lab_revoked_indexes, product["revoked"]))
    checks.append(Check(f"{name}: overall verdict = the lab's", lab_verdict == "CHAIN VALID AND TRUSTED", product["trusted"]))
    return checks


# ---------------------------------------------------------------- device plumbing

def run_java(source, *args):
    exe = os.path.join(os.environ["JAVA_HOME"], "bin", "java.exe") if os.environ.get("JAVA_HOME") else "java"
    p = subprocess.run([exe, source, *args], capture_output=True, text=True, timeout=120)
    if p.returncode != 0:
        raise RuntimeError(f"java failed: {p.stderr.strip()[:300]}")
    return p.stdout


def judge_variant(integrity, variant, google_roots, status_json):
    chain = variant_chain(integrity, variant)
    with tempfile.TemporaryDirectory() as tmp:
        chain_pem, roots_pem = os.path.join(tmp, "chain.pem"), os.path.join(tmp, "roots.pem")
        with open(chain_pem, "w", newline=chr(10)) as fh:
            fh.write("".join(pem_of(c) for c in chain))
        with open(roots_pem, "w", newline=chr(10)) as fh:
            fh.write("".join(variant_roots_pem(integrity, variant, google_roots)))
        out = run_java(os.path.join(m1.ROOT, "lab", "tools", "ChainVerify.java"), chain_pem, roots_pem)
        ext = run_java(os.path.join(HERE, "AttestExt.java"), chain_pem)
    certs, root = va.parse_java_output(out)
    revoked = []
    for c in certs:
        if va.is_revoked(status_json, c["serial_dec"], c["serial_hex"]):
            revoked.append(c["index"])
    verdict, _ = va.verdict(certs, root, [(i, {}) for i in revoked], None, legacy=va.is_legacy_chain(certs))
    record = None
    parts = ext.strip().split(chr(9))
    if parts[0] == "EXT":
        record = parse_key_description(parts[2])
    return certs, root, verdict, revoked, record


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

    dump = m1.launch_and_pull(serial, app_id, 90)
    integrity = (dump.get("raw") or {}).get("integrity")
    if not isinstance(integrity, dict) or "real" not in integrity:
        print("The product gave no attestation chain:", integrity)
        return 1

    google_roots = read_json(os.path.join(ASSETS, "google-attestation-roots.json"))
    status_json = read_json(os.path.join(ASSETS, "google-attestation-status.json"))

    lab_valid = {}
    for variant in VARIANTS:
        certs, root, verdict, revoked, rec = judge_variant(integrity, variant, google_roots, status_json)
        lab_valid[variant] = all(c["validity"] == "valid" for c in certs)
        record(f"chain-{variant}", compare_variant(variant, integrity[variant], certs, root, verdict, revoked))
        if variant == "real":
            real_record = rec
            real_certs = certs

    # A chain that ends at the anchor it was given is trusted only while every certificate in it is within its dates. The emulator images
    # before Android 12 carry fixed software attestation certificates that have expired by now (measured), and the product must refuse those.
    expected_trust = {"real": False, "tampered": False, "selfAnchored": lab_valid["selfAnchored"], "selfAnchoredTampered": False}
    record("outcomes", [Check(f"{v}: the outcome the situation calls for", expected_trust[v], integrity[v]["trusted"]) for v in VARIANTS]
           + [Check("tampered: the broken link is the leaf's (index 0)", [0], [l["index"] for l in integrity["tampered"]["links"] if l["signatureOk"] is False]),
              Check("real emulator chain is not mistaken for a hardware chain: it does not end at a Google root", False, integrity["real"]["endsAtTrustedRoot"])])

    rec = integrity["real"]["attestation"]
    record("attestation-record", [
        Check("record readable by both parsers", True, real_record is not None and rec is not None),
        Check("security level = independent parse", real_record["securityLevel"], rec["securityLevel"]),
        Check("KeyMint security level = independent parse", real_record["keyMintSecurityLevel"], rec["keyMintSecurityLevel"]),
        Check("attestation version = independent parse", real_record["attestationVersion"], rec["attestationVersion"]),
        Check("challenge in the record = independent parse", real_record["challengeHex"], rec["challengeHex"]),
        Check("the record answers the challenge the app made (nothing replayed)", integrity["challengeHex"], rec["challengeHex"]),
        Check("boot state = independent parse", real_record["bootState"], rec["bootState"]),
        Check("bootloader lock flag = independent parse", real_record["locked"], rec["locked"]),
        Check("OS patch level = independent parse", real_record["osPatchLevel"], rec["osPatchLevel"]),
        Check("vendor patch level = independent parse", real_record["vendorPatchLevel"], rec["vendorPatchLevel"]),
    ])

    ok_hashes, roots_n, status_n, fetched = reference_checks(ASSETS, integrity)
    record("reference-data", [
        Check("bundled files match their manifest checksums (hashlib)", True, ok_hashes),
        Check("number of roots the product loaded = the file's", roots_n, integrity["referenceRoots"]),
        Check("number of status entries the product loaded = the file's", status_n, integrity["referenceRevocations"]),
        Check("reference date shown = manifest date", fetched, integrity["referenceRetrievedOn"]),
    ])

    facts = {f["kind"]: f for f in dump.get("facts", [])}
    findings = {f["id"] for f in dump.get("findings", [])}
    software = rec["securityLevel"] == 0
    panel = [Check("all four integrity rows are present", True, all(k in facts for k in ("KEY_ATTESTATION", "BOOT_STATE", "BOOTLOADER", "ATTESTED_PATCH")))]
    if software:
        panel += [
            Check("software keys are reported as 'not possible', not as a pass", True, "not possible" in facts["KEY_ATTESTATION"]["summary"]),
            Check("software keys make the NOTE finding", True, "condition:software-keys" in findings),
            Check("the record's own flags are NOT believed: no bootloader or boot-state finding from a software record", False,
                  bool({"condition:bootloader-unlocked", "condition:boot-state", "condition:attestation-failed"} & findings)),
            Check("boot state, bootloader and patch rows say 'not established'", True,
                  all("not established" in facts[k]["summary"] for k in ("BOOT_STATE", "BOOTLOADER", "ATTESTED_PATCH"))),
        ]
    record("panel", panel)

    if not args.no_ui:
        texts = m1.check_texts(serial, app_id, ui)
        os.makedirs(SHOTS_DIR, exist_ok=True)
        ui.screenshot(serial, os.path.join(SHOTS_DIR, f"m4-inventory-api{api}.png"))
        record("on-screen", [Check(f"screen shows: {s}", True, s in texts) for s in (
            "Integrity", "Hardware key check:", "Boot state:", "Bootloader:", "Security update the hardware attests:",
            "This phone's keys live in software, so no hardware vouches for them" if software else "Hardware key check: passed",
        )])

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
