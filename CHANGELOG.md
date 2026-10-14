# Changelog

The project was called Candor while the name was a working name. It was renamed Declared on 2026-10-11, before any release: the application ID is `io.github.h_sib.declared`, the code packages are `io.github.h_sib.declared.*`, and the planned repository is `H-sib/declared`.

## 0.3.0 (unreleased)

Stage 3, "history and tripwires". Verified on emulators for Android 10 and 12 to 17 (`DESIGN.md` sections 24 and 25).

**Redesign (on top of Stage 3).** The first look is a short visual introduction, then a home screen with one statement, a lit phone whose six tiles are real readings, and one button. Five tabs (Home, Check, Paths, History, More) replace the long single screen. The palette is navy and cool cyan, with status colors that always come with a shape and a word. Every detail is in three layers: the finding first, why it matters second, the evidence and method third ("Nerd info", laid out as a spec sheet). Long texts are shown one sentence per line, and a **plain-words decoder** (`core/words/Glossary.kt`) marks jargon and explains it in a line, on the spot and in a list under More. Nothing was removed and no check changed. The accessibility pass runs at normal and double text size over every new screen.

**After the first testers.** Finding titles say what is at stake instead of that a setting is on ("Gboard is your keyboard, so it sees everything you type" in place of "Gboard is a keyboard that is switched on"); the first review asks once per app instead of once per ability (an app with a screen reader and notification access was asked about twice); the intro is a longer animated sequence; screenshots are allowed by default and a "Block screenshots" switch under More hides the app from screenshots, recordings and the recent apps list for people who want that, in every build.

**Also in 0.3.0.** A **tour** ("How to use Declared") offered on the home screen to a new person and always under More. A **simple view** (a switch on the intro pages and under More): text a fifth bigger, three places instead of five, each thing as one card with what it means in one sentence and one big button for what to do, the full layers behind "Technical details". A **plain-words decoder** for jargon. **Automatic checks**, off until turned on: a repeating job at an interval the person chooses (presets or any number of minutes, hours or days, from 15 minutes to 365 days), one notification that says how many changes need a look and never which app, never repeated for the same change; it reads and tells, and changes nothing. This adds two permissions, notifications and run at startup, both used only by this feature, and the manifest gate allows exactly these two more. A short animated **intro** of the logo and the reason for the name, shown once and replayable under Help. A **logo kit** in `brand/`: the base H template for every H-sib app, the premium Declared icon and mark in SVG and PNG, and a brand sheet.

- A **history** of the phone: every time the app looks, it can add an entry that holds only what changed since the one before. Entries are linked by hashes and each is signed with a key kept in the Android Keystore, so an entry changed, moved or removed from the middle shows, and so does a history rewritten from scratch under another key. It is kept encrypted on the phone in a file of its own.
- A **timeline** screen: what changed and when, with the stretches in which the app was not looking shown as gaps, never as quiet.
- **Tripwires**: two Keystore keys that Android destroys by itself, one when a fingerprint is added and one when the screen lock is removed. When the app next opens it says which happened. It says it may have been you, and that a changed PIN trips neither.
- A **check of the history** on demand: hashes worked out again, the changes replayed to the snapshot each entry recorded, every signature tested, and the history's key compared with the key this phone holds. The result lists what was checked and what this cannot see.
- The limit that cannot be removed from inside the phone is stated on the screen: someone with access to the app's files could cut off the newest entries without a trace, so the latest entry's fingerprint is shown to be written down.
- No new permission, no scheduled or background work, nothing leaves the phone.
- An independent verifier (`product/tools/ledger_oracle.py`, `hashlib` and OpenSSL, no shared code) agrees with the app on a signed sample history the app wrote, and checks the history file on the emulator.

## 0.2.0 (unreleased)

Stage 2, "the attack-path engine". Verified on one Android 14 emulator (`DESIGN.md` section 23).

- A new screen, **Attack paths**: the ways someone could get at what matters to you, given what the app could read on the phone and what you told it. Each path shows the chain of facts with where each came from (seen on the phone, the app's own statement, what you told it, worked out) and how sure the app is of each link, what the app cannot see on that path, and what would stop it. Paths that depend on something the app could not read, or on a question you have not answered, are listed as **not ruled out**, never dropped.
- The **smallest set of changes** that stops every path that can be stopped, with what each change costs. It is proven smallest when the search finishes and says so when it does not. A **preview** shows what a change would do and changes nothing on the phone.
- A screen **About you** with two questions only you can answer (do your accounts send sign-in codes by text message; do you often use public Wi-Fi) and a profile. The answers are kept encrypted on the phone with the baseline.
- Six rules from the threat model: an unfamiliar app that can read the screen; one-time codes taken through messages, notifications or the screen; an added certificate authority, a web proxy, open or public Wi-Fi without Private DNS; no screen lock; the shape of an app made to watch someone; an old security update, and Advanced Protection off where the phone has it.
- Saved state version 2 (version 1 files are still read). The person's own answers are now labeled as theirs, not as "the app's own statement". The first-run and "could not read the phone" screens scroll at large text sizes.

## 0.1.0 (release candidate, not published)

First version of Stage 1, "glass-box foundation". Verified on one Android 14 emulator; other Android versions and a physical phone are not yet covered (see `DESIGN.md` sections 18 to 22).

What it does:

- Reads accessibility services, notification access, device administrators, keyboards, drawing over other apps, usage access, installing other apps and text-message access, and lists every app it can see by where it came from.
- Reads a few of the phone's own settings and its network configuration, including Wi-Fi security type and certificate authorities added by a user.
- Judges the phone's key attestation chain on the phone, against bundled and checksummed Google reference data.
- Starts with a review: the person says which powerful apps they recognize. The answers and a snapshot are kept encrypted on the phone. Later, changes are listed against them, including an app that is now signed by a different certificate, and stay listed until acknowledged.
- A live view hears settings and app changes while the app is on screen and says that it hears nothing otherwise.
- A map of what the app cannot see, on every phone and on this one.
- Screens are kept out of screenshots and the recent-apps preview.

What it does not do: no score, no "safe", no internet permission, no account, no analytics.

Known limits are in `DESIGN.md` ("Not verified yet" at the end of each milestone section) and on the app's own "What this app cannot see" screen.
