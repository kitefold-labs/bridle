# Releasing Bridle

This is the checklist for cutting a release and for the one-time store setup.
Releases ship in three places: Google Play, GitHub Releases, and (optionally)
F-Droid. All three are built from the same tagged commit.

## Versioning

- `versionName` follows SemVer (`0.1.0`); `versionCode` increases by one for
  every build uploaded anywhere. Both live in `app/build.gradle.kts`.
- Tag the release commit `v<versionName>` (for example `v0.1.0`). The
  [release workflow](../.github/workflows/release.yml) only runs on tags.
- Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500
  characters max). F-Droid and the Play listing both use it.

## Signing keys

Kitefold Labs holds every key. None of them are in the repository.

| Key | Used for | Where it lives |
|-----|----------|----------------|
| Play **app-signing** key | Signs what Play delivers to phones | Google (Play App Signing); never downloaded |
| Play **upload** key | Signs the AAB uploaded to Play | Offline, backed up in two places; optionally a CI secret |
| **GitHub release** key | Signs APKs on GitHub Releases (and F-Droid, if it reproduces our build) | Offline, backed up; optionally a CI secret |

Create a key (RSA 4096, 30-year validity) with:

```sh
keytool -genkeypair -v -keystore bridle-release.jks -alias bridle \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Kitefold Labs, O=Kitefold Labs"
```

Publish the SHA-256 certificate fingerprint of the GitHub release key in the
README so users can check sideloaded APKs:

```sh
keytool -list -v -keystore bridle-release.jks -alias bridle | grep SHA256
apksigner verify --print-certs Bridle-*.apk
```

### How the build picks up a key

`app/build.gradle.kts` reads four values, each from a Gradle property or an
environment variable. Without `storeFile`, `assembleRelease` produces an
**unsigned** APK, which is what F-Droid's build server expects.

| Gradle property | Environment variable |
|-----------------|----------------------|
| `bridle.signing.storeFile` | `BRIDLE_SIGNING_STOREFILE` |
| `bridle.signing.storePassword` | `BRIDLE_SIGNING_STOREPASSWORD` |
| `bridle.signing.keyAlias` | `BRIDLE_SIGNING_KEYALIAS` |
| `bridle.signing.keyPassword` | `BRIDLE_SIGNING_KEYPASSWORD` |

For a local signed build, put them in `~/.gradle/gradle.properties` (never in
the project's `gradle.properties`). Signing uses APK Signature Scheme v2 and v3
only; v1 (JAR signing) is off because `minSdk` is 28.

### Signing in CI (optional)

To let the release workflow sign, add these repository secrets:
`BRIDLE_SIGNING_KEYSTORE_B64` (`base64 -w0 bridle-release.jks`),
`BRIDLE_SIGNING_STOREPASSWORD`, `BRIDLE_SIGNING_KEYALIAS`,
`BRIDLE_SIGNING_KEYPASSWORD`. Without them the workflow still builds, but the
artifacts are unsigned and you sign them offline with `apksigner`. Keeping the
keys offline is the safer default; use a protected GitHub *environment* with
required reviewers if you do store them.

## Cutting a release

1. On `main`, update `versionCode`/`versionName`, add the changelog file, and
   merge through a PR as usual (CI must be green).
2. Tag and push: `git tag -s v0.1.0 -m "Bridle 0.1.0" && git push origin v0.1.0`.
3. The release workflow reruns the full gate (tests, ≥95% coverage, lint,
   licences), builds the APK and AAB with no build cache, writes
   `SHA256SUMS`, and creates a **draft** GitHub Release with the files.
4. If CI did not sign: download the artifacts, sign the APK with the GitHub
   release key (`apksigner sign --ks bridle-release.jks ...`) and the AAB with
   the upload key (`jarsigner`), then replace the files on the draft.
5. Upload the AAB to the Play internal track, smoke-test it on a phone
   (connect over Tailscale and a public IP, launch and restart Claude, SFTP
   upload, backup round trip), then promote to closed, then production.
6. Publish the GitHub draft release.

## Google Play: one-time setup

- **Account:** a Kitefold Labs *organization* developer account (needs a
  D-U-N-S number). Check at signup whether the closed-testing requirement for
  new personal accounts applies; run an internal and a closed track anyway.
- **Play App Signing:** enable it and upload with the upload key.
- **Privacy policy URL:** link to [`PRIVACY.md`](../PRIVACY.md) on GitHub (or
  a Kitefold Labs page with the same text).
- **App access:** reviewers need an SSH server to get past the host list.
  Either explain that the app is a client for the user's own servers and
  provide a disposable demo host (user, password, `tmux` installed) in the
  "App access" section, or record a short video of the main flow.
- **Target SDK:** keep `target-sdk` in `gradle/libs.versions.toml` at the level
  Play requires for new apps and updates.
- **16 KB page size:** termlib's native libraries are 16 KB aligned (see
  ADR-0002). The APK ships only `arm64-v8a` and `x86_64`.

