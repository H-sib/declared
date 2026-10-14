# Stage 3 design: history and tripwires

Status: **proposal only**, written 2026-10-09 after Stage 2 was verified. Nothing of Stage 3 is built. It is a proposal because the shape of the stage depends on three decisions that are the user's (section 7) and on one measurement that is still owed (the Xperia background read-out, section 2). `CONCEPT.md` fixed the scope: "hash-chained posture history, Keystore tripwires, drift timeline".

## 1. What it adds to the person's life

Stage 1 answers "what changed since I last looked" by comparing now with the last acknowledged snapshot. It cannot say **when**, or **in what order**, or whether something changed and changed back while the app was closed. Stage 3 keeps a history the person can read and that cannot be quietly edited:

- **A ledger**: one entry each time the app takes a snapshot, linked to the one before by a hash, so removing or changing an old entry is visible.
- **A drift timeline**: the entries as a list of changes ("on 3 March: an app gained access to notifications; on 9 March: it lost it"), with gaps shown as gaps.
- **Tripwires**: two Keystore keys that the system destroys when a fingerprint is added or the screen lock is removed, so the app can say "something changed that this app could not have seen happen" even if it was closed at the time.

It must stay as honest as the first two stages: a gap in the history is shown, not smoothed over; a tripwire that fired says "something changed, was that you?", never "someone broke in"; and the limits (below) are on the blind-spot map.

## 2. What the measurements say

- **The app hears nothing in the background** (`FINDINGS.md`, Q7): on the API 34 emulator, nothing was delivered to a backgrounded app except a handful of settings events several seconds late, and nothing once the process was gone. So a history cannot be built from live callbacks.
- **Snapshots must therefore be taken by being opened, and optionally by scheduled work.** Whether scheduled work is dependable on a real phone is exactly what the 24 to 48 hour Xperia background test (`FINDINGS.md`, Q9) is meant to answer, and it has not been read out (the phone holds data and wireless debugging is off). Until it is, the design assumes the worst: **every entry records how it was taken** (opened by the person, scheduled, after boot), and a stretch with no entry is a gap on the timeline.
- **Tripwires** (`FINDINGS.md`, Q3, K6b): on emulators for Android 12 to 17 (identical), a key that requires a strong biometric is invalidated by enrolling another fingerprint; a key that also accepts the device credential is not; removing the screen lock destroys both; changing the PIN trips neither (the realistic stolen-PIN case goes unnoticed); keys survive an app update and a reboot. Android 10 differs; Android 11 is untested; **nothing was measured on hardware**. A missing key is ambiguous (lock removal, reinstall, cleared data, restore) and must be read together with the lock-screen reading and the install time.

## 3. The ledger

An entry holds: its sequence number, the time, how it was taken, the app version, a digest of the snapshot it describes (SHA-256 of the snapshot's canonical text) and the diff from the previous entry, and `hash = SHA-256(previousHash || canonical body)`. The first entry names a fixed genesis value. The chain is stored beside the baseline in the encrypted file (or a second encrypted file, so the baseline's size does not grow with history) and verified on open.

