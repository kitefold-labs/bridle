# Tutorial: Claude status and notifications

Bridle can tell you what Claude Code is doing on your hosts (working, waiting for your
permission, waiting for input, finished) and notify you when it needs you, so you can put the
phone down while Claude works. This tutorial installs the optional `bridle-helper` for accurate
status, explains the badges, and shows how to answer permission prompts from a notification.

It assumes Claude is running in tmux on a host, as set up in
[the previous tutorial](02-claude-in-tmux.md).

## 1. Allow notifications

On Android 13 and newer, the **Sessions** tab shows a card, *Get notified when Claude needs
you*. Tap **Allow** and accept Android's prompt. (**Not now** hides the card until you next open
the screen; you can also allow notifications later in Android's app settings.)

Which events notify is up to you in Bridle's settings: permission requests, Claude waiting for
input or finished, and Claude exiting can each be switched off, as can notifications as a
whole. Android's own settings also let you tune the *Claude status* notification channel.

## 2. Install bridle-helper (recommended)

Without the helper, Bridle **guesses** Claude's state from the last lines on its screen. That
works for the common cases but can be wrong. With the helper, Claude Code's own
[hooks](https://docs.claude.com/en/docs/claude-code/hooks) report every change, so the status is
exact.

1. On the **Sessions** tab, pick the host. The **This host** card says
   *Install bridle-helper for accurate Claude status*.
2. Tap **Install bridle-helper**. Bridle explains what it will do and shows the script's
   **SHA-256**. To check it, compare it with the output of
   `sha256sum host-helper/bridle-helper` in a checkout of the Bridle source at the same version.
3. Leave **Also add Claude Code hooks** checked. This adds Bridle's hooks to
   `~/.claude/settings.json` (your existing hooks are kept and a backup is made first). Without
   hooks the helper has nothing to report.
4. Tap **Install**. Bridle uploads the script to `~/.local/share/bridle/bridle-helper`,
   verifies its checksum on the host and installs the hooks. The card then says
   *Installed: bridle-helper*.

Claude sessions that were already running pick up the hooks the next time they start: use
**Restart (continue)** to keep the conversation.

The helper is a short, readable `sh` script. It needs no root, runs no daemon and never
connects to the network. See [host-helper.md](../host-helper.md) for the details and for
installing it by hand. To remove it, tap **Remove helper** on the host card: the hooks are taken
out of `~/.claude/settings.json` and the script is deleted. If *Use bridle-helper* is turned off
in Bridle's settings, status is guessed even where the helper is installed.

## 3. What the badges mean

Each tmux session card under **tmux sessions** can show:

- **Claude**: the session is running Claude Code.
- A **status chip**, such as *Needs permission · hooks*:

  | Status | Meaning |
  |--------|---------|
  | Working | Claude is busy. |
  | Needs permission | Claude asked to use a tool and is waiting for your answer. |
  | Waiting for input | Claude stopped and wants a reply. |
  | Idle | Claude finished its turn. |
  | Exited | Claude Code ended (the pane may be back at a shell prompt). |
  | Unknown | Claude is running but its state can't be told. |

  The second word says where the status comes from: **hooks** means bridle-helper reported it,
  exactly; **guess** means Bridle read it from the screen and may be wrong.
- The message Claude showed, for example *Claude needs your permission to use Bash*, and the
  last lines of the pane.

The card's first line says when the session was last active, how many windows it has, and
whether another client (such as your desktop) is attached.

## 4. Approve or Deny from a notification

When a session starts **waiting for your permission**, Bridle posts a notification such as
*claude-api · devbox needs your permission*, with two buttons:

- **Approve** picks *Yes* in Claude Code's permission menu (it sends `1`).
- **Deny** declines (it sends Escape), and Claude asks you what to do instead.

Tapping the notification itself opens Bridle attached to that session, so you can read the full
request first. Other notifications tell you when Claude **finished** or is **waiting for
you**, and when it **exited**. You won't be notified about a session you're looking at in the
terminal.

## 5. Limits: only while connected

Bridle has no server and uses no push service, by design. It watches a host only while it has a
connection to it:

- while a terminal tab to the host is open, including in the background (the *Bridle is
  connected* notification shows this), status is checked about every 45 seconds, and every
  5 seconds while Bridle is on screen;
- while the Sessions tab is showing the host.

When you close your tabs (or tap **Disconnect all**), notifications stop. Android may also stop
Bridle in the background to save battery; Claude keeps working in tmux, and you'll see where it
got to when you open Bridle again. A transition that happens while Bridle isn't connected is not
reported later: the first check after connecting only sets a baseline.
