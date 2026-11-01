#!/usr/bin/env python3
"""Fail if a JUnit test is written as an expression body that may not return Unit.

JUnit silently skips test methods whose return type isn't void, so
`fun foo() = runBlocking { ...; x shouldBe y }` can compile and never run.
Use `runBlocking<Unit> { ... }` (or a block body) instead.
"""

from __future__ import annotations

import re
import sys

PATTERN = re.compile(r"fun\s+[`\w][^(]*\([^)]*\)\s*=\s*\n?\s*runBlocking\s*\{", re.MULTILINE)


def main(paths: list[str]) -> int:
    failures = 0
    for path in paths:
        try:
            text = open(path, encoding="utf-8").read()
        except OSError as err:
            print(f"{path}: {err}", file=sys.stderr)
            failures += 1
            continue
        for match in PATTERN.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            print(f"{path}:{line}: use runBlocking<Unit> {{ … }} so JUnit runs the test")
            failures += 1
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
