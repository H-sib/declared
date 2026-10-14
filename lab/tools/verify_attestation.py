#!/usr/bin/env python3
"""Verifies a key attestation chain captured by the probe (signal K3), offline.

Inputs: a raw probe result file (results/raw/*.json), Google's published attestation roots and revocation list
(results/reference/), and the JDK. Output: a plain-language verdict that states what was and was not established.

What this can establish: the chain is signed link by link, it ends at a Google attestation root, no certificate in it
is on Google's revocation list, and the attested fields (boot state, patch level) come from that chain.
What it cannot establish: that the device is honest. A compromised device with leaked attestation keys can still
produce a valid-looking chain until those keys are revoked, and this check runs on the same machine as the data,
not on a separate trusted server (see THREAT_MODEL.md, trust assumption 4).
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
LAB = os.path.dirname(HERE)
REF = os.path.join(LAB, "results", "reference")


def der_to_pem(b64_der):
    body = base64.b64encode(base64.b64decode(b64_der)).decode()
    lines = [body[i:i + 64] for i in range(0, len(body), 64)]
    return "-----BEGIN CERTIFICATE-----\n" + "\n".join(lines) + "\n-----END CERTIFICATE-----\n"


def chain_from_raw(doc):
    """Return the list of base64 DER certificates (leaf first) from a probe document, or None."""
    probe = doc.get("probe", doc)
    for r in probe.get("readings", []):
        if r.get("id") == "K1" and r.get("code") == "OK":
            return (r.get("value") or {}).get("chainBase64")
    return None


def attestation_from_raw(doc):
    probe = doc.get("probe", doc)
    for r in probe.get("readings", []):
        if r.get("id") == "K1" and r.get("code") == "OK":
            return (r.get("value") or {}).get("attestation")
    return None


def is_revoked(status_doc, serial_dec, serial_hex):
    """Google's list is keyed by serial number; check the decimal and hexadecimal spellings."""
    entries = (status_doc or {}).get("entries", {})
    for key in (str(serial_dec), serial_hex, serial_hex.lstrip("0")):
        if key in entries:
            return entries[key]
    return None


def parse_java_output(text):
    """Parse ChainVerify output into (certs, root) where certs is a list of dicts."""
    certs, root = [], None
    for line in text.splitlines():
        parts = line.split("\t")
        if parts[0] == "CERT" and len(parts) >= 10:
            certs.append({"index": int(parts[1]), "subject": parts[2], "issuer": parts[3], "serial_dec": parts[4],
                          "serial_hex": parts[5], "not_before_ms": int(parts[6]), "not_after_ms": int(parts[7]),
                          "signature": parts[8], "validity": parts[9]})
        elif parts[0] == "ROOT":
            root = {"trusted": parts[1] == "TRUSTED", "detail": parts[2] if len(parts) > 2 else ""}
    return certs, root


LEGACY_ROOT_SERIAL = "f92009e853b6b045"


def is_legacy_chain(certs):
    """True if the chain hangs off Google's legacy RSA root (factory-provisioned keys on devices launched before 2021).

    The X.500 serialNumber attribute is printed by the JDK as OID 2.5.4.5 with a hex-encoded string, so the check
    looks for the ASCII bytes of the root's serial in the last certificate's subject and issuer, in either form.
    """
    if not certs:
        return False
    top = certs[-1]
    text = (top["subject"] + " " + top["issuer"]).lower()
    return LEGACY_ROOT_SERIAL in text or LEGACY_ROOT_SERIAL.encode().hex() in text


