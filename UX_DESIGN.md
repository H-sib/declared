# Declared: product and interface design

Status: proposal for approval, written 2026-10-11 while the Android version matrix runs. Nothing here is built. No source or build file was touched, so the running tests are undisturbed.

It answers the ten points of the owner's brief in order, then lists the few decisions that need the owner. It builds on what exists (`DESIGN.md`, `STAGE2_DESIGN.md`, `STAGE3_DESIGN.md`) and does not rebuild it.

## 1. What exists today

- **Stack:** Kotlin, a pure `core` (all logic, tested on the JVM), `sensors-android` adapters, and a Jetpack Compose app of about 1,030 lines of UI. The UI is stock Material 3 with wallpaper-derived colors and the default type scale. That is exactly the generic look the brief rejects.
- **The first screen** is one long scroll of about fourteen sections: changes and review, live view, attack paths, history, findings, notes, idle holders, fact groups, all apps, blind spots. It is crowded because it was the place to put things, not a design.
- **Already true to the brief:** no score and no "safe" (three-valued logic, confidence, "what this cannot tell" on every finding); a map of what the app cannot see; a signed, tamper-evident history and tripwires; no internet permission, enforced by the build; encrypted local storage; every reading checked against the system's own answer on seven Android versions.
- **Missing:** an assessment that the person starts and watches; a plan shown before it runs; basic and technical report views; export; one visual identity; a light theme made on purpose; a home that makes one statement instead of listing everything.

| Brief point | State | Work |
|---|---|---|
| 1 Uncluttered, distinctive home | missing | redesign (stage 4a) |
| 2 Transparency during a test | partly (evidence and limits exist per finding) | plan, live run (4b) |
| 3 Honest results, no invented score | done in substance | coverage statement, wording (4b) |
| 4 Basic and Advanced reports | missing | two views over one report, export (4c) |
| 5 Consent and privacy | not needed for V1 data, see D1 | plan screen, export screen, erase button (4b, 4c) |
| 6 Live view and history | done (live view, ledger) | present them better (4a) |
| 7 Business | none yet | see section 9 |
| 8 Visual identity | missing | section 7 |
| 9 Small V1 | this document | section 8 |
| 10 Review first, then build in stages | this document | section 8 |

## 2. Where this design disagrees with the brief

**D1. Version 1 collects nothing, so it needs almost no consent machinery.** Every check is a passive, local read. A consent dialog before a read that only the person's own phone ever sees is theatre, and theatre erodes trust. So V1 has: a plan screen before each assessment (what, why, which data, what it cannot see; nothing is sent anywhere), a real permission request only if a check ever needs a runtime permission (none does today), an explicit export screen, and an "Erase everything" button. Analytics, research sharing, uploads and accounts are not in V1. The app keeps its absent internet permission, which the build enforces; that is a promise a reviewer can verify in one line. If sharing ever exists it should be a separate companion app with its own manifest and privacy policy, so this app's promise stays checkable.

**D2. No percentage, ever.** There is no defensible method behind "37% at risk", so any such number would be invented. The honest quantity is coverage: "21 of 24 checks ran. 2 could not run. 1 is not supported on Android 10." Plus counts by state. The same reason keeps "safe", "secure" and "protected" out of the product.

**D3. No neon cyberpunk.** Black plus glowing green, cyan or violet, monospace everywhere, scan lines and matrix rain are the cliché that reads as generated and as untrustworthy, and it is what a "hacker-themed" page looks like. The cyber feel here comes from real artifacts instead: hash fingerprints drawn as glyphs, evidence identifiers in a clear monospace, ledger rules, a hairline grid, one signal color. Gen Z appeal comes from craft (type, spacing, motion with a reason), not from theming.

**D4. "Live monitoring" is limited by Android, and the app says so.** Measured on devices (`FINDINGS.md` Q7, Q9): an app that is not open gets no change events, and on the one phone tested a background job ran 9 times in 229 expected. So: live progress for assessments the app runs; live changes only while on screen; the history shows gaps as gaps. Never "continuous protection".

**D5. Basic and Advanced are two views of one report, and Advanced is different content, not more of the same.** Advanced adds method, source API, timestamps, raw evidence, coverage matrix, filters and export.

**D6. Selling user data is off the table.** See section 9.

**D7. The redesign breaks the UI-driven conformance scripts**, which find buttons by their text. Accepted. Labels stay stable where design allows, and each screen's change updates its scripts in the same step.

## 3. Information architecture

One home, three doors, one overflow. No bottom bar (it would turn three doors into permanent chrome).

