# Tutorial: run Claude Code in tmux from your phone

This tutorial sets up a **Launch Profile** that starts Claude Code in a tmux session on one of
your Linux hosts. You then attach to it from Bridle, restart it with `claude --continue`, answer
it with quick replies, and pick it up again after the network drops. Claude keeps working on the
host the whole time: tmux holds the session, and Bridle only attaches to it.

You need:

- a host saved in Bridle that you can connect to;
- `tmux` 3.0 or newer on the host;
- Claude Code (`claude`) installed for your user on the host.

## 1. Check the host

Open the **Sessions** tab and pick the host from the chips at the top. Bridle connects and shows
a **This host** card:

- **tmux 3.x**: you're ready. If it says *tmux isn't installed* (or is too old), install it
  with the commands on the card, for example `sudo apt install tmux`, then tap **Refresh**.
- **Claude Code: /path/to/claude**: Bridle found `claude`. If it says Claude Code isn't on the
  PATH, profiles still work in the default mode, because the command is typed into your login
  shell, where nvm and your PATH are set up.

## 2. Create a Launch Profile for Claude

Under **Launch profiles**, tap **+** (New profile). The form starts from the Claude preset:

| Field | Preset | What it does |
|-------|--------|--------------|
| Name | `Claude · ~` | Shown on the card. |
| tmux session name | `claude` | The tmux session the profile runs in. Letters, digits, `-` and `_` only. |
| Working directory | `~` | Where Claude starts, e.g. `~/src/api`. Backslashes and control characters aren't allowed. |
| Command | `claude` | What **Start** and **Restart fresh** run. |
| Restart command | `claude --continue` | What **Restart (continue)** runs, so Claude picks up the last conversation. |
| Restart | Respawn and continue | How **Restart** works (see below). |
| Launch | Type the command into a login shell | The pane runs your shell and Bridle types the command, so the pane stays at a prompt when Claude exits. *Run the command directly* starts Claude as the pane's program instead. |
| Environment variables | none | Set for the command, e.g. `ANTHROPIC_MODEL`. Names use letters, digits and `_`. Values are masked; tap the eye to show one. |
| Pin to the top | off | Pinned profiles are listed first. |

Environment variables are set in the tmux session's environment, not typed into the pane, so a
value such as an `ANTHROPIC_API_KEY` never appears on screen, in the Sessions preview or in your
shell history on the host. Two limits: it is passed to `tmux` as a command-line argument for an
instant, which other users on a shared host could see in a process list, and programs running in
that tmux session can read it. *Press Ctrl+C twice, then type the command* restarts keep the
values the shell started with; the respawn restarts pick up edited values.

Set the working directory to your project and give the session a name you'll recognise, such as
`claude-api`. Fields with a problem are marked and explain what to fix; **Save** is enabled once
everything is valid.

## 3. Start and attach

Tap **Start** on the profile card. Bridle creates the tmux session in the working directory
(if it isn't running yet), starts Claude in it, and opens a terminal tab attached to it. The
card now says **Running** and its button reads **Attach**.

In the terminal:

- the **key bar** has the keys Claude Code needs (Esc, ⇧Tab, Ctrl+C, arrows);
- the **compose box** (pencil button) is the easiest way to write a long prompt. **Send** pastes
  it as one block and presses Enter, and ↑/↓ go through what you sent before on this host.

The tmux session also appears under **tmux sessions** with a **Claude** badge. You can attach to
any session there with **Attach**, including ones you started on your desktop. If your desktop is
attached at the same time, the window follows whichever device you used last, so the phone never
shrinks your desktop for good.

## 4. Restart with `--continue`

When Claude gets stuck or you've changed its configuration, restart it without losing the
conversation: on the profile card (or the session card) tap **Restart → Restart (continue)**.
Bridle respawns the pane in the working directory and runs the restart command,
`claude --continue`. **Restart → Restart fresh** runs the plain command and starts a new
conversation.

The profile's **Restart** setting chooses the method:

- **Respawn and continue the conversation** (default): kill the pane's processes, start a fresh
  pane in the same session and window, run the restart command.
- **Respawn with a fresh conversation**: the same, running the plain command.
- **Press Ctrl+C twice, then type the command**: for when you'd rather not kill the pane, such
  as when other programs share it.

If the session isn't running, Restart simply starts it.

## 5. Answer Claude without opening the terminal

Session cards for Claude show **Quick reply** chips that are sent with `tmux send-keys`:

| Chip | Sends | Use it to |
|------|-------|-----------|
| `y` / `n` | the key | answer a yes/no question |
| Esc | Escape | cancel, or decline a permission prompt |
| ⇧Tab | Shift+Tab | cycle Claude's permission mode |
| Ctrl+C | Ctrl+C | interrupt |
| `/compact` | the command and Enter | compact the conversation |
| `/clear` | the command and Enter | start over in the same session |

The card's preview shows the pane's last lines, so you can often see what Claude is asking
before you answer. **Stop** asks for confirmation and then kills the tmux session.

## 6. Pick up again after the network drops

Phones switch networks all the time. Bridle reconnects on its own, and tmux tabs come back to
where they were:

- When the connection drops, the terminal shows **Connection lost. Reattaching…**. Bridle
  reconnects with backoff, and immediately when the phone gets a network again, then reattaches
  to the same tmux session. When you move between Wi-Fi and mobile data, Bridle checks each
  connection within a few seconds and replaces the ones that no longer answer. Claude kept running on the host the whole time.
- A tab that ended (say, after you detached tmux) shows **Session ended** with a **Reconnect**
  button, which opens the same session again.
- Plain shell tabs (not tmux) can't survive a drop. To make every connection to a host land in
  tmux, set a **default tmux session** on the host.

Tabs stay open while Bridle is in the background, kept alive by a notification
("Bridle is connected") with **Disconnect all**. If Android stops Bridle anyway, nothing is lost:
attach again from the Sessions tab.

Next: [get notified when Claude needs you](03-claude-status-notifications.md).
