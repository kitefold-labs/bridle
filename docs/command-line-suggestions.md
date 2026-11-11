# Command-line suggestions

Status: **design, spikes done.** Nothing in the app uses this yet.

A strip of chips above the key bar suggests file and directory names while you type at a shell
prompt. Type `w` in a directory containing `work/` and a `work/` chip appears; tap it and Bridle
types `ork/`. Enter still runs the command, so you can always run exactly what you typed.

## How it works

1. **Shell integration on the host.** A small script, sourced from `~/.bashrc` or `~/.zshrc`,
   marks each prompt with OSC 133 and reports the working directory with OSC 7:

   | Code | Sent when |
   |------|-----------|
   | `OSC 133;A` | A prompt is about to be drawn |
   | `OSC 133;B` | The prompt is drawn and input starts (also on every prompt redraw, e.g. Ctrl-L) |
   | `OSC 133;C` | A command starts |
   | `OSC 133;D;status` | A command finished, or the line was abandoned with Ctrl-C (`D;130`, no `C`) |
   | `OSC 7;file://host/path` | Before each prompt; the path is percent-encoded |

   Inside tmux the script wraps each code in tmux's passthrough sequence, turns on
   `allow-passthrough` for its own pane, and tags each OSC 133 code with `aid=$TMUX_PANE`.
   tmux unwraps passthrough, so Bridle always receives plain OSC 133 and OSC 7.

2. **Tracking.** `TerminalModeTracker` parses OSC 133 and OSC 7 and publishes a `ShellPrompt`
   state on the engine: at a prompt or not, the working directory and the pane.

3. **The typed line.** Bridle builds the line from the bytes it sends; it does not read the
   screen. Every send (soft and hardware keyboard, key bar, paste, compose box) goes through one
   decorator on `TerminalConnection`:
   - `A` starts a new, empty, known line. `B` alone is a redraw and keeps the line.
   - Printable text is appended. DEL removes a character; Ctrl-U and Ctrl-W act as in readline.
   - Any other control byte or escape sequence (arrows, history, Tab, a tmux prefix key, mouse
     reports) makes the line unknown, and the strip hides until the next `A`.
   - Bytes sent between `C` and the next `A` (typeahead) make that line unknown.
   - Terminal query replies (`CSI row;col R`, `CSI ? … c`) are not keystrokes and are ignored.

4. **Completion.** Bridle takes the word before the end of the line and resolves it against
   the working directory (`~`, `~/…`, absolute and relative paths). Words containing `$`, `*`,
   backticks or unbalanced quotes get no suggestions. Entries come from SFTP over the host's
   existing connection; each directory listing is cached until the next `A`. Directories come
   first with a trailing `/`; dotfiles appear only when the word starts with `.`; names with
   control characters or invalid UTF-8 are never offered.

5. **Accepting.** Tapping a chip sends the rest of the name, shell-escaped (`\ `, `\'` …), as
   printable bytes. The line model records it, so picking a directory moves the strip straight
   on to that directory's entries.

## Spike results

The spikes ran the scripts in `host-helper/` in a pseudo-terminal and recorded every byte the
outer terminal received.

| Check | Result |
|-------|--------|
| bash 5.3, plain `PS1` | Codes in order: `A`, `B` after the prompt text, `C`, `D;status`. Ctrl-L re-sends `B` only. Ctrl-C sends `D;130` without `C`. A multi-line command sends no `B` for continuation lines. |
| bash `PROMPT_COMMAND` as a string ending in `;`, or as an array | The script's hooks go first and last; the user's entries are kept in order. |
| `$?` for the user's own hooks | Preserved in bash (`PROMPT_COMMAND`, bash-preexec `precmd`) and zsh (`precmd_functions`). |
| zsh 5.9, plain prompt | Same codes and behaviour as bash. |
| starship 1.22 (bash and zsh) | Prompt ends with `B`; starship's status module still shows the right code. |
| bash-preexec 0.6.0 | Works alongside; its `preexec`/`precmd` still run. |
| tmux 3.6 (local), 3.5a (Debian 13), 3.2a (Ubuntu 22.04) | Bridle receives plain OSC 133/7. 3.2a passes them without `allow-passthrough`, which it does not have. |
| tmux: prompt in a hidden window | Not forwarded. |
| tmux: prompt in a visible but inactive pane | Forwarded, tagged with that pane's `aid`. |
| tmux: switching panes or windows | Sends no codes. |
| tmux: timing | `A` and `B` arrive *before* tmux draws the prompt text, so their screen positions are meaningless inside tmux. |
| termlib 0.3.14 | Its own OSC 133 parser compares `A`/`B`/`C` with the whole payload, so it ignores them when they carry `aid=` (only inside tmux). It stores `D`'s status as a string and never parses it. No change to termlib needed. |
| termlib's screen contents | `TerminalSnapshot` is Kotlin-`internal` (`getSnapshot$lib`). Not needed with the line model. |

