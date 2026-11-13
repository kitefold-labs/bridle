# Terminal input

This page describes what Bridle sends to the remote host when you press keys, paste text or use
the compose box, and how to write your own extra-keys bar. The logic lives in `:core:terminal`
(`KeyEncoder`, `BracketedPaste`, `KeyBarLayout`, `StickyModifiers`, `ComposeHistory`).

## Key encodings

Bridle behaves like xterm, which is what tmux and Claude Code expect (`TERM=xterm-256color`).
`CSI` is `ESC [` and `SS3` is `ESC O`. The reference is the xterm *Control Sequences* document
("PC-Style Function Keys" and "VT220-Style Function Keys").

| Key | Sends | With modifiers |
|-----|-------|----------------|
| Enter | `CR` (0x0d) | Alt: `ESC CR` |
| Tab | `HT` (0x09) | Shift: `CSI Z` (back-tab; cycles Claude Code's permission mode) |
| Backspace | `DEL` (0x7f) | Ctrl: `BS` (0x08). An option swaps the two for hosts that use `^H` |
| Escape | `ESC` | Alt: `ESC ESC` |
| ↑ ↓ → ← | `CSI A`/`B`/`C`/`D` | `CSI 1 ; m A` … |
| Home, End | `CSI H`, `CSI F` | `CSI 1 ; m H`, `CSI 1 ; m F` |
| Insert, Delete, PgUp, PgDn | `CSI 2~`, `CSI 3~`, `CSI 5~`, `CSI 6~` | `CSI n ; m ~` |
| F1–F4 | `SS3 P`/`Q`/`R`/`S` | `CSI 1 ; m P` … |
| F5–F12 | `CSI 15~ 17~ 18~ 19~ 20~ 21~ 23~ 24~` | `CSI n ; m ~` |

- **Modifier parameter** `m` = 1 + Shift·1 + Alt·2 + Ctrl·4 + Meta·8. For example
  Ctrl+↑ is `CSI 1;5A` and Ctrl+Shift+← is `CSI 1;6D`.
- **Application cursor mode.** When the remote program enables DECCKM (`CSI ? 1 h`, which
  vim, less and many TUIs do), unmodified arrows, Home and End are sent as `SS3 A` … instead of
  `CSI A` …. Modified keys always use the `CSI` form.
- **Ctrl + character** sends the classic control code: Ctrl+A … Ctrl+Z → 0x01–0x1a,
  Ctrl+Space / Ctrl+@ / Ctrl+2 → NUL, Ctrl+[ / Ctrl+3 → ESC, Ctrl+\ / Ctrl+4 → 0x1c,
  Ctrl+] / Ctrl+5 → 0x1d, Ctrl+^ / Ctrl+6 → 0x1e, Ctrl+_ / Ctrl+/ / Ctrl+7 → 0x1f,
  Ctrl+? / Ctrl+8 → DEL. Other characters are sent unchanged.
- **Alt (and Meta) + character** sends `ESC` followed by the character, e.g. Alt+B (word back in
  readline) is `ESC b`.
- All text is sent as **UTF-8**.

## Paste and the compose box

When the remote program turns on bracketed-paste mode (`CSI ? 2004 h`; Claude Code, bash,
zsh and vim all do), pasted text is wrapped in `ESC[200~` … `ESC[201~`. Programs then treat it
as one paste instead of typed keys: a shell doesn't run each line, and Claude Code keeps a
multi-line prompt together. The compose box's **Send** pastes the text this way and then sends
Enter outside the markers, so the prompt is submitted.

The terminal itself asks the keyboard for raw keystrokes, which turns off swipe typing,
autocorrect and suggestions. The compose box is an ordinary text field, so they all work there.
With **Settings → Enter sends from the compose box** on (the default), the keyboard's Enter key
becomes Send, and Enter in an empty box presses Enter in the terminal, so the box can stand in
for the terminal keyboard. Shift+Enter on a hardware keyboard still starts a new line. Turn the
setting off to write multi-line prompts on the soft keyboard; then only **Send** (or Ctrl+Enter)
sends.

Pasted text is always cleaned first:

- Any `ESC[201~` inside the text is removed. Without this, a malicious snippet (from a web page,
  say) could end the paste early and have the rest run as typed commands.
- Line endings (`\r\n`, `\n`) become `CR`, the byte the Enter key sends.
- Control characters are removed, except tab and line breaks. That includes NUL, ESC, DEL and
  the 8-bit C1 controls, so no escape sequence of any kind can come from pasted text.

Text the keyboard commits in one go (more than one character, such as a word, a suggestion or a
keyboard's clipboard strip) is cleaned the same way, so pasting through the keyboard can't
bypass this.

If the remote program has **not** enabled bracketed paste, every line break in a paste acts like
Enter, so each line runs as soon as it arrives. Bridle detects this case so it can warn you
before sending a multi-line paste.

## Sticky modifiers

The key bar's SHIFT, CTRL and ALT keys are sticky:

- **Tap** once: the modifier applies to the next key, then turns off. Tap again (slowly) to
  cancel.
- **Double-tap**: the modifier stays on until you tap it again.

CTRL and ALT apply to the next key on the bar or on the soft or hardware keyboard, and combine
with modifiers held on a hardware keyboard. SHIFT applies to the bar's own keys only (for
example SHIFT then TAB sends Shift+Tab, SHIFT then an arrow extends a selection in editors);
the keyboards have their own Shift. On the bar, sticky modifiers apply to keys, characters and
single-character text keys; longer text keys and macros are sent exactly as written.

## Customising the key bar

A key-bar layout is a JSON document with a list of rows, each a list of keys. Every key has a
`label`, an `action` for a tap, and optionally a `swipeUp` action (with its own `swipeUpLabel`)
for swiping up on the key.

```json
{
  "rows": [
    [
      { "label": "ESC", "action": { "type": "key", "key": "Escape" } },
      { "label": "⇧TAB", "action": { "type": "key", "key": "Tab", "modifiers": { "shift": true } } },
      { "label": "CTRL", "action": { "type": "modifier", "modifier": "CTRL" } },
      { "label": "^C", "action": { "type": "char", "char": "c", "modifiers": { "ctrl": true } } },
      { "label": "↑", "action": { "type": "key", "key": "Up" },
        "swipeUp": { "type": "key", "key": "PageUp" }, "swipeUpLabel": "PGUP" }
    ],
    [
      { "label": "/", "action": { "type": "text", "text": "/" },
        "swipeUp": { "type": "text", "text": "\\" }, "swipeUpLabel": "\\" },
      { "label": "/compact", "action": { "type": "macro", "actions": [
        { "type": "text", "text": "/compact" },
        { "type": "key", "key": "Enter" }
      ] } }
    ]
  ]
}
```

### Actions

| `type` | Fields | Does |
|--------|--------|------|
| `key` | `key`, optional `modifiers` | Presses a named key: `Enter`, `Tab`, `Backspace`, `Escape`, `Up`, `Down`, `Left`, `Right`, `Home`, `End`, `PageUp`, `PageDown`, `Insert`, `Delete`, `F1`–`F12` |
| `char` | `char` (one character), optional `modifiers` | Presses a character with modifiers, e.g. Ctrl+C |
| `modifier` | `modifier`: `CTRL`, `ALT` or `SHIFT` | Taps a sticky modifier |
| `text` | `text` | Types the text |
| `macro` | `actions`: a list of `key`, `char` and `text` actions | Runs the actions in order |

`modifiers` is an object with any of `ctrl`, `alt`, `shift` and `meta` set to `true`.

### Rules

- Key names and modifier names are case-insensitive. Unknown fields are ignored.
- `text` and `char` must be printable. To send Enter, Tab or Escape, use a `key` action; to send
  a control code, use a `char` action with `ctrl`. This keeps every layout readable.
- Macros cannot contain other macros or `modifier` actions.
- Limits: at most 6 rows, 16 keys per row, 16 characters per label, 256 characters per text,
  32 actions per macro, and 64 KiB for the whole file.

A layout that breaks a rule is rejected with a message naming the field, for example
`rows[1][0].action.text must be printable`.

### Presets

- **Default**: ESC, SHIFT, `/`, `|`, ↑, `~` on the first row and TAB, CTRL, ALT, ←, ↓, → on the
  second, so ↑ sits above ↓. Swipe up on the arrows for PgUp/PgDn/Home/End, on TAB for
  Shift+Tab, and on `/`, `|`, `~` for `\`, `&`, `` ` ``.
- **Claude**: ESC (swipe up for ESC ESC, which rewinds), ⇧TAB (cycle permission mode), ^C, `/`,
  ↑, Enter on the first row; `y`, `n`, `/compact`, `/clear`, `/resume`, ↓ on the second.

## File-name suggestions

On a host with Bridle's shell integration (**Sessions → This host → Install shell
integration**), a strip above the key bar suggests file and folder names while you type at a
bash or zsh prompt. Tap a chip to type the rest of the name: folders end in `/` and their
contents come up next. Enter is unchanged and runs exactly what you typed. Turn suggestions off
with **Settings → Suggest file names**.

