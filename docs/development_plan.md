# Tetherless — E2EE Chat Application Development Plan

> **Status:** Scaffolded. Compiles for `core-shared`, `chat-server`, `chat-desktop`; `chat-mobile` does **not** compile (missing dependencies and resources — see BUILD-01).
> **Document owner:** engineering
> **Last revised:** 2026-08-20

---

## 0. How to read this document

Each ticket follows a fixed shape so it can be lifted straight into an issue tracker:

| Field | Meaning |
|---|---|
| **ID** | Stable identifier. Never reuse. |
| **Priority** | `P0` blocks everything downstream · `P1` required for v1.0 · `P2` desirable · `P3` nice-to-have |
| **Depends on** | Tickets that must be `Done` first |
| **Files** | Paths created or modified |
| **Action items** | Numbered, individually verifiable steps |
| **Acceptance criteria** | Binary pass/fail conditions a reviewer checks |
| **Tests** | The specific test that proves the ticket |

### Global definition of done

A ticket is `Done` only when **all** of the following hold:

1. `./gradlew build` succeeds from a clean checkout (`./gradlew clean build`).
2. New behaviour is covered by at least one automated test that fails without the change.
3. No `e.printStackTrace()` remains in the code paths touched (replaced by the logging facade from `BUILD-03`).
4. No plaintext message content, key material, or derived secret is written to any log at any level.
5. Public methods added to `core-shared` have Javadoc stating thread-safety and nullability.
6. The ticket's acceptance criteria are demonstrated, not asserted — a reviewer can reproduce them.

### Conventions adopted for this project

- **Java level:** source/target `11` for `core-shared`, `chat-server`, `chat-desktop`; `core-shared` additionally emits `1.8`-compatible bytecode for Android consumption (see `BUILD-01`).
- **Naming:** protocol constants live in `com.e2eechat.core.protocol`; crypto primitives stay in `com.e2eechat.core.crypto`; no crypto in client modules.
- **Byte encoding:** all binary blobs crossing the wire are raw `byte[]`; Base64 is used *only* for display and on-disk text formats.
- **Time:** all timestamps are `long` epoch milliseconds UTC, produced by a single injectable `Clock`.
- **IDs:** `messageId` is a `java.util.UUID` rendered as a lowercase canonical string.

---

## 1. Current-state assessment

An honest inventory of what exists today, because several tickets below exist purely to correct scaffolding shortcuts.

### 1.1 What works

- `AESUtils` — AES-256-GCM encrypt/decrypt with a 96-bit IV and 128-bit tag. Correct primitive choice.
- `DHUtils` — DH keypair generation, shared-secret derivation with a SHA-256 pass to produce 32 bytes.
- `RSAUtils` — 2048-bit keygen, `SHA256withRSA` sign/verify, X.509/PKCS#8 decoding.
- `ChatServer` — accepts sockets, registers clients by id, routes by `receiverId`.
- `ChatWindow` — a functioning Swing send/receive UI.
- Two green unit tests (`AESUtilsTest`, `DHUtilsTest`).

### 1.2 Known defects and gaps (each maps to a ticket below)

| # | Observation | Location | Ticket |
|---|---|---|---|
| 1 | `chat-mobile` declares no Room, AppCompat, RecyclerView, or annotation processor. It cannot compile. | `chat-mobile/build.gradle` | `BUILD-01` |
| 2 | Manifest references `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`; `res/mipmap*` does not exist. | `chat-mobile/src/main/AndroidManifest.xml` | `BUILD-01` |
| 3 | `core-shared` sets no `sourceCompatibility`; Android consumption of Java 11+ bytecode will fail or require desugaring. | `core-shared/build.gradle` | `BUILD-01` |
| 4 | `Message` has **no field to carry the GCM IV**. AES-GCM output is undecryptable without it. | `Message.java` | `CORE-01` |
| 5 | `Message` has no `messageId` and no `timestamp`. Ordering, dedup, and replay defence are impossible. | `Message.java` | `CORE-01` |
| 6 | `Message` is mutable-by-reference: `getPayload()` hands out the live array. | `Message.java` | `CORE-01` |
| 7 | Wire format is Java native serialization over a raw socket. `ObjectInputStream.readObject()` on untrusted input is a remote code execution class of bug. | `ChatServer`, `ChatClient`, `ChatService` | `CORE-03` |
| 8 | `ObjectOutputStream` caches object back-references; re-sending an object graph without `reset()` transmits a stale handle. | all three socket sites | `CORE-03` |
| 9 | `DHUtils.generateKeyPair()` runs `AlgorithmParameterGenerator.init(2048)` per call — this takes tens of seconds to minutes and is a de-facto self-DoS. | `DHUtils.java:20-22` | `CORE-04` |
| 10 | DH is **unauthenticated**. The relay server can transparently MITM every session. The `signature` field exists but is never populated or checked. | protocol-wide | `CORE-05`, `CORE-06` |
| 11 | Server writes to a shared `ObjectOutputStream` from multiple handler threads with no synchronization. Interleaved writes corrupt the stream. | `ChatServer.routeMessage` | `SERVER-02` |
| 12 | `clients.remove(clientId)` removes by key without checking the mapped value is *this* handler's stream — a reconnect races the old handler's cleanup and evicts the live client. | `ChatServer.java:66` | `SERVER-01` |
| 13 | No read timeout on the server socket. A dead peer holds a thread and a map entry forever. | `ChatServer` | `SERVER-01` |
| 14 | `Executors.newCachedThreadPool()` is unbounded — one thread per connection, no backpressure. | `ChatServer.java:17` | `SERVER-03` |
| 15 | No `DISCONNECT` is ever sent by any client; the branch is dead code. | clients | `SERVER-01` |
| 16 | Server logs `senderId`/`receiverId` per message to stdout — a metadata leak and an unbounded log. | `ChatServer.routeMessage` | `SERVER-04` |
| 17 | Desktop `ChatClient.connect()` starts the reader thread before `setWindow()` is called; early messages hit a null window and vanish. | `Main.java:12-18` | `CLIENT-DESKTOP-01` |
| 18 | `ChatClient` sends and displays **plaintext**; the encryption is a comment. | `ChatClient.java:41,60` | `CLIENT-DESKTOP-03` |
| 19 | `DatabaseHelper` creates a table and has no insert, query, or connection lifecycle. Nothing is ever persisted. | `DatabaseHelper.java` | `CLIENT-DESKTOP-04` |
| 20 | Desktop `receiverId` is a fixed `args[1]`; there is no peer list, no way to talk to a second person. | `Main.java` | `CLIENT-DESKTOP-06` |
| 21 | `chat-desktop/build.gradle` declares no test dependency; the module is untestable. | `chat-desktop/build.gradle` | `BUILD-01` |
| 22 | `MainActivity` has a commented-out `setContentView`. There is no layout, no adapter, no ViewModel. | `MainActivity.java` | `CLIENT-MOBILE-02` |
| 23 | `ChatService` hardcodes `"mobileUser"` and host `10.0.2.2`, is not a foreground service (killed on Android 8+), and drops received messages on the floor. | `ChatService.java` | `CLIENT-MOBILE-03`, `CLIENT-MOBILE-04` |
| 24 | `MessageDao` returns a blocking `List`; querying on the main thread will throw. No `LiveData`/`Flow`, no `AppDatabase` singleton builder. | `MessageDao`, `AppDatabase` | `CLIENT-MOBILE-01` |
| 25 | `ChatServerTest` asserts `true == true`. | `ChatServerTest.java` | `SERVER-05` |
| 26 | Unused imports: `SecretKeySpec` in `AESUtils` and `DHUtils`, `Arrays` in `DHUtils`. | crypto | `BUILD-02` |
| 27 | No CI, no static analysis, no dependency pinning beyond `sqlite-jdbc`. | repo root | `BUILD-04` |

---

## 2. Target architecture

```
                         ┌──────────────────────────┐
                         │       chat-server        │
                         │  (dumb, untrusted relay) │
                         │  routes ciphertext only  │
                         └────────┬────────┬────────┘
                        TLS 1.3   │        │  TLS 1.3
                   ┌──────────────┘        └───────────────┐
                   │                                       │
        ┌──────────┴──────────┐                 ┌──────────┴──────────┐
        │    chat-desktop     │                 │     chat-mobile     │
        │  Swing UI + SQLite  │                 │  Android + Room     │
        └──────────┬──────────┘                 └──────────┬──────────┘
                   │                                       │
                   └──────────────┬────────────────────────┘
                                  │
                     ┌────────────┴────────────┐
                     │      core-shared        │
                     │  Message · Codec ·      │
                     │  AES/DH/RSA · Session   │
                     └─────────────────────────┘
```

### 2.1 Threat model (write this down before writing code)

**In scope — the design must defeat these:**

- **T1 — Passive relay operator.** The server sees every byte. It must learn nothing but routing metadata.
- **T2 — Active relay operator (MITM).** The server can substitute DH public keys during exchange. Defeated only by `CORE-05` + `CORE-06` (signed key exchange with out-of-band-verifiable fingerprints).
- **T3 — Network attacker.** Defeated by TLS (`SERVER-06`) *in addition to* E2EE — not instead of it.
- **T4 — Replay.** An attacker re-sends a captured ciphertext. Defeated by `CORE-01` (`messageId` + `timestamp`) and `CORE-07` (per-session monotonic counter and IV-reuse prevention).
- **T5 — Malicious peer payload.** A peer sends a hostile serialized object. Defeated by `CORE-03` (explicit binary codec, no `readObject` on untrusted data).
- **T6 — Local device compromise, at rest.** Partially mitigated by `CORE-02` (encrypted keystore) and `CLIENT-*-05` (encrypted local DB).

