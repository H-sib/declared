"""An independent check of Declared's history file (STAGE3_DESIGN.md sections 3 and 4). Python standard library plus the `openssl` command.

It imports nothing from the app and calls none of its code. It rebuilds the text each entry is hashed from, with its own
string escaping and its own ordering, works the hashes out with `hashlib`, replays the recorded changes to rebuild the
snapshot each entry says it describes, and checks every ECDSA signature with OpenSSL. If the Kotlin verifier and this one
ever disagree about a file, one of them is wrong, and `conformance_m8.py` and the committed sample both put them side by side.

Ordering note: the app sorts keys and events as Java strings, which compare by UTF-16 code unit, not by code point. Text with
characters outside the Basic Multilingual Plane sorts differently under the two rules, so this file sorts by UTF-16 too.
"""
import base64
import binascii
import calendar
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

BROKEN = "BROKEN"
WEAK = "WEAK"
HELD = {"HARDWARE", "SOFTWARE", "UNKNOWN", "NONE"}
TAKEN = {"OPENED", "ON_SCREEN"}
HEX64 = re.compile(r"^[0-9a-f]{64}$")
INSTANT = re.compile(r"^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.(\d{3}|\d{6}|\d{9}))?Z$")


class Malformed(Exception):
    """The file is not a history at all: the reason says where."""


# ---------------------------------------------------------------- the canonical text

def u16(s):
    return s.encode("utf-16-be", "surrogatepass")


def quote(s):
    out = ['"']
    for c in s:
        if c == '"':
            out.append('\\"')
        elif c == "\\":
            out.append("\\\\")
        elif c == "\n":
            out.append("\\n")
        elif c == "\r":
            out.append("\\r")
        elif c == "\t":
            out.append("\\t")
        elif ord(c) < 0x20:
            out.append("\\u%04x" % ord(c))
        else:
            out.append(c)
    out.append('"')
    return "".join(out)


def fields_text(rec):
    return "{" + ",".join(quote(k) + ":" + quote(v) for k, v in sorted(rec.items(), key=lambda kv: u16(kv[0]))) + "}"


def snapshot_text(api, records):
    return '{"api":' + str(api) + ',"records":{' + ",".join(quote(k) + ":" + fields_text(r) for k, r in sorted(records.items(), key=lambda kv: u16(kv[0]))) + "}}"


def change_text(c):
    k, t = quote(c["k"]), c["t"]
    if t == "A":
        return '{"k":' + k + ',"t":"A","a":' + fields_text(c["a"]) + "}"
    if t == "R":
        return '{"k":' + k + ',"t":"R","b":' + fields_text(c["b"]) + "}"
    return '{"k":' + k + ',"t":"M","b":' + fields_text(c["b"]) + ',"a":' + fields_text(c["a"]) + "}"


def changes_text(changes):
    return "[" + ",".join(change_text(c) for c in sorted(changes, key=lambda c: u16(c["k"]))) + "]"


def events_text(events):
    return "[" + ",".join(quote(e) for e in sorted(events, key=u16)) + "]"


def header_text(h):
    return '{"v":1,"created":' + quote(h["created"]) + ',"key":' + quote(h["key"]) + ',"held":' + quote(h["held"]) + "}"


def body_text(e):
    return ('{"v":1,"seq":' + str(e["seq"]) + ',"at":' + quote(e["at"]) + ',"taken":' + quote(e["taken"]) + ',"app":' + quote(e["app"]) +
            ',"api":' + str(e["api"]) + ',"snapshot":' + quote(e["snapshot"]) + ',"changes":' + changes_text(e["changes"]) +
            ',"events":' + events_text(e["events"]) + ',"prev":' + quote(e["prev"]) + "}")


