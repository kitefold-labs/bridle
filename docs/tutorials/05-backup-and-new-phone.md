# Tutorial 5: Back up and move to a new phone

Bridle has no account and no cloud sync. Your hosts, groups, Launch Profiles, snippets and port
forwards live only on your phone. An **encrypted backup** is how you keep a copy, and how you move
your setup to a new phone.

This tutorial walks through exporting a backup, importing it on another phone, and getting your
keys working again, which is the one thing a backup deliberately doesn't do for you.

For the technical details of the file format, see
[Backups and crash reports](../backup-and-privacy.md).

## Export a backup

1. Open **Settings** and scroll to **Backup**.
2. Tap **Export encrypted backup**.
3. Choose a passphrase and type it twice. It must be at least 10 characters. A few random words
   ("staple orbit lantern velvet") are easy to type and hard to guess.
4. Tap **Choose file** and pick where to save it, for example your Downloads folder or Google
   Drive. The suggested name is `bridle-<date>.bridle`.

Bridle shows **Backup saved** when it is done.

**Keep the passphrase somewhere safe**, such as your password manager. Without it the backup
can't be opened, by you or anyone else. There is no recovery.

### What's in it

- Hosts, groups, Launch Profiles, snippets and port forwards
- The host keys you have trusted, so you don't have to re-check fingerprints
- The *public* half and the name of each of your keys

Values you typed, such as environment variables on a host, are included too, protected by the
passphrase.

### What's not in it, and why

- **Private keys.** Keys that Bridle generated live in your phone's secure hardware and *cannot*
  be copied out, by Bridle or anyone else. That is what makes them safe: someone who steals your
  phone's storage, or your backup file, still doesn't have your key. Keys you imported are also
  never exported again, so a backup with a weak passphrase can't leak them.
- **Passwords.** Bridle never stores them.
- Terminal output and scrollback.

So after restoring a backup, you need a key on the new phone that your servers accept. The steps
below make that quick. If you plan to reuse a key file you already have (option B below), import it
first, then the backup.

## Import on the new phone

1. Install Bridle on the new phone and copy the backup file across (or open it from Drive).
2. Open **Settings → Backup → Import backup** and pick the file.
3. Enter the passphrase and tap **Open**.
4. Bridle shows what it will import **before changing anything**:
   - how many hosts, groups, profiles, snippets, forwards and trusted host keys it will add;
   - entries that are **already on this phone** (they are kept as they are, not duplicated);
   - **host key conflicts**, if this phone already trusts a different key for a server, with both
     fingerprints. This phone's key is always kept;
   - **keys that aren't on this phone**, and which hosts used them;
   - anything **skipped** because it failed the same checks as manual input.
5. Tap **Import**.

If the passphrase is wrong, or the file was damaged, Bridle says so and nothing is changed. Try
again; there is no limit.

## Get your keys working again

Your hosts are back, but the hosts that used a key from the old phone now have no key. You have
two options.

### Option A: a new key for the new phone (recommended)

A new phone is a good time for a new key. Each phone having its own key means you can revoke one
without touching the others.

1. Open **Keys** and generate a new key. It is created in this phone's secure hardware.
2. Install it on your servers. While you can still log in with a password or from another
   device, use **Add this device's key to all hosts**, or copy the public key into each server's
   `~/.ssh/authorized_keys`.
3. When everything works, remove the old phone's key from your servers' `authorized_keys` files.
   You can find it by its name or comment, shown in the import summary.

### Option B: import your existing key

If you use an OpenSSH key file on other machines too (for example `~/.ssh/id_ed25519` from your
laptop), you can import the same file on the new phone. Nothing needs to change on the servers.

Import the key **before** you import the backup: Bridle then matches the backup's hosts to it by
its public key and links them automatically. If you import the key afterwards, pick it in each
host's settings instead.

Copy key files with care: anyone with the file (and its passphrase, if it has one) can log in as
you.

## Tips

- Export a fresh backup after adding hosts or changing profiles. Old backups still import; Bridle
  skips what's already there.
- Importing the same backup twice is harmless: everything shows as already present.
- A backup from a newer version of Bridle may not open in an older one. Update the app first.