**Explicitly out of scope for v1.0 (state it, do not pretend otherwise):**

- Forward secrecy across sessions beyond a single DH handshake — a full Double Ratchet is `FUTURE-01`.
- Post-compromise security / self-healing.
- Metadata privacy from the relay (who talks to whom, when, how often).
- Multi-device sync for one identity.
- Group chat.
- Traffic analysis and message-size padding.

### 2.2 Protocol state machine

```
   CLIENT                                                    RELAY / PEER
     │
     ├─ TCP connect + TLS handshake ─────────────────────────────►
     │
     ├─ HELLO { userId, rsaPublicKey, protoVersion, nonce } ─────►   (registers routing entry)
     ◄── HELLO_ACK { serverTime, acceptedVersion } ───────────────
     │
     │  ══ per-peer session establishment ══
     ├─ KEY_EXCHANGE_INIT  { dhPub, params, nonceA, sig } ───────►  routed to peer
     ◄── KEY_EXCHANGE_REPLY { dhPub, nonceB, sig } ───────────────  from peer
     │   derive K = HKDF(SHA-256, DH(a,B), salt = nonceA||nonceB, info = "tetherless-v1")
     │   session state: ESTABLISHED
     │
     ├─ TEXT_MESSAGE { messageId, ts, iv, ciphertext, sig } ─────►  routed to peer
     ◄── DELIVERY_ACK { messageId } ──────────────────────────────
     │
     ├─ DISCONNECT ──────────────────────────────────────────────►
```

Session states: `IDLE → HANDSHAKE_SENT → ESTABLISHED → EXPIRED`, plus terminal `FAILED`. Every transition and every illegal transition must be explicitly handled — `CORE-07` owns this.

---

## Phase 0 — Build and toolchain hygiene (BUILD)

**Rationale for existing:** The mobile module cannot compile today. No downstream mobile ticket can be started, tested, or reviewed until this phase closes. This phase is a hard gate.

---

### BUILD-01 — Make every module compile from a clean checkout
**Priority:** P0 · **Depends on:** — · **Estimate:** 1 day
**Files:** `core-shared/build.gradle`, `chat-desktop/build.gradle`, `chat-server/build.gradle`, `chat-mobile/build.gradle`, `chat-mobile/src/main/res/**`, `gradle/libs.versions.toml` (new)

**Description:** Bring the Gradle configuration to a state where `./gradlew clean build` is green across all four modules.

**Action items:**
1. Create `gradle/libs.versions.toml` as a version catalog. Pin: JUnit `4.13.2`, Room `2.6.1`, AppCompat `1.6.1`, RecyclerView `1.3.2`, Lifecycle `2.7.0`, `sqlite-jdbc 3.41.2.1`, Mockito `5.x`, AssertJ `3.x`. Every version referenced by exactly one coordinate.
2. In `core-shared/build.gradle`, set:
   - `java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }` so the artifact is consumable by Android with `minSdk 24`.
   - Add `testImplementation` for AssertJ.
   - Explicitly forbid any Android or JavaFX dependency here — this module must stay platform-neutral.
3. In `chat-mobile/build.gradle`, add:
   - `implementation 'androidx.appcompat:appcompat'`, `androidx.recyclerview:recyclerview`, `androidx.constraintlayout:constraintlayout`.
   - `implementation 'androidx.room:room-runtime'` **and** `annotationProcessor 'androidx.room:room-compiler'` — without the processor, `AppDatabase` generates nothing and the module fails at link time.
   - `implementation 'androidx.lifecycle:lifecycle-viewmodel'`, `lifecycle-livedata`.
   - `compileOptions { sourceCompatibility JavaVersion.VERSION_1_8; targetCompatibility JavaVersion.VERSION_1_8 }`.
   - `buildFeatures { viewBinding true }` to avoid hand-written `findViewById` chains.
4. Create the missing Android resources: `res/mipmap-anydpi-v26/ic_launcher.xml` (adaptive icon), `res/mipmap-hdpi/…mdpi/…xhdpi/…xxhdpi/ic_launcher.png`, `ic_launcher_round.png`, and `res/values/colors.xml` + `res/values/themes.xml`. Alternatively, for the very first pass, remove the `android:icon`/`android:roundIcon` attributes and rely on the framework default — but do not leave dangling resource references.
5. Add `testImplementation` JUnit to `chat-desktop/build.gradle`.
6. Add a root-level `subprojects { tasks.withType(JavaCompile) { options.compilerArgs << "-Xlint:all" } }` block.
7. Add `.gitignore` covering `.gradle/`, `build/`, `.idea/`, `*.iml`, `local.properties`, `*.db`, `*.jks`, `*.p12`.
8. Commit the checked-in artefacts currently untracked (`gradlew`, `gradlew.bat`, `gradle/wrapper/**`, `settings.gradle`, all module sources).

**Acceptance criteria:**
- `./gradlew clean build` exits 0 on a machine with only a JDK 17 and the Android SDK installed.
- `./gradlew :chat-mobile:assembleDebug` produces an APK.
- `git status` is clean after a build (nothing generated lands in the working tree).

**Tests:** the build itself; add a CI job in `BUILD-04` that runs it on every push.

---

### BUILD-02 — Static analysis and code hygiene baseline
**Priority:** P2 · **Depends on:** `BUILD-01` · **Estimate:** 0.5 day
**Files:** root `build.gradle`, `config/spotbugs/exclude.xml`, `config/checkstyle/checkstyle.xml`

**Action items:**
1. Apply SpotBugs to all Java modules; fail the build on `HIGH` priority findings only, to start.
2. Apply the `find-sec-bugs` SpotBugs plugin — it specifically flags the `readObject` deserialization sink, hardcoded keys, and weak crypto modes, all of which are relevant here.
3. Apply Checkstyle with a minimal rule set: unused imports, missing braces, star imports. This immediately catches the dead `SecretKeySpec` import in `AESUtils.java:8` and `DHUtils.java:5`, and `java.util.Arrays` in `DHUtils.java:16`.
4. Delete those three unused imports.
5. Add an `.editorconfig`: 4-space indent, LF endings, UTF-8, trim trailing whitespace, final newline.

**Acceptance criteria:** `./gradlew check` runs SpotBugs and Checkstyle and is green; deliberately introducing an unused import fails the build.

---

### BUILD-03 — Logging facade and payload-safety rule
**Priority:** P1 · **Depends on:** `BUILD-01` · **Estimate:** 0.5 day
**Files:** all modules; `chat-server/src/main/resources/logback.xml`

**Description:** `System.out.println` and `e.printStackTrace()` appear in every module. Beyond being unstructured, they are the most likely accidental route for plaintext to reach disk.

**Action items:**
1. Adopt SLF4J as the API across `core-shared`, `chat-server`, `chat-desktop`; bind Logback in `chat-server` and `chat-desktop` only (never in the library).
2. On Android, use `android.util.Log` behind a thin `Logger` interface defined in `core-shared` so shared code can log without an Android dependency.
3. Replace every `System.out.println` and `printStackTrace()` call site.
4. Introduce a `Redact` helper: `Redact.id(String)` returns the first 6 chars of a SHA-256 of the id, for correlating logs without recording who talked to whom.
5. Write the rule into `CONTRIBUTING.md`: *payload bytes, IVs, keys, shared secrets, and passphrases are never passed to a logger at any level, including `TRACE`.*
6. Add a SpotBugs/Checkstyle regex rule, or a simple unit test that greps the source tree, asserting no `printStackTrace` remains.

**Acceptance criteria:** grep for `printStackTrace\|System\.out\.print` over `*/src/main/java` returns zero hits.

---

### BUILD-04 — Continuous integration
**Priority:** P2 · **Depends on:** `BUILD-01` · **Estimate:** 0.5 day
**Files:** `.github/workflows/ci.yml`

**Action items:**
1. Job `build`: JDK 17, Gradle cache, `./gradlew clean build`.
2. Job `android`: set up the Android SDK, `./gradlew :chat-mobile:assembleDebug :chat-mobile:testDebugUnitTest`.
3. Job `integration`: runs the Phase 5 cross-platform interop suite (initially skipped, wired up in `INTEG-01`).
4. Publish JUnit XML and the SpotBugs report as build artefacts.
5. Add a `dependency-review` / OSV scan step so a vulnerable transitive dependency fails the PR.

**Acceptance criteria:** a PR that breaks a test is red; a clean PR is green in under 10 minutes.

---

## Phase 1 — Core cryptography and protocol (CORE)

**Rationale:** Everything else consumes this module. Wire-format churn after the clients are written is the single most expensive mistake available, so the format is finalised and versioned here first.

---

### CORE-01 — Redesign the `Message` model
**Priority:** P0 · **Depends on:** `BUILD-01` · **Estimate:** 1.5 days
**Files:** `core-shared/src/main/java/com/e2eechat/core/models/Message.java`, new `MessageType.java`, new `MessageBuilder.java`

