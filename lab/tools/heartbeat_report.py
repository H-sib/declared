#!/usr/bin/env python3
"""Statistics for the Xperia background test (Q9, results/BACKGROUND_TEST.md). Standard library only.

Reads the probe's `heartbeat.log` (one line per time Android actually ran the 15-minute job: epoch milliseconds, then
`ran bucket=N ...`) and, for each window given, reports how many runs were delivered against how many were expected, the
longest gap, the gaps over 30 minutes, and every standby-bucket change. Times are given in the phone's local time, UTC+06:00.

  python heartbeat_report.py heartbeat.log --window "2026-10-08 17:26" "2026-10-09 16:06" --window "2026-10-09 17:10" "2026-10-10 17:30"
"""
import argparse
import datetime as dt
import re
import sys

TZ = dt.timezone(dt.timedelta(hours=6))
PERIOD_MS = 15 * 60 * 1000
BUCKETS = {5: "EXEMPTED", 10: "ACTIVE", 20: "WORKING_SET", 30: "FREQUENT", 40: "RARE", 45: "RESTRICTED", 50: "NEVER"}


def parse_runs(text):
    """[(epoch_ms, bucket)] for every line that records a run."""
    runs = []
    for line in text.splitlines():
        m = re.match(r"^(\d{10,})\s*,\s*ran\b.*?\bbucket=(\d+)", line.strip())
        if m:
            runs.append((int(m.group(1)), int(m.group(2))))
    return sorted(runs)


def to_ms(local_text):
    return int(dt.datetime.strptime(local_text, "%Y-%m-%d %H:%M").replace(tzinfo=TZ).timestamp() * 1000)


def clock(ms):
    return dt.datetime.fromtimestamp(ms / 1000, TZ).strftime("%m-%d %H:%M")


def window_stats(runs, start_ms, end_ms):
    inside = [r for r in runs if start_ms <= r[0] <= end_ms]
    stamps = [start_ms] + [r[0] for r in inside] + [end_ms]
    gaps = [(a, b) for a, b in zip(stamps, stamps[1:])]
    longest = max(gaps, key=lambda g: g[1] - g[0])
    expected = max(1, (end_ms - start_ms) // PERIOD_MS)
    over = [(a, b) for a, b in gaps if b - a > 30 * 60 * 1000]
    changes = [(t, BUCKETS.get(p, p), BUCKETS.get(b, b)) for (_, p), (t, b) in zip(inside, inside[1:]) if p != b]
    return {"expected": expected, "delivered": len(inside), "ratio": len(inside) / expected, "longest": longest, "over_30": over, "bucket_changes": changes,
            "first_bucket": BUCKETS.get(inside[0][1], inside[0][1]) if inside else None}


def report(runs, windows):
    lines = [f"{len(runs)} runs in the log, {clock(runs[0][0])} to {clock(runs[-1][0])}" if runs else "no runs in the log"]
    for start, end in windows:
        s = window_stats(runs, to_ms(start), to_ms(end))
        lines.append(f"window {start} to {end}: delivered {s['delivered']} of {s['expected']} expected ({s['ratio']:.0%}); longest gap {(s['longest'][1] - s['longest'][0]) / 60000:.0f} min "
                     f"({clock(s['longest'][0])} to {clock(s['longest'][1])}); {len(s['over_30'])} gap(s) over 30 min; first bucket {s['first_bucket']}")
        for t, a, b in s["bucket_changes"]:
            lines.append(f"  {clock(t)} bucket {a} -> {b}")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--window", nargs=2, action="append", metavar=("START", "END"), required=True)
    args = ap.parse_args(argv)
    with open(args.log, encoding="utf-8", errors="replace") as f:
        print(report(parse_runs(f.read()), args.window))
    return 0


if __name__ == "__main__":
    sys.exit(main())
