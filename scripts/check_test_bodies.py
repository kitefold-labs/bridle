#!/usr/bin/env python3
"""Fail if a JUnit test function may not return Unit.

JUnit silently skips test methods whose return type isn't void, so
`fun foo() = runBlocking { ...; x shouldBe y }` compiles and never runs. The same
happens for any other expression body that yields a value (`= shouldThrow<E> { }`,
`= runBlocking(Dispatchers.IO) { }`, ...), for an explicit non-Unit return type, and
for `suspend` test functions.
Use a block body, `runBlocking<Unit> { ... }` or `runTest { ... }` instead.
"""

from __future__ import annotations

import re
import sys

TEST = re.compile(
    r"@(?:Test|ParameterizedTest|RepeatedTest)\b[^\n]*\n"  # the test annotation
    r"(?:[ \t]*@[^\n]*\n)*"  # further annotations
    r"[ \t]*(?P<mods>(?:\w+[ \t]+)*)fun[ \t]+(?P<name>`[^`]+`|\w+)[ \t]*\((?P<params>[^)]*)\)"
    r"(?:[ \t]*:[ \t]*(?P<type>[\w<>?.,\s]+?))?"
    r"\s*(?P<body>=\s*\S+|\{)",
)
UNIT_BODIES = ("runBlocking<Unit>", "runTest")


def problem(match: re.Match[str]) -> str | None:
    if "suspend" in match.group("mods").split():
        return "suspend test functions are never run by JUnit; use runBlocking<Unit> { ... }"
    declared = (match.group("type") or "").strip()
    if declared:
        return None if declared == "Unit" else f"returns {declared}; JUnit only runs tests that return Unit"
    body = match.group("body")
    if body.startswith("="):
        expr = body[1:].strip()
        if not expr.startswith(UNIT_BODIES):
            return "use a block body, runBlocking<Unit> { ... } or runTest { ... } so JUnit runs the test"
    return None


def main(paths: list[str]) -> int:
    failures = 0
    for path in paths:
        try:
            text = open(path, encoding="utf-8").read()
        except OSError as err:
            print(f"{path}: {err}", file=sys.stderr)
            failures += 1
            continue
        for match in TEST.finditer(text):
            message = problem(match)
            if message:
                line = text.count("\n", 0, match.start("name")) + 1
                print(f"{path}:{line}: {match.group('name')}: {message}")
                failures += 1
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
