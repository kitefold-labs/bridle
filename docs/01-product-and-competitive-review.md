# 01 — Product definition & competitive review

## 1. Problem

Claude Code sessions and other long-running jobs run on Linux workstations
and servers. From a phone you want to:

1. Reach the machine quickly and safely, over Tailscale or a public IP.
2. Start one of these tools **detached**, so it survives the phone sleeping,
   changing networks, or the app being killed.
3. Check on it, answer its prompts, and **restart it** when it hangs, crashes,
   or needs a fresh context. Then walk away again.

General SSH clients handle (1) well. They treat (2) and (3) as "type tmux
commands on a phone keyboard", which is slow and error-prone. Our product
makes (2) and (3) one-tap operations without giving up a full terminal.

## 2. Target users

- **Primary:** developers who run Claude Code and similar agents on their own
  Linux boxes and supervise them from a phone.
- **Secondary:** general sysadmin/devops users who want a modern,
  Termius-class SSH/SFTP client on Android without a subscription for basics.

## 3. Decisions captured from the Q&A

| Topic | Decision | Implication |
|-------|----------|-------------|
| Stack | Android, Kotlin + Compose | Native IME, foreground service, Keystore access |
| Host side | Agentless plus an optional helper | Everything works with `sshd` + `tmux`; the helper adds Claude state via hooks |
| Network | Tailscale/VPN and public IP | Strict host-key checking, keepalives, fast reconnect; ProxyJump is cheap to add |
| Roaming | Reattaching to tmux is enough | No Mosh in v1; keep a transport seam so it can be added later |
| Claude priority | Launch and restart in tmux | Launch Profiles + session browser are the core differentiator |
| Keys | Generated in Keystore, **plus import** of existing OpenSSH keys | Hardware keys are the default; imported keys are encrypted with a Keystore-wrapped key |
| Notifications | Only while connected; no push | No backend, no FCM, no third-party push service |
| Agents | Claude-first | Profiles take any command, but the only presets are for Claude Code |
| Devices | Phone first | Tablet, foldable and hardware-keyboard polish moves to the later backlog |
| Name / license | **Bridle** by Kitefold Labs; Apache-2.0; free | Every dependency must be Apache-2.0-compatible (no GPL); no monetization or accounts |
| Distribution | Public Play Store | FGS policy, privacy/data safety, onboarding, accessibility, closed testing |
| Extras | SFTP | File browser in phase 3 |

## 4. Competitive review

### Termius (cross-platform, the reference product)
- Hosts with groups/tags; **Keychain**: identities and keys kept separate from
  hosts, so you can reuse them.
- **Snippets** (with packages) that you can run on one or many hosts;
  **Workspaces** that group tabs, with command broadcasting and saved templates.
- SFTP with tabs; port forwarding (local, remote, dynamic); Mosh and Telnet;
  agent forwarding; FIDO2 keys; autocomplete/shell integration (paid tiers).
- E2E-encrypted **cloud vault sync**, which is the core of its business model.
- **Take:** separate identities from hosts, snippets, groups, SFTP, port
  forwarding, extra-keys bar, workspaces (for us these are Launch Profiles).
  **Skip:** autocomplete, team vaults, cloud sync. Bridle is free and has no
  backend or accounts.

### JuiceSSH (Android)
- Identities separate from connections, connection groups, a pop-up extra-keys
  keyboard, volume keys for font size, plugins, team sharing (Pro).
- It is dated and not very active, but its "identity" model and fast connection
  list are still good references.

### ConnectBot (Android, open source)
- A long-lived, minimal client. Version 1.10 moved its UI to Compose and its
  emulation to **libvterm** through its own `termlib` component (Apache-2.0).
- **Take:** `termlib` is a strong candidate for our terminal component
  (see ADR-0002). Port-forward UI, host-key handling.

### Termux (Android, local terminal)
- The gold standard for an **on-screen extra-keys row** (configurable, swipe-up
  for alternate keys), volume-down as Ctrl, and good IME handling in
  `terminal-view`.
- **Take:** the extra-keys row design and its configuration format. Termux's
  `terminal-view` is our fallback terminal component.

### Blink Shell / Prompt 3 (iOS)
- Blink: ssh_config-style host definitions, Mosh, smart keys, gestures, and a
  keyboard-first UX on tablets.
