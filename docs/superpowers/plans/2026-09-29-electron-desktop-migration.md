# Electron Desktop Migration — Implementation Plan

**Scope: desktop only.** Mobile is explicitly out. The Android client and any React Native work are
not part of this plan and are not blocked by it.

**Route chosen: Electron UI over a headless Java engine.** The crypto and protocol are never
rewritten. `core-shared` and the desktop client logic keep running as Java, as a hidden child
process; the Electron window talks to it over stdin/stdout.

The alternative — a full TypeScript rewrite — was rejected. It would create a second implementation
of the wire protocol, which is exactly the drift `core-shared` exists to prevent, and it would put
AES-GCM, the signed DH handshake and the replay window into new code for no user-visible gain.

**Estimated total: 9–13 person-weeks** (revised down from 10–15; see the Phase 1 note).

---

## 1. What changes and what does not

| Component | Size | Fate |
|---|---|---|
| `core-shared` (codec, crypto, session, transport) | ~3,500 lines | **Untouched.** |
| Desktop client logic (`ChatClient`, `MessageRepository`, stores, keys, config) | ~3,000 lines | **Kept**, re-hosted in a headless process. |
| Swing UI (`ui/`, windows, panels, dialogs) | ~8,400 lines | **Retired**, replaced by React. |
| Java tests for logic and protocol | ~8,500 lines | **Kept and still run.** |
| `chat-server` relay | — | **Untouched.** |

**A finding that shaped this plan.** `ChatClient` is already headless. Its public surface is
`connect` / `disconnect` / `sendMessage` / `sendTyping` / `sendReadReceipt` / `startSecureChat` /
`restartSecureChat`, plus listener registration and accessors for the session, repository and peer
directory. No Swing type appears in it. The only coupling to the UI is that `ChatWindow` registers
as a `MessageListener` and `Main` bootstraps the Swing dialogs. So "extract a headless engine" is
mostly *writing a second `main()`* that speaks JSON instead of opening a window — not a refactor of
the client.

**What this retires.** The Swing client, the Compose Multiplatform client scaffold, and the unmerged
`mono-theme` branch's Swing theme work. The *palette itself* survives: the tokens become CSS custom
properties, and the design canvas already holds them as working HTML/CSS. See §8.

---

## 2. Architecture

```
┌── Electron ────────────────────────────────────────────┐
│  renderer (React)   no Node, no keys, no plaintext at   │
│        │            rest, sandboxed, CSP                │
│     preload  ── contextBridge: one typed, narrow API    │
│        │                                                │
│  main process ── spawns and supervises the engine       │
└────────┼────────────────────────────────────────────────┘
         │  newline-delimited JSON over stdin/stdout
┌────────┼────────────────────────────────────────────────┐
│  Java engine (headless)                                 │
│    ChatClient · MessageRepository · PeerDirectory        │
│    core-shared: codec, AES-GCM, signed DH, replay window │
│    TLS 1.3 pinned ──────────────────────────► relay      │
└─────────────────────────────────────────────────────────┘
```

### Where the code lives

The Electron app is **its own folder at the repository root**, with its own `package.json` and build.
It is not a Gradle module and Gradle never builds it.

```
chat-desktop-electron/        npm + Vite + electron-builder, no Gradle
  src/main/                   Electron main process, engine supervisor
  src/preload/                the contextBridge surface, and nothing else
  src/renderer/               React UI
  src/shared/                 the IPC contract types, imported by both sides
  resources/engine/           the jlink runtime + engine jar, staged at build time
desktop-engine/               NEW Gradle module: the headless Java engine
  src/main/java/.../EngineMain.java
```

The Java engine stays a **Gradle module**, because it is Java and it reuses `chat-desktop`'s logic
and `core-shared`. Only the TypeScript/Electron side lives in `chat-desktop-electron/`. Registered in
`settings.gradle` as an optional module alongside the others, so a relay-only build context is
unaffected.

**Why stdio and not a localhost socket.** A TCP port on loopback is reachable by every other process
running as this user. The passphrase, the plaintext of every message and the identity key all cross
this channel. A pipe to a child process is private to the parent by construction. Do not use a port.

---

## 3. Global constraints

**Security — these are not negotiable, and an Electron app that gets them wrong is worse than the
Swing app it replaced.**

