# ADR-0001: SSH library

- **Status:** Accepted. On the JVM, sshj authenticates through our `SshKeySigner`
  (ECDSA P-256/384/521, Ed25519, RSA with rsa-sha2-*), including ProxyJump.
  Integration tests run against an embedded Apache MINA SSHD server. Keystore-backed keys
  use the same signer path; on-device instrumented tests cover them.
- **Context:** We need an SSH client for the JVM that runs on Android,
  supports SFTP, port forwarding, `direct-tcpip` (for ProxyJump), keepalive,
  modern algorithms (curve25519 kex, chacha20-poly1305/aes-gcm, rsa-sha2), and
  can **authenticate with a non-exportable Android Keystore key**.

## Options

| | sshj | Apache MINA SSHD | JSch (mwiede fork) |
|-|------|------------------|--------------------|
| Footprint | Small | Large (NIO, many modules) | Small |
| API | Simple, blocking | Rich, async | Dated, blocking |
| SFTP / forwarding | Yes / yes | Yes / yes | Yes / yes |
| ProxyJump | Manual via direct-tcpip | Built in (HostConfigEntry) | Manual |
| ssh_config parsing | No | Yes | Partial |
| Custom signer for Keystore | Possible; needs care because it prefers BouncyCastle | Possible via a custom `KeyPair`/signature factory | Possible |
| Android track record | Used by several Android apps (e.g. Android Password Store) | Works on Android, but less common | Common, legacy |

## Decision (proposed)

Use **sshj** behind our `SshEngine` interface. It is small, has a simple API,
and other Android apps have used it with Keystore keys. Fall back to
**MINA SSHD** if Spike A shows that Keystore signing or algorithm coverage
blocks us.

## Consequences

- We write ProxyJump and ssh_config import ourselves. Both are small.
- Imported keys use sshj's own OpenSSH/PEM parsers (`OpenSSHKeyV1KeyFile`,
  `PKCS8KeyFile`) after Tink decrypts them, so we write no key-format code.
- Everything above `SshEngine` stays library-agnostic, so swapping libraries
  costs about a week.
