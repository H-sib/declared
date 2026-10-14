"""Unit tests for the pure functions in verify_attestation.py. Run: python tools/test_verify_attestation.py"""
import base64
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import verify_attestation as va  # noqa: E402


def cert(index, sig="ok", validity="valid", serial="123"):
    return {"index": index, "subject": "s", "issuer": "i", "serial_dec": serial, "serial_hex": hex(int(serial))[2:],
            "not_before_ms": 0, "not_after_ms": 1, "signature": sig, "validity": validity}


TRUSTED = {"trusted": True, "detail": "the last certificate in the chain IS trusted root #0"}
UNTRUSTED = {"trusted": False, "detail": "no Google root matches the end of this chain"}


class PemTests(unittest.TestCase):
    def test_der_to_pem_wraps_at_64_and_round_trips(self):
        raw = bytes(range(200))
        pem = va.der_to_pem(base64.b64encode(raw).decode())
        lines = pem.strip().splitlines()
        self.assertEqual(lines[0], "-----BEGIN CERTIFICATE-----")
        self.assertEqual(lines[-1], "-----END CERTIFICATE-----")
        self.assertTrue(all(len(x) <= 64 for x in lines[1:-1]))
        self.assertEqual(base64.b64decode("".join(lines[1:-1])), raw)


class RevocationTests(unittest.TestCase):
    status = {"entries": {"6681152659205225093": {"status": "REVOKED", "reason": "KEY_COMPROMISE"},
                          "c35747a084470c31": {"status": "SUSPENDED", "reason": "SOFTWARE_FLAW"}}}

    def test_decimal_serial_is_found(self):
        self.assertEqual(va.is_revoked(self.status, "6681152659205225093", "5cd18d1f9ae4a085")["status"], "REVOKED")

    def test_hex_serial_is_found(self):
        self.assertEqual(va.is_revoked(self.status, "999", "c35747a084470c31")["status"], "SUSPENDED")

    def test_unknown_serial_is_not_revoked(self):
        self.assertIsNone(va.is_revoked(self.status, "1", "1"))

    def test_missing_list_is_not_revoked(self):
        self.assertIsNone(va.is_revoked(None, "1", "1"))
        self.assertIsNone(va.is_revoked({}, "1", "1"))


class ParseTests(unittest.TestCase):
    def test_parse_java_output(self):
        text = ("CERT\t0\tCN=leaf\tCN=mid\t12\tc\t1000\t2000\tok\tvalid\n"
                "CERT\t1\tCN=mid\tCN=root\t13\td\t1000\t2000\tn/a\tNOT_VALID_NOW:CertificateExpiredException\n"
                "ROOT\tTRUSTED\tthe last certificate in the chain IS trusted root #1\n")
        certs, root = va.parse_java_output(text)
        self.assertEqual([c["index"] for c in certs], [0, 1])
        self.assertEqual(certs[1]["validity"], "NOT_VALID_NOW:CertificateExpiredException")
        self.assertTrue(root["trusted"])

    def test_parse_untrusted_root(self):
        _, root = va.parse_java_output("ROOT\tNONE\tno Google root matches the end of this chain\n")
        self.assertFalse(root["trusted"])


class VerdictTests(unittest.TestCase):
    def test_clean_chain_is_trusted(self):
        status, _ = va.verdict([cert(0), cert(1, sig="n/a")], TRUSTED, [], {"verifiedBootState": 0})
        self.assertEqual(status, "CHAIN VALID AND TRUSTED")

    def test_untrusted_root_fails(self):
        status, _ = va.verdict([cert(0), cert(1, sig="n/a")], UNTRUSTED, [], None)
        self.assertEqual(status, "CHAIN NOT TRUSTED")

    def test_broken_signature_fails(self):
        status, lines = va.verdict([cert(0, sig="FAIL:SignatureException"), cert(1, sig="n/a")], TRUSTED, [], None)
        self.assertEqual(status, "CHAIN NOT TRUSTED")
        self.assertTrue(any("broken link" in x for x in lines))

    def test_revoked_certificate_fails(self):
        status, lines = va.verdict([cert(0), cert(1, sig="n/a")], TRUSTED, [(0, {"status": "REVOKED", "reason": "KEY_COMPROMISE"})], None)
        self.assertEqual(status, "CHAIN NOT TRUSTED")
        self.assertTrue(any("REVOKED" in x for x in lines))

    def test_expired_certificate_fails(self):
        status, _ = va.verdict([cert(0, validity="NOT_VALID_NOW:CertificateExpiredException"), cert(1, sig="n/a")], TRUSTED, [], None)
        self.assertEqual(status, "CHAIN NOT TRUSTED")

    def test_expired_legacy_chain_is_trusted_unless_revoked(self):
        # Regression: a real pre-2021 phone has expired factory certificates and must not be rejected for that.
        expired = "NOT_VALID_NOW:CertificateExpiredException"
        chain = [cert(0), cert(1, validity=expired), cert(2, validity=expired), cert(3, sig="n/a", validity=expired)]
        status, lines = va.verdict(chain, TRUSTED, [], {"verifiedBootState": 0}, legacy=True)
        self.assertEqual(status, "CHAIN VALID AND TRUSTED")
        self.assertTrue(any("expected for a legacy" in x for x in lines))

    def test_expired_legacy_chain_that_is_revoked_still_fails(self):
        expired = "NOT_VALID_NOW:CertificateExpiredException"
        chain = [cert(0), cert(1, validity=expired), cert(2, sig="n/a", validity=expired)]
        status, _ = va.verdict(chain, TRUSTED, [(1, {"status": "REVOKED", "reason": "KEY_COMPROMISE"})], None, legacy=True)
        self.assertEqual(status, "CHAIN NOT TRUSTED")

    def test_expired_non_legacy_chain_still_fails(self):
        expired = "NOT_VALID_NOW:CertificateExpiredException"
        chain = [cert(0), cert(1, validity=expired), cert(2, sig="n/a")]
        status, _ = va.verdict(chain, TRUSTED, [], None, legacy=False)
        self.assertEqual(status, "CHAIN NOT TRUSTED")

    def test_legacy_root_detection_handles_both_spellings(self):
        hexform = "2.5.4.5=#" + "f92009e853b6b045".encode().hex()
        self.assertTrue(va.is_legacy_chain([{"subject": "CN=x", "issuer": hexform}]))
        self.assertTrue(va.is_legacy_chain([{"subject": "SERIALNUMBER=f92009e853b6b045,CN=root", "issuer": ""}]))
        self.assertFalse(va.is_legacy_chain([{"subject": "CN=other", "issuer": "CN=other root"}]))
        self.assertFalse(va.is_legacy_chain([]))

    def test_empty_chain(self):
        self.assertEqual(va.verdict([], None, [], None)[0], "NO CHAIN")


class RawTests(unittest.TestCase):
    def test_chain_and_attestation_extraction(self):
        doc = {"probe": {"readings": [{"id": "K1", "code": "OK", "value": {"chainBase64": ["AAA"], "attestation": {"osPatchLevel": 202207}}}]}}
        self.assertEqual(va.chain_from_raw(doc), ["AAA"])
        self.assertEqual(va.attestation_from_raw(doc)["osPatchLevel"], 202207)

    def test_failed_k1_gives_no_chain(self):
        doc = {"probe": {"readings": [{"id": "K1", "code": "ERROR", "value": None}]}}
        self.assertIsNone(va.chain_from_raw(doc))


if __name__ == "__main__":
    unittest.main(verbosity=2)
