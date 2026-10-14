# Stage 2 design: the attack-path engine

Status: written 2026-10-09 after Stage 1 (milestones 1 to 6) was verified on an API 34 emulator, and **built the same day (steps S2-1 to S2-5)**; `DESIGN.md` section 23 records what was measured and section 11 below lists where the build differs from this design. `CONCEPT.md` fixed the scope ("graph model including defensive controls as nodes, minimum fix set, what-if mode"); everything below the scope line is a design decision made without the user in the room. All of them can still be changed; the engine does not depend on any one of them.

## 1. What it adds to the person's life

Stage 1 answers "what holds powerful abilities, and what changed". It does not answer the question a person really has: **"so what could someone actually do with that, and what is the least I can change to stop it?"**

A list of findings treats each item alone. Attacks do not: an app that can read the screen is one thing; an app that can read the screen on a phone whose accounts send codes by text message, is a way into a bank account. The engine reasons over *combinations* (uniqueness property 1 in `CONCEPT.md`), says how sure it is of each link, and answers with the smallest set of changes that cuts the most paths: "Changing these three things removes four of the six paths this app can see."

It must also stay honest. A path exists "given what we could observe and what you told us" (`THREAT_MODEL.md` section 7); a path that depends on something the app could not read is shown as *not ruled out*, never as absent.

## 2. The model

An AND/OR graph over three-valued facts, evaluated by a monotone fixpoint.

**Nodes**

| Kind | Meaning | Example |
|---|---|---|
| Leaf | A fact about the phone or the person, with provenance (observed, declared, inferred), a confidence, and a value of TRUE, FALSE or UNKNOWN | `holds-accessibility(app)`, `no-screen-lock`, `declared: accounts use SMS codes` |
| AND | True when every child is true | reading the screen needs the power *and* its window-content ability |
| OR | True when any child is true | intercepting a code: through the screen, or notifications, or text messages |
| Goal | An AND or OR node that stands for something an attacker gains, tagged with the asset (`THREAT_MODEL.md` AS1 to AS7), the technique ids and a severity per profile | "one-time codes can be intercepted" |

**Defensive controls are leaves written as the vulnerable state** (`no-screen-lock`, `private-dns-off`), so a control that is present makes the leaf FALSE and the monotone evaluator needs no negation. A control that cannot be read (Play Protect) is simply not a node; it appears on the blind-spot map instead.

**Three values (Kleene logic).** UNKNOWN is a real value, not an error: an unreadable signal makes its leaf UNKNOWN. AND is FALSE if any child is FALSE, else UNKNOWN if any is UNKNOWN, else TRUE; OR is dual. A goal that evaluates to TRUE is *a path that exists*. A goal that evaluates to UNKNOWN is *a path this app could not rule out*, shown separately with the names of the unreadable leaves that keep it open. FALSE goals are not shown at all, except in the count.

**Confidence.** A path's confidence is the weakest link: `min` over the children of an AND, and `max` over the TRUE children of an OR (the best-supported way in is the one that counts), as decided in `THREAT_MODEL.md` section 6. Severity is kept separate from confidence and never multiplied into it. A path that uses any DECLARED leaf says so ("this depends on what you told us").

**Levers (the fixes).** A lever has an id, plain words, a trade-off ("a screen reader you use may stop working"), a cost (a small integer: how disruptive, so the engine prefers the gentler of two equal-sized fixes), a settings link, and the set of leaves it makes FALSE. One lever may cut several leaves ("remove this app" ends every power it held). A leaf that no lever can change (where an app was installed from) is a *fact* and not a fix target.

## 3. Evaluating and fixing

`evaluate(graph, applied: Set<Lever>) -> Evaluation` is a pure function: set the flipped leaves FALSE, run the fixpoint, return the value of every goal with a witness derivation for each TRUE goal. **What-if mode is the same function** with a different `applied` set; the screen's toggles are a reducer event.

**Minimum fix set.** Choose levers `L'` with least `(count, cost)` such that no goal in the target set is TRUE afterwards (the target set is the TRUE goals at or above a severity threshold; UNKNOWN goals are not targets, because they are not claims). This is a hitting-set problem over the minimal derivations of the goals and is NP-hard, so:

