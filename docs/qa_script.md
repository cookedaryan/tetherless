# Manual QA script

Run this before tagging a release. It covers the desktop client on Windows, which is the only
target this release ships.

Automated tests cover the protocol, the store, the transport and one full end-to-end exchange.
What they do not cover is the part a person has to look at: whether the window says the right thing
at the right time, and whether a build someone *installs* behaves like a build run from a checkout.
Every case below exists because it is invisible to the suite.

Time: about an hour. Record the result of each case in the table at the end, and file
anything that fails before tagging.

---

## What you need

- Windows 10 or 11.
- A JDK 17 or later on `PATH` for building. **The client under test must not need one** — that is
  part of what is being checked.
- This repository, built.
- Two profile directories, so one machine can run two identities.

### Set up

```bash
bash scripts/generate-dev-cert.sh
```

```bash
./gradlew :chat-server:installDist :chat-desktop:installDist
```

Start the relay and leave it running in its own terminal:

```bash
./gradlew :chat-server:run
```

Each client instance gets its own profile directory through `tetherless.config.dir`. Nothing else
separates them, so **check the path every time you start one** — pointing two instances at the same
profile is the easiest way to produce a confusing result that is not a bug.

```bash
./gradlew :chat-desktop:run -Dtetherless.config.dir=C:/Temp/qa-alice
```

```bash
./gradlew :chat-desktop:run -Dtetherless.config.dir=C:/Temp/qa-bob
```

Throughout, **Alice** and **Bob** mean those two profiles.

---

## 1 — Cold start

*A brand-new user, with nothing on disk.*

1. Delete `C:/Temp/qa-alice` if it exists.
2. Start Alice.
3. You are asked to set a passphrase. Enter one and confirm it.
4. Set a display name.

**Expect:**
- The identity is created without a visible pause of more than a second or two.
- The main window opens, with no conversations.
- `C:/Temp/qa-alice` now contains `identity.p12`, `chat.db` and `profile.properties`.
- The hamburger (top of the sidebar) opens a drawer showing your avatar, display name and peer
  id, with **Profile**, **Settings**, **Relay** and **About**. **About** shows a version, a
  commit and the channel.

**Watch for:** any mention of `keytool`, or a stack trace in the terminal. Identity generation used
to shell out to `keytool`; it no longer does, and this is the case that would catch a regression.

## 2 — Cold start on a machine with no JDK

*The case the automated suite cannot reach, and the one that has bitten this project before.*

```bash
./gradlew :chat-desktop:packageAppImage -PreleaseBuild
```

1. Copy `chat-desktop/build/jpackage/Tetherless` to a machine — or a user account — with **no JDK
   installed and nothing Java on `PATH`**. A clean VM is ideal.
2. A packaged build refuses the development certificate by design, so give it the relay's:
   put `truststore=dev-keystore.p12` in `config.properties` in the profile directory and copy the
   keystore beside it. See `docs/tls_provisioning.md`.
3. Run `Tetherless.exe` and create an identity.

**Expect:** the identity is created and the app connects. The bundled runtime contains one
executable and no JDK tools, so anything the client needs it must do itself.

**If this fails, do not tag the release.** It means the shipped artifact is broken for every new
user while working perfectly for everyone testing from a checkout.

## 3 — Warm start

*The same user, second launch.*

1. Close Alice, then start Alice again with the same profile directory.
2. Enter the passphrase.

**Expect:**
- The same peer id as before. Take it from the drawer's **Profile** → **Copy my id** and compare
  with the run before.
- Existing conversations and message history are present and readable.
- No prompt to set a passphrase or a display name again.

3. Restart once more and enter the **wrong** passphrase.

**Expect:** it is refused. You are not let in with an empty history, which would look like data
loss.

## 4 — A conversation

1. Start Bob in his own profile.
2. Take Bob's peer id from his drawer's **Profile** → **Copy my id**, then on Alice paste it into
   the sidebar's **search box**. A line appears under the box offering to start the chat; press
   Enter.
3. Send a few messages each way.

**Also check:** pasting something that is not an id and pressing Enter says so under the box
rather than doing nothing, and pasting your own id is refused with a different reason.

**Expect:**
- Both sides show the messages in the order sent.
- Outgoing messages show a tick, then a second tick when the peer's client acknowledges.
- The unread badge on the sidebar clears when the conversation is opened.
- Alice's safety number for Bob matches Bob's safety number for Alice. Open the shield button in
  the chat header — or the chat menu → **Safety number…** — to open **Chat info**, which shows
  both sides' safety numbers as grouped digits, on both sides. They must be **identical**; if they
  differ, stop — that is the signature of a machine-in-the-middle and is a release blocker.
