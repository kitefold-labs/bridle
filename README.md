# Bridle

*by Kitefold Labs*

A free, open-source Android SSH client for supervising long-running work on
Linux machines. It is modeled on Termius, with first-class support for
detached `tmux` sessions: launch, attach to, restart and monitor Claude Code
from your phone.

> On a kite, the *bridle* is the set of lines that attaches the flying line to
> the kite and sets its angle. Bridle is how you steer sessions running far
> away.

> Status: **design phase**. No app code yet.

- **Package id:** `com.kitefoldlabs.bridle`
- **License:** [Apache License 2.0](LICENSE)
- **Price:** free, with no paid tiers, ads, accounts or backend
- **Home:** moving to the Kitefold Labs GitHub org (proposed
  `kitefoldlabs/bridle`). Bugs and crash reports go to GitHub Issues.

## Documents

| Doc | What it covers |
|-----|----------------|
| [docs/01-product-and-competitive-review.md](docs/01-product-and-competitive-review.md) | Goals, users, decisions, review of Termius / JuiceSSH / ConnectBot / Termux / Blink / Prompt / Claude-specific tools, feature list |
| [docs/02-architecture.md](docs/02-architecture.md) | System architecture, modules, data model, SSH and terminal layers, tmux and Claude session model, host helper, security |
| [docs/03-implementation-plan.md](docs/03-implementation-plan.md) | Phased plan, milestones, spikes, testing strategy, risks |
| [docs/adr/](docs/adr/) | Architecture decision records |

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