def sha256_hex(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def genesis(header):
    return sha256_hex(header_text(header))


# ---------------------------------------------------------------- reading the file strictly

def _no_duplicates(pairs):
    d = {}
    for k, v in pairs:
        if k in d:
            raise Malformed("the key %r appears twice" % k)
        d[k] = v
    return d


def _fields(v, where):
    if not isinstance(v, dict) or not all(isinstance(a, str) and isinstance(b, str) for a, b in v.items()):
        raise Malformed("%s is not a set of text fields" % where)
    return v


def _exact(d, names, where, optional=()):
    if not isinstance(d, dict):
        raise Malformed("%s is not an object" % where)
    extra = set(d) - set(names) - set(optional)
    missing = set(names) - set(d)
    if extra or missing:
        raise Malformed("%s has the wrong keys (extra %s, missing %s)" % (where, sorted(extra), sorted(missing)))


def _instant(text, where):
    m = INSTANT.match(text) if isinstance(text, str) else None
    if not m:
        raise Malformed("%s is not a time" % where)
    y, mo, d, h, mi, s = (int(x) for x in m.groups()[:6])
    frac = m.group(7) or ""
    try:
        secs = calendar.timegm((y, mo, d, h, mi, s, 0, 0, 0))
        if not (1 <= mo <= 12 and 1 <= d <= 31 and h < 24 and mi < 60 and s < 60):
            raise ValueError
    except (ValueError, OverflowError):
        raise Malformed("%s is not a time" % where)
    return secs, int(frac.ljust(9, "0")) if frac else 0


def parse(text):
    """The history as plain dicts, or Malformed. Strict: nothing unknown, nothing missing, nothing of the wrong kind."""
    try:
        doc = json.loads(text, object_pairs_hook=_no_duplicates)
    except Malformed:
        raise
    except ValueError as ex:
        raise Malformed("not readable text: %s" % ex)
    _exact(doc, ["format", "header", "entries"], "the file")
    if doc["format"] != 1 or isinstance(doc["format"], bool):
        raise Malformed("the format is not 1")
    h = doc["header"]
    _exact(h, ["created", "key", "held"], "the header")
    if not all(isinstance(h[k], str) for k in h) or h["held"] not in HELD:
        raise Malformed("the header is not valid")
    _instant(h["created"], "the start time")
    if not isinstance(doc["entries"], list):
        raise Malformed("the entries are not a list")
    for i, e in enumerate(doc["entries"]):
        where = "entry %d" % (i + 1)
        _exact(e, ["seq", "at", "taken", "app", "api", "snapshot", "changes", "events", "prev", "hash"], where, optional=["sig"])
        for k in ("at", "taken", "app", "snapshot", "prev", "hash"):
            if not isinstance(e[k], str):
                raise Malformed("%s: %s is not text" % (where, k))
        for k in ("seq", "api"):
            if not isinstance(e[k], int) or isinstance(e[k], bool):
                raise Malformed("%s: %s is not a number" % (where, k))
        if "sig" in e and not isinstance(e["sig"], str):
            raise Malformed("%s: the signature is not text" % where)
        if e["taken"] not in TAKEN:
            raise Malformed("%s: unknown way of being taken" % where)
        if not isinstance(e["events"], list) or not all(isinstance(x, str) for x in e["events"]):
            raise Malformed("%s: events are not a list of text" % where)
        if not isinstance(e["changes"], list):
            raise Malformed("%s: changes are not a list" % where)
        _instant(e["at"], where + " time")
        for c in e["changes"]:
            if not isinstance(c, dict) or c.get("t") not in ("A", "R", "M") or not isinstance(c.get("k"), str):
                raise Malformed("%s: a change is not valid" % where)
            names = {"A": ["k", "t", "a"], "R": ["k", "t", "b"], "M": ["k", "t", "b", "a"]}[c["t"]]
            _exact(c, names, where + " change")
            for side in ("a", "b"):
                if side in c:
                    _fields(c[side], where + " change")
    return doc


# ---------------------------------------------------------------- replay and signatures

def replay(records, changes):
    """Apply one entry's changes to the records so far. Raises ValueError when a change does not fit what came before."""
    m = {k: dict(v) for k, v in records.items()}
    for c in changes:
        k, t = c["k"], c["t"]
        if t == "A":
            if k in m:
                raise ValueError("cannot add %s: it already exists" % k)
            m[k] = dict(c["a"])
        elif t == "R":
            if m.get(k) != c["b"]:
                raise ValueError("cannot remove %s: it is not what the change expects" % k)
            del m[k]
        else:
            if m.get(k) != c["b"]:
                raise ValueError("cannot modify %s: it is not what the change expects" % k)
            m[k] = dict(c["a"])
    return m


def openssl_path():
    return shutil.which("openssl") or next((p for p in (r"C:\Program Files\Git\usr\bin\openssl.exe", r"C:\Program Files\Git\mingw64\bin\openssl.exe") if os.path.exists(p)), None)


def ecdsa_verify(spki_der, message, signature_der):
    """True when OpenSSL says the signature is valid for SHA-256 over `message` under the key. Never raises for bad input."""
    exe = openssl_path()
    if exe is None:
        raise RuntimeError("openssl was not found")
    with tempfile.TemporaryDirectory() as d:
        paths = {n: os.path.join(d, n) for n in ("key", "msg", "sig")}
        for n, data in (("key", spki_der), ("msg", message), ("sig", signature_der)):
            with open(paths[n], "wb") as f:
                f.write(data)
        r = subprocess.run([exe, "dgst", "-sha256", "-verify", paths["key"], "-keyform", "DER", "-signature", paths["sig"], paths["msg"]],
                           capture_output=True, text=True)
        return r.returncode == 0 and "Verified OK" in r.stdout


NOT_ASKED = object()


class Result:
    def __init__(self, entries, signed, problems, key_tied=True):
        self.entries, self.signed, self.problems, self.key_tied = entries, signed, problems, key_tied

    @property
    def intact(self):
        return not any(p[1] == BROKEN for p in self.problems)

    @property
    def authentic(self):
        return self.intact and self.signed == self.entries and self.key_tied

    def texts(self):
        return [p[2] for p in self.problems]

    def __repr__(self):
        return "Result(entries=%d, signed=%d, intact=%s, authentic=%s, key_tied=%s, problems=%s)" % (self.entries, self.signed, self.intact, self.authentic, self.key_tied, self.problems)


def verify(text, signatures=True, held=NOT_ASKED):
    """Everything the app's own check does, from the file's text alone. Malformed files give a single BROKEN problem.

    `held` is the base64 of the public key the phone holds now (or None when it could not be read); pass it to tie the history
    to that key. Without it a history rewritten from scratch under someone else's key is flawless, by construction.
    `signatures=False` skips the OpenSSL call and counts a well-formed signature as valid: for scans only, never for a verdict.
    """
    try:
        doc = parse(text)
    except Malformed as ex:
        return Result(0, 0, [(None, BROKEN, "unreadable: %s" % ex)])
    problems = []
    header = doc["header"]
    spki = None
    if header["key"]:
        try:
            spki = base64.b64decode(header["key"], validate=True)
            if base64.b64encode(spki).decode("ascii") != header["key"]:
                raise ValueError
        except (binascii.Error, ValueError):
            problems.append((None, BROKEN, "the key the history began with is not readable"))
    else:
        problems.append((None, WEAK, "no signing key"))
    key_tied = True
    if held is not NOT_ASKED and header["key"] and spki is not None:
        if held is None:
            key_tied = False
            problems.append((None, WEAK, "the phone's signing key could not be read, so the signatures could not be tied to it"))
        elif held != header["key"]:
            key_tied = False
            problems.append((None, BROKEN, "the history was signed with a key that is not the one this phone holds"))
    records, replay_ok, signed = {}, True, 0
    prev_hash, prev_at = genesis(header), None
    for i, e in enumerate(doc["entries"]):
        seq = e["seq"]
        if seq != i + 1:
            problems.append((seq, BROKEN, "entry %d is numbered %d" % (i + 1, seq)))
        if e["prev"] != prev_hash:
            problems.append((seq, BROKEN, "entry %d does not follow the one before it" % seq))
        if sha256_hex(body_text(e)) != e["hash"]:
            problems.append((seq, BROKEN, "entry %d does not match its hash" % seq))
        if replay_ok:
            try:
                records = replay(records, e["changes"])
            except ValueError as ex:
                replay_ok = False
                problems.append((seq, BROKEN, "entry %d: %s" % (seq, ex)))
            if replay_ok and sha256_hex(snapshot_text(e["api"], records)) != e["snapshot"]:
                problems.append((seq, BROKEN, "replay to entry %d does not give the recorded snapshot" % seq))
        sig = e.get("sig")
        if sig is None:
            if spki is not None:
                problems.append((seq, WEAK, "entry %d is not signed" % seq))
        elif spki is not None:
            ok = False
            try:
                raw = base64.b64decode(sig, validate=True)
                canonical = base64.b64encode(raw).decode("ascii") == sig
                if canonical and HEX64.match(e["hash"]) and signatures:
                    ok = ecdsa_verify(spki, bytes.fromhex(e["hash"]), raw)
                elif canonical and not signatures:
                    ok = None
            except (binascii.Error, ValueError):
                ok = False
            if ok:
                signed += 1
            elif ok is None:
                signed += 1
            else:
                problems.append((seq, BROKEN, "the signature of entry %d is not valid" % seq))
        at = _instant(e["at"], "time")
        if prev_at is not None and at < prev_at:
            problems.append((seq, WEAK, "entry %d is dated before the one preceding it" % seq))
        prev_at, prev_hash = at, e["hash"]
    return Result(len(doc["entries"]), signed, problems, key_tied)


def head(text):
    """(sequence, hash) of the newest entry, or None for an empty history. The file must parse."""
    doc = parse(text)
    return (doc["entries"][-1]["seq"], doc["entries"][-1]["hash"]) if doc["entries"] else None


def events_of(text):
    """Every entry's events and the keys it changed, in order: what the conformance run compares with what the system did."""
    doc = parse(text)
    return [(e["seq"], e["at"], e["taken"], sorted(e["events"]), sorted((c["t"], c["k"]) for c in e["changes"])) for e in doc["entries"]]


def final_records(text):
    """The records as of the newest entry, by replaying every change."""
    doc = parse(text)
    records = {}
    for e in doc["entries"]:
        records = replay(records, e["changes"])
    return records


def main(argv):
    if len(argv) != 2:
        print("usage: ledger_oracle.py <history file>", file=sys.stderr)
        return 2
    with open(argv[1], encoding="utf-8") as f:
        text = f.read()
    r = verify(text)
    print(json.dumps({"entries": r.entries, "signed": r.signed, "intact": r.intact, "authentic": r.authentic, "keyTied": r.key_tied,
                      "head": head(text) if r.entries else None, "problems": r.problems}, indent=2))
    return 0 if r.authentic else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