**Description:** The current model is missing three fields without which the system cannot function at all, and one of them (the IV) makes decryption literally impossible. This is the highest-value ticket in the plan.

**Action items:**
1. Add the missing fields:
   - `String messageId` — canonical UUID string, generated by the sender, unique per message. Enables dedup, `DELIVERY_ACK` correlation, and idempotent DB writes.
   - `long timestamp` — sender's epoch-millis UTC. **Note it is sender-asserted and therefore untrusted** for anything security-relevant; the receiver records both this and its own receive time.
   - `byte[] iv` — the 12-byte GCM nonce for this specific message. Currently there is nowhere to put it, so `AESUtils.decrypt` can never be called on a received message.
   - `int protocolVersion` — a single `int`, checked on receipt. Costs 4 bytes now and saves a rewrite later.
2. Split the payload into a discriminated shape, per the original CORE-01 intent. Rather than overloading one `byte[]`, define the *interpretation* of `payload` per type in a table that lives in Javadoc **and** is enforced by the codec:

   | `MessageType` | `payload` contains | `iv` used? | `signature` required? |
   |---|---|---|---|
   | `HELLO` | encoded RSA public key (X.509) + client nonce | no | yes (self-signed proof of key possession) |
   | `HELLO_ACK` | server time + negotiated version | no | no |
   | `KEY_EXCHANGE_INIT` | encoded DH public key (X.509) + initiator nonce | no | **yes** |
   | `KEY_EXCHANGE_REPLY` | encoded DH public key (X.509) + responder nonce | no | **yes** |
   | `KEY_EXCHANGE_REJECT` | UTF-8 reason code | no | yes |
   | `TEXT_MESSAGE` | AES-256-GCM ciphertext incl. tag | **yes** | yes |
   | `DELIVERY_ACK` | referenced `messageId` as UTF-8 | no | no |
   | `DISCONNECT` | empty | no | no |
   | `ERROR` | UTF-8 error code + human-readable detail | no | no |
   | `PING` / `PONG` | 8-byte monotonic token | no | no |

3. Add `KEY_EXCHANGE_REJECT`, `DELIVERY_ACK`, `ERROR`, `PING`, `PONG`, `HELLO`, `HELLO_ACK` to the enum. Keep `CONNECT` as a deprecated alias of `HELLO` only if a migration path is needed; otherwise delete it.
4. **Never serialize the enum ordinal.** Java enum ordinals shift when a constant is inserted, silently reinterpreting old messages. Give each constant an explicit stable `int wireCode` and map by that. This is the classic cross-version break and it is cheap to prevent now.
5. Make the class immutable: `private final` on every field, defensive `.clone()` on `byte[]` in both the constructor and every getter. Today `getPayload()` hands callers a mutable reference into the message.
6. Replace the single public constructor with a `MessageBuilder` — there are now ~8 fields and 4 of them are optional per type. Validate invariants in `build()`: `TEXT_MESSAGE` must have a non-null 12-byte `iv` and non-empty payload; `HELLO` must have a null `receiverId`; every routed type must have a non-null `receiverId`.
7. Implement `equals`, `hashCode` (on `messageId`), and a `toString` that prints type, ids, size, and **never** payload bytes.
8. Add a `canonicalBytesForSigning()` method that produces a deterministic encoding of every field **except** `signature`. Field order, integer endianness (big-endian), and length prefixes must be fixed. Without this, signing and verification will disagree across platforms and the failure will look like "signature verification randomly fails".

**Acceptance criteria:**
- A `TEXT_MESSAGE` round-trips through the codec and decrypts successfully using only fields carried on the message.
- Mutating the array passed to the builder after `build()` does not change the message.
- `canonicalBytesForSigning()` returns byte-identical output for two independently constructed messages with equal fields, on both desktop JVM and Android.

**Tests:** `MessageTest` — immutability, builder validation rejects each invalid combination, canonical-bytes determinism, `toString` contains no payload.

---

### CORE-02 — Key management and the `KeyStoreManager`
**Priority:** P0 · **Depends on:** `CORE-01` · **Estimate:** 2 days
**Files:** new `core-shared/.../keys/KeyStoreManager.java`, `IdentityKeyStore.java` (interface), `SessionKeyCache.java`

**Description:** The identity RSA private key must survive restarts and must not sit in a plaintext file. The platforms differ enough that this needs an interface in `core-shared` and two implementations.