- The strip appears once the shell reports its first prompt and then keeps its row, so the
  terminal doesn't change size as suggestions come and go.
- Bridle follows what you type from the keys it sends. After Tab, arrow keys, history or a tmux
  pane switch it can't tell what the line holds, so the strip stays empty until the next prompt.
- Names are listed over SFTP on the existing connection, once per folder per prompt. Folders
  come first; hidden files appear when you type a leading `.`.
- Names are escaped for how the line is quoted (`my\ file`, `'it'\''s'`). A name with `!` is
  not offered inside double quotes, where bash and zsh would expand it.
- Words the shell would expand (`$VAR`, `*`, `{a,b}`, backticks, `~user`) get no suggestions.

Any program can print the codes the shell integration uses, for example a file shown with
`cat`. The worst this can do is list real files you can already read; a tap only types the
escaped name and never presses Enter. See
[command-line-suggestions.md](command-line-suggestions.md) for the design.

## Compose history

Each host keeps its last 200 compose-box submissions, most recent first and without duplicates.
↑ and ↓ in the compose box move through them; going back down past the newest entry restores
what you were typing. Very long submissions (over 16 KiB) are sent but not remembered, and
entries can be deleted individually or cleared.

History is saved on the phone, so anything typed into the compose box at a password prompt ends
up on disk too: type passwords on the terminal keyboard instead. **Settings → Remember compose
history** (on by default) controls this. Off, history is kept only while Bridle runs, and
turning it off deletes the history already saved for every host.
