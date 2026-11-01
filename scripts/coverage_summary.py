#!/usr/bin/env python3
"""Render a Kover/JaCoCo XML report as a Markdown summary (used for the CI job summary).

Usage: coverage_summary.py <report.xml> [threshold]
Exits non-zero only if the report is missing or unreadable; the gate itself is koverVerify.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET


def pct(missed: int, covered: int) -> float:
    total = missed + covered
    return 100.0 if total == 0 else covered * 100.0 / total


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: coverage_summary.py <report.xml> [threshold]", file=sys.stderr)
        return 2
    threshold = float(argv[2]) if len(argv) > 2 else 95.0
    try:
        root = ET.parse(argv[1]).getroot()
    except (OSError, ET.ParseError) as err:
        print(f"Coverage report unavailable: {err}")
        return 1

    print("## Coverage (handwritten code)\n")
    print("| Metric | Covered | Total | % | Gate |")
    print("|---|---:|---:|---:|:---:|")
    for counter in root.findall("counter"):
        kind = counter.get("type")
        if kind not in ("LINE", "BRANCH"):
            continue
        missed, covered = int(counter.get("missed", 0)), int(counter.get("covered", 0))
        value = pct(missed, covered)
        ok = "✅" if value >= threshold else "❌"
        print(f"| {kind.title()} | {covered} | {missed + covered} | {value:.2f} | {ok} |")

    rows = []
    for package in root.iter("package"):
        for counter in package.findall("counter"):
            if counter.get("type") == "LINE":
                missed, covered = int(counter.get("missed", 0)), int(counter.get("covered", 0))
                rows.append((package.get("name", "").replace("/", "."), pct(missed, covered)))
    if rows:
        print("\n<details><summary>Per package (lines)</summary>\n")
        print("| Package | Lines % |")
        print("|---|---:|")
        for name, value in sorted(rows):
            print(f"| `{name}` | {value:.2f} |")
        print("\n</details>")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
