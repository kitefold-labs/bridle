# ADR-0002: Terminal emulator component

- **Status:** Accepted for implementation (termlib 0.3.14, integrated in
  `:core:terminal-ui`). Still to be confirmed on devices by the acceptance
  test below; see "What we learned".
- **Context:** The terminal must render Claude Code's TUI correctly (true
  color, box drawing, wide characters, fast redraws, resize while running),
  handle IME, extra keys and bracketed paste well, and fit a Compose UI. Its
  license must allow a Play Store release.

## Options

| | ConnectBot `termlib` | Termux `terminal-emulator` + `terminal-view` | xterm.js in WebView |
|-|----------------------|---------------------------------------------|---------------------|
| Emulation core | libvterm (C, via JNI) | Java | JS (very complete) |
| UI integration | Native Compose | Android View (`AndroidView` wrapper) | WebView bridge |
| Features | True color, wide chars, selection, zoom, inline images | True color, selection; very mature IME handling | Most complete feature set |
| Gaps | Paste and some escape codes still listed as planned | View-based; older internals | IME in WebView is painful, JS bridge latency, memory use |
| License | Apache-2.0 (libvterm: MIT) | Apache-2.0 (the libraries; the Termux app itself is GPLv3) | MIT |
| In production | ConnectBot 1.10+ on Play | Termux and many forks | Many desktop/web terminals |

## Decision (proposed)

Use **`termlib`** behind a `TerminalEngine` interface: it is native Compose,
libvterm is accurate, and it already ships on Play. We implement bracketed
paste (the compose box) and the extra keys on top of it, and send fixes
upstream where they belong. The fallback is **Termux `terminal-view`**.
xterm.js is rejected because of IME and latency problems.

## Consequences

- We ship a native library, so we must meet Play's 16 KB page-size
  requirement and build for arm64-v8a and x86_64.
- Golden-screen tests feed byte streams through `TerminalEngine`, so they
  stay valid if we switch to the fallback.

## What we learned integrating termlib 0.3.14

`:core:terminal-ui` wraps termlib in `TermlibEngine` (about 100 lines); the rest
of the terminal UI (`TerminalScreenState`, key bar, compose box, status banner)
is written against `TerminalEngine` and tested on the JVM with a fake.

**Fits well**

- `TerminalEmulatorFactory.create(...)` returns a UI-free `TerminalEmulator`
  that can live in the service next to the `TerminalSession`. `writeInput` is
  synchronized and callable from any thread, so `EngineSink` feeds it from a
  background executor, coalescing the session's reads into one call per batch.
- Callbacks cover what the session needs: `onKeyboardInput` (query replies and
  termlib's own keyboard/IME output, posted to the main looper), `onResize`,
  `onClipboardCopy` (OSC 52), `onBell`, `onProgressChange` (OSC 9;4). Inline
  images are off unless enabled.
- The `Terminal` composable draws, scrolls, selects (long-press, handles, copy
  button), shows the IME, and sizes the emulator to its layout. Hooks we use:
  `initialFontSize`, `showSoftKeyboard`, `onTerminalTap`,
  `onImeVisibilityChanged`, `modifierManager` (the key bar's sticky Ctrl/Alt
  apply to termlib's keyboard), `onSelectionControllerAvailable`,
  `onHyperlinkClick` and `onPasteRequest`.
- Its native libraries' LOAD segments are 16 KB aligned (`0x4000`), so they
  meet Play's 16 KB page-size requirement. It ships armeabi-v7a, arm64-v8a,
  x86 and x86_64 (2.6–3.6 MB each); the app should keep only
  arm64-v8a and x86_64. It pulls in appcompat, Material Components and
  slf4j-api (MIT; with no binding, logging is a no-op). All pass `checkLicense`.

**Gaps and how we cover them**

- **Modes and title are not exposed.** DECCKM, bracketed-paste mode and the
  window title live in an internal snapshot. `TerminalModeTracker` follows them
  by scanning the output stream (a small VT parser: split sequences, DCS/tmux
  passthrough, RIS/DECSTR resets, capped OSC).
- **Paste is not sanitised.** `pasteText()` brackets in libvterm but keeps
  control characters (an embedded `ESC[201~` could end the paste early), and
  the default context-menu paste and compose-mode flush send raw code points.
  We never call `pasteText()`: `onPasteRequest` routes the menu's Paste through
  `BracketedPaste` (with the multi-line confirmation).
- **IME commits bypass Bridle's input layer.** termlib's own `InputConnection`
  dispatches a multi-character commit code point by code point, without the
  control-character filtering that `InputDispatcher.type` applies (Gboard's
  clipboard strip commits this way). Fix upstream (filter C0/C1 in
  `dispatchText` for multi-character commits) or replace termlib's IME with
  ours.
- **Pinch zoom is visual only.** It magnifies during the gesture and resets on
  release. `TerminalPane` watches the pinch and applies the scale to the font
  size, within bounds.
- **Not configurable:** scrollback is fixed at 1000 lines, and links of any
  scheme are reported (we open only http(s)).
- **No host-JVM emulation.** The JNI library is Android-only, so Robolectric
  tests stop at the native boundary (termlib can load a host build from
  `java.library.path`, which we don't have). Golden-screen tests of the Claude
  Code TUI need an instrumented test or a host build of `libjni_cb_term`.

**Acceptance test still to run on devices:** Claude Code's TUI (box drawing,
spinners, true color, wide characters, fast redraws, resize while running,
bracketed paste) on arm64 and x86_64, plus IME behaviour with Gboard and
SwiftKey.
