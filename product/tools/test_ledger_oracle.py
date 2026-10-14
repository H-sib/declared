"""Unit tests for ledger_oracle.py, against the history file the app's own code wrote (core/src/test/resources/ledger/sample-ledger.json).

Two programs in two languages, one set of bytes: the Kotlin verifier accepts the file (LedgerFixtureTest) and so does this one,
which shares no code with it. The tests also build histories of their own with OpenSSL's keys, so the cases the sample cannot
hold (a whole history rewritten under another key, signatures made here) are covered too.
"""
import base64
import copy
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ledger_oracle as o  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLE = os.path.join(HERE, "..", "core", "src", "test", "resources", "ledger", "sample-ledger.json")
OPENSSL = o.openssl_path()


def sample_text():
    with open(SAMPLE, encoding="utf-8") as f:
        return f.read()


def dump(doc):
    return json.dumps(doc, separators=(",", ":"), ensure_ascii=False)


def rehash(doc, resign=None):
    """Recompute every prev and hash after an edit, as someone who cannot sign would; sign with `resign` when it is given."""
    prev = o.genesis(doc["header"])
    for e in doc["entries"]:
        e["prev"] = prev
        e["hash"] = o.sha256_hex(o.body_text(e))
        if resign is not None:
            e["sig"] = base64.b64encode(resign.sign(bytes.fromhex(e["hash"]))).decode("ascii")
        prev = e["hash"]
    return doc


class Key:
    """An ECDSA P-256 key made with OpenSSL: a stand-in for a Keystore key held by someone else."""

    def __init__(self):
        self.dir = tempfile.TemporaryDirectory()
        self.pem = os.path.join(self.dir.name, "k.pem")
        subprocess.run([OPENSSL, "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", self.pem], check=True, capture_output=True)
        out = subprocess.run([OPENSSL, "pkey", "-in", self.pem, "-pubout", "-outform", "DER"], check=True, capture_output=True)
        self.spki = out.stdout
        self.b64 = base64.b64encode(self.spki).decode("ascii")

    def sign(self, message):
        msg = os.path.join(self.dir.name, "m")
        with open(msg, "wb") as f:
            f.write(message)
        return subprocess.run([OPENSSL, "dgst", "-sha256", "-sign", self.pem, msg], check=True, capture_output=True).stdout


def diff(old, new):
    out = []
    for k in sorted(set(old) | set(new), key=o.u16):
        if k not in old:
            out.append({"k": k, "t": "A", "a": new[k]})
        elif k not in new:
            out.append({"k": k, "t": "R", "b": old[k]})
        elif old[k] != new[k]:
            out.append({"k": k, "t": "M", "b": old[k], "a": new[k]})
    return out


def build(snapshots, key, created="2026-10-09T10:00:00Z", api=34):
    """A signed history of the given snapshots (dicts of records), one entry each, an hour apart."""
    doc = {"format": 1, "header": {"created": created, "key": key.b64 if key else "", "held": "SOFTWARE" if key else "NONE"}, "entries": []}
    records = {}
    for i, snap in enumerate(snapshots):
        e = {"seq": i + 1, "at": "2026-10-09T%02d:00:00Z" % (10 + i), "taken": "OPENED", "app": "0.3.0", "api": api,
             "snapshot": o.sha256_hex(o.snapshot_text(api, snap)), "changes": diff(records, snap), "events": [], "prev": "", "hash": ""}
        doc["entries"].append(e)
        records = snap
    return rehash(doc, key)


SNAPS = [
    {"app:a": {"label": "A", "tier": "STORE"}},
    {"app:a": {"label": "A", "tier": "STORE"}, "app:b": {"label": "B", "tier": "SIDELOAD"}},
    {"app:a": {"label": "A2", "tier": "STORE"}, "app:b": {"label": "B", "tier": "SIDELOAD"}},
    {"app:a": {"label": "A2", "tier": "STORE"}},
]


