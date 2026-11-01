# Development guide

This guide covers building Bridle, the quality gates every change must pass, and how the code is
organised. For the *why*, read [02-architecture.md](02-architecture.md).

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 21 | Temurin recommended. Bytecode targets Java 17. |
| Android SDK | platform `android-37`, build-tools 36.1+ | Set `ANDROID_HOME` or `sdk.dir` in `local.properties`. AGP downloads missing packages if licences are accepted. |
| Python | 3.10+ | For `pre-commit` and helper scripts. |
| tmux | 3.0+ | `:core:tmux` integration tests drive a real tmux server. |

The Gradle wrapper (`./gradlew`) downloads the pinned Gradle version on first use.

## Everyday commands

```sh
./gradlew check                 # compile, unit + integration tests, ktlint, detekt
./gradlew koverVerifyBridle     # fail if coverage < 95% (lines AND branches)
./gradlew koverHtmlReportBridle # open build/reports/kover/htmlBridle/index.html
./gradlew ktlintFormat          # auto-fix formatting
./gradlew checkLicense --no-configuration-cache --no-parallel  # Apache-2.0-compatible deps only
```

## Quality gates

Every pull request must pass all of these in CI (`.github/workflows/ci.yml`):

1. **Pre-commit hooks**: whitespace, YAML/TOML/JSON validity, merge markers, large files,
   private-key detection, [gitleaks](https://github.com/gitleaks/gitleaks) secret scanning,
   shellcheck.
2. **ktlint** (official Kotlin style) and **detekt** (complexity and smells, zero issues allowed).
3. **Tests**: JUnit 6 + Kotest assertions/property tests; Robolectric for Android code.
4. **Coverage ≥ 95%** for both **lines and branches** over handwritten code, merged across all
   modules (JVM modules plus the **debug** build of Android modules, Kover variant `bridle`) and
   measured with the JaCoCo engine via Kover. JaCoCo's Kotlin filters ignore
   compiler-generated members (default-argument bridges, data-class methods, serialization
   constructors). The only exclusions are generated sources: `BuildConfig`, `R`, Hilt/Dagger
   generated classes, Room `_Impl` classes and kotlinx.serialization `$serializer`s. Composable
   code is measured like any other, `@Preview` functions and `ComposableSingletons` lambdas
   included: exercise them from Robolectric Compose tests. Don't add exclusions to dodge the
   gate. Write the test.
5. **License check**: runtime dependencies must be on the allow-list in
   `config/license/allowed-licenses.json`. No GPL/AGPL.
6. **CodeQL** (`security-extended` queries) for Kotlin/Java.

### Pre-commit hooks

```sh
pip install pre-commit
pre-commit install --hook-type pre-commit --hook-type pre-push
```

Commit hooks are fast: hygiene checks, secret scanning, ktlint. The pre-push hook runs the full
`check koverVerifyBridle`. To run every hook on the whole tree: `pre-commit run --all-files`.

## Module map

| Module | Kind | Responsibility |
|--------|------|----------------|
| `:core:model` | JVM | Domain types, validation, snippet templates |
| `:core:ssh` | JVM | SSH engine (sshj), host-key trust, shell quoting, ssh_config import, SFTP, port forwarding |
| `:core:tmux` | JVM | tmux command builders/parsers, launch/restart orchestration, helper protocol |
| `:core:session` | JVM | Shared per-host connections and reconnects, terminal tabs, Claude status monitor |
| `:core:terminal` | JVM | Key encoding, bracketed-paste sanitising, key-bar layouts, compose history |
| `:core:terminal-ui` | Android | `TerminalEngine` (termlib adapter), output coalescing, terminal screen state, key bar, compose box, status banner |
| `:core:backup` | JVM | Encrypted configuration backups, import merge planning, scrubbed crash reports |
| `:core:data` | Android | Room database, repositories, known-hosts store, backup import/export, DataStore settings |
| `:core:*` / `:feature:*` (Android) | Android | Keystore, terminal UI, screens, service |

Logic lives in JVM modules wherever possible: they are fast to test and easy to cover. Android
modules stay thin.

## Conventions

- **Keep Hilt entry points thin** (`@HiltAndroidApp`, `@AndroidEntryPoint` activities, services and
  receivers). Hilt's Gradle plugin rewrites their bytecode, so coverage recorded while they run
  can't be matched to the compiled classes and shows as 0%. Put the logic in injected classes
  (e.g. `SessionActions`, `SessionCoordinator`), where it is tested and measured. Entry points
  are still exercised by Robolectric tests.

- `explicitApi()` is on for library modules, so public API is deliberate.
- Warnings are errors.
- Anything interpolated into a remote command **must** go through `ShellQuote` and be validated
  by `Validators`. Reviewers reject string-concatenated shell commands.
- Secrets never go in logs, exceptions, crash reports or the database (see `SECURITY.md`).
- Commits: imperative subject (≤ 72 chars), body explaining *why*.

## Toolchain

Gradle 9.8.2 (wrapper, checksum-pinned), Android Gradle Plugin 9.5 with built-in Kotlin,
Kotlin 2.4, KSP2, Hilt, Compose (BOM), Room, Robolectric for Android unit tests. Every plugin is
declared once in the root `build.gradle.kts` (`apply false`) so they share a classloader; the
convention plugins in `build-logic` (`bridle.jvm.library`, `bridle.android.library`,
`bridle.android.application`, `bridle.android.compose`, `bridle.hilt`, `bridle.quality`) hold all
shared configuration. Android unit tests run for the `debug` variant only; Android Lint runs
with warnings as errors (`config/lint.xml` holds the few scoped, justified suppressions).

## Working behind a restricted network

If Maven Central rate-limits you, point Gradle at a mirror with a user-level init script (do not
commit it), e.g. `~/.gradle/init.d/mirror.gradle` adding
`https://maven-central.storage-download.googleapis.com/maven2/` ahead of the default
repositories. Robolectric downloads its Android framework jars separately; point it at a mirror
with `bridle.robolectric.repoUrl=<url>` in `~/.gradle/gradle.properties`.