Not yet checked: fish 4 (which emits OSC 133 and OSC 7 itself), oh-my-zsh and powerlevel10k,
and a real phone.

## Rules for edge cases

- **Terminal height.** Showing or hiding a row would resize the terminal, which resizes the
  remote PTY and makes programs redraw. The strip's row is reserved for as long as the tab has
  seen an OSC 133 code, and left empty when there is nothing to suggest.
- **Panes.** While the line is known for pane P, codes from other panes are ignored, so a
  command finishing in a visible split doesn't take over. Once the line is unknown, the next `A`
  from any pane takes over. Remaining gap: after switching panes with the prefix key, a prompt in
  a *visible, inactive* pane can take over before you type; its directory is used until your
  next prompt.
- **Shells without the script** send no codes, and the strip never appears.

## Security

Any program's output can forge these codes, for example a file shown with `cat`. The impact is
bounded: the strip may list names of real files the user can already read, and a tap types
escaped, printable text and never Enter. The OSC 7 path is used only for SFTP listings, which
are rate-limited.

## Work by module

| Module | Change |
|--------|--------|
| `host-helper/` | Done: `shell-integration.bash` and `shell-integration.zsh`, and `bridle-helper install-shell` / `uninstall-shell` (see [host-helper.md](host-helper.md#shell-integration)). |
| `:core:tmux` | Bundles the scripts (done). Uploads them with a checksum check, like `HostHelper.installScript`, then runs `install-shell`; detects whether the block is installed. |
| `:core:terminal-ui` | `TerminalModeTracker`: OSC 133 with `aid`, OSC 7 with percent-decoding. `TerminalEngine.shellPrompt: StateFlow<ShellPrompt>`. |
| `:core:terminal` | `PromptLine`: the line model. `PathWord`: extracts, unquotes and escapes words. Both pure Kotlin. |
| `:core:terminal-ui` | A `TerminalConnection` decorator feeding `PromptLine`. `SuggestionStrip` composable, placed between `TerminalPane` and the key bar in `TerminalScreen`. |
| `:app` | A completion source per host (SFTP session, cache, rate limit). A per-host "Command-line suggestions" toggle. "Install shell integration" on the host screen. |
| `docs/` | `host-helper.md`, `terminal-input.md` (codes and security), a tutorial section. |

## Order of work

1. ~~Spikes~~ (see results above).
2. ~~`install-shell` / `uninstall-shell`, with tests that run bash and zsh and check the
   codes~~ (`ShellIntegrationTest`; CI installs zsh).
3. Tracker changes and `ShellPrompt`.
4. `PromptLine` and `PathWord`, covered to the 95% gate.
5. Completion source with a fake SFTP session, then the strip, the setting and the install
   button.
6. Docs, then a phone run against bash and zsh, with and without tmux.

## Limits

- No suggestions after history, Tab or arrow keys until the next prompt.
- File and directory names only. Command names and git branches could use the same pipeline
  later.
- bash before 4.4 (macOS's `/bin/bash` 3.2) is skipped: it has no `PS0`, so it can't mark the
  start of a command.

## Open questions

- Should → on the key bar accept the first chip when the line is known, as zsh-autosuggestions
  does? Tapping works either way.
- Should the shell integration install with bridle-helper, or from its own button? The plan
  assumes its own button, since it edits `~/.bashrc`. Either way the app uploads the helper and
  the scripts, then runs `install-shell`.
