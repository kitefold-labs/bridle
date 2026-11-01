# ADR-0002: Terminal emulator component

- **Status:** Proposed. To be confirmed by Phase 0 Spike B.
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

Use **`termlib`** behind a `TerminalSurface` interface: it is native Compose,
libvterm is accurate, and it already ships on Play. We implement bracketed
paste (the compose box) and the extra keys on top of it, and send fixes
upstream where they belong. The fallback is **Termux `terminal-view`**.
xterm.js is rejected because of IME and latency problems.

## Consequences

- We ship a native library, so we must meet Play's 16 KB page-size
  requirement and build for arm64-v8a and x86_64.
- Golden-screen tests feed byte streams through `TerminalSurface`, so they
  stay valid if we switch to the fallback.
