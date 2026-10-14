# Privacy

Declared (working name) is built so that this page can be short. Everything below can be checked in the source; where a build step enforces it, that is said.

## What leaves your phone

Nothing. The app asks Android for no internet permission, and the build fails if the merged manifest ever gains one (`verifyManifestPolicy` in `product/app/build.gradle.kts`). It has no accounts, no advertising, no analytics, no crash reporter and no network code at all in this version. Stage 1 of the project is offline by design.

## What the app reads

Only things Android lets any ordinary app read, with three normal permissions that need no prompt (network state, Wi-Fi state, and the Advanced Protection query on Android 16 and later). Two more permissions belong only to the optional automatic checks, described below:

- which apps have an accessibility service, a notification listener, a device administrator or a keyboard, and which apps hold a handful of special powers (drawing over other apps, usage access, installing other apps, text-message access);
- the name, the installer and the signing certificate fingerprint of the apps it can see, and which permissions they hold of a short list;
- a few of the phone's own settings (security update date, whether a screen lock exists, USB and wireless debugging, developer options) and its network configuration (connection type, VPN, Private DNS, web proxy, Wi-Fi security type, certificate authorities added by a user);
- one freshly made key attestation certificate chain (hardware-backed when the phone has the hardware for it), which it checks on the phone against reference data shipped inside the app.

It does not read your messages, notifications, contacts, files, photos, location, accounts, browsing, passwords or other apps' content.

## What the app keeps

One file in the app's private storage holds your answers from the first review (recognized, not recognized, not sure) with each app's signing fingerprint, your answers to the two questions on the "About you" screen (text-message codes, public Wi-Fi) and the profile you chose, and a snapshot of the state above, so that "what changed" can be worked out later and the attack paths can use what you told the app. It is encrypted with AES-256-GCM. The key is created inside the Android Keystore and never leaves it. The file is excluded from Android's cloud backup and from device-to-device transfer (and the key could not be moved anyway), so a new phone starts with a new review.

A second file in the app's private storage holds the history. Each entry holds the time, whether the app was opened or was already on screen, the app version, and only the differences from the entry before: which apps appeared or disappeared (with their names and signing fingerprints), which settings changed (including a web proxy address, if one is set), which findings appeared or went away, and whether a tripwire fired. It is encrypted with AES-256-GCM under a second key that stays in the Android Keystore, and every entry is signed by a third key, also in the Keystore, that cannot be exported. The two tripwire keys contain no data. All of these files are excluded from cloud backup and device transfer. If the history file is found damaged it is left as it is and, when you ask for a new history, kept aside under another name until the app's storage is cleared.

The list of what has changed while the app is on screen (the live view) is kept in memory only and is gone when the app closes.

Two small preference files, not encrypted because they hold no reading of the phone, keep how the app looks and one choice about checking: the simple view on or off, whether the intro and the tour were seen, whether automatic checks are on and at what interval, and short hashes of the changes already announced (so no app name is written there).

## Automatic checks (optional, off until you turn them on)

If you turn them on under More, Android runs one short job at the interval you choose, as often as every 15 minutes and as rarely as every 365 days; Android may run it a little later to save battery. The job reads the phone the same way the screen does, compares it with what you last saw, and changes nothing: it does not write the baseline or the history. If something that needs a look is new to you, it posts one notification that says how many, never which app, and shows only "Declared" on a locked screen. The same change is not announced twice.

This is why the app declares two more permissions: notifications (Android asks you when you turn the feature on) and run at startup (so the schedule survives a restart). With the feature off, nothing is scheduled and no job runs. There is still no internet permission, no foreground service and nothing that stays running. Turning the feature off cancels the job and forgets what was announced.

## Deleting it

Uninstalling the app removes the file and the key. Clearing the app's storage in Android's settings removes the file, and with it everything this app has kept, without uninstalling.

## Screens

Screenshots are allowed by default. Under More you can turn on Block screenshots, which asks Android to keep the app's screens out of screenshots, screen recordings and the recent-apps preview, because they list what is installed. An app that has been given the accessibility power can still read text on the screen, blocked or not; the app says so on its "What this app cannot see" screen.

## Changes

If a later version adds anything that sends data anywhere, this page and the store listing change first, the permission appears in the manifest, and the build gate is updated in the same change so it shows in the source history.

## Contact

Maintainer contact: use the private vulnerability report on the project's GitHub repository (see `SECURITY.md`).
