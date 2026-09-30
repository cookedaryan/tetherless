# Demo deployment

A short, reliable setup for showing Tetherless working, using the Electron client. Written for a live
demo, where the only thing that matters is that it works the first time.

The instructions below were run end to end before being written down.

---

## What runs

Three processes on one laptop: the relay, and two Electron windows — Alice and Bob — each with its
own identity. Each window starts its own Java engine as a child process; you never start those
yourself.

---

## Once, beforehand

**1. The development certificate.** It is gitignored, so a fresh clone does not have it:

```bash
bash scripts/generate-dev-cert.sh
```

Without `chat-server/src/main/resources/dev-keystore.p12` the relay will not start and the clients
cannot connect.

**2. Build everything.** Nothing should compile during the demo:

```bash
./gradlew :chat-server:installDist :desktop-engine:installDist
cd chat-desktop-electron
npm install
npm run build
```

If `npm install` finishes but `npx electron .` later says Electron "failed to install correctly",
npm blocked its download script. Run it directly:

```bash
node node_modules/electron/install.js
```

**3. Prove the stack works, before an audience does.** With the relay running (below), from
`chat-desktop-electron`:

```bash
npm run e2e
```

It starts two engines, has them exchange a signed key handshake and messages through the real relay,
and checks the delivery acknowledgement, the reply, stored history, and that both sides compute the
same safety number. It should end with `ROUND TRIP OK`. If it does not, the demo will not work
either, and it is far better to learn that now.

---

## On the day — three terminals

**Terminal 1 — the relay** (listens on 8080):

```bash
./gradlew :chat-server:run
```

**Terminal 2 — Alice**, from `chat-desktop-electron`:

```bash
export TETHERLESS_CONFIG_DIR="C:/Temp/demo-alice"
npx electron . --user-data-dir="C:/Temp/demo-alice-ui"
```

**Terminal 3 — Bob**, from `chat-desktop-electron`:

```bash
export TETHERLESS_CONFIG_DIR="C:/Temp/demo-bob"
npx electron . --user-data-dir="C:/Temp/demo-bob-ui"
```

Two settings differ per window, and both matter. `TETHERLESS_CONFIG_DIR` is the identity, database
and profile. `--user-data-dir` is Electron's own cache; two windows sharing it fight over Chromium's
lock files. Use throwaway paths so a rehearsal is reset by deleting the four folders.

---

## What to show, in order

About five minutes. It builds an argument rather than listing features.

1. **Create Alice's identity.** The screen says the passphrase never leaves the machine and that
   losing it loses the history. Point at that sentence.
2. **Show the peer id in the title bar.** It is a hash of Alice's public key, not a name she chose.
   Nothing was registered with anyone.
3. **Create Bob's identity.** Copy Alice's id from her title bar, use **New chat** in Bob's window,
   paste it, send the first message. Note that a malformed or self-addressed id is rejected in place.
4. **Verify.** **Chat info** on both sides shows two safety numbers in groups of five. Read them
   aloud, mark verified, and show the shield appear in the conversation list. This is what separates
   the project from a chat app that merely uses TLS.
5. **Show the relay's terminal.** It logs routing and redacted ids, and no message content. The
   central claim is visible here rather than asserted.
6. **Stop the relay**, send a message, show it fail, restart the relay and watch the client
   reconnect.

---

## Pre-demo checklist

- [ ] `dev-keystore.p12` present
- [ ] `npm run build` and both `installDist` tasks already run
- [ ] `npm run e2e` printed `ROUND TRIP OK` on this machine
- [ ] Port 8080 free (`netstat -ano | findstr 8080`)
- [ ] `C:/Temp/demo-*` folders deleted, so identity creation is part of the show
- [ ] Passphrases written down — a forgotten one cannot be recovered, by design
- [ ] Rehearsed once, on the machine you will present from

---

## Two laptops instead of one

More convincing, and it fails on the day unless prepared. **The development certificate is issued for
`localhost` only** (`SAN=dns:localhost,ip:127.0.0.1`) and the client enforces hostname verification,
so a client reaching `192.168.x.x` gets a TLS failure rather than a warning.

Reissue it with the relay machine's address in it:

```bash
keytool -genkeypair -alias e2ee-relay -keyalg EC -groupname secp256r1 \
  -sigalg SHA256withECDSA -validity 3650 \
  -keystore chat-server/src/main/resources/dev-keystore.p12 -storetype PKCS12 \
  -storepass changeit -keypass changeit \
  -dname "CN=relay-demo, OU=Dev, O=E2EE Chat, L=City, ST=State, C=US" \
  -ext "SAN=dns:localhost,ip:127.0.0.1,ip:192.168.1.50"
```

Replace `192.168.1.50` with the relay machine's real address, then copy the regenerated keystore to
the same path on **both** client machines — the client pins that exact certificate. Point each client
at the relay with `-Dtetherless.host` and `-Dtetherless.port`, and allow inbound TCP 8080 on the
relay machine. Rehearse on the actual network: campus and conference Wi-Fi often isolate clients from
each other, which no configuration fixes.

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| Relay will not start; keystore error | `dev-keystore.p12` missing — run `scripts/generate-dev-cert.sh` |
| Window opens, stays "offline" | Relay not running, or the engine cannot find the certificate |
| `Electron failed to install correctly` | npm blocked its postinstall — `node node_modules/electron/install.js` |
| Second window is blank or crashes | Both windows share `--user-data-dir` |
| Both windows show the same identity | Both use the same `TETHERLESS_CONFIG_DIR` |
| Messages send but never arrive | No session yet — the first message triggers the handshake; give it a moment |
| `npm run e2e` cannot find the engine | Run `./gradlew :desktop-engine:installDist` and `npm run build` first |
| Port already in use | A relay from a previous run is still alive |

Do not run a bare `./gradlew build` before the demo. It fails on `chat-mobile`'s pre-existing lint
errors and will cost you ten minutes convincing yourself nothing is broken.

---

## Known limits of this build

Stated so nobody discovers them on stage. This is an early Electron client; it has **not** got the
Swing client's search, settings panel, reply quoting, pin/mute/archive, notifications, or a packaged
installer. Run from source as above. The cryptography and relay are the same code as the Swing
client and are the more heavily tested part.
