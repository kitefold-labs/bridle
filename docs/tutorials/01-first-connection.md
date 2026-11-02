# Tutorial 1: your first connection

This walks you from a fresh install to a terminal on your own Linux machine, logging in with a
key that never leaves your phone. It takes about ten minutes.

You need:

- A Linux machine running `sshd` (OpenSSH server) that you can log in to with a password, at
  least once. Afterwards you'll use a key and can turn password logins off.
- A network path from the phone to it. [Tailscale](https://tailscale.com) is the easiest: no
  port forwarding, no public IP, and the machine gets a stable name.
- `tmux` on the machine if you want sessions that survive disconnects (recommended).

On first start, Bridle shows a short introduction. Its last page has **Generate a key** and
**Add a host**, which open steps 1 and 2 below directly.

## 1. Generate a key on the phone

1. Open the **Keys** tab and tap **+** (Generate key).
2. Give it a name you'll recognise in `authorized_keys`, for example `Pixel 9`.
3. Pick an algorithm. **ECDSA P-256** is the default and works with every current OpenSSH; on
   phones that have it, it's created in StrongBox, the separate security chip. **Ed25519**
   appears only on devices whose Keystore supports it. Pick **RSA** only for old servers.
4. Choose whether the key needs your fingerprint, face or screen lock:
   - **No**: the key signs whenever the phone is unlocked.
   - **Yes, then stay unlocked for a while**: one unlock covers reconnects for the window you
     pick (1 to 60 minutes).
   - **Yes, for every connection**: every login shows the biometric prompt.
5. Tap **Generate**.

The private key is created inside the phone's secure hardware (the Android Keystore) and can't
be copied out, not even by Bridle. The key's screen shows its **public** key, which is what
servers need; you can **Copy** it or **Share** it (for example to email it to yourself). The
list labels it **Hardware key**.

Already have a key you use everywhere? Use **Import key** instead (the upload icon on the Keys
tab): paste an OpenSSH or PEM private key, or choose the file. If it has a passphrase, Bridle
asks for it once. Imported keys are stored encrypted and labelled **Software key**.

## 2. Add the host

1. Open the **Hosts** tab and tap **+**.
2. Fill in:
   - **Name**: anything, e.g. `devbox`.
   - **Host name or IP address**: see the Tailscale tips below.
   - **Port**: `22` unless your `sshd` listens elsewhere.
   - **Username**: your account on the machine.
3. Under **Default tmux session**, enter a name such as `main`. Terminals then attach to that
   session (creating it the first time), so a dropped connection or a killed app loses nothing:
   reconnecting puts you back where you were.
4. Tap **Save**. Leave **Key** empty for now; step 3 sets it.

Fields with a problem are marked as you type, and all of them are checked when you save.

### Tailscale tips

- Install Tailscale on both the phone and the machine and sign in to the same tailnet.
- Use the machine's **MagicDNS** name as the host name: the short name (`devbox`) or the full
  one (`devbox.your-tailnet.ts.net`). The `100.x.y.z` Tailscale address works too and never
  changes for that machine.
- Bridle connects with ordinary SSH over the tailnet, so `sshd` must be running on the machine.
  This tutorial assumes the machine's own `sshd`, not Tailscale SSH (which authorises logins
  through your tailnet rather than with keys).
- If the connection times out, check that Tailscale is connected on the phone; the
  "Couldn't reach" message usually means it isn't.

Already have hosts in `~/.ssh/config` on a laptop? On the Hosts tab, tap the import icon, paste
the file (or choose it) and tap **Preview**. `Host` blocks become hosts, `ProxyJump` becomes the
jump host, and anything Bridle can't use (wildcards, `Match`, `Include`, `IdentityFile`) is
listed so nothing is skipped silently.

## 3. Install the key on the host

1. Go back to **Keys**, open your key and scroll to **Install on host**.
2. Pick the host and tap **Install**.
3. This first connection to the machine shows **Trust this host?** with the server's key
   fingerprint. Check it (see step 5) and tap **Trust and connect**.
4. Enter your account password when asked. It's used for this one login and never stored.

Bridle appends the public key to `~/.ssh/authorized_keys` (creating `~/.ssh` with the right
permissions if needed; running it twice doesn't add the key twice) and makes it the host's key.
From now on the host logs in with the key.

Prefer to do it yourself? **Copy** the public key and add it to `~/.ssh/authorized_keys` on the
machine (for example from a laptop with `ssh-copy-id` or an editor), then pick the key in the
host's **Key** field.

## 4. Connect

On the **Hosts** tab, tap the host. The dot next to it turns green while connected, amber while
connecting or reconnecting, and red if the connection failed. If the key needs biometrics, the
system prompt appears first.

Once it works with the key, you can turn off password logins on the machine
(`PasswordAuthentication no` in `/etc/ssh/sshd_config`), as long as you have another way in.

## 5. Verify the server's fingerprint

The first time you connect, Bridle shows the server's key type and its `SHA256:` fingerprint.
Compare it with what the server itself reports, from a session you already trust (a local
console, or an existing SSH session from a laptop):

```sh
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

Use the `.pub` file for the key type Bridle shows (`ssh_host_ecdsa_key.pub` for
`ecdsa-sha2-nistp256`, `ssh_host_rsa_key.pub` for `rsa-sha2-*`). The `SHA256:…` strings must
match exactly. Trusted keys are listed under **Keys → Known hosts**.

If a host's key ever changes, Bridle blocks the connection and shows a full-screen **Host key
changed!** warning with the old and new fingerprints. That's what a man-in-the-middle attack
looks like, but it also happens when a server is reinstalled. Only if you know why it changed,
check the new fingerprint as above and tap **Forget old key**; the next connection asks you to
trust the new one.

## Next steps

- Start Claude Code inside the tmux session and leave it running; reconnect from the phone
  whenever you want to check on it.
- Add a second key from another phone the same way: each device has its own hardware key.
