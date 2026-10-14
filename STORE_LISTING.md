# Google Play submission package: Declared

Prepared 2026-10-12. Nothing has been submitted or published. Everything below is paste-ready, and the files are in `play-store/`. Play's rules change, so read the Console's own wording on the day you submit.

## What is ready

| Item | Where | State |
|---|---|---|
| Signed app bundle (upload this) | `play-store/release/declared-0.3.0-release.aab` | Built and signed with the upload key, 5 permissions, target API 36, native libraries 16 KB aligned |
| Signed apk (for a phone) | `play-store/release/declared-0.3.0-release-signed.apk` | Same build, installable on a real phone |
| Upload key | `the H-sib-keys\declared folder on your Desktop` | Created. Not in the project. **Back it up** (see `READ-ME-FIRST.txt` there) |
| Title, short description, full description, release notes | `play-store/listing/en-US/*.txt` | Written, length-checked (29/30, 80/80, 2552/4000, 362/500) |
| App icon 512 | `play-store/graphics/icon-512.png` | Full square, as Play asks (Play rounds it) |
| Feature graphic 1024 x 500 | `play-store/graphics/feature-graphic-1024x500.png` | Done |
| 8 phone screenshots 1080 x 1920 | `play-store/graphics/phone-1.png` to `phone-8.png` | Real screens with captions |
| Privacy policy text | `PRIVACY.md` | Written. Needs a public web address (see the steps) |
| Logo kit (reuse for every H-sib app) | `brand/` and `the H-sib-brand folder on your Desktop` | Done |

## Store listing (English, United States)

**App name** (30 characters max): `Declared: Phone Privacy Check`. Brand-only fallback: `Declared`. Play ranks words in the name, which is why the longer one is recommended; check the name is free first.

**Short description** (80 characters): `See which apps can read your screen and notifications. Plain words. No internet.`

**Full description:** `play-store/listing/en-US/full.txt`. It opens with the question people actually have, explains the name in one line, lists what you get, what it looks at, the privacy facts, and its limits, in that order.

**What's new** (first release): `play-store/listing/en-US/release-notes.txt`

**Category:** Tools. **Tags (pick up to 5 from Play's list):** Security, Privacy, Utilities. **Price:** Free. **Contains ads:** No.

**Contact:** developer name `H-sib`; a public email (yours, to fill in); website `https://github.com/H-sib/declared` once the repository is public; privacy policy URL below.

## Words the listing must not use

"protects", "secures", "keeps you safe", "detects malware", "removes threats", "scans for viruses", "boosts", "optimizes", any number that looks like a score or a count of threats, anything about apps it cannot see. The files in `listing/` were checked against this list by a script.

## App content forms (answers)

**Privacy policy:** `https://h-sib.github.io/declared/privacy.html` (live on GitHub Pages, same text as `PRIVACY.md`).

**App access:** all functionality is available without a login or any special access.

**Ads:** No. **Advertising ID:** the app does not use it (it is not in the manifest). **News app, government app, financial features, health features:** No to all.

**Target audience:** 18 and over. This avoids the Families policy, which does not apply to a security utility.

**Content rating (IARC questionnaire):** category "Utility, Productivity, Communication, or other". Violence, sexual content, language, controlled substances, gambling: none. Users can interact or exchange content: No. Shares the user's location: No. Digital purchases: No. Unrestricted web access: No. Expected result: rated for everyone (PEGI 3 / ESRB Everyone).

**Data safety:**

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Why | Everything the app reads is processed on the phone and never leaves it: there is no internet permission, no account, no analytics and no crash reporter. Play counts data as "collected" only when it is sent off the device. |
| Is data encrypted in transit? | Not asked once the first answer is No |
| Can users request deletion? | Not asked once the first answer is No. In the app: uninstall, or clear storage in Android settings |

**Permissions, and why each one is there** (for the review notes):

| Permission | Why |
|---|---|
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Report the phone's own network setup: connection type, web proxy, VPN, Wi-Fi security |
| `QUERY_ADVANCED_PROTECTION_MODE` | Report Android 16's Advanced Protection state |
| `POST_NOTIFICATIONS` | Only for the optional automatic checks: one notification that says how many changes need a look. Asked when the person turns the feature on |
| `RECEIVE_BOOT_COMPLETED` | Only for the optional automatic checks, so the schedule the person chose survives a restart |

No `INTERNET`, no `QUERY_ALL_PACKAGES`, no foreground service, no accessibility service of its own, no VPN, no device-admin use. The package-visibility `<queries>` entries cover the four special components and apps with a launcher icon, and the app says on screen which apps that leaves out.

## Review notes to paste

- Purpose: shows the person which apps on their phone hold powerful abilities (accessibility services, notification access, device administrators, keyboards, drawing over other apps, usage access, installing other apps, text-message access) and a few risky settings, in plain words, and where to change each. It changes nothing by itself.
- Everything is processed on the device. There is no internet permission. The build fails if one is added (`verifyManifestPolicy` in `product/app/build.gradle.kts`).
- Automatic checks are optional and off by default. When on, one short scheduled job reads the phone and may post one notification. There is no service that stays running.
- Source: open, Apache-2.0. Reproduction notes: `product/README.md`. Test results: `product/conformance/`.

## Release

1. In Play Console create the app: name above, default language English (United States), App, Free, accept the declarations.
2. Turn on **Play App Signing** when asked (recommended). Your `declared-upload.jks` is the upload key; Play keeps the real signing key.
3. Create a **Closed testing** release, upload `declared-0.3.0-release.aab`, paste `release-notes.txt`. versionCode is 1 and versionName is 0.3.0. Raise versionCode for every later upload.
4. Closed testing requirement for a new personal account: at least 12 testers opted in for 14 continuous days before you can apply for production. Check the current number on the day.
5. Tester message (copy and send): "Please install Declared from this link, use it for two weeks, and tell me anything confusing. Open it once a day if you can. It has no ads and uses no internet."
6. After the testing period, apply for production, fix what testers found, and raise the version.

## Your steps, in order (only you can do these)

1. Back up `H-sib-keys\declared` to two places.
2. Register the Play Console developer account (one-time fee) and complete identity verification.
3. Check the name "Declared" is free (trademark search, Play search, the app stores) and decide between the long and short name.
4. Make the repository public and turn on GitHub Pages so `PRIVACY.md` has a public address. Choose the public contact email.
5. Do the Console steps above, using the files in `play-store/`.
6. If Android developer verification applies to you, register the package name `io.github.h_sib.declared` and the signing key.
7. Test the signed apk on your own phone once before the first upload.

## Open choices

- Name: long (recommended for search) or `Declared` alone.
- Money later: the app has no ads and no internet, so the fitting models are a one-time paid upgrade or optional support. Ads would contradict the privacy position.
- Languages: the listing is English because the app is. A Bengali listing makes sense after the app itself is translated.
