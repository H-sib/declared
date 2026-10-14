# Threat Model v0.2

Status: draft, 2026-10-08. Companion to `CONCEPT.md`. Technique IDs are from MITRE ATT&CK for Mobile (Android matrix, checked 2026-10-08). Items marked (?) are assumptions that `OBSERVABILITY_PLAN.md` must confirm before the model relies on them.

Changes in v0.2: trust tiers and false-alarm controls, defensive controls modeled as graph nodes, harm and advice-safety rules, the tool itself as an attack surface, a user-confirmed baseline, a decided confidence rule, and a corrected technique mapping for P4.

## 1. Scope

**In scope:** reducing the exposure of one personal Android phone and the network it is on, by observing its configuration, finding realistic attack paths that combine those observations, and showing the user which changes cut the most paths.

**Not in scope:** stopping attacks, detecting zero-click or commercial spyware, detecting kernel or firmware compromise, protecting accounts the user has not described, and monitoring other apps' runtime behavior. The app reports these as blind spots instead of pretending to cover them.

## 2. Assets

| ID | Asset | Why an attacker wants it |
|---|---|---|
| AS1 | Primary identity account (email / Google account) | Recovery path for everything else |
| AS2 | Financial accounts and payment apps | Direct monetary loss |
| AS3 | Second factors (SMS codes, authenticator, passkeys) | Bypasses account passwords |
| AS4 | Messages, photos, contacts | Privacy, extortion, impersonation |
| AS5 | Location and activity | Stalking, targeting |
| AS6 | Device control (install apps, enable services) | Persistence, further access |
| AS7 | Network traffic confidentiality and integrity | Credential theft, injection |

AS1 to AS3 are the accounts that matter most to ordinary users. The app can only learn about them through facts the user declares (for example "my bank sends SMS codes"). Those facts are labeled Declared and never presented as observed.

## 3. Adversaries

Likelihood is given as a qualitative weight per profile, not as a probability. Nothing in this project can honestly produce numeric probabilities.

| ID | Adversary | Capability | Goal | General user | High-risk user |
|---|---|---|---|---|---|
| A1 | Opportunistic malware operator | Delivers a dropper by sideload, fake update or smishing; abuses accessibility and SMS access | Bank and account takeover | Main concern | Relevant |
| A2 | Phishing / social engineer | Remote, no device access; lookalike links and fake support calls | Credentials, codes, installs | Main concern | Main concern |
| A3 | Hostile-network attacker | Public Wi-Fi, rogue AP, ISP-level tampering, compromised home router | Credential theft, injection, DNS hijack | Minor (HTTPS limits impact) | Relevant |
| A4 | Physical-access opportunist | Brief access to an unlocked or PIN-known phone | Add fingerprint, install or enable a service, steal data | Relevant | Main concern |
| A5 | Close-contact stalker | Long physical access plus personal knowledge | Covert location and message surveillance | Relevant (persona deferred) | Relevant |
| A6 | Targeted high-resource attacker | Zero-click exploits, commercial spyware | Full device compromise | Out of scope | Out of scope for detection; exposure reduction only |

## 4. Trust assumptions

1. At baseline the device is not rooted and its kernel is not compromised. If the app sees signs of root, it says its own readings may be unreliable.
2. The hardware-backed keystore is trustworthy. Attestation can still be spoofed on devices with leaked attestation keys (?), so attested facts carry a confidence label, not certainty.
3. The app's own process is not compromised.
4. The app cannot verify its own environment. Device-side attestation checking is weaker than verification on a separate trusted server, which Android's key attestation documentation recommends. The app labels device-side verification accordingly.
5. A hash-chained history proves that records were not altered after the fact. It cannot prove that the device was clean when a record was written.
6. The first snapshot is not a known-clean state. At first run the app walks the user through every holder of special access and asks them to confirm "I recognize this" or "I do not recognize this". The baseline is therefore user-confirmed instead of assumed clean, and unrecognized items become findings immediately.