```
Home
  Statement   what is known about this phone right now, in one sentence
  Coverage    "21 of 24 checks ran" with the three unusual states named
  [ Check this phone ]     starts an assessment (Plan, Run, Result)
  Door 1  Check     last result, run again
  Door 2  Paths     ways in that combine what is on the phone with what you said
  Door 3  History   signed record, what changed, tripwires, the hash glyph
  Overflow (top right): Apps, Blind spots, About you, Live view, Export, Privacy, Erase, About
```

Home shows at most: the statement, the coverage line, the primary action, three doors, one line of "changed since last time" if anything did. Everything else is one tap down. Each door opens a screen that owns its topic completely (its tests, settings, evidence), so no feature has to share the home.

**The statement** is generated from real findings in plain words, in the display face: "One app can read your screen. It arrived on Tuesday." or, when there is nothing: "Nothing was found by 21 checks. Three could not run." It never says the phone is fine.

## 4. The assessment

1. **Plan.** The checks, grouped (Powers, Apps, Phone settings, Network, Integrity). Each row opens to: what it reads, why, which data it touches, any permission, whether anything leaves the phone (never), what it cannot see. A single "Start" button. This is the transparency surface, so its text comes from the same source metadata the findings already use, not from separate marketing copy.
2. **Run.** One row per check with a state taken from the real reader: waiting, reading, done with a count, could not run with the reason, not supported with the reason. A sweep marker runs only on the row that is actually reading. Elapsed time is measured. Cancel works. Findings appear under their rows as they arrive. This needs one real change in `core`: the report builder announces each source as it starts and finishes. No timer-driven bar, no fake percentages.
3. **Result.** Coverage first, then findings by attention level, then "could not check" and "not supported" with reasons, then "nothing found by these checks" collapsed. Tapping a finding opens the existing detail screen (evidence, source, confidence, limits, actions).
4. **Safety note.** A finding shaped like monitoring software carries one extra paragraph: removing it can alert the person who installed it, which can be dangerous for someone in an abusive situation; the paragraph points to a specialist helpline rather than telling them to uninstall. This is a product risk the brief does not mention and the legal list below includes.

## 5. Reports

| | Basic | Advanced |
|---|---|---|
| Reader | anyone | developers, researchers |
| Content | statement, top findings in plain words, what to do, coverage line | per check: id, method, source API and minimum Android, timestamps, evidence, confidence, limits; coverage matrix; device and OS facts |
| Controls | none | filter by state and area, search, expand all |
| Export | share as text | JSON (versioned, stable keys); CSV later |
| Redaction | not applicable | toggles for app names, signer hashes, device identifiers; the export screen lists what the file contains before the share sheet opens |

Both read the same `Report`, so the simple view cannot contradict the technical one. Export goes through Android's share sheet: the person chooses the destination and the app never sends anything.

## 6. Privacy and data architecture (Version 1)

| Data | Where | Leaves the phone | Kept | Removed by |
|---|---|---|---|---|
| Baseline and answers | encrypted file (AES-256-GCM, Keystore key) | no | until erased | Erase everything, clear storage, uninstall |
| History ledger | second encrypted file, signing key in Keystore | no | until erased | same |
| Tripwire keys | Keystore, hold no data | no | until erased | same |
| Live buffer | memory | no | until the app closes | closing the app |
| Exports | wherever the person puts them | only by the person's action | the person's choice | the person |

Play Data safety form: no data collected, none shared. No third-party SDKs. Crash reports, if wanted, are text the person chooses to send. Privacy by design here means there is nothing to protect in transit because nothing is in transit.

## 7. Visual identity

**Idea.** Declared is about the gap between what is declared and what is true. The visual language is a *record*: documents, ledgers, seals. The mark is two halves of one token split along a stepped line (the old way to prove a record was not altered: only the true halves fit). When history verifies, the halves sit aligned. Cyber without costume.

**Color.** Two themes designed on purpose, not Material's dynamic colors.

| Role | Ink (dark) | Paper (light) |
|---|---|---|
| Background | `#0E1116` | `#F4F1EA` |
| Surface | `#151A21` | `#FBF9F4` |
| Text | `#E9E5DA` (15.0:1) | `#14181D` (15.8:1) |
| Muted text | `#9BA4AF` (7.5:1) | `#55606B` (5.7:1) |
| Signal (one accent) | `#F0A53A` (9.1:1) | `#9A5600` (5.0:1) |
| Hairline | `#2B3440` | `#CFC8B9` |

