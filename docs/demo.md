# Demo deployment

A short, reliable setup for showing Tetherless working. Written for a live demo in front of an
audience, where the only thing that matters is that it works the first time.

---

## Demo the Swing client, not the Electron one

The Swing client (`chat-desktop`) is complete: identity, conversations, verification, delivery
state, search, settings. The Electron client is mid-migration — it signs in and lists conversations
and cannot yet hold a conversation. **Use the Swing client for the demo.** The Electron work belongs
in the report as future work; `docs/superpowers/plans/2026-09-29-electron-desktop-migration.md` is a
costed plan you can point at.

---

## Option A — one machine (recommended)

Everything on one laptop: the relay plus two clients with separate profiles. No certificate work, no
network to fail, nothing to configure on the day.

### Once, beforehand

```bash
bash scripts/generate-dev-cert.sh
```

Only needed if `chat-server/src/main/resources/dev-keystore.p12` is missing — it is gitignored, so a
fresh clone never has it. Without it the relay will not start and the clients cannot connect.

Then warm the build so nothing compiles during the demo:

```bash
./gradlew :chat-server:installDist :chat-desktop:installDist
```

### On the day — three terminals

Relay (listens on 8080):

```bash
./gradlew :chat-server:run
```

Alice:

```bash
./gradlew :chat-desktop:run -Dtetherless.config.dir=C:/Temp/demo-alice
```

Bob:

```bash
./gradlew :chat-desktop:run -Dtetherless.config.dir=C:/Temp/demo-bob
```

Two different config directories is the whole trick — each is a separate identity, database and
profile. Use throwaway paths so a rehearsal can be reset by deleting the folders.

---

## Option B — two laptops on a LAN

More convincing, and it will fail on the day if you do not prepare it, because **the development
certificate is issued for `localhost` only** (`CN=localhost`, `SAN=dns:localhost,ip:127.0.0.1`) and
the client enforces hostname verification. A client connecting to `192.168.x.x` gets a TLS failure,
not a warning.

To make it work, reissue the certificate with the relay machine's address in it:

```bash
keytool -genkeypair -alias e2ee-relay -keyalg EC -groupname secp256r1 \
  -sigalg SHA256withECDSA -validity 3650 \
  -keystore chat-server/src/main/resources/dev-keystore.p12 -storetype PKCS12 \
  -storepass changeit -keypass changeit \
  -dname "CN=relay-demo, OU=Dev, O=E2EE Chat, L=City, ST=State, C=US" \
  -ext "SAN=dns:localhost,ip:127.0.0.1,ip:192.168.1.50"
```

Replace `192.168.1.50` with the relay machine's actual LAN address. Then:

1. Copy the regenerated `dev-keystore.p12` to **both** client machines — the client pins this exact
   certificate, so every machine needs the same file.
2. Point each client at the relay: `-Dtetherless.host=192.168.1.50 -Dtetherless.port=8080`
   (or set `host` in that profile's `config.properties`).
3. Allow inbound TCP 8080 on the relay machine's firewall. Windows will prompt the first time.

Rehearse this end to end at least once on the actual network you will use. Conference and campus
Wi-Fi frequently isolate clients from each other, which no amount of configuration fixes.

---

## What to show, in order

About five minutes, and it builds an argument rather than listing features.

1. **Create Alice's identity.** Point out that the passphrase never leaves the machine and that
   losing it loses the history — the screen says so before you choose one.
2. **Show Alice's peer id.** It is a hash of her public key, not a name she picked. Nothing was
   registered with a server.
3. **Create Bob's identity**, paste Alice's id into New chat, send the first message.
4. **Verify.** Open Chat info on both sides and compare safety numbers — this is the part that
   distinguishes the project from a chat app with TLS. Mark verified and show the shield.
5. **Show the relay's terminal.** It logs routing, redacted peer ids, and no message content. This is
   the central claim, and it is visible rather than asserted.
6. **Kill the relay**, send a message, show it fail and the client reconnect when the relay returns.

If you have time, `./gradlew :chat-desktop:integTest` runs two clients and a live relay through a
hundred messages each way and asserts the relay learned nothing — a good slide, and it is real.

---

## Pre-demo checklist

- [ ] `dev-keystore.p12` present — and copied to every client machine if using Option B
- [ ] `:chat-server:installDist` and `:chat-desktop:installDist` already built
- [ ] Port 8080 free (`netstat -ano | findstr 8080`)
- [ ] Demo profile folders deleted, so first-run identity creation is part of the show
- [ ] Passphrases written down — a forgotten one cannot be recovered, by design
- [ ] Rehearsed once, on the machine and network you will use

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| Relay will not start; keystore error | `dev-keystore.p12` missing — run `scripts/generate-dev-cert.sh` |
| Client never connects, TLS error | Wrong or missing keystore on the client, or Option B without reissuing the certificate |
| Client connects, messages fail | No session yet — open the conversation and let the handshake complete |
| Both clients show the same identity | Both pointed at the same `tetherless.config.dir` |
| Tests fail on a fresh clone | Same missing keystore; it is gitignored |
| Port already in use | A relay from a previous run is still alive |

Do not run a bare `./gradlew build` before the demo. It fails on `chat-mobile`'s pre-existing lint
errors and will cost you ten minutes convincing yourself nothing is broken.