**Action items:**
1. Define `IdentityKeyStore` in `core-shared` with: `KeyPair loadOrCreateIdentity()`, `void storePeerKey(String peerId, PublicKey key)`, `Optional<PublicKey> getPeerKey(String peerId)`, `String fingerprint(PublicKey key)`.
2. Implement `JceKeyStoreManager` for desktop, backed by a `PKCS12` `java.security.KeyStore` file at `~/.tetherless/identity.p12`:
   - Passphrase supplied by the user at launch; derive the store password with PBKDF2-HMAC-SHA256 at ≥ 210,000 iterations with a stored random 16-byte salt.
   - Create the file with owner-only permissions (`PosixFilePermissions` where supported; `AclFileAttributeView` on Windows — this project's primary dev environment is Windows, so do not assume POSIX).
   - Never hold the passphrase in a `String`; use `char[]` and zero it after use.
3. Implement `AndroidKeyStoreManager` for mobile, backed by the `AndroidKeyStore` provider so the private key is hardware-backed where available and non-exportable. Note: `AndroidKeyStore` RSA keys cannot be exported, which is correct and means the mobile identity key can never be backed up — call this out in the UI (`CLIENT-MOBILE-06`).
4. Implement `SessionKeyCache`: a `Map<String peerId, SessionState>` where `SessionState` holds the derived `SecretKey`, both nonces, the send counter, the set of seen receive counters, and `establishedAt`.
   - Back it with a `ConcurrentHashMap`; document thread-safety explicitly.
   - **In-memory only.** Session keys are never written to disk in v1.0 — that is the forward-secrecy property being bought.
   - Enforce a TTL (default 24 h) and a max message count (default 2^20) after which the session moves to `EXPIRED` and a fresh handshake is required.
   - Provide `destroy(peerId)` that calls `SecretKey.destroy()` where supported and drops the entry.
5. Implement `fingerprint()` as a SHA-256 over the X.509-encoded public key, rendered as 12 groups of 5 decimal digits (a "safety number") — long enough to compare aloud, structured enough to compare reliably.

**Acceptance criteria:**
- Restarting the desktop client twice yields the same identity fingerprint; deleting the `.p12` yields a new one.
- A wrong passphrase fails cleanly with a typed exception, not a stack trace, and does not corrupt the store.
- `SessionKeyCache` entries disappear after TTL and after `destroy()`.

**Tests:** `KeyStoreManagerTest` (temp dir, create/reload/wrong-password), `SessionKeyCacheTest` (TTL expiry, counter cap, concurrent access under 100 threads).

---

### CORE-03 — Replace Java serialization with an explicit binary codec
**Priority:** P0 · **Depends on:** `CORE-01` · **Estimate:** 2 days
**Files:** new `core-shared/.../protocol/MessageCodec.java`, `FrameReader.java`, `FrameWriter.java`, `ProtocolException.java`

**Description:** This is a security ticket disguised as a plumbing ticket, and it is the reason it sits in Phase 1 rather than Phase 6. Today all three network endpoints call `(Message) in.readObject()` on bytes from an untrusted socket. Java native deserialization instantiates arbitrary classes present on the classpath; it is the mechanism behind a long line of RCE vulnerabilities and it is not fixable by validating the object afterwards, because the damage happens *during* deserialization. There is a secondary, purely functional reason too: `ObjectOutputStream` maintains a back-reference table, so writing the same object twice sends a 5-byte handle instead of the object, and long-lived streams grow that table until the process runs out of memory.

**Action items:**
1. Define a length-prefixed frame: `[4-byte big-endian length][1-byte version][payload]`. Reject any frame whose declared length exceeds `MAX_FRAME_BYTES` (default 1 MiB) **before allocating the buffer** — otherwise a 4-byte header triggers a 2 GiB allocation and a trivial remote OOM.
2. Implement `MessageCodec.encode(Message) -> byte[]` and `decode(byte[]) -> Message` over `DataOutputStream`/`DataInputStream` with explicit, documented field order:
   `wireCode(int) | protocolVersion(int) | messageId(UTF) | senderId(UTF) | receiverId(nullable UTF) | timestamp(long) | iv(len-prefixed bytes) | payload(len-prefixed bytes) | signature(len-prefixed bytes)`.
3. Encode strings with an explicit `int` length + UTF-8 bytes, **not** `DataOutput.writeUTF` — the latter caps at 65535 bytes and uses modified UTF-8, which is a portability trap.
4. Enforce per-field caps on decode: ids ≤ 128 bytes, payload ≤ 64 KiB, signature ≤ 512 bytes, iv exactly 12 bytes when present. Throw `ProtocolException` — a checked, non-fatal exception — on violation.
5. Implement `FrameReader`/`FrameWriter` wrapping `InputStream`/`OutputStream` with `readFully` semantics and a configurable `SO_TIMEOUT`. `FrameWriter.write` must be `synchronized` — this is what fixes defect #11 at the source.
6. Remove `implements Serializable` and `serialVersionUID` from `Message`. Removing the interface is what makes the vulnerable path unreachable rather than merely unused.
7. Delete every `ObjectInputStream`/`ObjectOutputStream` usage in `ChatServer`, `ChatClient`, and `ChatService`.
8. Write a fuzz-style test: 10,000 random byte arrays and 10,000 single-bit mutations of valid frames fed to `decode()`. The only acceptable outcomes are a correct `Message` or a `ProtocolException`. Any other exception type — `OutOfMemoryError`, `NegativeArraySizeException`, `ArrayIndexOutOfBoundsException` — is a bug.

**Acceptance criteria:**
- `grep -r "ObjectInputStream\|ObjectOutputStream\|Serializable" */src/main/java` returns nothing.
- A frame declaring `length = Integer.MAX_VALUE` is rejected in constant memory.
- The fuzz test passes with zero non-`ProtocolException` failures.
- Encoding on the JVM and decoding on Android (and vice versa) produce identical `Message` objects — covered by `INTEG-02`.

---

### CORE-04 — Fix DH parameter generation
**Priority:** P0 · **Depends on:** — · **Estimate:** 0.5 day
**Files:** `core-shared/.../crypto/DHUtils.java`

**Description:** `generateKeyPair()` calls `AlgorithmParameterGenerator.getInstance("DH").init(2048)` on every invocation. Generating fresh 2048-bit DH parameters (finding a safe prime) is a minutes-long operation. Every "Initiate Secure Chat" click would appear to hang the application. Standard practice is to use a well-known fixed group.

**Action items:**
1. Replace runtime parameter generation with the RFC 3526 MODP Group 14 (2048-bit) constants — a fixed, published, safe prime `p` and generator `g = 2`, embedded as constants. This is public data by design; using a shared group is not a weakness for finite-field DH at this size.
2. Keep `generateKeyPairFromParams(PublicKey)` for the responder, but **validate** the incoming parameters rather than trusting them: reject any `DHParameterSpec` whose `p` is not the expected group, or whose bit length is below 2048. Accepting attacker-chosen parameters permits small-subgroup and weak-group attacks.
3. Add public-key validation in `generateSharedSecret`: reject `y <= 1`, `y >= p-1`, and confirm `y^q mod p == 1` for the group's subgroup order. An unvalidated peer key allows an invalid-curve-style attack that forces a predictable shared secret.
4. Replace the bare `SHA-256(sharedSecret)` derivation with **HKDF-SHA256** (`extract` with `salt = nonceA || nonceB`, `expand` with `info = "tetherless-v1 aes-256-gcm"`, `L = 32`). The current construction has no salt and no domain separation; two sessions with the same DH secret derive the same key. Implement HKDF in `core-shared` (~40 lines over `Mac`) rather than adding a dependency, or add BouncyCastle if a vetted implementation is preferred.
5. Strip leading zero bytes handling: `KeyAgreement.generateSecret()` for DH can return a value with leading zeros stripped or not depending on provider, which changes the derived key across platforms. Left-pad the shared secret to the byte length of `p` before feeding HKDF. **This is a real, frequently-hit JVM-vs-Android interop bug and it fails intermittently (~1 in 256 handshakes), which makes it agonising to debug later.**
6. Add a `@Deprecated` note or an ADR recording that finite-field DH was chosen for JCE ubiquity, and that X25519 (`XDH`, available from Java 11 and Android 31+) is the preferred successor — tracked as `FUTURE-02`.

**Acceptance criteria:**
- `generateKeyPair()` completes in under 50 ms (currently: tens of seconds).
- A handshake against a peer offering non-Group-14 parameters is rejected with a typed exception.
- 1,000 consecutive handshakes derive matching 32-byte keys on both sides with zero mismatches (this is what catches the leading-zero bug).

**Tests:** `DHUtilsTest` extended — timing assertion, rogue-parameter rejection, rogue public-key rejection (`y = 0`, `y = 1`, `y = p-1`), 1,000-iteration derivation-consistency loop, HKDF test vectors from RFC 5869.

---

### CORE-05 — Signature envelope
**Priority:** P0 · **Depends on:** `CORE-01`, `CORE-02` · **Estimate:** 1 day
**Files:** new `core-shared/.../protocol/MessageSigner.java`, `SignatureVerifier.java`

**Description:** The `signature` field exists on `Message` and is never set or checked. Until it is, the relay can rewrite any message and impersonate any user, and the DH handshake is MITM-able by design.

**Action items:**
1. `MessageSigner.sign(Message, PrivateKey)` computes `SHA256withRSA` over `canonicalBytesForSigning()` from `CORE-01` and returns a new `Message` with the signature attached (the model is immutable).
2. `SignatureVerifier.verify(Message, PublicKey)` recomputes the canonical bytes and verifies. Return a typed result — `VALID`, `INVALID`, `NO_KEY_FOR_SENDER`, `UNSUPPORTED_ALGORITHM` — not a bare boolean, because the caller must react differently to "wrong signature" (drop and alert) versus "I don't have this peer's key yet" (prompt to verify).
3. Define the **enforcement policy** explicitly and test it: `KEY_EXCHANGE_INIT`, `KEY_EXCHANGE_REPLY`, and `TEXT_MESSAGE` **must** carry a valid signature or be dropped. Unsigned or badly-signed messages of these types are never rendered, never persisted, and never acknowledged.
4. Ensure verification is constant-time where it matters and that a verification failure produces the same observable timing and the same generic user-facing error as an unknown-sender failure.
5. Guard against signature-stripping: the codec must treat a *missing* signature on a must-sign type as a decode-time protocol error, so no code path can accidentally treat "absent" as "not required".

**Acceptance criteria:**
- Flipping one bit anywhere in a signed message causes verification to fail.
- A `TEXT_MESSAGE` with a stripped signature is dropped before reaching the UI or the database.
- A message signed by peer B but claiming `senderId = A` fails verification.

---

### CORE-06 — Identity trust model (TOFU + safety numbers)
**Priority:** P1 · **Depends on:** `CORE-05` · **Estimate:** 1.5 days
**Files:** new `core-shared/.../keys/TrustStore.java`, `TrustDecision.java`

**Description:** Signatures are worthless without a way to know which public key legitimately belongs to a peer. With a fully untrusted relay and no PKI, the practical answer is trust-on-first-use plus a human-verifiable fingerprint.

**Action items:**
1. On first `HELLO`/`KEY_EXCHANGE_INIT` from an unknown peer, persist `(peerId → publicKey, firstSeenAt)` and surface the peer's safety number in the UI marked **unverified**.
2. If a subsequent message from a known `peerId` carries a *different* key, do **not** silently accept it. Move the conversation to a `KEY_CHANGED` state, block sending, and require explicit user re-verification. Silent key rotation acceptance is exactly the hole a malicious relay walks through.
3. Provide `TrustStore.markVerified(peerId)` for when a user has compared safety numbers out of band, and render verified conversations distinctly.
4. Compute the displayed safety number over **both** parties' identity keys, sorted deterministically, so both users see the identical string. A one-sided fingerprint is easy to compare wrong.
5. Persist the trust store alongside the identity keystore, integrity-protected (an HMAC over the serialized store keyed from the same passphrase-derived key), so an attacker with file access cannot swap in their own key for a peer without detection.

**Acceptance criteria:**
- Alice and Bob independently render the same safety number string.
- Substituting a peer's key in the trust store file causes an integrity failure on load.
- A simulated MITM that swaps DH public keys is detected and blocks the session (this is the test that proves the whole E2EE claim).

**Tests:** `TrustStoreTest`, plus `MitmSimulationTest` under `INTEG-03` — a malicious relay stub that rewrites key-exchange payloads must not be able to establish a session.

---

### CORE-07 — Session state machine and replay defence
**Priority:** P1 · **Depends on:** `CORE-02`, `CORE-04` · **Estimate:** 1.5 days
**Files:** new `core-shared/.../session/Session.java`, `SessionManager.java`

**Action items:**
1. Model states `IDLE`, `HANDSHAKE_SENT`, `HANDSHAKE_RECEIVED`, `ESTABLISHED`, `EXPIRED`, `FAILED` and permit only the legal transitions. Every client currently has this logic implicit and scattered; make it one tested class shared by both clients.
2. Handle the **simultaneous-initiation race**: both peers click "Initiate Secure Chat" at once and each receives an `INIT` while in `HANDSHAKE_SENT`. Resolve deterministically — e.g. the lexicographically smaller `userId` wins and the other side abandons its own handshake. Without a rule, both sides derive different keys and every message silently fails to decrypt.
3. Prevent **IV reuse**, which is catastrophic for GCM (two messages under one key+IV leaks their XOR and enables forgery). Derive the IV as `counterPrefix(4 bytes, direction) || sendCounter(8 bytes)` rather than random bytes, and refuse to send once the counter would wrap. Keep `AESUtils.generateIV()` for tests only.
4. Maintain a sliding window (size 1024) of received counters to reject replays and accept modest reordering.
5. Reject messages whose `timestamp` is more than ±5 minutes from local time, with a clearly-worded UI note that clock skew is the likely cause.
6. Expose `SessionManager.onMessage(Message)` returning a typed outcome: `DELIVER(plaintext)`, `DROP_REPLAY`, `DROP_BAD_SIGNATURE`, `DROP_NO_SESSION`, `REKEY_REQUIRED`.

**Acceptance criteria:**
- Replaying a captured `TEXT_MESSAGE` yields `DROP_REPLAY` and does not surface in the UI or DB.
- Two simultaneous initiations converge on one session with one shared key, verified 100 times in a loop.
- No IV is ever emitted twice under the same session key (asserted over 100,000 sends).

---

## Phase 2 — Relay server stabilization (SERVER)

**Rationale:** The server is deliberately dumb — it must never hold key material and must be assumed hostile by the clients. Its job is availability and correct routing, nothing more.

---

### SERVER-01 — Connection lifecycle, heartbeat, and leak-free cleanup
**Priority:** P0 · **Depends on:** `CORE-03` · **Estimate:** 1.5 days
**Files:** `chat-server/.../ChatServer.java`, new `ClientRegistry.java`, `ClientSession.java`

**Action items:**
1. Extract the client map into a `ClientRegistry` with `register`, `unregister(clientId, expectedSession)`, `lookup`, and `broadcastPresence`. Hiding the `ConcurrentHashMap` behind a class is what makes the following fixes enforceable.
2. **Fix the reconnect race.** `clients.remove(clientId)` currently removes whatever is mapped, so a dying old handler evicts a freshly reconnected client's live stream. Use `clients.remove(clientId, thisSession)` — the two-argument form removes only if the value still matches.
3. **Reject duplicate ids.** Two clients claiming the same `userId` currently silently displace one another. Either reject the second with an `ERROR` frame, or evict the first with an explicit `DISCONNECT` — pick one, document it, and test it.
4. Set `socket.setSoTimeout(READ_TIMEOUT_MS)` (default 90 s) so a dead peer's handler thread unblocks. Today the read blocks forever and the thread and map entry leak.
5. Implement `PING`/`PONG`: the server sends `PING` every 30 s of idleness; two consecutive misses close the connection. Clients respond immediately and may also initiate. This is what makes half-open TCP connections (laptop lid closed, mobile loses signal) detectable.
6. Make cleanup unconditional and idempotent: a `finally` block that unregisters, closes the socket, and logs a redacted disconnect exactly once. Wrap `socket.close()` — currently `catch (IOException e) {}` swallows silently.
7. Send `DISCONNECT` from both clients on graceful shutdown (desktop window close, Android `onDestroy`), making the currently-dead `DISCONNECT` branch reachable.
8. Add a shutdown hook that closes the `ServerSocket`, sends `DISCONNECT` to all clients, and calls `executorService.shutdownNow()` with an awaited termination. Currently `Ctrl-C` drops every connection abruptly.

**Acceptance criteria:**
- Killing a client process (`SIGKILL`, no clean close) frees its registry entry within 90 s.
- 1,000 connect/disconnect cycles leave the registry empty and the thread count at baseline.
- A reconnect during the old connection's teardown leaves the *new* connection registered.

**Tests:** `ClientRegistryTest` (unit, including the CAS-remove race), `ChatServerLifecycleTest` (integration, real sockets on an ephemeral port).

---

### SERVER-02 — Thread-safe, non-blocking routing
**Priority:** P0 · **Depends on:** `SERVER-01`, `CORE-03` · **Estimate:** 1 day
**Files:** `ChatServer.java`, `ClientSession.java`

**Description:** `routeMessage` writes to another client's output stream directly from the sender's handler thread. Two senders targeting the same recipient will interleave writes and corrupt the stream irrecoverably. A slow recipient also blocks the sender's thread — head-of-line blocking that one bad client can weaponise.

**Action items:**
1. Give each `ClientSession` a bounded outbound `ArrayBlockingQueue<byte[]>` (capacity 256) and a single dedicated writer thread. All routing becomes an enqueue; only the writer thread ever touches the socket.
2. Define the overflow policy explicitly: on a full queue, drop the connection with an `ERROR` frame rather than blocking the sender or growing without bound. Document it.
3. Ensure `FrameWriter.write` remains `synchronized` as a belt-and-braces guard.
4. Route strictly on `receiverId`; when the recipient is offline reply to the *sender* with a typed `ERROR` (`RECIPIENT_OFFLINE`). Today the server only prints to its own console and the sender never learns the message vanished.
5. Enforce that the server **never** inspects, decrypts, logs, or stores `payload`. Add a unit test asserting the routing path calls no accessor on `payload` beyond passing the encoded frame through — ideally route the opaque frame bytes without decoding the message at all, decoding only the routing header.
6. **Optimisation with a security benefit:** decode only the header fields the server needs (`wireCode`, `senderId`, `receiverId`) and forward the original frame verbatim. This guarantees the server cannot alter ciphertext or signature even by accident, and it removes a re-encode round trip.

**Acceptance criteria:**
- 10 concurrent senders × 1,000 messages each to one recipient: all 10,000 arrive, uncorrupted, and every signature still verifies (proving the server did not re-encode).
- A recipient that stops reading is disconnected rather than stalling its senders.

---

### SERVER-03 — Resource limits and abuse resistance
**Priority:** P1 · **Depends on:** `SERVER-02` · **Estimate:** 1 day

**Action items:**
1. Replace `Executors.newCachedThreadPool()` with a bounded pool, or move to a virtual-thread executor on JDK 21. Unbounded is a one-line remote resource exhaustion.
2. Cap concurrent connections (default 500) and reject beyond it with `ERROR: SERVER_FULL` rather than accepting and thrashing.
3. Cap connections per source IP (default 10) to blunt trivial floods.
4. Rate-limit per client: token bucket, default 20 messages/second burst 50; on breach, throttle then disconnect.
5. Enforce the `MAX_FRAME_BYTES` cap from `CORE-03` at the server boundary too, and add a handshake deadline — a socket that connects but sends no valid `HELLO` within 10 s is closed. Otherwise idle half-open connections accumulate for free.
6. Make every limit configurable via `server.properties` / environment variables with documented defaults, so operators can tune without a rebuild.

**Acceptance criteria:** a load-test client opening 1,000 sockets and flooding is contained; the server stays responsive to well-behaved clients throughout.

---

### SERVER-04 — Structured, privacy-preserving logging and metrics
**Priority:** P2 · **Depends on:** `BUILD-03`, `SERVER-01` · **Estimate:** 0.5 day

**Action items:**
1. Log connection lifecycle events (`accept`, `hello`, `disconnect`, `timeout`, `rate-limited`) at `INFO`, one structured line each, with `Redact.id(...)` in place of raw user ids.
2. Never log `payload`, `iv`, or `signature`. Remove the current per-message `"Routed message from X to Y"` line entirely — at any real volume it is both a metadata archive and a disk-filling hazard.
3. Expose counters — connected clients, messages routed, frames rejected by reason, queue depth high-water mark — via a plain `/metrics` HTTP endpoint or JMX.
4. Configure Logback with a size-and-time rolling policy, capped total history.

**Acceptance criteria:** grepping a full log file from a busy session yields no user id, no payload byte, and no fixed-size-per-message growth.

---

### SERVER-05 — Real server test suite
**Priority:** P1 · **Depends on:** `SERVER-02` · **Estimate:** 1 day
**Files:** `chat-server/src/test/java/.../ChatServerTest.java` (replace the `assertEquals(true, true)` placeholder), new `TestClient.java` harness

**Action items:**
1. Make `PORT` injectable (constructor parameter, default 8080) and support port `0` for an OS-assigned ephemeral port, so tests never collide with a running server or each other.
2. Build a `TestClient` harness: connect, `HELLO`, send, await-with-timeout, assert, close.
3. Cover: two-client routing; routing to an offline recipient returns `RECIPIENT_OFFLINE`; duplicate id handling; oversized frame rejection; malformed frame rejection; idle timeout; graceful and abrupt disconnect; the 10×1,000 concurrency case from `SERVER-02`.
4. Every test must have a hard timeout so a hang fails rather than blocking CI forever.

**Acceptance criteria:** `./gradlew :chat-server:test` runs the suite in under 60 s with zero flakes across 20 consecutive runs.

---

### SERVER-06 — TLS transport
**Priority:** P1 · **Depends on:** `SERVER-02` · **Estimate:** 1 day

**Description:** E2EE protects content from the relay; TLS protects metadata and connection integrity from the network. Both are needed — neither substitutes for the other.

**Action items:**
1. Replace `ServerSocket` with `SSLServerSocket`, TLS 1.3 only, restricted to AEAD cipher suites.
2. Document certificate provisioning: Let's Encrypt for a deployed relay; a scripted self-signed cert for local development.
3. Clients pin the relay certificate or its issuer. **Do not** ship a trust-all `TrustManager`, not even behind a debug flag — those flags reliably escape into production builds.
4. Support `PROXY` protocol or `X-Forwarded-For` handling only if a terminating load balancer is introduced; otherwise terminate TLS in-process.

**Acceptance criteria:** a plaintext client cannot connect; a client with a wrong pin refuses to connect; a correctly-pinned client completes a full exchange.

---

## Phase 3 — Desktop client (CLIENT-DESKTOP)

---

### CLIENT-DESKTOP-01 — Connection layer rewrite
**Priority:** P0 · **Depends on:** `CORE-03`, `SERVER-01` · **Estimate:** 1.5 days
**Files:** `ChatClient.java`, new `ConnectionManager.java`, `Main.java`

**Action items:**
1. **Fix the initialisation race.** `Main` currently calls `client.connect(...)` and only afterwards `client.setWindow(window)` on the EDT — messages arriving in that gap hit `window == null` and are dropped. Construct the UI first, or better: decouple entirely with a listener interface (`MessageListener`) registered before `connect()` is ever called, and buffer anything that arrives pre-registration.
2. Replace the ad-hoc `new Thread(...)` reader with a `ConnectionManager` owning the socket, a reader thread, a writer thread, and the connection state (`DISCONNECTED`, `CONNECTING`, `CONNECTED`, `RECONNECTING`).
3. Implement reconnect with exponential backoff (1 s → 60 s, full jitter) and re-send `HELLO` on reconnect. Today a dropped connection is permanent and silent.
4. Surface connection state in the UI — a status bar, not a dialog. A user must never be unsure whether a message was actually sent.
5. Make host and port configurable (CLI args → `~/.tetherless/config.properties` → defaults), replacing the hardcoded `"localhost", 8080`.
6. Wire graceful shutdown: on window close, send `DISCONNECT`, flush, close, and join threads with a timeout.
7. Replace every `e.printStackTrace()` with logging plus a user-visible error where the user can act on it.

**Acceptance criteria:** killing and restarting the server causes the client to reconnect automatically within 60 s with no user action and no lost UI state.

---

### CLIENT-DESKTOP-02 — Identity bootstrap and first-run flow
**Priority:** P0 · **Depends on:** `CORE-02` · **Estimate:** 1 day

**Action items:**
1. On first launch, prompt for a display name and a passphrase; generate the RSA identity; store it via `JceKeyStoreManager`.
2. On subsequent launches, prompt only for the passphrase; fail closed after 5 attempts with an increasing delay.
3. Show the user their own safety number in an "My identity" panel, copyable.
4. Replace the `args[0] ? "user1"` default id with the identity-derived id. Silent defaults to `user1` in a multi-user system are a footgun.

**Acceptance criteria:** two clients on one machine with separate profile directories have distinct identities and can both run simultaneously.

---

### CLIENT-DESKTOP-03 — Key exchange and encrypted messaging
**Priority:** P0 · **Depends on:** `CORE-04`, `CORE-05`, `CORE-07`, `CLIENT-DESKTOP-01` · **Estimate:** 2 days

**Action items:**
1. Add "Start secure chat" to the UI, taking a peer id. Show a spinner and a state label — handshakes involve a network round trip and must not look like a freeze.
2. Initiator: create a DH keypair from the fixed group, build `KEY_EXCHANGE_INIT` with a fresh 32-byte nonce, sign it, send, transition to `HANDSHAKE_SENT`.
3. Responder: verify the signature **before** touching the DH material (`CORE-05`), validate the peer's DH public key (`CORE-04` step 3), generate the matching keypair, reply with `KEY_EXCHANGE_REPLY` + nonce + signature, derive via HKDF, transition to `ESTABLISHED`.
4. Initiator on reply: verify signature, validate key, derive, transition to `ESTABLISHED`.
5. Handle every failure path explicitly and visibly: signature invalid, unknown peer key, peer offline, peer rejected, handshake timeout (30 s), key changed since last session. Each gets a distinct, plain-language message. Silent handshake failure is the worst possible outcome because the user will keep typing.
6. On send: fetch the session key, derive the counter-based IV, encrypt with AES-256-GCM, build the message, sign, enqueue. **Delete the `// In real app, we would encrypt` comment and the plaintext `text.getBytes()` path** at `ChatClient.java:60`.
7. On receive: verify signature → check replay window → decrypt → render → persist. A GCM tag mismatch (`AEADBadTagException`) must be handled as a security event: drop the message, warn the user, and do not retry.
8. Block the send button entirely while a conversation is not `ESTABLISHED`. Never fall back to plaintext — a fallback path is a downgrade attack waiting to happen.

**Acceptance criteria:**
- Wireshark/`tcpdump` on the relay port shows no plaintext of any sent message.
- A relay modified to flip one ciphertext byte causes the receiving client to drop the message with a visible warning, never to display garbage.

---

### CLIENT-DESKTOP-04 — Persistence layer
**Priority:** P1 · **Depends on:** `CLIENT-DESKTOP-03` · **Estimate:** 1.5 days
**Files:** `DatabaseHelper.java` (substantial rewrite), new `MessageRepository.java`, `ConversationDao.java`

**Description:** `DatabaseHelper` currently creates a table and offers no way to write to or read from it, and opens a fresh `Connection` per call to a hardcoded relative path `chat.db` — meaning the database location depends on the working directory.

**Action items:**
1. Move the DB to the profile directory (`~/.tetherless/chat.db`), created with restrictive permissions.
2. Introduce a schema-versioned migration mechanism: a `schema_version` table plus ordered migration steps. Retrofitting migrations after users have data is painful; the first migration costs an hour now.
3. Extend the schema:
   ```sql
   CREATE TABLE messages (
     id             INTEGER PRIMARY KEY AUTOINCREMENT,
     message_id     TEXT    NOT NULL UNIQUE,   -- dedup + ACK correlation
     conversation_id TEXT   NOT NULL,
     sender         TEXT    NOT NULL,
     receiver       TEXT    NOT NULL,
     content        TEXT    NOT NULL,          -- decrypted plaintext, at rest
     sent_at        INTEGER NOT NULL,          -- sender-asserted epoch ms
     received_at    INTEGER NOT NULL,          -- local epoch ms
     direction      INTEGER NOT NULL,          -- 0 out, 1 in
     delivery_state INTEGER NOT NULL           -- PENDING/SENT/DELIVERED/FAILED
   );
   CREATE INDEX idx_messages_conversation ON messages(conversation_id, sent_at);
   ```
   `UNIQUE(message_id)` gives idempotent inserts for free, so a redelivery cannot duplicate a row.
4. Use a single long-lived connection (SQLite handles one writer) with `PRAGMA journal_mode=WAL`, `foreign_keys=ON`, `busy_timeout=5000`.
5. Use `PreparedStatement` exclusively — no string concatenation anywhere near SQL.
6. All DB work happens off the EDT on a single-threaded executor; results are marshalled back with `SwingUtilities.invokeLater`.
7. Implement `MessageRepository`: `insert`, `findByConversation(id, limit, beforeTimestamp)` for paging, `updateDeliveryState`, `deleteConversation`.
8. On startup, load the most recent 50 messages per conversation and page older ones on scroll — do not load an unbounded history into a `JTextArea`.

**Acceptance criteria:** messages survive a restart; inserting the same `message_id` twice leaves one row; the UI never blocks on a DB call (verified by a 100k-row load test).

---

### CLIENT-DESKTOP-05 — Local database encryption
**Priority:** P2 · **Depends on:** `CLIENT-DESKTOP-04`, `CORE-02` · **Estimate:** 1 day

**Description:** Storing decrypted plaintext on disk is a deliberate, documented tradeoff in `CLIENT-DESKTOP-04`. This ticket removes it.

**Action items:**
1. Either adopt SQLCipher (`sqlite-jdbc` does not support it; requires a different driver) **or** encrypt the `content` column with a DB key derived from the user's passphrase via HKDF from the keystore.
2. Prefer per-column encryption for v1.0 — it keeps the existing driver, keeps indices on non-sensitive columns working, and is far less invasive.
3. Document what remains visible on disk with column-level encryption: participants, timing, and message counts. Do not overstate the protection.

---

### CLIENT-DESKTOP-06 — Multi-conversation UI
**Priority:** P2 · **Depends on:** `CLIENT-DESKTOP-03`, `CLIENT-DESKTOP-04` · **Estimate:** 2 days
**Files:** `ChatWindow.java` (rewrite), new `ConversationListPanel.java`, `MessageBubbleRenderer.java`

**Action items:**
1. Replace the single hardcoded `receiverId` with a conversation list (`JList` backed by a model) beside the transcript pane.
2. Replace the append-only `JTextArea` with a `JList`/`JPanel` of bubbles that can render sender, timestamp, delivery state, and a decryption-failure marker. `JTextArea` cannot express any of that.
3. Show per-conversation trust state: unverified (with safety number), verified, or **key changed** (prominent, blocking).
4. Add unread counts, timestamp separators by day, and auto-scroll that yields when the user has scrolled up.
5. Keep every UI mutation on the EDT; keep every crypto and I/O operation off it.
6. Handle window close → `DISCONNECT` → dispose.

---

### CLIENT-DESKTOP-07 — Desktop test coverage
**Priority:** P1 · **Depends on:** `CLIENT-DESKTOP-04` · **Estimate:** 1 day

**Action items:**
1. Unit-test `MessageRepository` against an in-memory SQLite DB.
2. Unit-test `ConnectionManager` against a stub server: backoff schedule, reconnect, state transitions.
3. Unit-test the send/receive pipeline with a fake transport — assert that what leaves is ciphertext and that a tampered frame is rejected.
4. Keep Swing out of the unit tests; extract logic into testable non-UI classes rather than reaching for a UI-automation framework.

---

## Phase 4 — Mobile client (CLIENT-MOBILE)

---

### CLIENT-MOBILE-01 — Room data layer
**Priority:** P0 · **Depends on:** `BUILD-01` · **Estimate:** 1 day
**Files:** `AppDatabase.java`, `MessageDao.java`, `MessageEntity.java`, new `DatabaseProvider.java`

**Action items:**
1. Add a singleton builder — `AppDatabase` is abstract and currently has no `Room.databaseBuilder` call anywhere, so no instance can exist. Use a double-checked-locked singleton with `applicationContext`.
2. Mirror the desktop schema on `MessageEntity`: add `messageId` (with `@Index(unique = true)`), `conversationId`, `sentAt`, `receivedAt`, `direction`, `deliveryState`; change `content` from `byte[]` to `String` (plaintext at rest, per the same tradeoff as desktop).
3. Change `@PrimaryKey` `int id` to `long`.
4. Change `getAllMessages()` from a blocking `List` to `LiveData<List<MessageEntity>>` — the current signature will throw if called on the main thread and blocks if not.
5. Add `@Insert(onConflict = OnConflictStrategy.IGNORE)` for idempotent redelivery, `getConversation(id)` paged with the Paging 3 library or a `LIMIT/OFFSET` query, and `updateDeliveryState`.
6. Add an explicit `Migration` from version 1 to 2 rather than `fallbackToDestructiveMigration()`. Destructive fallback silently deletes user message history on upgrade.
7. Enable Room's schema export (`room.schemaLocation`) and commit the JSON schemas so migrations are reviewable.

**Acceptance criteria:** `MessageDaoTest` (instrumented or Robolectric) covers insert/query/dedup/migration; the app reads history without touching the main thread (verified with `StrictMode`).

---

### CLIENT-MOBILE-02 — UI, ViewModel, and RecyclerView
**Priority:** P0 · **Depends on:** `CLIENT-MOBILE-01` · **Estimate:** 2 days
**Files:** `MainActivity.java`, new `ChatViewModel.java`, `MessageAdapter.java`, `res/layout/activity_main.xml`, `res/layout/item_message_in.xml`, `item_message_out.xml`, `res/values/{colors,themes,dimens}.xml`

**Action items:**
1. Create `activity_main.xml`: a `RecyclerView` (weight 1), an input `EditText`, and a send `Button`, plus a connection-status bar. Then uncomment and fix `setContentView` in `MainActivity.java:8`.
2. Build `MessageAdapter` on `ListAdapter` + `DiffUtil` (not `notifyDataSetChanged`), with two view types for inbound and outbound bubbles.
3. Build `ChatViewModel` exposing `LiveData<List<MessageEntity>>` from the DAO and a `LiveData<ConnectionState>`; survive rotation without re-fetching or re-connecting.
4. Wire the send button through the ViewModel to the service; disable it whenever the session is not `ESTABLISHED`, matching the desktop rule.
5. Handle keyboard insets, scroll-to-bottom on new message (unless scrolled up), and empty state.
6. Add a conversation-picker screen or, for v1.0, a peer-id entry field plus a "Start secure chat" action mirroring desktop.
7. Add the safety-number screen for trust verification (`CORE-06`), including the blocking `KEY_CHANGED` banner.

**Acceptance criteria:** the app launches to a functional chat screen; rotation preserves scroll position and input text; messages appear without a manual refresh.

---

### CLIENT-MOBILE-03 — ChatService as a foreground service
**Priority:** P0 · **Depends on:** `CORE-03`, `CLIENT-MOBILE-01` · **Estimate:** 2 days
**Files:** `ChatService.java` (rewrite), `AndroidManifest.xml`

**Description:** The current service is a background `Service` spawning a raw thread with an infinite loop. On Android 8+ it is killed shortly after the app leaves the foreground; on Android 12+ it cannot even be started from the background. It also hardcodes the user id and drops every received message.

**Action items:**
1. Convert to a foreground service: `startForeground()` with a low-importance notification channel and an ongoing "Connected" notification, called within 5 s of start or the system throws `ForegroundServiceDidNotStartInTimeException`.
2. Declare in the manifest: `<service android:name=".ChatService" android:foregroundServiceType="dataSync" android:exported="false"/>`, plus `<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>` and, for API 34+, `FOREGROUND_SERVICE_DATA_SYNC`.
3. Request `POST_NOTIFICATIONS` at runtime on API 33+, and handle refusal gracefully.
4. Replace the hardcoded `"mobileUser"` id (`ChatService.java:28`) with the identity from `AndroidKeyStoreManager`.
5. Replace the hardcoded `"10.0.2.2"` host with configuration, keeping the emulator address as a debug-build default only.
6. Use the `CORE-03` codec; delete the `ObjectInputStream`/`ObjectOutputStream` usage.
7. Replace the raw thread with an `ExecutorService` the service owns and shuts down in `onDestroy`; the current thread outlives the service and leaks.
8. Fill in the empty `// Handle incoming message` branch (`ChatService.java:37`): verify → replay-check → decrypt → insert into Room. Persisting to Room and letting `LiveData` drive the UI is the cleanest path and works whether or not an Activity is alive.
9. Expose a `LocalBinder` (currently `onBind` returns `null`) so the Activity can call `sendMessage` and observe connection state directly, instead of round-tripping through `startService` intents.
10. Handle `onTaskRemoved` and return `START_STICKY` deliberately with a documented restart policy.

**Acceptance criteria:** the connection survives backgrounding the app for 10 minutes; incoming messages persist and appear when the app is reopened; force-stopping and reopening reconnects cleanly.

---

### CLIENT-MOBILE-04 — Network resilience and lifecycle
**Priority:** P1 · **Depends on:** `CLIENT-MOBILE-03` · **Estimate:** 1.5 days

**Action items:**
1. Register a `ConnectivityManager.NetworkCallback` to trigger reconnect on `onAvailable` and pause on `onLost`, rather than blind retrying while offline and burning battery.
2. Implement exponential backoff with jitter, matching the desktop schedule.
3. Handle Doze and App Standby: accept that sockets are suspended in Doze, and document that reliable delivery to a backgrounded device requires push (`FUTURE-03`). Do not pretend a raw socket solves this.
4. Acquire a `WifiLock`/partial `WakeLock` only while actively sending, and release in a `finally`. An unreleased wake lock is the classic battery-drain bug.
5. Queue outbound messages in Room with `deliveryState = PENDING` and flush on reconnect, so a send while offline is not lost.
6. Handle `onTrimMemory` and process death: on restart, rehydrate from Room, and note that in-memory session keys are gone — a fresh handshake is required. Surface that to the user rather than failing silently.

**Acceptance criteria:** toggling airplane mode for 2 minutes results in automatic reconnect and delivery of queued messages, with no duplicates.

---

### CLIENT-MOBILE-05 — Mobile crypto integration
**Priority:** P0 · **Depends on:** `CORE-04`, `CORE-05`, `CORE-07`, `CLIENT-MOBILE-03` · **Estimate:** 1.5 days

**Action items:**
1. Implement the identical handshake state machine by **using `SessionManager` from `core-shared`** — do not reimplement it. Two divergent implementations of a handshake is how interop bugs are born.
2. Verify DH/HKDF availability on the target API levels. Android supports `DH` via Conscrypt, but provider differences (notably the shared-secret leading-zero behaviour from `CORE-04` step 5) are exactly where JVM↔Android interop breaks. Add an instrumented test that derives a key against a vector produced on the desktop JVM.
3. Run all crypto off the main thread; a 2048-bit DH operation on a low-end device is comfortably long enough to trigger an ANR.
4. Store the identity key in `AndroidKeyStore`; keep session keys in memory only, cleared on `onDestroy`.
5. Ensure `AEADBadTagException` is caught specifically and surfaced as a security warning, never as a crash and never as garbled text.

**Acceptance criteria:** a desktop client and a mobile client complete a handshake and exchange messages in both directions — this is the milestone that proves the product works.

---

### CLIENT-MOBILE-06 — Mobile identity, backup, and permissions UX
**Priority:** P2 · **Depends on:** `CLIENT-MOBILE-05` · **Estimate:** 1 day

**Action items:**
1. First-run screen: display name, identity generation, safety-number display.
2. Explain the `AndroidKeyStore` consequence plainly: the identity key cannot be exported or backed up, so uninstalling the app permanently loses the identity and all peers will see a key change.
3. Set `android:allowBackup="false"` and add `android:dataExtractionRules` — the manifest currently has `allowBackup="true"`, which would ship the message database to cloud backup.
4. Add a `networkSecurityConfig` that disallows cleartext traffic once TLS lands (`SERVER-06`).

---

## Phase 5 — Cross-platform integration (INTEG)

**Rationale:** Every defect that survives Phases 1–4 will be a desktop-vs-Android disagreement. These tickets exist to catch them mechanically instead of by hand.

---

### INTEG-01 — End-to-end harness
**Priority:** P1 · **Depends on:** `SERVER-05`, `CLIENT-DESKTOP-07` · **Estimate:** 1.5 days

**Action items:**
1. A Gradle task that boots the relay on an ephemeral port, launches two headless client cores (no UI), and drives a full script: HELLO → handshake → 100 messages each way → verify → disconnect.
2. Assert on the plaintext at both ends and on the ciphertext observed at the relay — specifically that no plaintext substring appears in any routed frame.
3. Run it in CI as a separate job with a hard timeout.

---

### INTEG-02 — Codec and crypto golden vectors
**Priority:** P1 · **Depends on:** `CORE-03`, `CORE-04` · **Estimate:** 1 day

**Action items:**
1. Generate a fixture file of encoded `Message` frames covering every `MessageType`, every boundary (empty payload, max payload, null `receiverId`, non-ASCII ids and content, emoji, RTL text).
2. Commit the fixtures. Both the JVM test suite and the Android instrumented suite decode them and assert field-exact equality.
3. Do the same for HKDF and the DH derivation: fixed inputs, committed expected 32-byte output. This is what catches the leading-zero-stripping bug and any future provider change.
4. A changed fixture output is a **wire-format break** and must force a `protocolVersion` bump — enforce this by making the fixture test fail loudly with that message.

---

### INTEG-03 — Adversarial relay test
**Priority:** P1 · **Depends on:** `CORE-05`, `CORE-06` · **Estimate:** 1 day

**Description:** The entire security claim of this project is "the relay cannot read or forge your messages." That claim should be tested, not assumed.

**Action items:**
1. Build a `MaliciousRelay` test double that can, per scenario: substitute DH public keys (MITM), flip ciphertext bits, replay captured frames, drop `DELIVERY_ACK`s, reorder messages, and forge messages from a third identity.
2. Assert the correct client-side outcome for each: MITM → handshake rejected; bit-flip → message dropped with warning; replay → dropped silently; reorder → correctly windowed; forgery → signature failure.
3. Treat any scenario where a client renders attacker-influenced content as a release blocker.

---

### INTEG-04 — Manual QA script and interop matrix
**Priority:** P2 · **Depends on:** `CLIENT-MOBILE-05` · **Estimate:** 0.5 day

**Action items:** a checked-in `docs/qa_script.md` covering the desktop↔desktop, desktop↔mobile, and mobile↔mobile matrix; cold start, warm start, reconnect, key change, offline send, and clock-skew cases; run before each release.

---

## Phase 6 — Hardening and release (REL)

---

### REL-01 — Security review pass
**Priority:** P1 · **Depends on:** Phase 5 · **Estimate:** 2 days

**Action items:**
1. Walk the threat model in §2.1 and record, per threat, the specific control and the specific test that proves it.
2. Verify the negatives: no plaintext on the wire, no key material in logs, no secrets in heap dumps after `destroy()`, no `Serializable` on the wire path, no trust-all `TrustManager` in any build variant.
3. Run the `find-sec-bugs` report to zero unresolved `HIGH` findings, with any suppression individually justified in `exclude.xml`.
4. Write `docs/security.md`: threat model, guarantees, **and an explicit list of non-guarantees**. Overclaiming is the most common failure mode for projects like this — the honest limits list is the most valuable part of the document.
5. Document the responsible-disclosure contact.

---

### REL-02 — Packaging and distribution
**Priority:** P2 · **Depends on:** `REL-01` · **Estimate:** 1.5 days

**Action items:**
1. Desktop: `jlink`/`jpackage` producing a self-contained installer per platform, so users need no JDK.
2. Server: a fat JAR plus a Dockerfile plus a `docker-compose.yml` including TLS cert mounting; a systemd unit for bare-metal.
3. Mobile: a signed release APK/AAB with R8 enabled and **keep rules for the `core-shared` model classes** — obfuscation renaming a class used reflectively by Room, or shrinking a crypto provider entry, is a classic release-only breakage.
4. Verify the release build end-to-end, not just the debug build. R8-only bugs are invisible in CI unless CI builds release.
5. Reproducible version stamping from git describe, surfaced in an About dialog for bug reports.

---

### REL-03 — Documentation
**Priority:** P2 · **Depends on:** `REL-02` · **Estimate:** 1 day

**Action items:** rewrite `README.md` beyond its current two lines — architecture diagram, quick start for all three components, `docs/protocol.md` with the full wire format and state machine, `docs/security.md` from `REL-01`, `CONTRIBUTING.md` with the logging and crypto rules, and an ADR log recording why DH-2048 over X25519, why a relay over true P2P, and why Java serialization was removed.

---

## Appendix A — Execution order and critical path

```
BUILD-01 ──┬── CORE-01 ──┬── CORE-03 ──┬── SERVER-01 ── SERVER-02 ──┬── SERVER-03/04/05/06
           │             │             │                            │
           │             ├── CORE-02 ──┼── CORE-05 ── CORE-06       │
           │             │             │                            │
           ├── CORE-04 ──┴─────────────┴── CORE-07 ────────────┐    │
           │                                                   │    │
           ├── CLIENT-DESKTOP-01/02 ── CLIENT-DESKTOP-03 ──────┤    │
           │        └── CLIENT-DESKTOP-04/05/06/07             │    │
           │                                                   │    │
           └── CLIENT-MOBILE-01/02 ── CLIENT-MOBILE-03 ────────┤    │
                    └── CLIENT-MOBILE-04/05/06                 │    │
                                                               ▼    ▼
                                              INTEG-01/02/03/04 ── REL-01/02/03
```

**Critical path:** `BUILD-01 → CORE-01 → CORE-03 → SERVER-01 → SERVER-02 → CLIENT-DESKTOP-03 → CLIENT-MOBILE-05 → INTEG-01 → REL-01`.

**Parallelisable once `CORE-03` lands:** the server track, the desktop track, and the mobile UI track are independent and can run concurrently.

### Suggested milestones

| Milestone | Contents | Exit criterion |
|---|---|---|
| **M0 — It builds** | `BUILD-01…04` | `./gradlew clean build` green on CI, all four modules |
| **M1 — Protocol frozen** | `CORE-01…07` | Golden vectors committed; `protocolVersion = 1` declared stable |
| **M2 — Relay solid** | `SERVER-01…06` | 10k-message concurrency test green; zero leaks over 1k cycles |
| **M3 — Desktop E2EE** | `CLIENT-DESKTOP-01…04` | Two desktop clients exchange encrypted, persisted messages |
| **M4 — Mobile E2EE** | `CLIENT-MOBILE-01…05` | Desktop↔mobile encrypted exchange works both directions |
| **M5 — Proven** | `INTEG-01…04` | Adversarial relay suite fully green |
| **M6 — Shipped** | `REL-01…03` | Signed artefacts for all three targets; `security.md` published |

---

## Appendix B — Risk register

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | JVM↔Android crypto provider divergence (leading zeros, DH availability, provider defaults) silently breaks interop | **High** | **High** | `CORE-04` step 5 + `INTEG-02` golden vectors, tested on both platforms in CI |
| R2 | Wire-format change after clients ship | Medium | High | `protocolVersion` from day one (`CORE-01`); fixture test forces a version bump |
| R3 | Java deserialization RCE reachable before `CORE-03` lands | **High** | **Critical** | `CORE-03` is P0 and gated on the critical path; never deploy a public relay before it |
| R4 | Unauthenticated DH ships, making the E2EE claim false | Medium | **Critical** | `CORE-05`/`CORE-06` are P0/P1; `INTEG-03` MITM test is a release blocker |
| R5 | IV reuse under a session key breaks GCM catastrophically | Medium | **Critical** | Counter-based IVs + hard send cap (`CORE-07` step 3), asserted over 100k sends |
| R6 | Android background execution limits make the socket unreliable | **High** | Medium | Foreground service (`CLIENT-MOBILE-03`); document the limitation honestly; push as `FUTURE-03` |
| R7 | Relay is a single point of failure and a metadata honeypot, contradicting the "decentralized" README claim | Medium | Medium | Be accurate in docs today; `FUTURE-04` for multi-relay/P2P |
| R8 | Scope creep into group chat / multi-device before 1:1 is solid | Medium | Medium | Both are explicitly out of scope in §2.1 |
| R9 | R8/ProGuard breaks reflection-dependent Room or crypto code in release builds only | Medium | High | `REL-02` step 4 — CI must build and test the release variant |
| R10 | Plaintext at rest on a lost device | Medium | Medium | `CLIENT-DESKTOP-05`; documented as a known v1.0 limitation until then |

---

## Appendix C — Deferred work (FUTURE)

Recorded so it is a decision rather than an oversight.

- **FUTURE-01 — Double Ratchet.** Per-message forward secrecy and post-compromise security. The single largest security upgrade available after v1.0.
- **FUTURE-02 — X25519 / Ed25519.** Faster, smaller, fewer validation footguns than finite-field DH and RSA. Requires Android API 31+ for `XDH` or a bundled implementation.
- **FUTURE-03 — Push notifications.** The only reliable way to deliver to a backgrounded mobile device; requires careful design to avoid leaking content or metadata to the push provider.
- **FUTURE-04 — True decentralization.** The README says "decentralized"; the architecture is a central relay. Either implement multi-relay federation / DHT peer discovery, or amend the README. Do not leave the claim unbacked.
- **FUTURE-05 — Group messaging.** Sender keys or MLS. Substantial protocol work; not a v1.0 stretch goal.
- **FUTURE-06 — Multi-device.** Requires identity-key sharing or per-device keys with a linking flow; interacts heavily with `FUTURE-01`.
- **FUTURE-07 — Attachments.** Chunked, separately-keyed encrypted blobs; needs a storage story the relay does not currently have.
- **FUTURE-08 — Disappearing messages, message deletion, and edit.** UX-visible, protocol-light, high user value.
- **FUTURE-09 — Padding and traffic shaping** to blunt size-based traffic analysis at the relay.
