# Terminal (working name)

An Android SSH client built for managing long-running work on Linux machines.
It is modeled on Termius, with first-class support for detached `tmux` sessions:
launch, attach, restart and monitor tools like Claude Code from your phone.

> Status: **design phase**. No app code yet.

## Documents

| Doc | What it covers |
|-----|----------------|
| [docs/01-product-and-competitive-review.md](docs/01-product-and-competitive-review.md) | Goals, users, decisions made so far, review of Termius / JuiceSSH / ConnectBot / Termux / Blink / Prompt / Claude-specific tools, feature list |
| [docs/02-architecture.md](docs/02-architecture.md) | System architecture, modules, data model, SSH and terminal layers, tmux and Claude session model, host helper, security |
| [docs/03-implementation-plan.md](docs/03-implementation-plan.md) | Phased plan, milestones, spikes, testing strategy, risks, open questions |
| [docs/adr/](docs/adr/) | Architecture decision records |

## Decisions so far

- **Platform:** Android only, native Kotlin + Jetpack Compose.
- **Host side:** agentless by default (needs only `sshd` + `tmux`), with an optional helper script that adds richer Claude status.
- **Networks:** Tailscale/VPN and public IPs. On reconnect the app reattaches to tmux; no Mosh in v1.
- **Keys:** generated in the Android Keystore (hardware-backed, non-exportable).
- **Distribution:** public Play Store release.
- **Extras in scope:** SFTP file browser.
