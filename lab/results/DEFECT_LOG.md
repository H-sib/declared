# Defect log

Real defects found while building and using the lab. Each entry: what broke, why the first idea was wrong or incomplete, how the fix was confirmed. Kept deliberately honest; see `../../CONCEPT.md` (quality bar).

## D-001 Gradle fails with "Unable to establish loopback connection" (2026-10-08)

- **Symptom:** the first Gradle build failed before compiling anything.
- **First idea (wrong):** an IPv4/IPv6 resolution problem. Forcing `-Djava.net.preferIPv4Stack=true` changed nothing.
- **Narrowing:** plain blocking sockets and NIO connects worked; only `Selector.open()` (which builds a loopback pipe) failed. Unix-domain sockets failed in the user's `TEMP` directory but worked in a normal path.
- **Root cause:** this JDK builds its selector pipe from a Unix-domain socket under the temp directory. The machine's `TEMP` is the Windows 8.3 short path `C:\Users\LORDHA~1\...`, which Windows rejects for Unix-domain sockets.
- **Fix:** `tools/env.ps1` points `TEMP` and `TMP` at a normal long path for the session. Confirmed: `Selector.open` succeeded and the build passed (BUILD SUCCESSFUL, 8 parser tests, 0 failures).

## D-002 One restricted read hid the rest of a signal (P5) (2026-10-08)

- **Symptom:** P5 (input methods) came back SECURITY_EXCEPTION with no data.
- **Cause:** `Settings.Secure.ENABLED_INPUT_METHODS` is readable only to apps targeting API 33 or lower; this probe targets 36. The exception aborted the whole reading, discarding the readable parts (default input method, installed list).
- **Fix:** each part is now read independently, so a restricted source degrades the signal instead of erasing it. The hypothesis "enabled input methods are readable" is refuted for targetSdk above 33.

## D-013 Two watcher-timing conditions did not test what their names said (2026-10-08)

- **Symptom:** in the first watcher run, "deep idle" showed the same fast, complete delivery as "app in front", and "screen off" gave a strange mix (settings changes delayed 6 to 10 s, package events fast).
- **Cause:** the setup for those two conditions forced the idle flag and put the screen to sleep, but the probe app had just been launched and was still the foreground app. "Deep idle" was really "app in front with the idle flag set", and "screen off" was "foreground app, screen off". Neither condition had the app in the background, which is the whole point.
- **Fix:** every background condition now starts by sending the app home, waits for it to settle, and reads the process state back from the system; the complete event log and process state are saved per condition (`results/raw/watch-*.log`) so an odd result can be explained from evidence. The first run's CSV was archived, and only its "app in front" and "process killed" rows were valid.

## D-012 The second fingerprint enrollment never opened on Android 16 and 17 (2026-10-08)

