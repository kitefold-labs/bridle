# Development guide

This guide covers building Bridle, the quality gates every change must pass, and how the code is
organised. For the *why*, read [02-architecture.md](02-architecture.md).

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 21 | Temurin recommended. Bytecode targets Java 17. |
| Android SDK | API 36 platform | Needed for the Android modules only. Set `ANDROID_HOME` or `local.properties`. |
| Python | 3.10+ | For `pre-commit` and helper scripts. |
| tmux | 3.0+ | `:core:tmux` integration tests drive a real tmux server. |

The Gradle wrapper (`./gradlew`) downloads the pinned Gradle version on first use.

## Everyday commands

```sh
./gradlew check                 # compile, unit + integration tests, ktlint, detekt
./gradlew koverVerify           # fail if coverage < 95% (lines AND branches)
./gradlew koverHtmlReport       # open build/reports/kover/html/index.html
./gradlew ktlintFormat          # auto-fix formatting
./gradlew checkLicense          # every runtime dependency must be Apache-2.0-compatible
```

## Quality gates

Every pull request must pass all of these in CI (`.github/workflows/ci.yml`):

1. **Pre-commit hooks**: whitespace, YAML/TOML/JSON validity, merge markers, large files,
   private-key detection, [gitleaks](https://github.com/gitleaks/gitleaks) secret scanning,
   shellcheck.
2. **ktlint** (official Kotlin style) and **detekt** (complexity and smells, zero issues allowed).
3. **Tests**: JUnit 6 + Kotest assertions/property tests; Robolectric for Android code.
4. **Coverage ≥ 95%** for both **lines and branches** over handwritten code, merged across all
   modules and measured with the JaCoCo engine via Kover. JaCoCo's Kotlin filters ignore
   compiler-generated members (default-argument bridges, data-class methods, serialization
   constructors). The only class-level exclusions are generated sources: `BuildConfig`, `R`,
   Hilt/Dagger factories, Room `_Impl` classes, Compose singletons, and `@Preview` functions.
   Don't add exclusions to dodge the gate. Write the test.
5. **License check**: runtime dependencies must be on the allow-list in
   `config/license/allowed-licenses.json`. No GPL/AGPL.
6. **CodeQL** (`security-extended` queries) for Kotlin/Java.

### Pre-commit hooks

```sh
pip install pre-commit
pre-commit install --hook-type pre-commit --hook-type pre-push
```

Commit hooks are fast: hygiene checks, secret scanning, ktlint. The pre-push hook runs the full
`check koverVerify`. To run every hook on the whole tree: `pre-commit run --all-files`.

## Module map

| Module | Kind | Responsibility |
|--------|------|----------------|
| `:core:model` | JVM | Domain types, validation, snippet templates |
| `:core:ssh` | JVM | SSH engine (sshj), host-key trust, shell quoting, ssh_config import, reconnect policy |
| `:core:tmux` | JVM | tmux command builders/parsers, launch/restart orchestration, helper protocol |
| `:core:*` / `:feature:*` (Android) | Android | Keystore, Room, terminal UI, screens, service |

Logic lives in JVM modules wherever possible: they are fast to test and easy to cover. Android
modules stay thin.

## Conventions

- `explicitApi()` is on for library modules, so public API is deliberate.
- Warnings are errors.
- Anything interpolated into a remote command **must** go through `ShellQuote` and be validated
  by `Validators`. Reviewers reject string-concatenated shell commands.
- Secrets never go in logs, exceptions, crash reports or the database (see `SECURITY.md`).
- Commits: imperative subject (≤ 72 chars), body explaining *why*.

## Working behind a restricted network

If Maven Central rate-limits you, point Gradle at a mirror with a user-level init script (do not
commit it), e.g. `~/.gradle/init.d/mirror.gradle` adding
`https://maven-central.storage-download.googleapis.com/maven2/` ahead of the default
repositories.