- Prompt: "Clips" (snippets), Mosh and Eternal Terminal, Panic Sync.
- **Take:** an ssh_config import/export mental model and gesture vocabulary.
  Their tablet and hardware-keyboard polish is a reference for later, after
  the phone experience is done.

### Claude-specific tools (2026)
- **ServerCC** (iOS): runs Claude Code/Codex over SSH with persistent tmux
  sessions and can attach to sessions already running on the host. It confirms
  that our core concept is in demand. It has no Android version.
- **claude-control** (open source PWA): runs Claude inside tmux, detects
  permission prompts, and sends push notifications.
- **Common DIY setup:** Termius/Termux + Tailscale/Twingate + tmux. This is
  exactly the manual workflow we automate.
- Anthropic's own options (claude.ai/code, Claude apps, remote control) cover
  Claude-hosted and paired sessions. We complement them: we work with any CLI,
  on any self-managed host, inside a real terminal.

## 5. Feature list (prioritized)

**P0 — MVP**
- Host management: label, host, port, user, identity, group, color, tags;
  favorites and recents; search.
- Keystore key generation (ECDSA P-256 by default, Ed25519 where the hardware
  supports it, RSA-3072 for legacy hosts). Export the public key by copy,
  share sheet or QR.
- **Key import:** paste or pick an OpenSSH private key (Ed25519, ECDSA, RSA,
  passphrase-protected or not), stored encrypted with a Keystore-wrapped
  key and optionally gated by biometrics.
- **Install key on host:** one-time password login that appends the public key
  to `authorized_keys`. The password is never stored.
- Host-key verification: trust on first use (TOFU) with fingerprint display;
  a key mismatch blocks the connection.
- Mobile-friendly terminal: true color, Unicode, selection, pinch zoom,
  URL tap, OSC 52 clipboard.
- **Extra-keys bar** (Esc, Tab, Ctrl, Alt, arrows, `| ~ / -`), configurable.
- **Compose box:** a native multi-line text field (autocorrect, voice dictation)
  that sends its contents with bracketed paste. This matters a lot for writing
  Claude prompts.
- Multiple concurrent sessions as tabs. A foreground service keeps them alive.
- Auto-reconnect. A per-host "always use tmux session X" option makes the
  app reattach after a reconnect.
- Biometric app lock; optional FLAG_SECURE.

**P1 — the tmux/Claude layer**
- **Launch Profiles:** host + working dir + command + tmux session name + env.
  Actions: Start/Attach, Restart, Stop, Send keys.
- **Session browser:** all tmux sessions on a host, each with its running
  command, cwd, last activity and a text preview from `capture-pane`, without
  attaching.
- **Restart Claude:** respawn in place with `claude --continue` (or a fresh
  start), keeping the tmux session name.
- Claude key row: Esc, Shift+Tab (mode cycle), Ctrl+C, `/`, y/n, ↑, plus quick
  commands (`/compact`, `/clear`, `/resume`) and saved prompts.
- Snippets with variables (paste into the terminal or run as exec).
- `~/.ssh/config` import (paste text or pick a file). ProxyJump.

**P2**
- SFTP browser: list, upload/download via the Storage Access Framework,
  rename, delete, mkdir, a small-file text editor, "open terminal here".
- Optional **host helper**: Claude Code hooks write session state
  (working / waiting for permission / idle / exited). The dashboard shows
  status badges.
- Local notifications while connected: "Claude is waiting for permission",
  with **Approve / Deny** notification actions that send keys to the pane.
- Port forwarding (local and dynamic), for example to preview a dev server
  from the phone's browser.
- Encrypted backup export/import of hosts, profiles and snippets. Keys are
  never included.

**Later**
- Tablet, foldable and hardware-keyboard polish; split panes; DeX/ChromeOS.
- Mosh; FIDO2 security keys (NFC/USB); tmux control mode (`-CC`) mapping
  tmux windows to native tabs; agent forwarding; a Wear OS "approve" tile;
  git-worktree-aware Launch Profiles (one Claude per worktree).

**Out of scope (by decision)**
- Push notifications while disconnected, and any Bridle-operated server.
- Cloud sync or accounts. Encrypted export/import is the way to move
  configuration between devices.
- Built-in presets for agents other than Claude Code.