### Foreground service declaration

`SessionService` uses the `specialUse` type with the subtype text in the
manifest. In Play Console → App content → Foreground service permissions,
declare **Special use** and give:

- **Description:** "Bridle is an SSH client. While the user has an
  interactive terminal session or a file transfer open to their own server,
  a foreground service keeps that network connection alive when the app is in
  the background, so typing and output are not lost. The service shows an
  ongoing notification with the open sessions and a Disconnect action, and it
  stops as soon as the last session or transfer ends."
- **User impact if deferred:** the SSH connection drops when the user
  switches apps; any running transfer fails.
- **Video:** connect to a host, switch to another app, return to the
  terminal and show the session is still live; then disconnect from the
  notification.

### Data Safety form

Bridle has no backend, analytics, ads or crash SDK, and it connects only to
hosts the user adds. The expected answers are:

| Question | Answer |
|----------|--------|
| Does the app collect or share any required user data types? | **No** |
| Is all user data encrypted in transit? | Yes (SSH) |
| Do you provide a way for users to request that their data be deleted? | Not applicable; nothing leaves the device. Uninstalling or clearing app data removes everything |

Notes for whoever fills it in:

- Host names, usernames, keys, snippets and known hosts stay on the device
  and are excluded from Android backup and device-to-device transfer.
- The encrypted backup file is written to a location the user picks through
  the system file picker; the app never uploads it.
- Crash reports are opt-in. The app shows the scrubbed text and, only if the
  user taps *Report*, opens a pre-filled GitHub issue page in the browser; the
  user submits it from their own GitHub account. This is user-initiated
  sharing through another app, not collection by Kitefold Labs. Re-check
  Google's current definitions before each submission.
- Keep this table in sync with `PRIVACY.md` whenever networking, storage or
  permissions change.

## F-Droid

Bridle stays F-Droid-compatible: every dependency is open source (checked by
`checkLicense`), there are no Google Play Services, Firebase or proprietary
SDKs, and the APK carries no Google-encrypted dependency metadata
(`dependenciesInfo.includeInApk = false`). Store text and the changelog live
in `fastlane/metadata/android/`, which F-Droid reads directly.

To list it, open a merge request to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) with
`metadata/com.kitefoldlabs.bridle.yml` along these lines:

```yaml
Categories:
  - Development
  - Internet
License: Apache-2.0
AuthorName: Kitefold Labs
SourceCode: https://github.com/kitefoldlabs/bridle
IssueTracker: https://github.com/kitefoldlabs/bridle/issues

AutoName: Bridle

RepoType: git
Repo: https://github.com/kitefoldlabs/bridle.git

Builds:
  - versionName: 0.1.0
    versionCode: 1
    commit: v0.1.0
    subdir: app
    gradle:
      - yes

AllowedAPKSigningKeys: <sha256 of the GitHub release certificate, lowercase, no colons>

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.1.0
CurrentVersionCode: 1
```

With `AllowedAPKSigningKeys` and a matching `Binaries:` URL pointing at the
GitHub release APK, F-Droid rebuilds the app, checks that the result is
byte-identical apart from the signature, and ships *our* signed APK, so users
can move between GitHub Releases and F-Droid without reinstalling.

### Checking reproducibility

Before tagging, build twice from clean checkouts (different directories, same
JDK) and compare the unsigned APKs:

```sh
./gradlew clean :app:assembleRelease --no-build-cache
sha256sum app/build/outputs/apk/release/app-release-unsigned.apk
# or, against a signed release APK:
apksigcopier compare Bridle-v0.1.0.apk --unsigned app/build/outputs/apk/release/app-release-unsigned.apk
```

Differences usually come from a different JDK or build-tools version,
timestamps, or the build path. The Gradle wrapper and every dependency are
pinned, and AGP writes no build timestamps.

## When the repository moves to `kitefoldlabs/bridle`

- Update `GitHubIssueUrl.DEFAULT_REPO` in `core/backup` (crash reports open
  issues there) and its test.
- Update links in `README.md`, `SECURITY.md`, `PRIVACY.md`, `CONTRIBUTING.md`
  and the issue templates.
- Re-enable private vulnerability reporting, branch protection (required
  checks: *Pre-commit hooks*, *Build, test, coverage gate*, *Analyze*),
  Dependabot and CodeQL on the new repository.
- Move the signing secrets if CI signs.
