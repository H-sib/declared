"""Unit tests for the pure helpers of conformance_m4.py: the independent DER reader and the comparison against the lab verifier."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import conformance_m4 as c  # noqa: E402


def length(n):
    if n < 128:
        return bytes([n])
    if n < 256:
        return bytes([0x81, n])
    return bytes([0x82, n >> 8, n & 0xFF])


def tlv(tag, content):
    return bytes([tag]) + length(len(content)) + content


def int_tlv(v):
    n = max(1, (v.bit_length() + 8) // 8)
    return tlv(0x02, v.to_bytes(n, "big", signed=True))


def enum_tlv(v):
    return tlv(0x0A, bytes([v]))


def explicit(tag_no, content):
    head = bytes([0xA0 | tag_no]) if tag_no < 31 else bytes([0xBF]) + b128(tag_no)
    return head + length(len(content)) + content


def b128(n):
    parts = [n & 0x7F]
    n >>= 7
    while n:
        parts.append(n & 0x7F)
        n >>= 7
    parts.reverse()
    return bytes((p | 0x80) if i < len(parts) - 1 else p for i, p in enumerate(parts))


def key_description(challenge=b"\x07" * 16, level=1, boot=0, locked=True, with_rot=True):
    rot = tlv(0x30, tlv(0x04, b"\x01" * 32) + tlv(0x01, b"\xff" if locked else b"\x00") + enum_tlv(boot) + tlv(0x04, b"\x02" * 32))
    hw = tlv(0x30, (explicit(704, rot) if with_rot else b"") + explicit(705, int_tlv(140000)) + explicit(706, int_tlv(202609)) + explicit(718, int_tlv(20260905)))
    body = tlv(0x30, int_tlv(300) + enum_tlv(level) + int_tlv(300) + enum_tlv(level) + tlv(0x04, challenge) + tlv(0x04, b"") + tlv(0x30, b"") + hw)
    return tlv(0x04, body).hex()


class Der(unittest.TestCase):
    def test_reads_a_key_description(self):
        r = c.parse_key_description(key_description(challenge=bytes(range(16))))
        self.assertEqual(300, r["attestationVersion"])
        self.assertEqual(1, r["securityLevel"])
        self.assertEqual(bytes(range(16)).hex(), r["challengeHex"])
        self.assertEqual(0, r["bootState"])
        self.assertEqual(True, r["locked"])
        self.assertEqual(202609, r["osPatchLevel"])
        self.assertEqual(20260905, r["vendorPatchLevel"])

    def test_software_record_with_unlocked_flags(self):
        r = c.parse_key_description(key_description(level=0, boot=2, locked=False))
        self.assertEqual((0, 2, False), (r["securityLevel"], r["bootState"], r["locked"]))

    def test_missing_root_of_trust_gives_none(self):
        r = c.parse_key_description(key_description(with_rot=False))
        self.assertIsNone(r["bootState"])
        self.assertIsNone(r["locked"])

    def test_long_form_lengths(self):
        self.assertEqual((b"\x09" * 300).hex(), c.parse_key_description(key_description(challenge=b"\x09" * 300))["challengeHex"])

    def test_malformed_input_is_refused(self):
        good = key_description()
        for bad in (good[:-10], "", "04", "3000", tlv(0x30, b"").hex(), good + "00"):
            with self.assertRaises(ValueError):
                c.parse_key_description(bad)

    def test_der_reader_rejects_overlong_lengths(self):
        for bad in (bytes([0x04, 0x05, 1, 2]), bytes([0x04, 0x85, 1, 2, 3, 4, 5]), bytes([0x1F, 0x81])):
            with self.assertRaises(ValueError):
                c.der_read(bad)


def lab_cert(index, sig="ok", validity="valid", serial="100"):
    return {"index": index, "subject": f"CN=c{index}", "issuer": "x", "serial_dec": serial, "serial_hex": "64", "not_before_ms": 0,
            "not_after_ms": 1, "signature": sig, "validity": validity}


def product(links, ends=False, legacy=False, revoked=(), trusted=False):
    return {"links": links, "endsAtTrustedRoot": ends, "legacyRoot": legacy, "revoked": list(revoked), "trusted": trusted}


class Compare(unittest.TestCase):
    def failures(self, prod, certs, root, verdict, revoked=()):
        return [x.name for x in c.compare_variant("v", prod, certs, root, verdict, list(revoked)) if not x.ok]

    def test_agreement_passes(self):
        certs = [lab_cert(0, "ok", serial="1"), lab_cert(1, "n/a", serial="2")]
        links = [{"index": 0, "serial": "1", "signatureOk": True, "validNow": True}, {"index": 1, "serial": "2", "signatureOk": None, "validNow": True}]
        self.assertEqual([], self.failures(product(links, ends=True, trusted=True), certs, {"trusted": True, "detail": "d"}, "CHAIN VALID AND TRUSTED"))

    def test_a_link_the_jdk_rejects_but_the_product_accepts_is_caught(self):
        certs = [lab_cert(0, "FAIL:SignatureException", serial="1"), lab_cert(1, "n/a", serial="2")]
        links = [{"index": 0, "serial": "1", "signatureOk": True, "validNow": True}, {"index": 1, "serial": "2", "signatureOk": None, "validNow": True}]
        f = self.failures(product(links, ends=True, trusted=True), certs, {"trusted": True, "detail": "d"}, "CHAIN NOT TRUSTED")
        self.assertIn("v: each link's signature (JDK) = the product's", f)
        self.assertIn("v: overall verdict = the lab's", f)

    def test_a_trusted_verdict_the_lab_does_not_share_is_caught(self):
        certs = [lab_cert(0, "ok", serial="1"), lab_cert(1, "n/a", serial="2")]
        links = [{"index": 0, "serial": "1", "signatureOk": True, "validNow": True}, {"index": 1, "serial": "2", "signatureOk": None, "validNow": True}]
        self.assertIn("v: ends at a trusted root (JDK) = the product's", self.failures(product(links, ends=True, trusted=True), certs, {"trusted": False, "detail": ""}, "CHAIN NOT TRUSTED"))

    def test_revocation_and_validity_disagreements_are_caught(self):
        certs = [lab_cert(0, "ok", validity="NOT_VALID_NOW:CertificateExpiredException", serial="1"), lab_cert(1, "n/a", serial="2")]
        links = [{"index": 0, "serial": "1", "signatureOk": True, "validNow": True}, {"index": 1, "serial": "2", "signatureOk": None, "validNow": True}]
        f = self.failures(product(links, ends=True), certs, {"trusted": True, "detail": ""}, "CHAIN NOT TRUSTED", revoked=[1])
        self.assertIn("v: each certificate's validity (JDK) = the product's", f)
        self.assertIn("v: revoked certificates = the lab's", f)


class Variants(unittest.TestCase):
    INTEGRITY = {"chainBase64": ["AAA", "BBB", "CCC"], "tamperedLeafBase64": "TTT"}

    def test_tampered_variants_swap_only_the_leaf(self):
        self.assertEqual(["AAA", "BBB", "CCC"], c.variant_chain(self.INTEGRITY, "real"))
        self.assertEqual(["TTT", "BBB", "CCC"], c.variant_chain(self.INTEGRITY, "tampered"))
        self.assertEqual(["TTT", "BBB", "CCC"], c.variant_chain(self.INTEGRITY, "selfAnchoredTampered"))
        self.assertEqual(["AAA", "BBB", "CCC"], c.variant_chain(self.INTEGRITY, "selfAnchored"))

    def test_self_anchored_variants_trust_only_the_top_certificate(self):
        roots = c.variant_roots_pem({"chainBase64": ["QUFB", "QkJC", "Q0ND"]}, "selfAnchored", ["google"])
        self.assertEqual(1, len(roots))
        self.assertIn("Q0ND", roots[0].replace(chr(10), ""))
        self.assertEqual([chr(103) + "oogle" + chr(10)], c.variant_roots_pem(self.INTEGRITY, "real", ["google"]))


class Reference(unittest.TestCase):
    def test_the_real_bundled_files_pass_their_own_manifest(self):
        ok, roots, entries, fetched = c.reference_checks(c.ASSETS, {})
        self.assertTrue(ok)
        self.assertGreaterEqual(roots, 2)
        self.assertGreater(entries, 1000)
        self.assertEqual("2026-10-08", fetched)


if __name__ == "__main__":
    unittest.main()