def verdict(certs, root, revoked, attestation, legacy=False):
    """Turn the facts into (status, [lines]). Pure function, unit tested.

    legacy: the chain is rooted at Google's legacy root. Google's guidance for those devices is that the factory
    certificates are expired but remain trustworthy unless revoked, so expiry is reported but not a failure there.
    Chains from remote key provisioning (new roots) must still be within their validity period.
    """
    lines = []
    ok = True
    if not certs:
        return "NO CHAIN", ["No certificates could be read."]
    bad_sigs = [c for c in certs if c["signature"] not in ("ok", "n/a")]
    lines.append(f"chain length: {len(certs)}; links signed correctly: {not bad_sigs}")
    if bad_sigs:
        ok = False
        lines += [f"  broken link at certificate {c['index']}: {c['signature']}" for c in bad_sigs]
    lines.append("ends at a Google attestation root: " + ("yes (" + root["detail"] + ")" if root and root["trusted"] else "NO"))
    if not (root and root["trusted"]):
        ok = False
    not_valid = [c for c in certs if c["validity"] != "valid"]
    if not not_valid:
        lines.append("every certificate currently within its validity period: yes")
    elif legacy:
        lines.append(f"{len(not_valid)} certificate(s) are past their validity date, which is expected for a legacy "
                     "factory-provisioned chain (device launched before 2021): trustworthy unless revoked, per Google's guidance")
    else:
        lines.append("every certificate currently within its validity period: NO")
        lines += [f"  certificate {c['index']}: {c['validity']}" for c in not_valid]
        ok = False
    lines.append("any certificate on Google's revocation list: " + ("YES" if revoked else "no"))
    for index, entry in revoked:
        lines.append(f"  certificate {index} is {entry.get('status')} ({entry.get('reason')})")
        ok = False
    if attestation:
        lines.append("attested facts (trustworthy only if the lines above are all clean): "
                     f"boot state={attestation.get('verifiedBootState')} (0=Verified), locked={attestation.get('deviceLocked')}, "
                     f"os patch={attestation.get('osPatchLevel')}, vendor patch={attestation.get('vendorPatchLevel')}, "
                     f"security level={attestation.get('attestationSecurityLevel')} (0=software, 1=TEE, 2=StrongBox)")
    return ("CHAIN VALID AND TRUSTED" if ok else "CHAIN NOT TRUSTED"), lines


def find_java():
    home = os.environ.get("JAVA_HOME")
    exe = os.path.join(home, "bin", "java.exe") if home else "java"
    return exe


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("raw", help="raw probe result JSON (results/raw/*.json)")
    p.add_argument("--roots", default=os.path.join(REF, "google-attestation-roots.json"))
    p.add_argument("--status", default=os.path.join(REF, "google-attestation-status.json"))
    args = p.parse_args(argv)

    with open(args.raw, encoding="utf-8") as f:
        doc = json.load(f)
    chain = chain_from_raw(doc)
    if not chain:
        print("No attestation chain in that file.")
        return 2
    with open(args.roots, encoding="utf-8") as f:
        roots = json.load(f)
    with open(args.status, encoding="utf-8") as f:
        status = json.load(f)

    with tempfile.TemporaryDirectory() as tmp:
        chain_pem, roots_pem = os.path.join(tmp, "chain.pem"), os.path.join(tmp, "roots.pem")
        with open(chain_pem, "w", newline="\n") as f:
            f.write("".join(der_to_pem(c) for c in chain))
        with open(roots_pem, "w", newline="\n") as f:
            f.write("".join(r if r.endswith("\n") else r + "\n" for r in roots))
        out = subprocess.run([find_java(), os.path.join(HERE, "ChainVerify.java"), chain_pem, roots_pem],
                             capture_output=True, text=True, timeout=120)
    if out.returncode != 0:
        print("ChainVerify failed:", out.stderr.strip())
        return 3

    certs, root = parse_java_output(out.stdout)
    revoked = []
    for c in certs:
        entry = is_revoked(status, c["serial_dec"], c["serial_hex"])
        if entry:
            revoked.append((c["index"], entry))
    result, lines = verdict(certs, root, revoked, attestation_from_raw(doc), legacy=is_legacy_chain(certs))
    print(result)
    for line in lines:
        print(" ", line)
    print(f"  (revocation list has {len(status.get('entries', {}))} entries; checked at {time.strftime('%Y-%m-%d %H:%M')})")
    return 0 if result == "CHAIN VALID AND TRUSTED" else 1


if __name__ == "__main__":
    sys.exit(main())