- `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true` on every window. The renderer
  gets Node access over the author's dead body.
- A Content-Security-Policy with no `unsafe-inline` and no remote origins. The app loads no remote
  content, ever — no CDN fonts, no analytics, no remote images.
- **Peer message text is attacker-controlled input.** Render it as text, never as HTML. No
  `dangerouslySetInnerHTML` anywhere in the transcript path. A cross-site-scripting bug in a chat
  renderer is a remote code execution bug in an Electron app.
- Keys, the passphrase and decrypted history live in the **Java engine only**. The renderer receives
  already-decrypted text for display and holds no key material. The passphrase crosses the preload
  bridge once, at unlock, and is never stored in renderer state.
- `webSecurity` stays on. `allowRunningInsecureContent` stays off. No `<webview>`, no `shell.openExternal`
  on a peer-supplied string without an allowlist check.

**Engineering**

- The Java engine stays **Java 8** source level and keeps the existing gates: Checkstyle
  (`UnusedImports`, `AvoidStarImport`, `NeedBraces`) and SpotBugs + find-sec-bugs at HIGH confidence.
- Never run a bare `./gradlew build` — `chat-mobile` fails on pre-existing lint errors. Module-scoped
  tasks only.
- The frozen protocol vectors keep running. They are the guarantee that nothing about the wire
  changed.
- TypeScript `strict` on. The IPC contract is typed in one file and shared by preload and renderer.

---

## 4. The IPC contract

One file defines it; both sides import it. Newline-delimited JSON, one object per line.

**Commands (renderer → engine)** — each carries an `id`; the engine answers with the same `id`.

| Command | Payload | Answer |
|---|---|---|
| `unlock` | `{passphrase, displayName?}` | `{clientId, fingerprint}` or `{error}` |
| `createIdentity` | `{passphrase, displayName}` | `{clientId, fingerprint}` |
| `connect` | `{host, port}` | `{state}` |
| `disconnect` | `{}` | `{state}` |
| `listConversations` | `{}` | `{conversations[]}` |
| `history` | `{peerId, limit, before?}` | `{messages[]}` |
| `send` | `{peerId, text, replyToId?}` | `{messageId, status}` |
| `startSecureChat` | `{peerId}` | `{}` |
| `renegotiate` | `{peerId}` | `{}` |
| `setVerified` | `{peerId, verified}` | `{}` |
| `fingerprint` | `{peerId}` | `{ours, theirs}` |
| `typing` | `{peerId, typing}` | `{}` |
| `readReceipt` | `{peerId}` | `{}` |
| `search` | `{peerId?, query, limit}` | `{messages[]}` |
| `settings` / `setSetting` | — | current resolved config |

**Events (engine → renderer)** — unsolicited, no `id`.

`message` · `deliveryStatus` · `typing` · `connectionState` · `keyChanged` · `sessionState` ·
`outboxSent`

`keyChanged` matters: it is the machine-in-the-middle warning, and the UI must block sending until
the user re-verifies, exactly as the Swing client does today.

---

## 5. Phases

Each phase ends with something runnable. Estimates assume one developer already comfortable with
TypeScript, React and Electron.

### Phase 0 — Spike: prove the round trip · 0.5 wk
A throwaway `EngineMain` that opens the existing `ChatClient` against a local relay and echoes
`connectionState` events as JSON to stdout, driven by a 30-line Node script.
**Exit:** two Electron-less processes exchange a real message through the real relay.
**If this phase is hard, stop and reconsider the route.**

### Phase 1 — The headless engine · 1–1.5 wk
A new Gradle module (or a second entry point in `chat-desktop`) with `EngineMain`: reads the config
dir, opens `DatabaseHelper`/`DesktopConfig`/`JceKeyStoreManager`, and runs the command loop. It
registers itself as the `MessageListener`/`OutboxListener` that `ChatWindow` used to be.
- Unlock and identity creation move here from `Main`'s Swing bootstrap.
- Errors become `{error: {code, message}}` lines, never stack traces on stdout.
- stdout is the protocol channel and carries **nothing else** — logging goes to stderr or a file, or
  it corrupts the stream.
**Exit:** `EngineMain` drives a full conversation from a scripted stdin file. Java gates green.