- In Chat info, toggle **Verified** on for Alice's view of Bob. A shield appears next to Bob's
  name in Alice's sidebar row for the conversation.

## 5 — Reconnect

*The relay goes away and comes back.*

1. With both clients connected, stop the relay (Ctrl-C in its terminal).
2. Watch both windows.
3. Wait about thirty seconds.
4. Start the relay again.

**Expect:**
- Both clients show a disconnected or reconnecting state within a few seconds.
- They keep retrying, and the gap between attempts visibly grows rather than the client hammering
  the relay. Watch the relay's terminal on restart: a burst of connection attempts in a tight loop
  is a regression.
- Within a minute of the relay returning, both clients reconnect on their own, with no restart.
- The existing conversation still works — send a message each way to confirm.

## 6 — Offline send

*The peer is not there.*

1. Close Bob entirely.
2. From Alice, send a message to Bob.

**Expect:**
- Alice is told the message could not be delivered, rather than being shown a tick that means
  nothing.
- Nothing crashes, and Alice's window stays usable.

3. Start Bob again and send another message from Alice.

**Expect:** the new message arrives. Note that a message sent while Bob was offline is **not**
queued for later delivery — the relay stores nothing. If it silently reappears, that is a finding.

## 7 — Key change

*Someone's identity key is not what it was.*

1. Close Bob.
2. Delete only `identity.p12` from Bob's profile, leaving the rest.
3. Start Bob, set a passphrase, and open a chat with Alice from Bob's new identity.

**Expect:**
- Bob has a **different peer id**, because the id is derived from the key.
- Alice's existing conversation with Bob's **old** id is still listed and still readable — the
  history is hers, and a peer regenerating a key must not destroy it.
- Opening a chat to Bob's new id works, and produces a **different** safety number from before.

Then check the warning path, which is the one that matters:

4. In Alice's chat with Bob, use the chat menu → **Renegotiate encryption**.

**Expect:** the session is torn down and re-established, and Alice is not left in a state where
messages silently stop arriving. The automated suite covers what happens when a key genuinely
changes under a stable id (`AdversarialRelayTest`); what a person is checking here is that the
resulting warning is legible and hard to miss rather than buried in a log.

## 8 — Clock skew

*The two machines disagree about the time.*

1. Change the Windows clock forward by ten minutes (Settings → Time & language, turn off automatic
   time).
2. Restart Alice and try to send a message to Bob.

**Expect:** the message is rejected rather than delivered — frames more than five minutes from
local time are refused. Bob should not render it.

3. Set the clock forward by only two minutes and try again.

**Expect:** delivery works. The tolerance exists so ordinary clock drift does not break the app.

4. **Turn automatic time back on before continuing.**

## 9 — Update notice

1. Start a client with a repository that has a newer release than this build:
   ```bash
   ./gradlew :chat-desktop:run -Dtetherless.config.dir=C:/Temp/qa-alice -Dtetherless.updates.repository=cookedaryan/tetherless
   ```

**Expect:**
- If a newer release exists, a banner appears across the top of the window naming the version.
- Clicking it opens the release page in a browser.
- The ✕ dismisses it, and the window is usable underneath either way.

2. Start with the check switched off:
   ```bash
   ./gradlew :chat-desktop:run -Dtetherless.config.dir=C:/Temp/qa-alice -Dtetherless.updates=false
   ```

**Expect:** no banner, and no request to `api.github.com`.

3. Start with no network at all (disable the adapter, or point the check at a repository that does
   not exist).

**Expect:** the window opens at the usual speed and nothing is shown. A slow or failed update check
must never delay startup or produce an error.

## 10 — A damaged peer store

*The file holding pinned peer keys is corrupt, and the client must refuse to run rather than
forget everyone.*

This is the one case here that checks a **refusal**, so run it last among the client cases and be
ready to restore the file.

1. Close Alice. Copy `peers.properties` out of her profile directory, somewhere safe.
2. Truncate the copy in place — open the original in a hex editor and delete the last forty bytes,
   or overwrite the tail of the last line with anything that is not valid Base64.
3. Start Alice.

**Expect:** the client **does not start**. It reports that the stored peer keys are unusable, names
the file, and says to restore a backup or delete it and re-verify safety numbers. It must not start
with an empty contact list.

