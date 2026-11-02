# Bridle

*by Kitefold Labs*

A free, open-source Android SSH client for supervising long-running work on
Linux machines. It is modeled on Termius, with first-class support for
detached `tmux` sessions: launch, attach to, restart and monitor Claude Code
from your phone.

> On a kite, the *bridle* is the set of lines that attaches the flying line to
> the kite and sets its angle. Bridle is how you steer sessions running far
> away.

> Status: **in development**. See [CONTRIBUTING.md](CONTRIBUTING.md) to get involved.

- **Package id:** `com.kitefoldlabs.bridle`
- **License:** [Apache License 2.0](LICENSE)
- **Price:** free, with no paid tiers, ads, accounts or backend
- **Home:** [github.com/kitefold-labs/bridle](https://github.com/kitefold-labs/bridle). Bugs and
  crash reports go to [GitHub Issues](https://github.com/kitefold-labs/bridle/issues).

## Documents

Start at the [documentation index](docs/README.md), which also lists the tutorials.

| Doc | What it covers |
|-----|----------------|
| [docs/01-product-and-competitive-review.md](docs/01-product-and-competitive-review.md) | Goals, users, decisions, review of Termius / JuiceSSH / ConnectBot / Termux / Blink / Prompt / Claude-specific tools, feature list |
| [docs/02-architecture.md](docs/02-architecture.md) | System architecture, modules, data model, SSH and terminal layers, tmux and Claude session model, host helper, security |
| [docs/03-implementation-plan.md](docs/03-implementation-plan.md) | Phased plan, milestones, spikes, testing strategy, risks |
| [docs/host-helper.md](docs/host-helper.md) | The optional `bridle-helper` script for accurate Claude status |
| [docs/terminal-input.md](docs/terminal-input.md) | Key encodings, safe bracketed paste, and customising the extra-keys bar (JSON format) |
| [docs/backup-and-privacy.md](docs/backup-and-privacy.md) | What encrypted backups contain (and never contain), how they are encrypted, how crash reports are scrubbed and filed |
| [docs/tutorials/01-first-connection.md](docs/tutorials/01-first-connection.md) | Tutorial: generate a key, add a host (with Tailscale tips), install the key, connect and verify the fingerprint |
| [PRIVACY.md](PRIVACY.md) | Privacy policy: no data collection, no servers, opt-in crash reports you file yourself |
| [docs/tutorials/04-files-and-forwards.md](docs/tutorials/04-files-and-forwards.md) | Tutorial: browse, edit and transfer files over SFTP; save and run snippets; preview a dev server through a local port forward |
| [docs/tutorials/05-backup-and-new-phone.md](docs/tutorials/05-backup-and-new-phone.md) | Tutorial: encrypted export and import, why keys aren't included, getting keys working on a new phone |
| [docs/development.md](docs/development.md) | Building, quality gates (≥95% coverage, lint, licenses), pre-commit hooks |
| [docs/release.md](docs/release.md) | Signing, the release workflow, Play Console (foreground service, Data Safety), F-Droid |
| [docs/adr/](docs/adr/) | Architecture decision records |

## Tutorials

- [Run Claude Code in tmux from your phone](docs/tutorials/02-claude-in-tmux.md): a Launch
  Profile for Claude, restarting with `--continue`, quick replies, reattaching after network drops
- [Claude status and notifications](docs/tutorials/03-claude-status-notifications.md): installing
  bridle-helper, what the badges mean, Approve/Deny from notifications, and the limits (only while
  connected)

## Decisions

- **Platform:** Android, native Kotlin + Jetpack Compose. Phones come first;
  tablets and foldables come later.
- **Host side:** agentless by default (needs only `sshd` + `tmux`), with an
  optional helper script that adds richer Claude status.
- **Networks:** Tailscale/VPN and public IPs. On reconnect the app reattaches
  to tmux; no Mosh in v1.
- **Keys:** generated in the Android Keystore (hardware-backed,
  non-exportable). Existing OpenSSH keys can also be imported; they are
  stored encrypted.
- **Claude-first:** Launch Profiles take any command, but the only built-in
  presets are for Claude Code.
- **Notifications:** only while connected. There is no push service and no
  server.
- **Distribution:** public Play Store release, Apache-2.0, free.
- **Extras in scope:** SFTP file browser.
