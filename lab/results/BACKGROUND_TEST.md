# Background-execution test log (Q9, signals B1 to B3)

Device: Sony Xperia 10 II (au SOV43), Android 12 (API 31), default battery settings, probe app `lab.probe.q` (older build, left untouched so the job is never reset).

## What is being measured

A 15-minute periodic job (JobScheduler, persisted) appends one line to `files/heartbeat.log` each time Android actually runs it, with the app's standby bucket, background-restriction flag and battery-optimization flag. The question: under normal use (phone on battery, screen off, mostly idle, app never opened), what fraction of the expected runs are delivered, how long are the gaps, and how does the standby bucket move?

## Timeline (phone clock = PC clock within about 1 second; times are UTC+06:00)

| When | Event |
|---|---|
| 2026-10-08 16:04:54 | Job scheduled; first run. Phone plugged in, screen on. |
| 2026-10-08 16:05 to about 17:25 | **Not a valid window.** Phone was charging with the screen on, so Doze could not engage. Runs in this window are kept in the log but excluded from the delivery statistic. |
| 2026-10-08 17:23:25 | Standby bucket moved from ACTIVE (10) to FREQUENT (30) while still plugged in. |
| **2026-10-08 about 17:26 (PC clock 17:26:06 when logged)** | **User reports the phone unplugged. Start of the clean window.** The exact instant will be refined from the phone's battery history (`dumpsys batterystats --history`) when it is reachable again. |
| **2026-10-09 about 16:06 to about 17:06 (the user's report at 17:47)** | **Not a valid window.** The phone was used for an hour for USB tethering (plugged in, in use, Wi-Fi off). Runs in this hour are excluded; the exact plug and unplug instants will be taken from the battery history (`dumpsys batterystats --history`). The clean window before it is 2026-10-08 17:26 to 2026-10-09 16:06, 22 h 40 min. A second clean window starts when the phone is again unplugged, screen off and on Wi-Fi, and the first hours of it include the standby bucket settling after use, which is reported separately. |

## Conditions for the clean window

Phone unplugged, screen off, lying still, Wi-Fi on, probe never opened or force-stopped, no battery-optimization changes. If the battery gets low and the phone is charged, the time is noted (and is also recoverable from the battery history), and those stretches are excluded.

## Read-out plan

- Earliest useful read: 24 hours after the start, 2026-10-09 about 17:30. Better: 48 hours, 2026-10-10 about 17:30.
- To read it, wireless debugging must be turned on again (the PC stays paired). Command: `adb exec-out run-as lab.probe.q cat files/heartbeat.log`.
- Statistics: runs delivered divided by runs expected (elapsed minutes / 15) inside the clean window; the longest gap; the number of gaps over 30 minutes; every standby-bucket transition and its time; and the phone's own record of plugged and idle (Doze) periods, to explain each gap.
- Report as "observed on this device and this firmware", not as a property of Android 12.

## Result (read out 2026-10-09 about 17:55, from the phone's own files)

Observed on one phone and one firmware (Sony Xperia 10 II, au SOV43, Android 12), default battery settings, one run. Not a property of Android 12. Raw files: `raw/xperia_heartbeat.log`, `raw/xperia_batterystats_history.txt`; statistics from `tools/heartbeat_report.py`.

- **Clean window:** 2026-10-08 17:25:48 (the phone's battery history: unplugged) to 2026-10-09 16:07:20 (plugged into the PC by USB for tethering), 22 h 41 min. The screen came on briefly at 09:49 (3 s) and 09:59 (18 s) and for good at 16:05; nobody reported the first two.
- **Delivered: 4 of about 90 expected runs (4%).** All four came in the first hour, while the phone was in light Doze (17:45, 18:04 and twice at 18:27, a catch-up pair). Deep Doze began at 18:29:23. After that **no run for 21 h 36 min**, until the phone was picked up at 16:05, although the phone's own record shows four deep-Doze maintenance windows (19:29, 21:29, 01:30 and 07:30, one, two, four and six hours apart), each only 30 seconds long.
- **Standby bucket:** FREQUENT while the runs were delivered (17:23 to 18:27); RARE by the first run after the long gap (10-09 16:33). The moment it changed is not recorded, because no run happened in between.
- **After the phone was picked up and used (16:05 on):** the job ran about every 15 minutes again (16:33 to 17:23, six runs), while the phone was in use and plugged in.
- **Before the clean window** (10-08 16:04 to 17:23, plugged in, screen on): 6 runs for about 5 expected. Not counted.
- **Not covered:** the second window the plan hoped for was not measured; the phone was used for USB tethering from about 16:06 to 17:06 and has been on the charger since 17:52.

What this means for the product: a scheduled snapshot cannot be relied on for monitoring in the background on a phone like this one. On a phone left alone overnight it may produce a handful of snapshots, then none for a day. The history therefore shows gaps as gaps, the timeline says the app only looks while it is open, and scheduled background work stays off. Whether the same job would be delivered if the app were exempted from battery optimization was not tested (it needs a permission the build gate forbids).

## Second read-out (2026-10-11 about 10:20)

The phone was left alone after the first read-out, apart from being on the charger from 2026-10-09 17:52 to 22:01 and touched at 2026-10-10 14:49. Read with the same two files (`raw/xperia_heartbeat_2.log`, `raw/xperia_batterystats_history_2.txt`); a "clean" stretch below is any time the battery history says unplugged with the screen off.

| Clean stretch | Hours | Runs delivered of expected |
|---|---|---|
| 10-08 17:25 to 10-09 09:49 | 16.4 | 4 of 65 |
| 10-09 10:00 to 10-09 16:05 | 6.1 | 0 of 24 |
| 10-09 22:01 to 10-10 10:00 | 12.0 | 0 of 47 |
| 10-10 10:03 to 10-10 14:49 | 4.8 | 0 of 19 |
| 10-10 15:27 to 10-11 10:00 | 18.5 | 5 of 74 |
| **Total** | **57.8** | **9 of 229 (3.9%)** |

- The 4 runs of the first stretch were all in the first hour after the unplug. The 5 runs of the last stretch came in pairs at 18:59 and 22:59 and singly at 05:00, which are deep-Doze maintenance windows; the app's standby bucket was RARE by then (every run since 10-09 18:20 reports RARE).
- The 12-hour stretch that began when the charger was removed at 22:01 had no run at all, not even in its first hour.
- The longest time between two runs in the whole log is 24 h 06 min (10-09 18:53 to 10-10 18:59).
- Read: a 15-minute job from an app that is never opened is delivered a few times a day at most on this phone, at times the app does not choose. One phone, one firmware, one run.
