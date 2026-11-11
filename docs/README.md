# Bridle documentation

## Tutorials

Step-by-step guides for using the app.

1. [First connection](tutorials/01-first-connection.md): create a key, add a
   host, install the key, connect.
2. [Claude in tmux](tutorials/02-claude-in-tmux.md): Launch Profiles, the
   terminal's key bar and compose box, attach, restart with `--continue`.
3. [Claude status and notifications](tutorials/03-claude-status-notifications.md):
   the Sessions screen, status badges, the host helper, Approve / Deny.
4. [Files, snippets and port forwards](tutorials/04-files-and-forwards.md):
   SFTP, transfers, the editor, saved commands, local and SOCKS forwards.
5. [Backups and a new phone](tutorials/05-backup-and-new-phone.md):
   encrypted export and import.

## Reference

| Doc | What it covers |
|-----|----------------|
| [host-helper.md](host-helper.md) | The optional `bridle-helper` script for accurate Claude status |
| [terminal-input.md](terminal-input.md) | Key encodings, safe bracketed paste, customising the extra-keys bar, file-name suggestions |
| [backup-and-privacy.md](backup-and-privacy.md) | What backups contain, how they are encrypted, how crash reports are scrubbed |

## Design

| Doc | What it covers |
|-----|----------------|
| [01-product-and-competitive-review.md](01-product-and-competitive-review.md) | Goals, users, competitive review, feature list |
| [02-architecture.md](02-architecture.md) | Modules, data model, SSH and terminal layers, tmux/Claude model, security |
| [03-implementation-plan.md](03-implementation-plan.md) | Phases, testing strategy, risks, decisions |
| [command-line-suggestions.md](command-line-suggestions.md) | Design and spike results for file-name suggestions at the shell prompt |
| [adr/](adr/) | Architecture decision records |

## Contributing and releasing

| Doc | What it covers |
|-----|----------------|
| [development.md](development.md) | Building, quality gates (≥95% coverage, lint, licences), pre-commit hooks |
| [release.md](release.md) | Versioning, signing keys, release workflow, Play Console (FGS, Data Safety), F-Droid, reproducible builds |