- **Symptom:** on Android 16 and 17 (and intermittently on Android 15) the second fingerprint never registered, three times in a row, while the first always did. The K6 cell on those versions showed "valid" only because no fingerprint had been added.
- **First idea (wrong):** a new "Add fingerprint" button that the driver did not know. I added a pattern for it, which changed nothing.
- **Cause (found from the step trace and the system's own message):** the trace showed my probe app's own screen for all 90 steps, so the enrollment screen never appeared, and `am start` printed "Activity not started, its current task has been brought to the front". The first enrollment leaves a stale Settings task on those versions (a "Fingerprint added" screen with ADD ANOTHER and DONE buttons that my cleanup never dismissed), and the next launch only resurfaced that task.
- **Fix:** each enrollment now force-stops Settings and launches into a fresh task (`am start -S`, NEW_TASK and CLEAR_TASK), and discards the Settings task afterwards, with a test for the command. On Android 16 the second fingerprint then registered (count 1 to 2).
- **What it invalidates:** the K6 cells recorded for Android 16 and 17 (and the second Android 15 run) before this fix did not exercise the fingerprint test. Those runs were redone.
- **Lesson:** the decisive evidence was a trace I turned on only after two guesses failed. For any automated step, log what the system says, not only what the script intended.

## D-011 The fingerprint driver mistook an intro screen for a sensor prompt (2026-10-08)

- **Symptom:** on the Android 10 emulator, enrollment of the first fingerprint failed in two separate sweeps, even after I taught the driver to press buttons that are not marked clickable. A screenshot showed an ordinary "Unlock with fingerprint" screen with a visible NEXT button.
- **First idea (wrong, twice):** Android 10 does not mark its buttons clickable, so a text fallback would fix it. It did not.
- **Cause (found by running the driver verbosely against the real emulator):** the driver classified the screen as "sensor" on every step. The screen's description says "Just touch the fingerprint sensor to unlock your phone", which matched my sensor-prompt pattern, and the sensor check ran before the button check. So it sent finger touches forever and never pressed NEXT.
- **Fix:** the sensor pattern now matches only screens that are really waiting for a finger; regression tests cover the intro text and the real sensor screens. Enrollment then worked on Android 10 in four steps.
- **Lesson:** I guessed at the cause twice before running the tool step by step and looking at its own decisions. The verbose trace found it in one run. The same overly broad pattern may also explain the flaky second enrollments on Android 15 to 17, which will be checked the same way.

## D-010 Intermittent silent failures in the second tripwire run (2026-10-08)

- **Symptom:** after fixing D-009, a rerun still returned nothing for every probe request before the reboot, and the second fingerprint did not register. Reproducing the early phase by hand worked, so the failure was intermittent.
- **Hypothesis (not proven):** the emulator's display went to sleep during the long waits, so the screen-driven steps and the activity launches had nothing to act on. I did not capture a failing screenshot at the time, so this is the most consistent explanation, not a confirmed cause.
- **Fix:** keep the display awake for the whole run (`svc power stayon true` plus a long screen timeout), wake before each request, retry once on a miss, and capture a screenshot and the foreground activity whenever a request still fails. The third run completed with every step answered and zero failure captures.
- **What the partial run still gave:** its post-reboot rows (removing the lock destroys the keys; creating keys without a lock is refused) matched the clean run, which is some evidence that those two results are stable.

## D-009 The tripwire harness typed a PIN into the foreground app (2026-10-08)

- **Symptom:** in the first emulator tripwire run, the fingerprint step (K6) gave clean results, but every reading after the PIN change returned nothing, even the control steps. The probe was alive and had received the request.
- **First idea (wrong):** the Keystore call hangs after a lock change, so the probe's single worker thread was stuck.
- **Cause (found by looking at the screen):** a location-permission dialog from the probe was in the foreground. My `unlock()` always swiped and typed the PIN plus Enter. Changing a PIN does not lock the phone, so the keystrokes went into the probe itself: the Enter key pressed its first button ("Request permissions") and opened the dialog, which blocked every later request.
- **Fix:** unlock only when the keyguard is really showing (checked with the system, with a parser and tests); press Home and restart the probe before every request. The experiment was rerun from a clean state. The thread-stuck hypothesis was wrong and is recorded as such.
- **Lesson:** a harness that automates input must verify the state it assumes, the same lesson as D-004 and D-006.

## D-008 The first attestation verifier rejected a genuine phone's chain (2026-10-08)

- **Symptom:** on the Xperia, a chain whose every link verified, that ended at a Google root and had no revoked certificate was reported "NOT TRUSTED", because three certificates had expired.
- **Cause:** I treated expiry as a failure everywhere. Google's documentation (read earlier in the session) says devices launched before 2021 carry factory-provisioned certificates that are expired but remain trustworthy unless revoked, and that only chains from remote key provisioning must be within their validity period. The emulator images could never expose this; only real hardware did.
- **Why it matters for the product:** a device-side verifier that does not special-case the legacy root would falsely accuse every pre-2021 phone, including one that is correctly locked and verified. The distinction ships in the product, with tests.
- **Fix:** the verdict now knows whether the chain hangs off the legacy root (detected from the root's serial-number attribute, in either of the JDK's spellings). Expiry is reported but accepted there; revocation is still enforced; non-legacy chains still require validity. Four regression tests.

## D-007 Redacted runs were compared with plaintext truth and reported false mismatches (2026-10-08)

- **Symptom:** the first redacted run on the Xperia reported two mismatches (P3, P5) where the probe value looked like `h:5e14c6ce24`.
- **Cause:** redaction replaces identifiers with short hashes by design, but the comparators still treated the hashes as real values.
- **Fix:** redacted runs are marked not comparable (`n/a`, with a note), with a regression test. The two stored rows were corrected by hand in the physical-device CSV.

## D-006 The cleanup path assumed adb could remove a device admin; production firmware refuses (2026-10-08)

- **Symptom:** on the Xperia, the "disable all fixtures" step reported success for everything, but a system check showed the fixture device admin still active. `dpm remove-active-admin` prints nothing and exits non-zero.
- **Cause:** the emulator images are userdebug builds, where the shell may remove any admin. On a production build the shell refuses non-test admins: "Attempt to remove non-test admin". My setup command hid that, because I did not check the exit code or the real state.
- **Consequence:** an active device admin also blocks uninstalling its app, so the `restore` command would have failed halfway. The admin's only policy is `force-lock`, so the exposure is small, but it has to be deactivated by the user in Settings.
- **Fix:** fixtures are now `testOnly`, which lets the shell remove an admin on production firmware (they are installed with `adb install -t` already). Takes effect when the fixtures are rebuilt after the sweep. The lesson, same as D-004: after any state change, verify the state with the system, never trust a quiet command.
- **Also:** the settings snapshot saved on the PC contained device identifiers (`android_id`, Bluetooth address and name, device name). They are now redacted in the stored file and by default in `settings-dump`, with tests.

## D-005 One refused read erased the whole tripwire result on the first physical phone (2026-10-08)

- **Symptom:** on the Xperia, the K6 tripwire-create reading came back SECURITY_EXCEPTION with no data: "Must have USE_BIOMETRIC permission".
- **Cause:** asking `BiometricManager.canAuthenticate` needs the normal permission `USE_BIOMETRIC`, which the probe never declared. The exception was thrown while building the result, after the keys had already been created, so everything was lost. This is the same shape as D-002: one restricted read must degrade a signal, not erase it.
- **Why it matters for the product:** reading biometric state at all costs a declared permission. It is a normal, no-prompt permission, but it shows in the app's permission list, so the product should ask for it only if the tripwire stage ships.
- **Fix:** the permission is declared and the biometric state is read defensively. Emulators never exposed this because the failing code path ran only on the physical device. Verification pending the next probe rebuild.

## D-004 The sweep never enabled the fixtures, and the results still "matched" (2026-10-08)

- **Symptom:** after sweeping API 29 to 36, every ground-truth comparison matched, yet the probe reported no device admins on API 31, 33, 35 and 36 while API 34 (set up by hand) showed the fixture admin.
- **First idea (wrong):** a real platform difference, such as device admins being hidden on those versions.
- **Root cause:** in `sweep.ps1` the role list was written as `a11y, notif, admin, ime, perms`. PowerShell turns a comma-and-space list into separate arguments for a native command, so the runner's `setup` rejected it. I had also piped its output to `Out-Null`, hiding the error. The "all fixtures enabled" runs therefore ran with nothing enabled.
- **Why nothing caught it:** probe and system truth were both empty, so they agreed. A comparison is vacuous when setup silently fails.
- **Fix:** the role list is one quoted argument and setup errors are logged. More importantly, the runner now verifies the real state with the system itself before trusting any comparison: `role_present`, `check_expectations` and `SETUP:<role>` rows in the CSV, inferred from the run label, with unit tests that include the silent admin failure. The invalid matrix was archived and the full sweep rerun.

## D-003 Ground-truth comparator was wrong, not the probe (P7) (2026-10-08)

- **Symptom:** P7 reported a mismatch: probe said `READ_SMS` is allowed (0), truth said default (3).
- **First idea (wrong):** the probe reads the wrong app-op.
- **Cause:** `appops get` omits runtime-permission-backed ops until they are first used, so my comparator treated "not listed" as "default". The permission was granted, so "allowed" was correct and the probe was right.
- **Fix:** the comparator now treats a granted backing permission as allow. A regression test pins the behavior. The first sweep rows were regenerated after the fix.

## Product defects (Stage 1, milestone 1)

The entries below were found while building the product in `../../product/`. They continue the numbering above.

## D-014 A test that could not fail (wording rules) (2026-10-09)

- **Symptom:** the first run of `WordingTest` failed on `'safe' in: A store listing is not a safety guarantee.`. That was a real over-strict rule (a prefix match banned "safety", an honest disclaimer). While fixing it I rewrote the regexes through a shell edit, and the rewritten test passed.
- **Cause of the second problem:** my editing tool turned the regex escapes `\b` into a single `\b`, which is the backspace character in a Kotlin string, and turned the dash escapes into literal dash characters. The banned-word check therefore matched nothing and the test passed vacuously. This is the same shape as D-004: a comparison that cannot fail.
- **How it was caught:** I printed the exact bytes of the changed lines (`repr`) instead of trusting a green result.
- **Fix:** the checks are written without backslashes (lookarounds and character codes), the rule is split into "prefix" words and "whole word" words, and a self-test `theCheckersCanActuallyFail` proves each rule matches a positive example and spares a negative one. Result: 6 wording tests, all of the generated text for 180 finding shapes checked.
- **Lesson kept:** after editing a test, make sure it can still go red.

## D-015 The manifest policy gate flagged its own documentation (2026-10-09)

- **Symptom:** `verifyManifestPolicyDebug` failed on the real manifest with "contains QUERY_ALL_PACKAGES".
- **Cause:** the check searched the raw text, and the manifest comment says "no QUERY_ALL_PACKAGES".
- **Fix:** comments are stripped first; only real elements count. Confirmed both ways: the real manifest passes, and adding `INTERNET`, `QUERY_ALL_PACKAGES`, a foreground-service permission, or removing a `<queries>` entry each fails the build with a message naming the problem.

## D-016 Two services of one app looked identical on screen (2026-10-09)

- **Symptom:** the emulator's inventory listed "Android Accessibility Suite" twice. TalkBack and Select to Speak are two services of one app, and the row showed only the app's name.
- **Why the 53 passing checks missed it:** they compared sets of components with the system, which were correct. The defect was in what a person sees, found only by reading the screenshot.
- **Fix:** the sensor now carries the service's own label, every finding has a `subject` line with the label and the full component, and idle rows show "App: Service". A unit test pins it, and the conformance run compares the service label with the label in the APK's manifest. The screen now shows "Android Accessibility Suite: TalkBack" and "...: Select to Speak".

## D-017 The on-screen check could not see below the fold (2026-10-09)

- **Symptom:** 3 of 6 detail-screen checks failed ("How sure I am", "What I cannot tell", "What you can do").
- **Cause:** a screen dump contains only what is on screen. Those sections were further down; the app was right and the check was blind.
- **Fix:** the check scrolls, merges what it sees, and stops when nothing new appears. A second bug in the same script (misuse of the UI helper's return type) crashed the run before any result was written, so no partial CSV was kept. All 6 checks pass.

## D-018 The sensors library depended on a manifest entry that lived in a different module (2026-10-09)

- **Symptom:** lint reported `QueryPermissionsNeeded` for the library, plus three app-level warnings (missing legacy backup rules, no launcher icon, no monochrome icon layer).
- **Why it was real:** the package-visibility `<queries>` block sat in the app manifest while the sensors that need it lived in the library. Any other app using the library would have seen almost nothing on Android 11 and later, with no error.
- **Fix:** the block moved into the library manifest, and the app build now fails if a required entry goes missing. Lint is now enforced (`warningsAsErrors`); the one disabled rule is `OldTargetApi`, because targeting API 36 is Google Play's requirement for new apps until 2027.


## Product defects (Stage 1, milestone 2)

## D-019 The inventory screen scanned the whole holder list for every finding card (2026-10-09)

- **Symptom:** none yet on a real phone. Found while designing the scale test: the M1 screen called `holders.firstOrNull { it.findingId == f.id }` once per card, which is quadratic in the number of holders.
- **Fix:** `Report` builds its lookups once (`finding(id)`, `holderOf(findingId)`), and the screens use them. A test checks all lookups for a synthetic phone with 1,000 apps.

## D-020 The first scale test passed with a quadratic scan injected (2026-10-09)

- **Symptom:** to prove the scale test could fail, I injected a per-app linear scan into the report builder. The test still passed.
- **Cause:** it compared 1,000 with 5,000 apps, and at those sizes fixed costs hid the quadratic term. The same shape as D-014: a guard that cannot go red.
- **Fix:** it now compares 4,000 with 20,000 apps. Measured: correct code 27 ms and 81 ms (ratio 3.0); with the scan injected 81 ms and 8,446 ms (ratio 104) and the test fails. The injected code was reverted and the revert checked.

## D-021 The wording test scanned machine identifiers instead of what the user reads (2026-10-09)

- **Symptom:** after adding device-admin findings, the banned-word check flagged `wipe` in the identifier `wipe-data`.
- **Cause:** the test scanned `Value.toString()`, which contains internal keys. The user never sees them.
- **Fix:** it scans `Describe.observation(...)`, the text that is actually shown. The word list is unchanged and the self-test that proves each rule can fail stays.

## D-022 Declared device-admin policies could never be read (2026-10-09)

- **Symptom:** the first milestone 2 conformance run: 137 of 141 checks passed; "declared policies = XML resource: expected 8, got None". The fixture admin declares `force-lock`, the product reported unknown policies.
- **Cause:** `queryBroadcastReceivers` returns no metadata unless asked for `GET_META_DATA`, and `DeviceAdminInfo` reads the policies from that metadata. The exception was caught on purpose (an unreadable admin must stay in the list), so everything looked fine. Fakes cannot show this, because they never go through Android.
- **Fix:** the query passes `GET_META_DATA`. The conformance run compares the policies with the admin's own XML resource, read from the APK. After the fix: 141 of 141. The admin's declared powers are what makes it a finding with a stated meaning, so this was not cosmetic.

## D-023 Grants of built-in apps looked wrong because they share a user id (2026-10-09)

- **Symptom:** the same run: 28 built-in packages "disagreed" with `dumpsys package` about granted permissions.
- **Cause:** packages that share a user id (for example the system uid) are reported by `dumpsys` with the whole uid's grants, while the app reads each package's own manifest. Neither is wrong.
- **Fix:** the comparison skips shared-uid packages and says why; the inventory screen now states that such apps show only what their own manifest asks for. For every other package (including all non-built-in apps, which are the ones that produce findings) the grants match.

## D-024 Three harness problems found in the same run (2026-10-09)

- The setup check for the `perms` fixture did not ask the system about that package, so it aborted the enable phase with a false "setup failed" (the lab runner needs the package named in its truth collection). Fixed by naming it.
- `aapt2 dump badging` prints only `application: label=...` for two fixtures, not the usual `application-label:` line, so the expected label was empty. The parser accepts both forms.
- A unit test shared one dictionary between five fixtures and then edited it, so every fixture got the last label. Each role now has its own copy.


## Product defects (Stage 1, milestone 3)

## D-025 The harness left a proxy in force and its ground truth looked at the wrong setting (2026-10-09)

- **Symptom:** the second milestone 3 run failed in the "as found" state: the product reported the proxy `10.0.2.2:8888`, while `settings get global http_proxy` said `null`.
- **First idea (wrong):** the product reads a stale value. It does not: `ConnectivityManager` reports the proxy that is actually in force.
- **Cause:** my revert deleted the `http_proxy` setting, which does not clear a proxy that is already in force. Android keeps it in `global_http_proxy_host` and `global_http_proxy_port`, and those still held the test proxy. The truth I compared against read only the old, deprecated key. This is the shape of D-004 and D-006: a quiet command, and a state I assumed instead of verified.
- **Fix:** the truth reads the real keys (`global_http_proxy_host`, `_port`, `global_proxy_pac_url`) and treats the old key as a fallback only; the revert writes `:0`, which is what Android's own settings do, and then verifies the real keys are empty. Confirmed on the emulator: after `:0` the product reports no proxy. A unit test pins the parsing with the real state seen.

## D-026 A test sample I wrote from memory had the wrong token (2026-10-09)

- **Symptom:** "Private DNS in use" disagreed with the system in the changed state (73 of 74).
- **Cause:** my parser looked for `PrivateDnsActive` in `dumpsys connectivity`. The real text is `UsePrivateDns: true PrivateDnsServerName: ...`. The unit test sample had been written from memory with the same wrong token, so the test could not have caught it. The product was right.
- **Fix:** the parser and the sample now use text captured from the device. Rule kept: samples in tests are captured, not remembered.

## D-027 Harness upkeep after the product changed shape (2026-10-09)

- The screen reader of the conformance script stopped at 8 scrolls; the longer inventory needed more (same shape as D-017). It now scrolls until nothing new appears, up to 40 times.
- The milestone 1 runner read the old report shape and crashed with `KeyError: 'component'`. Milestone 2's script covers accessibility with more checks, so the runner is retired (it prints where to go) and the file stays as the shared helper library. Each script now judges only the signals it owns: milestone 2 judged the new device signals and failed on Advanced Protection, which is unavailable below Android 16 by design.

## D-028 A screen full of text hid what mattered (2026-10-09)

- **Symptom:** found by looking at the screenshot: the "What this app cannot see" section opened with a single sentence naming 18 readable signals, a wall of text above the lines about the signal that was not readable.
- **Fix:** the summary is a count ("Readable on this phone: 18 of 19 checks") followed by one line for every signal that is not fully readable. Nothing unreadable is dropped; the readable ones are the sections and rows already on the screen.


## Product defects (Stage 1, milestone 4)

## D-029 The same cause was printed three times (2026-10-09)

- **Symptom:** found by looking at the screenshot of the inventory on the emulator: under "What this app cannot see", boot state, bootloader and attested security update each had their own near-identical line ("not established: the keys are in software").
- **Fix:** signals that are unreadable for the same reason share one line that names all of them. Nothing is dropped, and a single cause reads as a single cause.

## D-030 Each conformance script judged the others' results (2026-10-09)

- **Symptom:** adding the integrity panel made two older scripts fail: the milestone 2 script judged the new `integrity.*` signals, and the milestone 3 script saw the new `software-keys` finding and called it unexpected.
- **Cause:** both scripts compared "everything the product reported" instead of "everything this script owns". Third time in a row that a new milestone broke an older harness (D-027).
- **Fix:** every script now names what it owns and ignores the rest, and each ownership rule has a unit test. The older scripts pass again unchanged otherwise (297 of 297 and 84 of 84).


## Product defects (Stage 1, milestone 5)

## D-031 The random-sequence test found two reducer bugs (2026-10-09)

- **Symptom:** a property test of 3,000 random sequences of 80 events failed. (1) A finding could be opened while the first-run screen was showing, leaving a stack whose bottom was not the inventory; the first-run gate did not cover the Open events. (2) Draft answers stayed in memory for holders that were no longer up for review after the saved state changed, so a later save could write answers for apps that had gone.
- **Fix:** the gate now blocks every Open event during the first run; the stack bottom is normalized after each step; the draft is pruned against the pending list each time the state settles. Both have named tests, and the property test stays.

## D-032 The conformance script stalled on a button below the fold, and test data aliased (2026-10-09)

- **Symptom:** the first full run of the milestone 5 script stopped at the first review: "Finish the review" was below the visible screen (the same shape as D-017 and D-027). A unit test of the comparison also passed for the wrong reason because the in-memory and on-disk views were the same Python object.
- **Fix:** the script scrolls to find a button; the review's answer chips were wrapped (`FlowRow`) so three chips fit at large font sizes; the test data are separate copies, and a test proves that disagreement between memory and disk is caught. An `ensure_baseline` helper in the shared library now gets the older scripts past the first-run screen on a fresh install.


## Product defects (Stage 1, milestone 6)

## D-033 At large text the first screen could not be used (2026-10-09)

- **Symptom:** the new instrumented accessibility test passed at normal text size and timed out at twice the size, waiting for the review screen. The control to start the review ("Start the review") was in the tree but below the bottom of the screen, and the first-run screen could not be scrolled: a person with large text would have been unable to start the app.
- **Why the script did not catch it:** the conformance script's large-text phase starts after a baseline exists, so it never sees the first-run screen; and a click on a node that is in the tree but off the screen does not fail on its own.
- **Fix:** the first-run screen and the "could not read the phone" screen scroll. The instrumented test now refuses to click a control that is not displayed (`assertIsDisplayed`) and scrolls to it first, so the same class of defect fails loudly. The accessibility framework's checks were proven able to fail by adding a tiny unlabeled clickable on purpose: it was rejected (`SpeakableTextPresentCheck`) and removed.

## D-034 The first version of the watcher reported the network's starting state as a change (2026-10-09)

- **Symptom:** found by looking at a screenshot: "Heard 1 change since the app was opened" on a phone where nothing had changed. The live file showed a network announcement a moment after start.
- **Cause:** Android calls a network callback with the current state when it is registered, and again when validation completes; the first version announced every callback.
- **Fix:** the watcher remembers the default network and the facts the report reads (transports, internet, validated, VPN, captive portal) and announces only when one of them differs. Verified: no announcement after a fresh start; the conformance script's settings and app phases do not depend on it.

## D-035 A machine timestamp on the first screen (2026-10-09)

- **Symptom:** found by looking at a screenshot: "Baseline from 2026-10-08T21:37:13.688423Z." A person cannot read that, and it is in UTC.
- **Fix:** one formatting function, in the phone's language and time zone, used by the first screen and the what-changed screen.

## D-036 The wording of a map entry said "Measured" twice (2026-10-09)

- **Symptom:** found by looking at a screenshot: "What was measured: Measured: every holder of the four powers was seen ...".
- **Fix:** the label is "Evidence:" and the entries no longer carry their own prefix. Also on the live view the list of what was heard moved above the long explanation, because a screen about what just happened should start with what just happened.

## D-037 A service that settings said was on could vanish from the report (2026-10-09)

- **Symptom:** the milestone 5 script, rerun after the live view was added, failed: during the first review the accessibility fixture's card disappeared, the answers were saved without it, and afterwards it was "up for review" again. It had passed before the live view existed.
- **Cause:** the report called a service switched on only if Android's list of running services had it. A test tool such as `uiautomator` suspends every accessibility service while it is attached to the screen, so for a second Android's own list lost the service while the settings value (`enabled_accessibility_services`) still had it. Before the live view the app read the phone only when it came to the front, so the overlap was rare; with a read every 30 seconds and after every announcement it was not. The same thing happens, more rarely, to a service that is starting or has crashed.
- **Fix:** a service listed by **either** source is shown as switched on. When the two disagree it is reported with lower confidence and the limits say which source said what; hiding it would be the unsafe error. Test: `aServiceOnlyTheSettingsListIsShownAsSwitchedOnWithLowerConfidenceNotHidden`.
- **Lesson:** a harness can change what it measures; here the finding was real, because the product had silently picked the "off" answer when its two sources disagreed.

## D-038 A fifth scroll cap, and a fixture left uninstalled (2026-10-09)

- The default scroll limit of the shared screen-search helper (12) was too small at twice the text size, the same shape as D-017, D-027 and D-032. The default is now 40, and the helper says why.
- The new milestone 6 script uninstalled the hidden-icon fixture to test it and did not put it back, which made the milestone 2 script fail its check that the hidden fixtures are installed. The script now leaves the phone as it found it.

## Product defects (Stage 2)

## D-039 The person's own answer was labeled "the app's own statement" (2026-10-09)

- **Symptom:** found while writing the attack-path rules, which have to tell a person's word apart from an app's own metadata. The finding raised by "I do not recognize this" carried the provenance DECLARED, which the detail screen words as "Declared: the app's own statement". An accessibility service's declared abilities and the user's own answer had the same label.
- **Fix:** a new provenance, TOLD ("Told: what you said"), used for the user's answers and for the declarations on the "About you" screen. A path that rests on one says so ("This path depends on what you told this app"), and a path that merely rests on a service's own metadata does not. Tests pin both.

## D-040 The first solver was fast on easy graphs and too slow on hard ones, and the tests could not tell (2026-10-09)

- **Symptom:** the first scale test passed instantly, which looked good. It was vacuous: its random graphs were almost all FALSE, so the solver had nothing to do ("0 levers" for most rows). A test built to be hard (every goal an OR of several ANDs over a shared pool of facts, a hitting-set problem) took 33 seconds on 1,800 nodes.
- **Cause:** every search step evaluated the whole graph and allocated a witness; a graph of 1,800 nodes cost milliseconds per step.
- **Fix:** array-based evaluation that repairs only the part above a flipped fact and undoes it on backtrack, plus a lower bound from witnesses that share no usable lever. The same hard instance now takes about 1.3 seconds at the default budget of 20,000 steps. The numbers are recorded in `HardFixesTest` and `DESIGN.md` section 23.

## D-041 The network was unreadable for a while after a setting changed (2026-10-09)

- **Symptom:** a run of the Stage 2 conformance script failed in its first three states: the app held the open Wi-Fi path "not ruled out" while the system said the Wi-Fi was open and Private DNS off. The same script passed on the run before and the run after, and six launches in a row later all agreed.
- **Cause:** a few minutes after Private DNS and the proxy were reverted, Android re-validates the network and the app could not read the security type of the Wi-Fi (or whether Private DNS is in use) during that window. The app's answer, "not ruled out because the network could not be read", is the correct one for a reading that is not there; the conformance script compared it with a truth read a moment later, after the system had settled.
- **Fix (harness, not product):** the script launches the app, and when the app could not read the network while the system can, waits and launches again (up to eight times, each reported as an info line), so it judges the app on a settled phone. The product keeps its honest answer.

## D-042 One live read left the app count behind for a whole wait (2026-10-09)

- **Symptom:** in one full run of the milestone 6 script (two full runs had passed before and ten of ten consecutive installs in a probe afterwards were reflected in about two seconds) the number of apps the report listed did not go up after a fixture was installed, for the 12 seconds the script waited.
- **Cause:** not proven; it did not recur. The likeliest story is a read that failed while the package manager was busy with the install: the old report stayed on screen (correctly labelled as old) and the next read was the 30 second one.
- **Fix:** a failed read while the app is on screen is tried again after two seconds, up to three times in a row (`ReportViewModel`); the debug build now writes the refresh error to the live file, and the script prints it if the check ever fails again, so the cause can be read instead of guessed.

## D-043 A tripwire that fired and was armed again in the same look was reported the wrong way round (2026-10-09)

- **Symptom:** a unit test of the ledger's reading of its own events ("armed", "fingerprint added") gave the wrong armed set when both events sat in one entry.
- **Cause:** events are stored sorted by name, so "armed" sorted before "fingerprint-added" and was applied first.
- **Fix:** `Armed.of` applies the events that mean a key died before the events that mean one was made, whatever their stored order. A test fixes the order.

## D-044 Renaming a signature's key passed the single-character mutation test (2026-10-09)

- **Symptom:** 3 of 4,000 random one-character changes to a stored history were accepted: the key `"sig"` was changed to another word, which made the entry look unsigned, and the history was still called authentic.
- **Cause:** `authentic` meant "the chain holds and no signature is wrong", so a missing signature did not count against it.
- **Fix:** `authentic` now needs the chain to hold and every entry to carry a valid signature; a signature's text must also be the canonical text of its bytes (base64 has unused bits). The test passes at 0 of 4,000, and the independent verifier runs the same kind of scan over every position of the sample.

## D-045 Adding an entry replayed the whole history (2026-10-09)

- **Symptom:** building a long history was quadratic: each new entry replayed every diff since the start.
- **Fix:** an appended ledger carries the records it leads to, so the next entry starts from them. A history of 1,000 entries over 2,000 apps is built, checked and stored in the time the scale test records.

## D-046 A history rewritten from scratch under another key verified as authentic (2026-10-09)

- **Symptom:** found by thinking through who the history is meant to stop, not by a test: someone able to write the app's file but not to use its Keystore key could build a flawless history of their own under a key they made, and the check, which tests signatures against the key the file names, called it authentic.
- **Fix:** the check can be tied to the key the phone holds (`HeldKey`), and the app's "Check the history" always does so. A different key is reported as broken; a key that could not be read just now is reported as weak and not authentic. Tests build such a forgery in Kotlin and, with OpenSSL, in Python. A check of the file alone still passes it, and the tests say so.

## D-047 The History screen used a word the project bans (2026-10-09)

- **Symptom:** the sentence about where the signing key is held said "secure hardware"; the project does not use "secure" or "safe" as a claim.
- **Fix:** the History screen's fixed sentences moved from the screens into `core` (`HistoryWords`) so the wording test covers them, and now say "protected hardware".

## D-048 Two mistakes in the new conformance script, not in the app (2026-10-09)

- **Symptom:** the first run of `conformance_m8.py` failed 9 of 39 checks.
- **Cause:** the fixture was installed without `-t` (the other scripts use it) and the failure was swallowed; and the pattern for the button "Start a new history" also matched the paragraph above it that contains those words, so the tap never reached the button. The tripwire steps then failed because the history was still in its damaged state.
- **Fix:** both corrected (`-t`; the pattern is anchored), the install no longer ignores failure.

## D-049 Android 17 hid the connection from an app without internet permission (2026-10-11)

- **Symptom:** on the API 37 image the app reported no network, no web proxy and no Wi-Fi security while the system listed them (m3, m6, m8).
- **Cause:** `ConnectivityManager.getActiveNetwork()` is null and `getActiveNetworkInfo()` says "blocked" for an app that cannot use the network, which this app cannot by design. The list of networks and their capabilities stayed readable.
- **Fix:** when the active network is null the app picks the likeliest default from the networks it can still see (`DefaultNetworkGuess`: confirmed before unconfirmed, then VPN, wired, Wi-Fi, cellular), marks the reading as inferred at medium confidence and says so on the finding. Tested in `core`; m3 82/82, m7 110/110 and m8 59/59 on Android 17.

## D-050 The Android 10 tripwires (2026-10-11)

- **Symptom:** on Android 10 the fingerprint tripwire never fired and the app said "nothing happened"; the lock tripwire key could not be created (`InvalidAlgorithmParameterException`).
- **Cause:** the lab experiment had already shown that a fingerprint-bound key survives another fingerprint being added on Android 10; the app did not use that. The per-use credential binding does not exist on Android 10.
- **Fix:** the fingerprint tripwire reports "cannot work on Android 10 (measured on an emulator)" instead of a false calm; the lock tripwire key uses a one-second authentication window there.

## D-051 The tripwire headline vanished at the next look (2026-10-11)

- **Symptom:** a fired tripwire showed on the History card for one look; the next look (often seconds later) said "not possible: no screen lock" and the news was gone from the headline.
- **Fix:** the newest firing event is kept (`LastTripwire`) and shown as "Last tripwire event" until a newer one replaces it. The event itself was always in the timeline.

## D-052 The conformance scripts assumed Android 14 (2026-10-11)

- Package visibility (Android 10 shows every package), older `dumpsys` and `cmd wifi` formats, expired fixed software attestation certificates on the old emulator images, the `appops` default for platform packages from Android 15, Advanced Protection unreadable by the oracle on Android 16 and 17, and what Android 10 refuses to say. Each expectation is now version-aware and says why.

## D-053 The accessibility pass cannot run on Android 17 (2026-10-11)

- AndroidX Test's Espresso calls `InputManager.getInstance`, which Android 17 removed. The failure is in the test framework before any screen is checked. The pass runs on Android 10 and 12 to 16.

## D-054 A half-visible card measured as a tiny touch target (2026-10-11)

- A tall clickable card cut off by the edge of the window reported only its visible part, without text, and was flagged as 60 pixels high. The check now skips nodes cut off by the window edge. A test keeps the real case (a short target in the middle) caught.

## D-055 The test harness on fresh images and slow hosts (2026-10-11)

- A fresh image keeps working for minutes and the first start of a new debug app is slow; the PC went to sleep during a long run and froze three runs; a download stalled for twelve minutes; limits chosen on a fast host failed on a slow one (the same steps took 1.7 times as long). Fixes: install and compile the app and let the image settle before the UI steps, keep Windows awake while the runner works, retry the emulator start, wait for the first screen, and limits of 240 seconds where 60 or 90 caught slowness as failure. `run_matrix.py` and `run_suite.py` replace long hand-driven sessions.

## D-056 Known intermittent checks (2026-10-11)

- The app count after an install (D-042) and five Live view text checks on fresh Android 16 and 17 images are intermittent on a slow host. They are timing in the scripts; the same checks pass on Android 14.
