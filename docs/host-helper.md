# bridle-helper (optional)

`bridle-helper` is a single POSIX `sh` script that Bridle can install on a host to show
**accurate Claude Code status** (working, waiting for permission, waiting for input, idle,
exited) on session cards and in notifications. Bridle works fully without it. Without the
helper, status is guessed from the visible screen and labelled as a guess.

## What it does

- It is called by [Claude Code hooks](https://docs.claude.com/en/docs/claude-code/hooks)
  (`SessionStart`, `UserPromptSubmit`, `PreToolUse`, `Notification`, `Stop`, `SessionEnd`).
- It writes one small JSON file per tmux session to
  `${XDG_STATE_HOME:-~/.local/state}/bridle/sessions/`, atomically (temp file + `mv`).
- `bridle-helper list` prints those files. Bridle reads them over your existing SSH connection.

It needs no root, runs no daemon, never makes network connections, and never blocks or fails
Claude Code: hook errors are swallowed and the hook always exits 0.

## Installing

In Bridle: **Sessions → (pick the host) → This host → Install bridle-helper**. Bridle shows the
script's SHA-256 and asks for confirmation, then uploads it to
`~/.local/share/bridle/bridle-helper`, verifies the checksum on the host, and (if you agree) runs
`bridle-helper install-hooks`. See the
[status and notifications tutorial](tutorials/03-claude-status-notifications.md).

`install-hooks` edits `~/.claude/settings.json` with Python's JSON module (never with text
substitution), keeps a timestamped backup next to it, keeps your existing hooks, and is
idempotent. `bridle-helper install-hooks --dry-run` prints the result without writing anything.

Manual install:

```sh
mkdir -p ~/.local/share/bridle
cp host-helper/bridle-helper ~/.local/share/bridle/ && chmod 700 ~/.local/share/bridle/bridle-helper
~/.local/share/bridle/bridle-helper install-hooks
```

## Removing

**Sessions → (pick the host) → This host → Remove helper**, or run
`bridle-helper uninstall-hooks` and delete the script. State files can be deleted with
`rm -r ~/.local/state/bridle`.

## Protocol

Each line of `list` output is a JSON object:

```json
{"v":1,"state":"waiting_permission","event":"Notification","tmux_session":"claude-api",
 "session_id":"…","cwd":"/home/me/src/api","message":"Claude needs your permission to use Bash","ts":1793558318}
```

`v` is the protocol version (`bridle-helper version` prints it). Unknown fields are ignored, so
newer helpers stay compatible with older apps.
