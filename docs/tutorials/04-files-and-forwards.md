# Tutorial 4: Files, snippets and port forwards

This tutorial covers the three host tools: the **file browser** (SFTP), for looking at, editing
and moving files; **snippets**, for commands you run often; and **port forwards**, for opening a
web app that runs on your server in your phone's browser.

Open them from the host's **⋮** menu on the **Hosts** tab: **Files**, **Snippets** and **Port
forwards**. In a terminal, the **⋮** menu next to the tabs also has **Files** and **Snippets**
for the host of the tab you are looking at; **Back** returns to the terminal.

They all work over the connection you already have to the host. If a terminal is open on that
host, they share it; if not, Bridle connects for them.

You need a host you can already connect to (see the earlier tutorials).

## Browse files

1. On the **Hosts** tab, open the host's **⋮** menu and choose **Files**.
2. Bridle opens your home directory.
   - Folders are listed first. Tap one to open it, or tap **..** to go up.
   - The bar under the title shows the path. Tap any part of it, such as **home**, to jump there.
   - Pull down on the list to refresh it.
3. Use **⋮** in the top bar to sort by **name**, **size** or **date**, and to **show hidden
   files** (names starting with a dot, such as `.bashrc`).

Each file shows its size, when it was last changed and its permissions (`rw-r--r--`).

## Create, rename, delete and change permissions

- **New folder**: the folder icon in the top bar.
- **Rename**, **Permissions** and **Delete**: the **⋮** button on a file's row.
- **Permissions** takes an octal mode: `644` for a normal file, `600` for a private one, `755`
  for a script or folder.
- **Deleting a folder deletes everything inside it.** Bridle asks first. Symbolic links are
  removed, never followed, so deleting a folder can't reach files outside it.

## Edit a small text file

Tap a file (or choose **Edit** from its menu) to open it in the editor. The editor is for quick
fixes to configuration files, scripts and notes, up to 1 MB.

1. Make your change. A dot after the file name (`notes.md •`) means there are unsaved edits.
2. Tap the save icon.

**If someone else changed the file** while you were editing (another terminal, a script, a
colleague), Bridle notices when you save and asks what to do:

- **Overwrite** replaces their version with yours.
- **Reload** throws your edits away and shows their version.
- **Cancel** keeps editing, so you can copy what you need first.

Bridle warns you before editing a file that doesn't look like UTF-8 text (for example a binary or
a file in another encoding), because saving it from the editor could damage it. For files over
1 MB, download them instead.

Every save is atomic: Bridle writes a temporary file next to the original and renames it into
place, so a dropped connection never leaves a half-written file. The file keeps its permissions.

## Upload and download

- **Upload**: tap the upload icon and pick one or more files from your phone, Google Drive or
  any other app that provides documents. They are uploaded into the folder you are in. Bridle
  never overwrites an existing file; rename one of them first.
- **Download**: choose **Download** from a file's menu, then pick where to save it on your phone.

Transfers run one at a time in the background. They keep going if you leave the file browser or
switch to a terminal. Tap the transfers icon (two arrows, with a count while transfers are
running) to see progress and cancel a transfer. A cancelled upload leaves nothing on the server,
and Bridle deletes the partial file of a failed download from your phone (where the storage app
allows it). For very large transfers, keep Bridle open until they finish: Android may stop
background work.

## Save and run commands with snippets

A snippet is a command you save once and run on any host: a deploy, a log tail, the way you
start Claude in a project.

1. Open the host's **⋮** menu and choose **Snippets** (or **Snippets** in the terminal's **⋮**
   menu).
2. Tap **+**, give the snippet a name and type the command. Write `{{name}}` for a value to fill
   in each time, or `{{name:default}}` to suggest one, for example:

   ```sh
   cd {{project:~/src/app}} && git log --oneline -{{count:10}}
   ```

3. Under **When run**, choose what tapping the snippet does: **Paste into terminal** or **Run
   and show output**. You can always pick the other one in the run dialog.
4. Tap **Save**.

Tap a snippet (or its ▶ button) to run it. Fill in the values; the dialog shows the command as it
will run.

- **Paste into terminal** types the command into a terminal on this host and switches to it. It
  goes to the tab you were last looking at if that tab is on this host, otherwise to the host's
  newest tab. **Nothing runs until you press Enter**, so you can check or edit the command
  first. If no terminal is open on the host, Bridle says so: open one, or use **Run and show
  output** instead.
- **Run and show output** runs the command on its own, without a terminal, and shows what it
  printed and its exit status. Long output is cut to its end.

Pasting is as safe as any paste into Bridle's terminal: control characters are removed, so a
snippet can't send keystrokes or escape sequences of its own. When the program in the terminal
doesn't support bracketed paste (a plain `sh`, for example) and the snippet has several lines,
each line would run as it arrived, so the terminal asks first: confirm only if you want the lines
to run right away.

## Preview a dev server with a local forward

Say you are working on a web app on your server (or Claude is) and its dev server listens on
port 3000. The port isn't open to the internet, and it shouldn't be. A **local forward** makes it
appear on your phone instead.

1. Start the dev server on the host as usual, for example in a tmux window:

   ```sh
   npm run dev     # listening on http://localhost:3000
   ```

2. In Bridle, open the host's **⋮** menu and choose **Port forwards**.
3. Tap **+** and fill in:
   - Type: **Local (-L)**
   - **Port on this phone**: `8080` (any free port from 1024 to 65535)
   - **Destination host**: `localhost` (this means the server itself)
   - **Destination port**: `3000`
4. Tap **Save**, then turn on the switch next to the new forward. It shows **Running** and how
   many connections are open.
5. Open your phone's browser and go to `http://localhost:8080`. You are looking at the dev
   server, tunnelled through SSH.

The destination is resolved **on the server**, so you can also reach other machines on its
network: a database at `db.internal:5432`, or a dashboard on another box.

To have a forward start whenever you connect to the host, edit it and turn on **Start
automatically when connected**. It starts each time you open a terminal on the host (if it isn't
running already) and keeps running when you close the terminal, until you switch it off on the
**Port forwards** screen or tap **Disconnect all**. If it can't start (say another app already
uses the port), that screen shows it as **Failed** with the reason.

### Why only this phone can use it

Forwards listen on `127.0.0.1`, the phone's loopback address. Apps on your phone can use them, but
other devices on the same Wi-Fi can't, even if they know the port. Nothing on the server is exposed
either: traffic goes through your existing, encrypted SSH connection.

### A SOCKS proxy for everything else

A **SOCKS (-D)** forward is a proxy rather than a single tunnel. Point an app that supports SOCKS5
(some browsers do) at `localhost` and the forward's port, and it can reach anything the server can
reach, as if it were running there.

### Troubleshooting

| Problem | What to try |
|---------|-------------|
| **Failed: Address already in use** | Another forward or app uses that port on your phone. Pick a different port. |
| The browser can't connect | Check the forward says **Running**. Is the dev server listening on `localhost:3000` on the host? Try `curl localhost:3000` in a terminal there. |
| The page loads but assets fail | Some dev servers only accept their own host name. Configure the dev server to allow `localhost:8080`, or use the same port on both sides (8080 → 8080). |
| The forward stops | Forwards need the connection. If it drops, Bridle reconnects and re-opens the forward. **Disconnect all** stops every forward. |