- **Exact search.** Bounded search tree: find one witness derivation of a TRUE target goal; one of the levers that flips a leaf of it must be in any fix; branch on those, recurse, with iterative deepening on the number of levers. Complete and exact; exponential only in the size of the answer, which on a personal phone is small.
- **Greedy beyond a stated size.** When the search exceeds a node budget the engine returns the greedy answer (largest number of goals cut per lever, ties broken by cost) and **says it is an approximation**. The threshold is named in code and in the screen's wording. Quality of the approximation is tested against the exact answer on graphs where both finish, and the size where exact stops being practical is measured and recorded (`THREAT_MODEL.md` section 10, item 4).
- **Goals no lever can cut** (every derivation rests only on facts) are reported as such: "nothing this app knows how to change would stop this".

**Honest output.** "Changing these three things removes four of the six paths" is computed, not estimated: it is the evaluation with the three levers applied. Paths that remain are listed with why.

## 4. The rule book

The rules are code now (a small Kotlin builder), written to be data later: each rule names its technique ids, the leaves it uses, the levers that cut it and the part of the path the app cannot see. They instantiate per app (an app with accessibility is one instance), and the graph is built from the report and the declarations. First rules, from `THREAT_MODEL.md` section 5:

| Rule | Goal | Leaves it needs | Levers |
|---|---|---|---|
| P1 | Credentials and codes can be captured from the screen | an app holds accessibility with window-content; and either it came from a sideload or unknown source, or the person said they do not recognize it; or it draws over other apps | turn the service off, remove the app, revoke draw-over |
| P2 | One-time codes can be intercepted | (an app holds text-message read, or notification access, or a screen-reading service) and declared: accounts use SMS codes | revoke the grant, move the account to an authenticator (declared lever) |
| P3 | Network traffic can be tampered with | (a user-added certificate authority, or a web proxy, or open Wi-Fi without Private DNS) | remove the certificate, clear the proxy, turn on Private DNS, leave open Wi-Fi |
| P4 | Someone with brief physical access can add access or install | no screen lock; or a device administrator or accessibility service added since the baseline | set a lock; review the new grants |
| P5 | Stalkerware-shaped combination | an app with admin or accessibility, no launcher icon, and a location or microphone grant it asks for | remove the app, revoke grants |
| P6 | A known flaw can be used | patch older than a year (or older than the profile's limit), and Advanced Protection off where it can be read | update, turn Advanced Protection on, replace an unsupported phone |

P7 (a malicious app scanning the local network) waits for the Stage 4 sensors. P8 (SIM swap) is declared-only and appears as a declared lever, with no observation behind it.

**Profiles.** The same engine runs for the general profile and the high-risk profile; only severity weights, the thresholds (patch age limit) and which paths are surfaced differ, so the reuse is checkable.

## 5. What the person tells the app (declarations)

Some paths need facts the phone cannot show. They are asked once, plainly, optional, and stored in the same encrypted file as the baseline: **my accounts send codes by text message** (yes / no / not sure), **I often use public Wi-Fi** (yes / no / not sure), and the profile. "Not sure" makes the leaf UNKNOWN, which keeps the dependent paths open instead of dropping them. Declared leaves are labeled as declared everywhere they appear.

This needs the saved state to grow (version 2) with a migration from version 1 that supplies "not sure" for everything; a damaged file is still reported, never read as empty.

## 6. Typed state for the rules

The rules need the phone's state as values, not as sentences. Stage 1 keeps some facts only as text (Private DNS has no observation at all when it is off). Stage 2 adds `PhoneState` to the report: one `Tri` (true, false, unknown) or number per condition, built where the facts are built, so there is one place that decides, for example, what "no screen lock" means and when it is unreadable.

## 7. Screens

- **Paths** (new, reached from the first screen): "These attack paths exist given what we could observe and what you told us", ordered by severity then confidence. Each path shows the chain in plain words, each link with where it came from (observed, told, inferred) and how sure; "What this app cannot see on this path"; and what could be changed, with the trade-off.
- **Not ruled out**: paths held open by something unreadable, with the unreadable thing named.
- **Smallest set of changes**: "Changing these 3 things removes 4 of the 6 paths", each lever tappable to preview (what-if), with an "exact" or "approximate" label.
- **About you**: the three declarations.

Wording rules from Stage 1 apply unchanged (no score, no "safe", no accusing words); `WordingTest` is extended to every sentence the engine can emit.

## 8. How it will be tested

1. **Evaluator.** Truth tables of the three-valued logic; the fixpoint on hand-built graphs including shared sub-derivations; monotonicity (applying more levers never makes a goal more true); UNKNOWN propagation; confidence as weakest link.
2. **Rule book.** A corpus of about 30 phone states (benign fixtures mirroring the documented campaigns in `THREAT_MODEL.md` section 10) with the expected goals, each reviewed by hand; a clean-baseline phone must raise no TRUE goal.
3. **Solver.** An independent oracle: brute force over all lever subsets for up to 20 levers, compared with the exact search on random graphs (thousands); the fix really blocks every target goal; no smaller fix exists; the greedy answer is never smaller than the exact one and its ratio is measured; synthetic graphs of 300 to 2,000 nodes for time, with the point where exact search exceeds its budget recorded.
4. **Reducer.** The what-if events inside the existing random-sequence property test.
5. **Conformance.** On the emulator, the app's path list for a set of fixture states is compared with an independent evaluation of the same rules written in Python from the system's own readings (`dumpsys`, `settings`, `appops`), not from the app's output.
6. **Accessibility.** The instrumented pass extended to the new screens at both text sizes.

## 9. Order of work

| Step | Content | Done when |
|---|---|---|
| S2-1 | Graph model, three-valued evaluator, confidence and provenance, `PhoneState` in the report | evaluator tests pass; the report carries typed state |
| S2-2 | Rule book P1 to P6, profiles, scenario corpus | corpus tests pass, clean baseline raises nothing |
| S2-3 | Levers, exact and greedy fix search, what-if, oracle and scale tests | oracle agreement on thousands of random graphs; scale numbers recorded |
| S2-4 | Declarations and saved state version 2, reducer events, the four screens | reducer and codec tests pass; instrumented pass extended |
| S2-5 | Emulator conformance against an independent evaluation, documentation, release notes (0.2.0) | conformance passes; `DESIGN.md` section 23 written |

## 10. Decisions left to the user

None of these block the engine; each changes wording or defaults. The build took each proposal as written (marked below).

- Whether the high-risk profile is offered in the first release or only after a review of its wording (it changes severities, and a wrong severity for a journalist is worse than none).
- Whether the declarations stay in the encrypted baseline file (this design) or are never stored and asked each time.
- The patch-age limit per profile (a year for the general profile, six months for high risk are proposals).
- The exact-search budget. Built as 20,000 search steps, which is about one second on a graph of 1,800 nodes on a laptop (measured, section 11); on a phone it has not been timed.

## 11. As built: where the build differs from the design

1. **A fourth provenance, TOLD.** The design said a declared leaf is "labeled as declared". Stage 1 already used DECLARED for an app's own statement about itself (its metadata), and the person's answer to the baseline review carried the same label (a defect, D-039). The person's word is now TOLD ("Told: what you said"), and "this path depends on what you told this app" means exactly that.
2. **Advanced Protection on a version that has none is FALSE, not UNKNOWN.** There is no mode to turn on, so its absence is not a weakness this app could point at. Unreadable for any other reason is UNKNOWN.
3. **The search budget is 20,000 steps, and the search is array-based.** The first version evaluated the whole graph at every step and was too slow on hard instances (D-040). The measured table is in `DESIGN.md` section 23. A lower bound from witnesses that share no usable lever prunes the exact search.
4. **Paths are per app.** An app is one instance of P1, P2 and P5, so "removes 4 of 6" counts per-app paths, and one lever ("remove this app") ends every power the app held.
5. **Declarations are stored in the baseline file** (saved state version 2, with a migration from version 1), and the higher-risk profile is offered on the About you screen from the start.
6. **A network settings target** (`SettingsTarget.NETWORK`) was added so "Turn on Private DNS" opens Network and internet settings and not Wi-Fi settings.
7. **With every question left at "not sure" the not-ruled-out list is not empty on most phones** (P2 and P3 depend on a question). That is the intended behavior and it is the screen's first call to answer the questions, not a defect; a phone with Private DNS on and no unfamiliar apps raises nothing.
8. **The independent check shares an author with the engine.** `paths_oracle.py` re-derives the rules from this document and from what `adb` reports, with its own logic and a brute-force search; it catches an implementation that disagrees with the rules, not rules that are wrong.

