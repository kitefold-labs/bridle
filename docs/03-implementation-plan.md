# 03 — Implementation plan

The phases below assume roughly 1–2 engineers. Each phase ends with a usable
build installed on a real phone (internal track or sideload).

## Phase 0 — Foundations & spikes (week 1–2)

Goal: retire the two biggest technical risks before building UI on top of
them.

- [ ] Gradle multi-module skeleton, version catalog, convention plugins,
      Hilt, Compose, Room.
- [ ] CI with GitHub Actions: build, lint, detekt/ktlint, unit tests, and JVM
      integration tests for `:core:ssh`/`:core:tmux` against a disposable
      OpenSSH + tmux container (Testcontainers).
- [ ] **Spike A — SSH + Keystore signing** (decides ADR-0001):
      sshj authenticates to OpenSSH 9.x with a Keystore ECDSA P-256 key, an
      Ed25519 key on a KeyMint 200 device, and an RSA-3072 key using
      `rsa-sha2-512`. Also checks ProxyJump through `direct-tcpip`, keepalive,
      and detection of a dropped network. If sshj blocks us, try MINA SSHD.
- [ ] **Spike B — Terminal rendering & input** (decides ADR-0002):
      `termlib` runs Claude Code, `htop`, `vim` and `tmux` in a demo app.
      Checks IME input, Ctrl combinations, bracketed paste, resize while
      Claude streams output, and scrolling performance.
- [ ] Settle the app name and package id; write the ADRs.

**Exit:** both spikes pass, or we switch to the fallback library.

## Phase 1 — MVP SSH client (weeks 3–6)

- [ ] `:core:security`: key generation (P-256/Ed25519/RSA), public-key
      export (copy, share, QR), optional biometric requirement per key.
- [ ] `:feature:keys`: identity list; **Install key on host** with a one-time
      password.
- [ ] `:feature:hosts`: CRUD, groups, tags, favorites, search, recents.
- [ ] Known hosts: TOFU dialog, mismatch block screen, management list.
- [ ] `SessionService` + `ConnectionManager`: multiplexed connections,
      keepalive, reconnect on network change with backoff.
- [ ] `:feature:terminal`: tabs, extra-keys bar (default layout), **compose
      box**, pinch zoom, selection/copy, URL tap, font and theme settings.
- [ ] Per-host "default tmux session": the attach command is
      `tmux new-session -A -s <name>`, so reconnects reattach automatically.
- [ ] App lock (biometric), FLAG_SECURE toggle.

**Exit:** you can create a key, install it on a host, connect over Tailscale
or a public IP, run Claude in tmux, lock the phone, switch networks, and come
back to the same session.

## Phase 2 — tmux & Claude layer (weeks 7–9)

- [ ] `:core:tmux`: command builders, parsers for `list-sessions`,
      `list-panes` and `capture-pane`, and capability probe. Integration tests
      against the container.
- [ ] `:feature:sessions`: a session browser per host and across all hosts,
      with preview cards.
- [ ] Launch Profiles: create from the browser, from SFTP ("Launch here") or
      from scratch. Start/Attach, Restart (continue / fresh), Stop.
- [ ] Claude key-bar preset (Esc, Shift+Tab, Ctrl+C, y/n, `/`) and quick
      commands (`/compact`, `/clear`, `/resume`).
- [ ] Snippets with variables (paste or exec mode); snippet packages.
- [ ] `~/.ssh/config` import (Host, HostName, User, Port, ProxyJump,
      IdentityFile mapped to "pick identity").
- [ ] Home-screen shortcuts / Quick Settings tile: "Attach to <profile>".

**Exit:** one tap from the home screen restarts a hung Claude with
`--continue` in the right directory and attaches to it.

## Phase 3 — SFTP (weeks 10–12)

- [ ] File browser (paged listing, breadcrumbs, sort, hidden files).
- [ ] Transfer queue in the service; upload/download via SAF; progress
      notifications.
- [ ] Rename, delete, mkdir, chmod; small-file text editor with conflict
      detection.
- [ ] "Open terminal here" and "Launch Claude here".

## Phase 4 — Helper, notifications, power features (weeks 13–15)

- [ ] `host-helper` script with `list --json` and `hook`, plus an
      `install-hooks` that backs up and merges `~/.claude/settings.json`.
      Shell tests in CI (shellcheck, plus bats against the container).