*Why this is a case a person runs.* Starting with no pinned keys is not a visible failure — the
client would look completely normal and would trust the next `HELLO` from a long-known contact on
sight, with no key-change warning possible because there is nothing left to compare against. The
whole point is that the failure is loud, so the thing to check is that a user actually sees it and
is told what to do.

4. Restore the file you copied out. Start Alice.

**Expect:** the client starts normally, the conversation with Bob is intact, and the safety number
is unchanged from case 4.

## 11 — The relay's metrics port

*Operational counters must not be reachable from anywhere but the machine running the relay.*

1. With the relay running, on the same machine: open `http://127.0.0.1:8081/metrics`.

**Expect:** a plain-text list of counters.

2. From another machine on the same network, or using this machine's LAN address rather than
   loopback: `http://<lan-address>:8081/metrics`.

**Expect:** the connection is **refused**. The endpoint has no authentication, so anything that can
reach it can read who is using the relay and how much.

3. Check the relay's startup log.

**Expect:** a line reading `Metrics server started on 127.0.0.1:8081`. If it names any other
address, the deployment has overridden `METRICS_HOST` and needs authentication in front of it —
the relay logs a warning in that case, and the warning is the thing to look for.

## 12 — Closing down

1. With both clients connected, close Alice's window.

**Expect:** the relay's log records a disconnect for that client promptly, rather than holding the
session open until a timeout. A client that leaves without saying so keeps the relay routing to
somewhere nobody is listening.

## 13 — Panels and theme

*Settings and Chat info used to be a dialog and a JOptionPane. Now they are sliding panels, and
FlatLaf drives the look and feel — check both.*

1. With a conversation open, open the drawer from the hamburger and choose **Settings**.
2. Without closing it, try to open **Chat info** from the shield button in the chat header.
3. Close whichever panel is open, then open **Chat info** from the header.
4. Press **Escape**.
5. Open **Chat info** again, then click the dimmed area outside the panel.
6. Open the drawer and toggle **Night mode**. With the theme switched, look at a tooltip, a
   scrollbar (the conversation list, or a panel body long enough to scroll), and the window's
   own title bar.
7. Visit each drawer destination in turn — **Profile**, **Settings**, **Relay**, **About** — and
   operate a control on each: copy your id, flip a toggle, read the relay status.

**Expect:**
- Only one panel is open at a time — opening the second one while the first is still open does not
  leave both on screen at once.
- Escape closes the open panel.
- Clicking the dimmed area outside the panel closes it.
- **Every panel's controls actually respond.** A panel whose contents look faded and whose clicks
  close it instead of acting is the scrim sitting above the sheet — the defect fixed in "Put a
  side panel above its own scrim", and the reason this step exists.
- Night mode changes the tooltip, the scrollbar and the title bar along with the rest of the
  window. The title bar is drawn by FlatLaf rather than Windows; if it stays light while the app
  goes dark, that is a regression.

---

## Result

| # | Case | Result | Notes |
|---|------|--------|-------|
| 1 | Cold start | | |
| 2 | Cold start, no JDK | | |
| 3 | Warm start | | |
| 4 | A conversation | | |
| 5 | Reconnect | | |
| 6 | Offline send | | |
| 7 | Key change | | |
| 8 | Clock skew | | |
| 9 | Update notice | | |
| 10 | Damaged peer store | | |
| 11 | Relay metrics port | | |
| 12 | Closing down | | |
| 13 | Panels and theme | | |

Tested by: &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; Version / commit: &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; Date:

---

## Known limits of this script

- **Desktop only, Windows only.** The Android client is not part of this release, so the
  desktop↔mobile and mobile↔mobile combinations are deliberately absent. Add them when mobile
  ships; the cases above transfer nearly unchanged.
- **Both clients on one machine.** That leaves genuine network behaviour — a flaky link, a captive
  portal, NAT timeouts — untested. Case 5 approximates the relay disappearing, not the network
  degrading.
- **No code signing.** Windows will warn about an unknown publisher on first run of the packaged
  build. That is expected for this release and is not a finding.
- **File permissions are not checked here, and cannot be on Windows.** The identity keystore and
  peer store are created owner-only where the filesystem supports POSIX modes. Windows has no
  equivalent, so the tests covering it skip on this platform and this script cannot stand in for
  them. Someone has to run `./gradlew :core-shared:test` on Linux or macOS, where
  `PrivateFilesTest` and `JceKeyStoreManagerTest.keyMaterialIsNotReadableByOtherLocalAccounts`
  actually execute. Until that has happened, treat the `0600` guarantee as written but unverified.