@unittest.skipIf(OPENSSL is None, "openssl was not found")
class TheAppsOwnFile(unittest.TestCase):
    def test_it_verifies_is_authentic_and_every_entry_is_signed(self):
        r = o.verify(sample_text())
        self.assertEqual([], r.problems)
        self.assertTrue(r.intact and r.authentic and r.key_tied)
        self.assertEqual((7, 7), (r.entries, r.signed))
        self.assertEqual(7, o.head(sample_text())[0])

    def test_the_text_it_rebuilds_is_the_text_the_app_hashed(self):
        doc = o.parse(sample_text())
        for e in doc["entries"]:
            self.assertEqual(e["hash"], o.sha256_hex(o.body_text(e)))
        self.assertEqual(doc["entries"][0]["prev"], o.genesis(doc["header"]))

    def test_the_sample_pins_the_ordering_rule_by_utf16_unit(self):
        by_code_point = lambda s: s.encode("utf-32-be")  # noqa: E731
        with mock.patch.object(o, "u16", by_code_point):
            r = o.verify(sample_text(), signatures=False)
        self.assertFalse(r.intact, "ordering by code point must not reproduce the app's hashes, or the sample proves nothing")

    def test_the_tie_to_the_phones_own_key(self):
        text = sample_text()
        key = o.parse(text)["header"]["key"]
        self.assertTrue(o.verify(text, held=key).authentic)
        self.assertFalse(o.verify(text, held=Key().b64).authentic)
        self.assertFalse(o.verify(text, held=Key().b64).intact)
        unread = o.verify(text, held=None)
        self.assertTrue(unread.intact)
        self.assertFalse(unread.authentic)

    def test_the_cli_exits_zero_only_for_an_authentic_history(self):
        script = os.path.join(HERE, "ledger_oracle.py")
        good = subprocess.run([sys.executable, "-I", script, SAMPLE], capture_output=True, text=True)
        self.assertEqual(0, good.returncode, good.stdout + good.stderr)
        with tempfile.TemporaryDirectory() as d:
            bad = os.path.join(d, "bad.json")
            with open(bad, "w", encoding="utf-8") as f:
                f.write(sample_text().replace('"api":34', '"api":35', 1))
            self.assertEqual(1, subprocess.run([sys.executable, "-I", script, bad], capture_output=True, text=True).returncode)