- [ ] In-app installer: show the diff and checksum, get consent, then upload
      over SFTP. Includes version upgrade and uninstall.
- [ ] Status badges (working / waiting / idle / exited) on session cards.
- [ ] Local notifications on state changes while connected, with Approve /
      Deny / Open actions.
- [ ] Port forwarding (local and dynamic) with auto-start per host.
- [ ] Encrypted backup export/import.
- [ ] Tablet, foldable and hardware-keyboard polish; split panes.

## Phase 5 — Public release hardening (weeks 16–18)

- [ ] Onboarding: generate a key, add a host, install the key, connect. Add
      a "Using with Tailscale" help card.
- [ ] Accessibility pass, string externalization (localization-ready),
      dark/light/dynamic themes.
- [ ] Play requirements: privacy policy, Data Safety form, foreground-service
      type declaration and justification, target SDK, 16 KB page-size
      compliance for the native libvterm library.
- [ ] Closed testing track. New personal developer accounts must run a closed
      test before production; at last check this was 12 testers for 14 days,
      so verify the current rule. Opt-in crash reporting.
- [ ] Security review: ShellQuote fuzzing, host-key flows, logging audit
      (no secrets or terminal content in logs).

## Later backlog

Push notifications while disconnected (ntfy/UnifiedPush or an FCM relay),
Mosh transport, FIDO2 security keys, E2E-encrypted sync, tmux control mode
with native tabs, agent forwarding, Wear OS approve action, git-worktree-aware
Launch Profiles, multi-host snippet broadcast.

## Testing strategy

| Layer | How |
|-------|-----|
| ShellQuote, tmux parsers, ssh_config parser | Pure JVM unit tests plus property-based tests (Kotest) |
| `:core:ssh`, `:core:tmux` | Testcontainers: OpenSSH + tmux image; scripted Claude stand-in (a TUI that prints prompts) |
| Keystore signing | Instrumented tests on a device farm or emulators (API 28, 33, latest) and one StrongBox device |
| Terminal | Golden-screen tests: feed recorded byte streams (Claude, vim, htop sessions) into the emulator and compare screen buffers |
| UI | Compose UI tests for host editor, key bar, compose box, session cards |
| Manual matrix | Tailscale vs public IP, Wi-Fi ↔ LTE handoff, Doze, process death, phone and desktop attached together |

## Risks & mitigations

| Risk | Mitigation |
|------|------------|
| SSH library can't sign with Keystore keys cleanly | Spike A in week 1; `SshEngine` seam; MINA SSHD fallback |
| `termlib` gaps (paste/IME) or rendering bugs with Claude's TUI | Spike B; `TerminalSurface` seam; Termux `terminal-view` fallback; contribute fixes upstream |
| FGS policy rejection on Play | Work lives in tmux, so the service is a convenience, not a requirement; justify `specialUse`; minimize service lifetime |
| Users lose access with a new phone (non-exportable keys) | Multi-identity hosts, "add this device's key to all hosts", clear onboarding copy; reconsider key import (open question) |
| Exec `PATH` differs from interactive shell | Login-shell wrapping, per-host probe, user-editable command paths |
| Claude CLI flags/UX change | Restart commands are profile data, not hard-coded; quick commands are editable |

## Open questions for you

1. **Name & package id**: what is the app called, and what's the
   publisher/package (e.g. `com.<you>.<app>`)?
2. **Business model & license**: free and open source (Apache-2.0/GPL), free
   with a paid tier, or paid up front? This affects whether sync is planned
   and what license our dependencies need.
3. **Key import**: you chose Keystore-only keys, but Play Store users will
   expect to import existing OpenSSH keys. Should we add import (stored
   encrypted with a Keystore-wrapped key) in Phase 2, or stay hardware-only
   on principle?
4. **Push when disconnected**: is it acceptable to depend on a third-party
   push service (ntfy/UnifiedPush) or to run our own small FCM relay? Or
   should notifications be "while connected" only?
5. **Other agents**: should Launch Profiles ship with presets for Codex CLI,
   aider, etc., or stay Claude-first?
6. **Device focus**: phone-only at first, or should tablets/foldables and
   hardware keyboards be first-class from Phase 1?