### Phase 2 — Contract, client library, and the Electron shell · 1.5–2 wk
The typed contract, a small TS client (spawn, frame, correlate replies, surface events as an emitter,
supervise crashes and restart), and the Electron main process with the full security baseline from §3.
**Exit:** an Electron window with no UI logs a live `message` event from a real peer.

### Phase 3 — Core UI · 3–4 wk
Sign-in (all three modes: first run, unlock, unlock-needs-name), conversation list, transcript with
grouping and date separators, composer, delivery ticks, typing indicator, connection banner.
Built on the palette in §11, as CSS custom properties.
**Exit:** two Electron clients hold a verified conversation, with history surviving restart.

### Phase 4 — Panels and the rest of the surface · 1.5–2 wk
Chat info with the safety number, verification, renegotiate, search, settings (appearance, privacy,
relay), about/profile, the navigation drawer, notifications, the update banner.
**Exit:** feature parity checklist from §7 passes.

### Phase 5 — Packaging · 1–1.5 wk
`jlink` a minimal runtime (~40–60 MB) containing only the modules the engine needs, bundle it as an
electron-builder `extraResource`, and resolve the engine path for both dev and packaged runs.
Windows installer, per-user install, no code signing (unchanged from today).
**Exit:** a clean Windows VM installs and runs it with no JDK present.

### Phase 6 — Parity, QA and cutover · 1–1.5 wk
Work the QA script against the Electron build, fix the gaps, then retire the Swing entry point.
**Exit:** `docs/qa_script.md` passes end to end.

---

## 6. Data compatibility — free, and worth stating

Because the engine *is* the existing Java code, an existing user's `identity.p12`, `chat.db` (with
its PBKDF2-derived key) and `peer-*.properties` all keep working untouched. There is **no data
migration** in this plan. That is the single largest advantage of Route 1 over a TypeScript rewrite,
where every one of those formats would have to be re-implemented byte-for-byte.

---

## 7. Parity checklist (the cutover gate)

Sign-in in all three modes · create identity · wrong passphrase shakes in place · conversation list
with unread badges, pinning, mute, archive, delete · transcript grouping, date separators, reply
quoting · delivery ticks including the failed state · typing indicator · read receipts · offline send
and later delivery · safety number display and verification · key-change warning blocks sending ·
renegotiate · search with jump-to-message · settings including the update-check control ·
Night/Day theme · notifications · reconnect with backoff · update banner.

---

## 8. What this retires, and what survives

- **Retired:** the Swing UI (~8,400 lines), the Compose client scaffold, and the Swing-side theme
  work on the unmerged `mono-theme` branch.
- **Also retired: the warm monochromatic palette.** The Electron client uses the new direction in
  §11 instead. This is a reversal of an earlier decision and worth stating plainly rather than
  letting it rot in a branch.
- **Consequence for `mono-theme`:** its value has dropped. It repaints a Swing client that Phase 6
  deletes, in a palette that is no longer the direction. Revised recommendation: **do not merge it.**
  Leave the branch in place as a record, and cherry-pick only the two commits worth keeping outside
  the theme - the dead-import fix that unblocked `:core-shared:check`, and ADR-13 if the reasoning
  about unread badges and avatar glanceability is worth keeping in history. Everything else is now
  work about a retired toolkit in a retired palette.

---

## 9. Risks

**The renderer is a browser rendering hostile text.** The highest-severity risk in this plan, and it
is new — Swing had no scripting engine. Mitigated by §3, and worth a dedicated review pass before
cutover rather than a checkbox.

**stdout contamination.** Any stray `System.out.println` in the engine or its dependencies corrupts
the protocol stream. Redirect `System.out` to stderr inside `EngineMain` before the loop starts, and
write protocol frames to a captured handle.

**Engine crash handling.** If the engine dies with unsent messages the user must be told, not left
with a window that silently stops working. The supervisor restarts it and the UI shows a disconnected
state; the outbox already persists.

**Installer size and memory.** Roughly 200–250 MB installed against ~60 MB today, and 200–400 MB of
RAM. Acceptable for a desktop chat app, but it is a real regression — state it rather than discover it.

**Two runtimes to debug.** A failure can now be in the renderer, the main process, the pipe, or Java.
Phase 2's supervisor should log every frame in dev builds.

