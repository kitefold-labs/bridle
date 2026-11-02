# Security policy

Bridle holds the keys to your servers, so we take security reports seriously.

## Reporting a vulnerability

Please use **GitHub private vulnerability reporting**:
<https://github.com/kitefold-labs/bridle/security/advisories/new> ("Report a vulnerability"
under the repository's *Security* tab). Don't open public issues for security problems.

Include affected version(s), reproduction steps and impact. We aim to acknowledge reports within
3 business days and to ship a fix for confirmed high-severity issues within 30 days. We credit
reporters in release notes unless you ask us not to.

## Supported versions

Only the latest release on Google Play / GitHub Releases receives security fixes.

## Security model (summary)

- **Keys:** generated keys live in the Android Keystore (StrongBox when available) and can't be
  exported. Imported keys are stored only as AES-256-GCM ciphertext under a Keystore-wrapped key.
- **Passwords** are used once to install a public key and are never stored.
- **Host keys:** trust on first use with SHA-256 fingerprints. A changed host key blocks the
  connection until you explicitly accept it.
- **Remote commands:** every value interpolated into a shell command is validated and
  single-quoted by one audited function (`ShellQuote`).
- **No telemetry, no servers.** Bridle only connects to hosts you configure. Crash reports are
  opt-in, shown to you in full, and filed as GitHub issues by you.
- **Logs** never contain secrets, hostnames, usernames or terminal content.

See [docs/02-architecture.md](docs/02-architecture.md) §6 for details.