**Signing.** Each entry's hash is signed with a Keystore key (ECDSA P-256, no user authentication) created when the ledger starts. On a phone with hardware keys that makes entries unforgeable by any app that cannot use the key; the key's attestation chain (Stage 1's verifier) can be shown with the history to say how the key is held.

**What it does and does not prove** (THREAT_MODEL.md trust assumption 5): it proves that records were not altered **after the fact by something that cannot use the key**, that none was removed from the middle, and the order. It cannot prove the phone was clean when a record was written. It **cannot detect removal of the newest entries** by something with access to the app's files, because nothing outside the phone holds the head. An opt-in export of the head hash and its attestation (text the person can store anywhere, never sent by the app) closes that for a person who keeps it.

## 4. The drift timeline

A list from the ledger: each entry that differs from the one before it becomes the plain-language change items Stage 1 already produces (new finding, escalated finding, a changed signing certificate, a setting that moved), grouped by day, with the attention level each had. Gaps are rows ("nothing was recorded between these two times"). A "verify the history" action runs the chain and signature check and says what it checked and what it could not.

## 5. The tripwires

Two keys, as the measurement says: one that needs a strong biometric (fires when a fingerprint is added; also when the lock is removed), one that accepts the credential too (fires on lock removal). On open the app tries each key and pairs the result with the current lock-screen reading and the app's install time before saying anything:

| Biometric key | Credential key | Lock now | Reading |
|---|---|---|---|
| fine | fine | set | nothing happened that these keys can see |
| destroyed | fine | set | a fingerprint was added since the app last looked (it may have been you) |
| destroyed | destroyed | none | the screen lock was removed |
| destroyed | destroyed | set | cannot tell: the app was reinstalled, its data cleared, or the phone restored |
| any | any | none, never had one | no tripwire is possible on this phone (itself a finding) |

After a tripwire fires, the keys are created again and the event is written to the ledger. Wording: "something changed, was that you?".

## 6. How it will be tested

1. **Ledger properties**: flipping any single bit of any entry, swapping or dropping an entry, or rewriting the middle is detected; appending is accepted; the documented blind spot (dropping the newest entries) is asserted as undetected, so the limit cannot be forgotten.
2. **Signing**: verified against the JDK's own signature verification and against an independent reader, on the emulator, the way Stage 1's chain verifier was.
3. **Tripwire table**: every row of the table above, on the emulator, using the fingerprint enrollment and lock removal the lab already drives (`FINDINGS.md`, Q3), compared with the system's own fingerprint count and lock state.
4. **Timeline**: a month of synthetic history (1,000 entries) renders and verifies in time; gaps are never hidden.
5. **Background**: if scheduled work is chosen, its behavior is measured on the emulator and, when the Xperia is available, on the phone, and the result is printed on the screen next to the timeline ("scheduled snapshots ran N of M expected times on this phone").

## 7. Decisions for the user

1. **Scheduled work or not.** Without it the ledger grows only when the app is opened and the timeline has many gaps. With it, Android's work scheduler adds permissions to the manifest (`RECEIVE_BOOT_COMPLETED` and `WAKE_LOCK`), which the build gate forbids today because Stage 1 promised exactly three permissions. Adding them is a change to a public promise and to the store listing's Data safety answers, so it should be a decision, not a side effect. A middle path: scheduled work off by default, a switch the person turns on, and the gate widened by name.
2. **Where the head hash lives.** Only in the app (simple, cannot catch removal of the newest entries) or also exported on request (honest about the limit, a little work for the person).
3. **Whether to start this before the multi-version emulator run and the Xperia read-out.** Both would change what the design can claim; neither is needed to build the ledger and its tests, and both are needed before the timeline's claims about background reliability can be written.

## 8. Order of work

| Step | Content | Done when |
|---|---|---|
| S3-1 | Canonical snapshot text, entry format, hash chain, verifier (pure `core`) | ledger property tests pass, including the stated blind spot |
| S3-2 | Keystore signing and the tripwire keys in `sensors-android`, the tripwire table in `core` | table tests pass; emulator conformance of the signature and of each row |
| S3-3 | Storage beside the baseline, entries on open (and scheduled, if decided), gaps | reducer and codec tests; emulator conformance after reboot, update and damage |
| S3-4 | Drift timeline and verify screens, instrumented accessibility pass extended | pass at both text sizes |
| S3-5 | Documentation, release notes (0.3.0), and the measured background behavior on whatever phones are available | `DESIGN.md` section 24 written |

## 9. As built (2026-10-09)

The record is `DESIGN.md` section 24. What differs from the proposal above:

- **Decisions taken with the proposal's defaults:** history, timeline and Keystore tripwires first; scheduled background work off (no new permission); the latest entry's fingerprint is shown on screen and not exported.
- **`authentic` is stricter than "no broken link".** It needs every entry signed under the key the history began with, and, when asked, that key to equal the key the phone holds. A missing signature is a weak finding that stops it. This came from the mutation scan (D-044) and from the forgery under another key (D-046).
- **Events in one entry are applied firing events first.** Stored events are sorted by name, so the order they were written in is not the order they happened in (D-043).
- **The history has its own file and key**, `declared-history.bin`, not a second section of the baseline file, so a damaged history never touches the baseline.
- **The independent verifier is `product/tools/ledger_oracle.py`**, with a sample history written by the Kotlin code and kept in the repository, so both programs are tested against the same bytes.

The background question (Q9) is answered for one phone: `lab/results/BACKGROUND_TEST.md`. A 15-minute job was delivered 4 times in 22 h 41 min of a clean window and not at all for the last 21 h 36 min of it. The default stays: no scheduled work.
