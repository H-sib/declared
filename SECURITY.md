# Security policy

Declared tells people what to look at on their own phone, so a flaw in it can mislead someone in a way that matters. Reports are welcome and taken seriously.

## How to report

**Contact: GitHub private vulnerability reporting.** Open the repository's Security tab and choose "Report a vulnerability" (https://github.com/H-sib/declared/security/advisories/new). The report is private to the maintainer. Please do not use a public issue for something that could be misused.

Please do not open a public issue for something that could be misused. Say what you found, which version, which Android version, and how to reproduce it. A reproduction on an emulator is ideal.

## What counts

These are the problems this project most wants to hear about, roughly most serious first:

1. **The integrity check accepts what it should not.** A certificate chain that is not trusted, is revoked, answers a different challenge or has been altered, and the panel still says it passed. The verifier is `core/integrity/ChainVerifier.kt`; its tests, and the conformance run against the JDK's own verifier, are in `product/core/src/test` and `product/conformance/`.
2. **The app says "clear" when it could not read something.** Every unreadable signal must show up as unreadable, with the reason, never as an empty or green row.
3. **The saved baseline can be read, changed or replaced by another app, or a damaged file is accepted as an empty baseline.**
4. **Data leaves the phone.** The app has no internet permission and the build enforces it. Anything that finds a way around that is a vulnerability.
5. **Crashes or freezes caused by what other apps declare** (very long or odd names, thousands of components, hostile strings in labels).
6. **A wording problem that states something false** (a finding that calls an app malicious, a screen that says the phone is safe).

## What does not count

- Things the app says it cannot see (the "What this app cannot see" screen). Those limits are documented and measured, not hidden.
- A phone that has been altered, rooted or has a compromised system: such a phone can report anything to any app. The app says so.
- Findings that need the attacker to already hold the powers the app reports on.

## What to expect

This is a small project. Reports are read as soon as the maintainer can, a fix goes into the next release, and the reporter is thanked in the release notes unless they prefer not to be. No bounty is offered.

## Supported versions

Only the latest release.