## 5. Initial attack-path catalogue

**Status (2026-10-09):** P1 to P6 are implemented in `product/core/.../paths/RuleBook.kt` and checked against an independent evaluation on an emulator (`STAGE2_DESIGN.md`, `DESIGN.md` section 23). P7 waits for the Stage 4 sensors; P8 is declared-only and has no node yet. The table below is the specification the engine follows.

O = Observed, D = Declared, I = Inferred. "Invisible" lists what the app cannot see on that path.

| ID | Path in plain terms | ATT&CK Mobile | Signals the model uses | Invisible | User fix levers |
|---|---|---|---|---|---|
| P1 | Sideloaded dropper gets accessibility, then captures input and notifications and steals credentials | T1660, T1453, T1417.002, T1517, T1407, T1655.001 | O: install source, enabled accessibility services and their capability flags, overlay grant, signer SHA-256 vs known publisher. I: lookalike signer | What the service actually does; payload downloaded at runtime | Revoke accessibility, remove app, block install-unknown-apps for browsers and messengers |
| P2 | SMS-code takeover: an app that can read SMS or notifications intercepts one-time codes | T1636.004, T1517, T1582 | O: SMS and notification-listener holders. D: account uses SMS codes | Carrier-side interception | Move the account to an authenticator or passkey, revoke grants |
| P3 | Hostile network tampers with traffic (DNS hijack, TLS interception, injection) | T1638 | O: user-installed CA, Wi-Fi proxy, Private DNS state, DNS and TLS probe results (Stage 4). I: Wi-Fi security type | ARP spoofing, passive sniffing, a relay that does not tamper | Remove user CA and proxy, enable Private DNS, avoid open Wi-Fi |
| P4 | Physical access: attacker with brief access adds a fingerprint or enables a persistent service | T1626.001, T1453, T1629.001 (T1461 applies only if the lock is bypassed, not when the PIN is known) | O: lock screen present, device admins, new special-access grants since baseline, Keystore tripwire (?) | Everything done while the app was not watching | Stronger lock, review grants after physical access, remove stray fingerprints |
| P5 | Stalkerware: device admin or accessibility plus hidden icon plus location and microphone access | T1626.001, T1453, T1628.001, T1430, T1429, T1512 | O: capability combination, hidden launcher icon (?), install source. I: capability-shaped app with no launcher entry | Behavior; disguised or system-level installs | Remove app, revoke grants (persona handling deferred, see CONCEPT.md) |
| P6 | Unpatched device exploited through a known vulnerability | T1404, T1664, T1658 | O: patch level, OS end-of-life, attested patch level (?), Advanced Protection state (?) | Whether a given exploit is in use | Update, replace unsupported device, enable Advanced Protection |
| P7 | Malicious app scans the LAN; exposed router services (UPnP, admin pages) widen the damage | T1423, T1422.002 | O: apps holding LAN access, gateway exposure (own networks only). Router compromise itself is network-side, not a mobile technique | Router internals and other LAN devices | Disable UPnP, update router, revoke LAN access |
| P8 | SIM swap and account-recovery abuse | T1451 | D only (recovery method, carrier PIN) | The swap itself | Carrier port-freeze or PIN, stronger recovery options |

Negative space, listed as blind spots in every report: zero-click spyware, kernel and firmware compromise, cellular interception (IMSI catchers), compromised accounts, and anything a rooted attacker chooses to hide.

Platform notes that affect P1 (?): Android restricts access to accessibility and notification-listener settings for sideloaded apps, which raises friction for droppers but does not make the path impossible, so the model treats "installed from a non-store source" and "grant present" as separate nodes.

**Defensive controls are graph nodes too.** Existing protections can cut paths, so the model includes them: screen lock, Private DNS, current patch level, Advanced Protection (when readable), and OEM protective features where they exist (none investigated, so none modeled yet). A fix set may therefore recommend enabling a control. Controls that cannot be read (for example Play Protect state) are listed as Invisible, not guessed.

