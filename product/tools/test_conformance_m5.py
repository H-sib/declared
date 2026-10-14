"""Unit tests for the pure helpers of conformance_m5.py."""
import copy
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import conformance_m5 as c  # noqa: E402


class Helpers(unittest.TestCase):
    def test_expected_pending_is_the_holders_of_the_enabled_roles_minus_the_answered(self):
        self.assertEqual(
            sorted(["ACCESSIBILITY:lab.fixture.a11y/lab.fixture.FixtureA11yService", "DEVICE_ADMIN:lab.fixture.admin/lab.fixture.FixtureAdminReceiver"]),
            c.expected_pending(["a11y", "admin"]),
        )
        self.assertEqual(4, len(c.expected_pending(["perms"])))
        self.assertEqual([], c.expected_pending(["a11y"], answered=c.HOLDER_KEYS["a11y"]))
        self.assertEqual([], c.expected_pending([]))

    def test_screens_drops_the_arguments(self):
        self.assertEqual(["Inventory", "ReviewHolders", "FindingDetail"], c.screens({"stack": ["Inventory", "ReviewHolders(first=false)", "FindingDetail(findingId=x)"]}))

    def test_flipping_a_byte_changes_exactly_one_byte(self):
        data = bytes(range(40))
        flipped = c.flip_byte(data, 7)
        self.assertEqual(1, sum(a != b for a, b in zip(data, flipped)))
        self.assertEqual(data, c.flip_byte(flipped, 7))

    def test_damage_variants_are_all_different_and_each_expects_a_reason(self):
        data = bytes([0xCA, 1, 12]) + bytes(range(12)) + bytes(range(200))
        v = c.damaged_variants(data)
        self.assertEqual(4, len(v))
        self.assertEqual(4, len({d for d, _ in v.values()}))
        self.assertTrue(all(d != data and reason for d, reason in v.values()))
        self.assertEqual(20, len(v["cut inside the header"][0]))
        self.assertEqual(0x7F, v["wrong header"][0][0])

    def test_the_damage_reasons_match_what_the_core_says(self):
        # The strings the product gives (SealedFile and EncryptedFileStore) must contain these.
        def read(*parts):
            with open(os.path.join(c.PRODUCT, *parts), encoding="utf-8") as fh:
                return fh.read()

        sealed = read("core", "src", "main", "kotlin", "io", "github", "h_sib", "declared", "core", "state", "SealedFile.kt")
        store = read("app", "src", "main", "kotlin", "io", "github", "h_sib", "declared", "app", "EncryptedBytes.kt")
        for _, reason in c.damaged_variants(bytes(100)).values():
            self.assertTrue(reason in sealed or reason in store, reason)


def good_state(answers=None, signers=None):
    answers = answers if answers is not None else {"K1": "RECOGNIZED", "K2": "UNSURE"}
    signers = signers if signers is not None else {"K1": ["aa"], "K2": ["bb"]}
    body = {k: {"answer": a, "signers": signers[k]} for k, a in answers.items()}
    # separate copies: the in-memory and the on-disk view must be able to disagree in a test
    return {"saved": {"answers": copy.deepcopy(body)}, "persisted": {"status": "Loaded", "answers": copy.deepcopy(body), "baselineAt": "2026-10-09T10:00:00Z", "recordCount": 100}}


class Compare(unittest.TestCase):
    def failures(self, state, answers, signers):
        return [x.name for x in c.compare_persisted(state, answers, signers) if not x.ok]

    def test_agreement_passes(self):
        self.assertEqual([], self.failures(good_state(), {"K1": "RECOGNIZED", "K2": "UNSURE"}, {"K1": ["aa"], "K2": ["bb"]}))

    def test_a_wrong_answer_a_wrong_signer_and_an_unreadable_file_are_caught(self):
        self.assertTrue(any("answers on disk" in n for n in self.failures(good_state(), {"K1": "NOT_RECOGNIZED", "K2": "UNSURE"}, {"K1": ["aa"], "K2": ["bb"]})))
        self.assertTrue(any("signing certificate" in n for n in self.failures(good_state(), {"K1": "RECOGNIZED", "K2": "UNSURE"}, {"K1": ["zz"], "K2": ["bb"]})))
        bad = good_state()
        bad["persisted"] = {"status": "Corrupt", "reason": "x"}
        self.assertTrue(any("can be read back" in n for n in self.failures(bad, {}, {})))

    def test_memory_and_disk_disagreeing_is_caught(self):
        s = good_state()
        s["saved"]["answers"]["K1"]["answer"] = "NOT_RECOGNIZED"
        self.assertTrue(any("in memory = answers on disk" in n for n in self.failures(s, {"K1": "RECOGNIZED", "K2": "UNSURE"}, {"K1": ["aa"], "K2": ["bb"]})))


if __name__ == "__main__":
    unittest.main()