---

## 10. Open decisions

1. Confirm `mono-theme` is abandoned and only the `:core-shared` fix is cherry-picked (§8).
2. Does the Swing client stay buildable during the migration as a fallback, or is it deleted at
   Phase 1? Recommendation: keep it until Phase 6 passes.
3. Accent colour: the recommendation in §11, or one of the two alternates beside it.
4. React framework and styling approach — free choice, but no remote assets (§3).

---

## 11. Visual direction — the palette

Cool graphite surfaces, near-neutral with a faint blue cast, and **one vivid accent used sparingly**.
Deliberately not the warm monochromatic direction, and deliberately not the original Telegram blue.

**The move that makes it read as current: outgoing bubbles are not coloured.** They are a lighter
elevated neutral, and the accent is reserved for things that mean something — the primary action,
read ticks, the verified shield, focus rings. A transcript of two neutral greys with one bright
colour appearing only where it carries information is the shape modern tools have converged on;
a wall of accent-coloured bubbles is what dates an interface.

### Tokens

| Token | Dark (default) | Light |
|---|---|---|
| `--page` | `#0B0D10` | `#F7F8FA` |
| `--surface` | `#111419` | `#FFFFFF` |
| `--surface-hover` | `#161A21` | `#F0F2F5` |
| `--surface-raised` | `#1A1E26` | `#FFFFFF` + shadow |
| `--chat-bg` | `#0E1116` | `#FFFFFF` |
| `--bubble-in` | `#171B22` | `#F1F3F6` |
| `--bubble-out` | `#232A35` | `#E4E9F0` |
| `--bubble-error` | `#2A1A1C` | `#FDECEC` |
| `--text` | `#E8EBEF` | `#0F1319` |
| `--text-secondary` | `#8B94A3` | `#5E6875` |
| `--text-tertiary` | `#6B7484` | `#8A93A0` |
| `--divider` | `#1E232B` | `#E6E9EE` |
| `--accent` | `#22D3EE` | `#0891B2` |
| `--accent-hover` | `#06B6D4` | `#0E7490` |
| `--accent-ink` | `#062B33` | `#FFFFFF` |
| `--tick` | `#22D3EE` | `#0891B2` |
| `--unread` | `#FF6B57` | `#F0553D` |
| `--unread-ink` | `#FFFFFF` | `#FFFFFF` |
| `--danger` | `#FF6B6E` | `#D93A3D` |

`--accent-ink` is dark on the dark theme's accent on purpose: white text on vivid cyan fails contrast,
dark ink on it does not, and it is what makes a bright accent look deliberate rather than cheap.

The unread badge stays **off-accent** — the one piece of reasoning worth carrying over from ADR-13.
An unread count competing with the accent for attention makes both weaker.

### Avatars — glanceability comes back

Seven vivid gradients on the neutral ground. The monochromatic direction gave up hue as a way to
identify a peer at a glance; a neutral base lets the avatars carry colour instead, so this recovers
what that cost without making the whole interface loud.

| Slot | From | To |
|---|---|---|
| Cyan | `#22D3EE` | `#0891B2` |
| Violet | `#A78BFA` | `#7C3AED` |
| Emerald | `#34D399` | `#059669` |
| Amber | `#FBBF24` | `#D97706` |
| Rose | `#FB7185` | `#E11D48` |
| Indigo | `#818CF8` | `#4F46E5` |
| Orange | `#FB923C` | `#EA580C` |

### Typography

**Geist** (open-source, genuinely current) bundled locally, with a system stack behind it. Not Inter
and not Roboto — both are so ubiquitous now that they read as a default rather than a choice. No
remote font loading, per §3.

### Alternates, if the accent is wrong

The base holds either way; only the accent changes.

- **Violet** `#8B5CF6` / `#7C3AED` — softer, more product-like. The most common choice in current
  tooling, which is both why it works and why it is less distinctive.
- **Lime** `#BEF264` / `#65A30D` — sharpest and most distinctive, and the biggest risk: it reads
  energetic rather than trustworthy, which may fight what a privacy tool is trying to say.

Cyan is the recommendation: crisp on near-black, reads technical and calm, and is neither the blue
this project started with nor the teal it just left.
