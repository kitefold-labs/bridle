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

2. **Tracking.** `TerminalModeTracker` parses OSC 133 and OSC 7 (`ShellEvents` in
   `:core:terminal`). The engine passes each `ShellEvent` to `onShellEvent` in output order, and
   folds them into `shellPrompt`: whether the shell has the integration, whether it is at a
   prompt, the pane of the last code, and each pane's working directory. An OSC 7 directory
   belongs to the pane whose `A` follows it. Malformed codes, and paths with control characters
   or invalid UTF-8, are dropped.

3. **The typed line.** `PromptLine` builds the line from the bytes Bridle sends; it does not
   read the screen. Every send (soft and hardware keyboard, key bar, paste, compose box) goes
   through one decorator on `TerminalConnection`:
   - `A` starts a new, empty, known line. `B` alone is a redraw and keeps the line.
   - Printable text and bracketed pastes of printable text are appended. DEL and BS remove a
     character, Ctrl-U clears the line, Ctrl-L changes nothing.
   - Ctrl-W removes the last word only when it is letters, digits and `_ . ~ / -`, where bash's
     unix-word-rubout and zsh's backward-kill-word agree; otherwise the line becomes unknown.
   - Any other control byte or escape sequence (Enter, arrows, history, Tab, a tmux prefix key,
     mouse reports) makes the line unknown, and the strip hides until the next `A`.
   - Bytes sent while the line isn't known, and not yet submitted with Enter or Ctrl-C
     (typeahead during a command, a key pressed after a tmux pane switch), make the next `A`
     unknown too, because the shell may already hold them.
   - Terminal replies on the same path (cursor position, device attributes, mode reports,
     focus events, OSC and DCS replies) are not keystrokes and are ignored.

4. **Completion.** `PathWord` takes the word at the end of the line as bash and zsh read it:
   it splits on unquoted whitespace, operators (`; | & < > ( )`) and `=` (for `--opt=path`),
   removes quotes and backslashes, and remembers a quote still open at the end. It resolves the
   word against the working directory (`~/…` against home, absolute and relative paths). Words
   the shell would expand (`$`, backticks, `* ? [ {`, `!`, `~user`) and comments get no
   suggestions. A chosen name is escaped for the quote the line is in: backslashes unquoted,
   `'\''` inside single quotes, and `\` before `" \ $` and backticks inside double quotes,
   where a name containing `!` is never offered (history expansion).

   `PathCompleter` lists the directory over SFTP on the host's existing connection. Each
   listing is cached until the next `A` (a directory that can't be listed is cached as empty),
   and at most 10,000 entries are read from one directory. A word in command position (the first
   word, or one after `; | & ( )`) is only completed when it contains a `/`, because otherwise
   the shell looks it up in PATH. Prefixes are case-sensitive. Directories, and symlinks to
   them, come first with a trailing `/`; dotfiles appear only when the word starts with `.`;
   names with control characters or invalid UTF-8 are never offered. An I/O error drops the
   SFTP session, and the next request opens a new one.

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
| `:core:tmux` | Done: `ShellIntegration` uploads the helper and scripts with checksum checks and runs `install-shell`; `HostProbe.shellIntegration` reports which rc files load it; removing the helper also runs `uninstall-shell`. |
| `:core:terminal-ui` | Done: `TerminalModeTracker` parses OSC 133 (with `aid`) and OSC 7; `TerminalEngine.shellPrompt` and `onShellEvent`. |
| `:core:terminal` | Done: `ShellEvent`, `ShellEvents.parseOsc` and the `ShellPrompt` reducer; `PromptLine`, the line model; `PathWord`, which extracts, unquotes, resolves and escapes the last word. |
| `:core:terminal-ui` | Done: `PathCompleter` (SFTP listing, cache, symlinks, ordering). To do: a `TerminalConnection` decorator feeding `PromptLine`, and the `SuggestionStrip` composable, placed between `TerminalPane` and the key bar in `TerminalScreen`. |
| `:app` | Done: install and remove buttons on the Sessions host card, with a consent dialog showing checksums. To do: give each terminal tab a `PathCompleter` on its host's connection, and a per-host "Command-line suggestions" toggle. |
| `docs/` | `host-helper.md`, `terminal-input.md` (codes and security), a tutorial section. |

## Order of work

1. ~~Spikes~~ (see results above).
2. ~~`install-shell` / `uninstall-shell`, with tests that run bash and zsh and check the
   codes~~ (`ShellIntegrationTest`; CI installs zsh). ~~Install and remove from the app~~.
3. ~~Tracker changes and `ShellPrompt`~~.
4. ~~`PromptLine` and `PathWord`~~. `PathWordTest` has real bash (and zsh where installed)
   read back escaped names in every quoting context.
5. ~~Completion source with a fake SFTP session~~, then the strip and the setting.
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