All text pairs pass 4.5:1; contrast figures are against the background and were computed, not guessed. Meaning is never carried by color alone: **Look first** is a filled square with its label, **Look** an outlined square, **Note** a dot, **Could not check** a dashed outline, **Not supported** a struck marker. No red alarm palette; one amber.

**Type.** Atkinson Hyperlegible Next for text and Atkinson Hyperlegible Mono for evidence (SIL Open Font License, bundled in the app so nothing is fetched). Made for legibility, with letterforms that tell 0 from O and 1 from l from I, which is what hashes, package names and signer fingerprints need. Not a trend face, not a default. Scale in sp: 12 labels, 14 secondary, 16 body (line 24), 20 titles, 26 section statements, 32 the home statement (line 40). Weights 400, 500 and 700 only; no light weights, no gradient text, no oversized hero. Counts use tabular figures. Uppercase is used only for state labels, with 4% letter spacing.

**Motifs, each backed by real data.** The *hash glyph*: a small symmetric pattern drawn deterministically from the ledger head hash, shown on History and on exports; it changes exactly when the history does. Evidence identifiers such as `F-0417`. A hairline grid in the header band only, fading out. Ledger rules between list rows instead of cards.

**Motion.** Three kinds only: the sweep on the row that is really reading; a state change on a row (120 ms fade); screen transitions (180 ms). Reduced-motion setting replaces the sweep with a static marker. Nothing floats, pulses or glows.

**Rules that keep it from looking generated.** No gradient or blur surfaces. No glow. No icon-in-a-circle cards repeated three times. No stock illustration. No emoji as icons. No pill shapes on everything. No "AI" labels. No progress that is not a real reading. No number that is not a measurement. Custom line icons, one stroke width, about six of them.

## 8. Version 1 scope and stages

**Essential (V1):** the new home and navigation; plan, live run and result; coverage statement; basic and advanced views; JSON export with redaction; erase everything; both themes; fonts; the existing history, paths and blind spots moved into the new frame; accessibility pass and copy review; privacy page and store listing.

**Next stage:** CSV, comparing two points in the history, charts, tablet layout, specialist checks, scheduled checks only if the platform and the permission rules allow it.

**Later, only with a concrete reason:** accounts, cloud sync, organization features, an API.

| Stage | Content | Check |
|---|---|---|
| 4a | tokens, fonts, both themes, home, navigation, existing screens re-framed, no logic change | JVM tests, accessibility pass, scripts updated, screenshots |
| 4b | progress events in `core`, plan, run, result, coverage | unit tests for events, conformance compares rows with real reader outcomes |
| 4c | basic and advanced views, JSON export, redaction, erase | export file validated by an independent parser |
| 4d | accessibility at two text sizes, copy review, release build with the real key | full suite on the version matrix |

Release gate: application ID set (done), signing key made, matrix green, name search done.

## 9. Business, without spending trust

The app is open source (Apache-2.0), so paywalling features inside the same code is awkward: anyone can build the free version. Options that fit: sponsorship and donations; an organization offering (fleet summaries, signed attestation of a phone's state for a company's records) built as a separate paid service; commercial support and consulting; the app as the proof of skill. **Data:** do not sell device data or findings. Findings about a person's phone are about as sensitive as data gets, "anonymized" device data is often re-identifiable, and the app's whole promise is that nothing leaves. If research data is ever wanted: a separate opt-in companion app, aggregate counts only, a published schema, an ethics review, and a lawyer. Datasets with real value (which Android versions hide which powers; how often power management delays background jobs) can be produced from the project's own lab measurements and published openly, which is the better asset for a reputation than a data business.

## 10. Needs a professional, not a guess

Privacy policy and Play Data safety wording for each country of distribution, including the home country's data protection law (even a no-collection app needs a correct policy). Claims review: nothing may say or imply protection, detection of malware, or completeness. Liability limits in terms of service, and what they can and cannot do. The stalkerware safety paragraph and helpline references. Cryptography export classification for open-source distribution. Trademark clearance for the name (a search is not a clearance). Android developer verification and Play account rules. Anything that ever collects research data from people (ethics review, consent, retention).

## 11. Decisions needed from the owner

1. Identity: **Ink and amber, record language** as above, instead of neon cyberpunk. (Recommended.)
2. Permission to download the two font files (Atkinson Hyperlegible Next and Mono, SIL OFL, from the Braille Institute's repository on GitHub, a few hundred kilobytes) to bundle in the app.
3. V1 stays fully offline: no analytics, no accounts, no research sharing.
4. The three doors: Check, Paths, History, with everything else under the overflow.