## 6. Profile differences

| Rule area | General | High-risk |
|---|---|---|
| Sideloaded app holding accessibility | High severity | Critical |
| Patch level older than the latest available | Medium | High |
| Advanced Protection off (when readable) | Informational | High |
| Public Wi-Fi without Private DNS | Medium | High |
| Declared SMS-code accounts | Medium | High |
| Tripwire trip (new fingerprint, lock removed) | High | Critical |

Both profiles run the same graph engine; only node weights, thresholds and which paths are surfaced differ. That reuse is itself part of the evidence for the architecture.

**Confidence rule (decided):** each observation carries an ordinal confidence (Low, Medium, High). A path's confidence is the weakest link along it. Severity is kept separate from confidence and is never multiplied into it. Revisit only if the validation study shows this ranks paths badly.

## 7. What the output may and may not claim

**May say:** "These attack paths exist given what we could observe and what you told us. Changing these three settings removes four of six."

**Must say, every time:** what was observed, what was declared, what is invisible, and the confidence of each reading.

**Must never say:** "your device is safe", "no malware found", "protected", or any single score.

## 8. False-alarm and harm controls

A tool that cries wolf gets uninstalled, and one that gives careless advice can hurt people.

**Trust tiers.** Every app is classified before any rule runs: platform-signed, system, OEM preinstalled, store-installed, sideloaded, or unknown. Phones ship with many system apps that legitimately hold special access (an OEM's own accessibility and notification services, for example). They are shown but not flagged as threats. The classifier uses signals that normal apps can read, such as the system flag, a signature match against the platform package, and the install source, and the observability plan tests how reliable they are, including install-source spoofing by `adb install -i` (?).

**Legitimate use of dangerous capabilities.** Accessibility services are essential assistive technology, and password managers and screen readers use them too. The app never states that an accessibility service is malicious. It states the capability, the install source and the trust tier, and says plainly that some people depend on such services, so disabling one has a cost.

**Advice safety.** The app never recommends rooting, flashing or disabling security features. It never recommends a factory reset without a data-loss warning. It never uses alarming language, and every recommendation links to the evidence that produced it and the trade-off of acting on it.

## 9. The tool as an attack surface

The app holds a sensitive inventory (installed apps, networks, grants), which makes it a target in its own right.

- The local store is encrypted at rest with a Keystore-bound key.
- Exports redact identifiers by default; sharing a full report is an explicit user action.
- Screens that list the inventory use the platform's secure-window flag. A malicious accessibility service can still read the screen, which is listed as a blind spot.
- The app makes no network requests other than the logged, user-visible probes.
- Rule packs and signer lists are signed and listed in a hash manifest, with version numbers that cannot be rolled back, so a tampered rule pack cannot silently suppress warnings.
- Releases publish SHA-256 checksums and the project's signing-certificate fingerprint, and the README tells users how to detect a clone.

## 10. Validation plan

1. Encode about 30 documented mobile campaigns and malware behaviors (from the ATT&CK Mobile technique pages and public reports) as scenario graphs.
2. Reproduce their enabling configurations with benign fixture apps that declare the same capabilities (no real malware).
3. Measure whether the model surfaces the enabling paths, how small the proposed fix set is, and how often a clean baseline device raises false alarms.
4. Compare the exact minimum fix set against a greedy approximation on synthetic graphs of 300 or more nodes, and document the size at which exact search stops being tractable.

## 11. Open questions

- Which readings are actually available to a normal app across API levels and OEMs? (`OBSERVABILITY_PLAN.md`)
- Can dangerous-capability apps be enumerated without `QUERY_ALL_PACKAGES`? (?)
- Is a Keystore tripwire reliable across reboots, updates and OEMs? (?)
- Which OEM protective features expose state a normal app can read? Not investigated (no Samsung data; the model carries no OEM-specific controls).
- Does periodic background snapshotting survive aggressive OEM power management? (?)
- How reliable is trust-tier classification, including install-source spoofing? (?)