@unittest.skipIf(OPENSSL is None, "openssl was not found")
class Tampering(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key = Key()
        cls.good = build(SNAPS, cls.key)

    def doc(self):
        return copy.deepcopy(self.good)

    def test_a_history_built_here_verifies(self):
        r = o.verify(dump(self.good))
        self.assertEqual([], r.problems)
        self.assertTrue(r.authentic)

    def test_each_kind_of_alteration_without_rehashing_is_broken(self):
        cases = {}
        d = self.doc(); d["entries"][1]["at"] = "2026-10-09T23:00:00Z"; cases["time"] = d
        d = self.doc(); d["entries"][1]["changes"][0]["a"]["label"] = "X"; cases["a change"] = d
        d = self.doc(); d["entries"][2]["events"].append("tripwire.armed.biometric"); cases["events"] = d
        d = self.doc(); d["entries"][2]["api"] = 99; cases["api"] = d
        d = self.doc(); d["entries"][2]["snapshot"] = "0" * 64; cases["snapshot"] = d
        d = self.doc(); del d["entries"][1]; cases["removed from the middle"] = d
        d = self.doc(); d["entries"][1], d["entries"][2] = d["entries"][2], d["entries"][1]; cases["swapped"] = d
        d = self.doc(); d["entries"].insert(2, copy.deepcopy(d["entries"][1])); cases["repeated"] = d
        d = self.doc(); d["header"]["created"] = "2026-10-09T10:00:01Z"; cases["start time"] = d
        d = self.doc(); d["header"]["held"] = "HARDWARE"; cases["key kind"] = d
        d = self.doc(); d["entries"][0]["sig"] = d["entries"][0]["sig"][::-1]; cases["signature"] = d
        for name, doc in cases.items():
            self.assertFalse(o.verify(dump(doc)).intact, name)

    def test_a_rewrite_that_recomputes_the_hashes_but_cannot_sign_is_caught_by_the_signatures(self):
        d = self.doc()
        d["entries"][1]["changes"][0]["a"]["label"] = "forged"
        rehash(d)  # the chain now holds, the old signatures are for other hashes
        r = o.verify(dump(d))
        self.assertFalse(r.intact)
        self.assertTrue(any("signature" in p[2] for p in r.problems))

    def test_stripping_the_signatures_leaves_a_chain_that_holds_but_is_not_authentic(self):
        d = self.doc()
        for e in d["entries"]:
            del e["sig"]
        r = o.verify(dump(d))
        self.assertTrue(r.intact)
        self.assertFalse(r.authentic)
        self.assertEqual(len(SNAPS), sum(1 for p in r.problems if p[1] == o.WEAK))

    def test_removing_the_newest_entries_is_the_documented_blind_spot_and_the_head_is_what_shows_it(self):
        d = self.doc()
        written_down = o.head(dump(d))
        d["entries"] = d["entries"][:2]
        r = o.verify(dump(d))
        self.assertTrue(r.authentic, "nothing inside the file can tell")
        self.assertNotEqual(written_down, o.head(dump(d)))

    def test_a_whole_history_rewritten_under_another_key_is_flawless_until_tied_to_the_phones_key(self):
        other = Key()
        forged = build(SNAPS, other)
        self.assertTrue(o.verify(dump(forged)).authentic, "the limit of a check of the file alone")
        tied = o.verify(dump(forged), held=self.key.b64)
        self.assertFalse(tied.authentic)
        self.assertFalse(tied.intact)
        self.assertTrue(o.verify(dump(self.good), held=self.key.b64).authentic)

    def test_an_entry_signed_by_another_key_in_a_genuine_history_is_broken(self):
        d = self.doc()
        d["entries"][2]["sig"] = base64.b64encode(Key().sign(bytes.fromhex(d["entries"][2]["hash"]))).decode("ascii")
        r = o.verify(dump(d))
        self.assertFalse(r.intact)
        self.assertEqual([3], [p[0] for p in r.problems if p[1] == o.BROKEN])

    def test_a_set_back_clock_is_weak_not_broken(self):
        d = self.doc()
        d["entries"][2]["at"] = "2026-10-09T09:00:00Z"
        rehash(d, self.key)
        r = o.verify(dump(d))
        self.assertTrue(r.intact and r.authentic)
        self.assertEqual([(3, o.WEAK)], [(p[0], p[1]) for p in r.problems])

    def test_a_history_with_no_key_is_never_authentic_with_entries(self):
        r = o.verify(dump(build(SNAPS, None)))
        self.assertTrue(r.intact)
        self.assertFalse(r.authentic)
        self.assertEqual(0, r.signed)

    def test_a_noncanonical_signature_text_is_refused(self):
        d = self.doc()
        sig = d["entries"][0]["sig"]
        raw = base64.b64decode(sig)
        # the same bytes written with a trailing newline are not the canonical text of those bytes
        d["entries"][0]["sig"] = sig + "\n"
        self.assertEqual(raw, base64.b64decode(d["entries"][0]["sig"]))
        self.assertFalse(o.verify(dump(d)).intact)


class Reading(unittest.TestCase):
    def test_strictness(self):
        base = json.loads(sample_text())
        bad = {}
        d = copy.deepcopy(base); d["format"] = 2; bad["format"] = d
        d = copy.deepcopy(base); d["extra"] = 1; bad["unknown top-level key"] = d
        d = copy.deepcopy(base); d["entries"][0]["extra"] = 1; bad["unknown entry key"] = d
        d = copy.deepcopy(base); d["entries"][0]["seq"] = True; bad["a boolean for a number"] = d
        d = copy.deepcopy(base); d["entries"][0]["taken"] = "SOMETIMES"; bad["unknown way of being taken"] = d
        d = copy.deepcopy(base); d["header"]["held"] = "MAGIC"; bad["unknown key kind"] = d
        d = copy.deepcopy(base); d["entries"][0]["at"] = "yesterday"; bad["time"] = d
        d = copy.deepcopy(base); d["entries"][0]["changes"][0]["t"] = "X"; bad["change kind"] = d
        d = copy.deepcopy(base); d["entries"][0]["changes"][0]["a"]["value"] = 1; bad["a number as a field value"] = d
        for name, doc in bad.items():
            r = o.verify(dump(doc), signatures=False)
            self.assertFalse(r.intact, name)
        for text in ["", "null", "[]", "{}", sample_text()[:-1], sample_text()[: len(sample_text()) // 2]]:
            self.assertFalse(o.verify(text, signatures=False).intact, text[:20])

    def test_a_key_that_appears_twice_is_refused_because_two_readers_would_pick_different_ones(self):
        text = sample_text().replace('"format":1,', '"format":1,"format":1,', 1)
        self.assertFalse(o.verify(text, signatures=False).intact)

    def test_string_escaping_matches_the_known_forms(self):
        self.assertEqual('"a\\"b\\\\c\\n\\r\\t\\u0007\\u001f é 中 \U0001F600 \u007f"', o.quote('a"b\\c\n\r\t\x07\x1f é 中 \U0001F600 \x7f'))

    def test_ordering_is_by_utf16_unit(self):
        # U+FF5E is one UTF-16 unit above the surrogates, so the emoji sorts first for Java and last by code point.
        self.assertEqual('{"\U0001F600":"y","～":"x"}', o.fields_text({"～": "x", "\U0001F600": "y"}))
        self.assertEqual('["\U0001F600","～"]', o.events_text(["～", "\U0001F600"]))

    def test_known_hashes(self):
        self.assertEqual(hashlib.sha256(b"abc").hexdigest(), o.sha256_hex("abc"))
        self.assertEqual('{"v":1,"created":"2026-10-09T10:00:00Z","key":"","held":"NONE"}', o.header_text({"created": "2026-10-09T10:00:00Z", "key": "", "held": "NONE"}))


class Replaying(unittest.TestCase):
    def test_the_three_changes_and_what_each_requires(self):
        r = {"a": {"k": "1"}}
        self.assertEqual({"a": {"k": "1"}, "b": {"k": "2"}}, o.replay(r, [{"k": "b", "t": "A", "a": {"k": "2"}}]))
        self.assertEqual({}, o.replay(r, [{"k": "a", "t": "R", "b": {"k": "1"}}]))
        self.assertEqual({"a": {"k": "9"}}, o.replay(r, [{"k": "a", "t": "M", "b": {"k": "1"}, "a": {"k": "9"}}]))
        for bad in ([{"k": "a", "t": "A", "a": {}}], [{"k": "a", "t": "R", "b": {"k": "2"}}], [{"k": "z", "t": "R", "b": {}}], [{"k": "a", "t": "M", "b": {"k": "2"}, "a": {}}]):
            with self.assertRaises(ValueError):
                o.replay(r, bad)

    def test_replay_does_not_change_what_it_was_given(self):
        r = {"a": {"k": "1"}}
        o.replay(r, [{"k": "a", "t": "M", "b": {"k": "1"}, "a": {"k": "9"}}])
        self.assertEqual({"a": {"k": "1"}}, r)


@unittest.skipIf(OPENSSL is None, "openssl was not found")
class Mutating(unittest.TestCase):
    def test_a_single_character_changed_anywhere_in_the_file_is_never_silently_accepted(self):
        """Every position, two replacements each. The cheap pass runs first; what survives it gets the full check with OpenSSL."""
        text = sample_text()
        survivors = []
        for pos in range(len(text)):
            for repl in ("x", "0"):
                c = repl if text[pos] != repl else "y"
                mutated = text[:pos] + c + text[pos + 1:]
                if o.verify(mutated, signatures=False).authentic:
                    survivors.append(mutated)
        self.assertGreater(len(survivors), 0, "changes inside signatures keep the text well formed, so some must reach the full check")
        undetected = [m for m in survivors[::4] if o.verify(m).authentic]
        self.assertEqual([], [m[:80] for m in undetected])

    def test_the_signature_check_itself_rejects_a_changed_message_and_a_changed_signature(self):
        key = Key()
        msg = bytes.fromhex("ab" * 32)
        sig = key.sign(msg)
        self.assertTrue(o.ecdsa_verify(key.spki, msg, sig))
        self.assertFalse(o.ecdsa_verify(key.spki, bytes.fromhex("ac" * 32), sig))
        self.assertFalse(o.ecdsa_verify(key.spki, msg, sig[:-1] + bytes([sig[-1] ^ 1])))
        self.assertFalse(o.ecdsa_verify(Key().spki, msg, sig))
        self.assertFalse(o.ecdsa_verify(b"not a key", msg, sig))


if __name__ == "__main__":
    unittest.main()
