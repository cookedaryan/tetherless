# Software Requirements Document — Tetherless

**Status:** draft for review
**Audience:** engineers, reviewers, and anyone testing whether the product does what is claimed
**Upstream:** [prd.md](prd.md) · **Companions:** [protocol.md](protocol.md), [security.md](security.md)

Requirements are numbered and written to be testable. Where an existing test already pins one, it is
named. Where nothing pins one, the gap is stated rather than hidden.

---

## 1. Definitions

| Term | Meaning |
|---|---|
| **Relay** | The `chat-server` process. Routes ciphertext; assumed hostile. |
| **Peer id** | First 16 bytes of SHA-256 over an identity public key. A user's address. |
| **Session** | An established shared secret between two peers, with its own counter spaces. |
| **Engine** | `desktop-engine`, the headless Java process the Electron client drives. |
| **Frame** | One newline-delimited JSON object on the engine channel. |
| **Safety number** | Human-readable rendering of an identity key fingerprint, for out-of-band comparison. |

---

## 2. System context

```
Electron renderer ──preload── Electron main ──pipe(JSON)── desktop-engine ──TLS 1.3── relay
     (no keys)                  (no keys)                   (keys, crypto)          (ciphertext)
```

Three trust boundaries, in decreasing order of importance:

1. **Engine ↔ relay.** The relay is hostile. Everything crossing it is ciphertext, and everything
   arriving from it is untrusted until verified.
2. **Renderer ↔ engine.** The renderer displays attacker-controlled text in a browser engine. It
   holds no key material and no plaintext at rest.
3. **Process ↔ disk.** The identity keystore and the message database are both encrypted at rest
   under keys derived from the passphrase.

---

## 3. Functional requirements

### 3.1 Identity

| # | Requirement | Verified by |
|---|---|---|
| FR-1 | The client shall generate an RSA identity key pair locally on first run | `EngineTest` |
| FR-2 | The identity shall be stored in a PKCS#12 keystore encrypted under the user's passphrase | `EngineTest` |
| FR-3 | The peer id shall be derived deterministically from the identity public key | `PeerId` tests, `EngineTest` |
| FR-4 | The passphrase shall never be written to disk, logged, or passed as a process argument | ADR-7 |
| FR-5 | An incorrect passphrase shall be refused without disclosing which check failed | `EngineTest` |
| FR-6 | The passphrase shall be zeroed in memory once the derived keys exist | Code review — **no automated test** |
| FR-7 | Changing the display name shall not change the peer id | `EngineTest` |

### 3.2 Session establishment

| # | Requirement | Verified by |
|---|---|---|
| FR-8 | Key agreement shall use finite-field Diffie–Hellman, RFC 3526 MODP Group 14 | `DHUtils` tests |
| FR-9 | Key-exchange frames shall be signed with the sender's identity key | `SecureChat` tests |
| FR-10 | A key-exchange frame whose signature does not verify shall be discarded | `SecureChat` tests |
| FR-11 | A frame whose claimed sender id is not the hash of its key shall be discarded | Protocol tests |
| FR-12 | A changed identity key for a known peer shall be reported and shall block sending | `security.md` §3 |

### 3.3 Messaging

| # | Requirement | Verified by |
|---|---|---|
| FR-13 | Message bodies shall be encrypted with AES-256-GCM under the session key | `SecureChat` tests |
| FR-14 | Each message shall use a unique nonce for its key, with a direction bit from the two peer ids | `IvReuseTest` |
| FR-15 | A message whose counter falls at or below the replay window floor shall be rejected | `Session` tests |
| FR-16 | A repeated counter within the window shall be rejected | `Session` tests |
| FR-17 | Exhausting the per-key send counter shall force renewal rather than reuse a nonce | `Session` tests |
| FR-18 | A rekey shall reset both counter spaces without opening a replay gap | `Session` tests |
| FR-19 | Sending shall be refused when no session exists; there shall be no plaintext fallback | `SecureChat` |
| FR-20 | The transport shall report whether a message was accepted, and a refusal shall persist as FAILED | `ChatClientTest` |
| FR-21 | Delivery acknowledgements shall persist so state survives restart | `ChatClientTest` |
| FR-22 | A delivery state shall never regress (READ shall not fall back to DELIVERED) | `TranscriptStatusTest`, `MessageRepositoryTest` |

### 3.4 Persistence

| # | Requirement | Verified by |
|---|---|---|
| FR-23 | Message bodies shall be encrypted at rest with AES-256-GCM | `MessageRepositoryTest` |
| FR-24 | The database key shall be derived with PBKDF2-HMAC-SHA256 at no fewer than 210,000 iterations | `DatabaseKeysTest` |
| FR-25 | A database encrypted under the legacy derivation shall migrate, or be left untouched on failure | `EngineTest` — migration path **not covered end to end** |
| FR-26 | Verification state shall be stored separately from peer-asserted display names | `PeerDirectoryTest` |

**FR-26 is not tidiness.** Display names are claims a peer makes about itself; verification is the
local user's own judgement. Storing them together would let a compromised names file forge a shield.

### 3.5 Transport

| # | Requirement | Verified by |
|---|---|---|
| FR-27 | Connections shall use TLS 1.3 with a pinned certificate | `TlsHostnameVerificationTest` |
| FR-28 | A release build shall refuse the development certificate | `BuildInfo`, `TlsSupport` |
| FR-29 | The client shall not report CONNECTED until the relay acknowledges registration | `ConnectionManagerTest` |
| FR-30 | Reconnection shall back off, and a connection that fails immediately shall not reset the backoff | `ConnectionManagerTest` |
| FR-31 | Each connection attempt shall own its socket and threads, so an abandoned attempt cannot disturb its successor | `ConnectionManagerTest` |

### 3.6 Engine channel

| # | Requirement | Verified by |
|---|---|---|
| FR-32 | The engine shall communicate over stdin/stdout only, and shall open no listening socket | Code review, `EngineMain` |
| FR-33 | Engine stdout shall carry protocol frames and nothing else; all logging shall go to stderr | Verified by observation — **no automated test** |
| FR-34 | Every command shall be answered exactly once, correlated by id | `EngineTest` |
| FR-35 | An unknown command shall be refused by name, regardless of lock state | `EngineTest` |
| FR-36 | A malformed frame shall be refused without terminating the loop | `EngineMain` — **no automated test** |
| FR-37 | Engine termination shall surface to the UI as a reportable state, not a silent stall | `EngineClient` |

### 3.7 Relay

| # | Requirement | Verified by |
|---|---|---|
| FR-38 | The relay shall route by receiver id and shall not decrypt message content | `integTest` |
| FR-39 | The relay shall refuse a registration that cannot prove ownership of its id | Server tests |
| FR-40 | The relay shall not allow a client to send frames claiming another sender id | Server tests |
| FR-41 | The relay shall apply per-connection rate limiting and per-IP connection caps | `TokenBucket` tests |
| FR-42 | A dying session shall not unregister a newly reconnected one for the same id | `ClientRegistry` tests |

---

## 4. Non-functional requirements

### 4.1 Security

| # | Requirement |
|---|---|
| NFR-S1 | No key material, IV, or message body shall be logged. Peer ids shall be redacted in logs |
| NFR-S2 | All randomness used for keys, nonces and salts shall come from a cryptographically secure source |
| NFR-S3 | No Java serialization shall appear on the wire |
| NFR-S4 | The renderer shall run with `contextIsolation`, `sandbox`, and `nodeIntegration: false` |
| NFR-S5 | The renderer shall enforce a CSP of `default-src 'none'` with no remote origins |
| NFR-S6 | Message text shall never be rendered as HTML |
| NFR-S7 | The renderer shall hold no key material and shall not perform decryption |
| NFR-S8 | No credential or keystore password shall appear as a literal in source |
| NFR-S9 | Every failure of a security check shall fail closed |

NFR-S4 through S7 exist because of one fact: **in Electron, a cross-site-scripting bug in a chat
transcript is remote code execution.** Swing had no scripting engine; this architecture does.

### 4.2 Performance

| # | Requirement |
|---|---|
| NFR-P1 | Passphrase unlock shall complete within 2 seconds on target hardware (PBKDF2 is intentionally costly) |
| NFR-P2 | A sent message shall appear in the transcript within 100 ms of being accepted |
| NFR-P3 | Opening a conversation of 200 messages shall render within 500 ms |
| NFR-P4 | Cryptographic and I/O work shall not run on the UI thread |

### 4.3 Reliability

| # | Requirement |
|---|---|
| NFR-R1 | Loss of the relay connection shall not lose unsent messages |
| NFR-R2 | The client shall recover from relay restart without user action |
| NFR-R3 | Engine failure shall be reported and the process restartable without losing stored history |
| NFR-R4 | A malformed frame from a peer shall not drop the connection |

**NFR-R4 is a real, previously-found defect class**, not a hypothetical: a null payload on an
acknowledgement path once threw inside the reader loop, and the loop's handler closed the socket.

### 4.4 Portability and maintainability

| # | Requirement |
|---|---|
| NFR-M1 | Java modules shall compile at Java 8 source level |
| NFR-M2 | The protocol shall have exactly one implementation, shared by every client |
| NFR-M3 | Frozen conformance vectors shall run on every client |
| NFR-M4 | Checkstyle and SpotBugs with find-sec-bugs shall pass at HIGH confidence |
| NFR-M5 | Wire-format changes shall be versioned, never edited in place |
| NFR-M6 | The client shall run on Windows without a separately installed Java runtime |

---

## 5. Constraints

- `MessageType` is serialised by ordinal; constants may only ever be **appended**.
- The wire format is frozen at version 2 and shared with the Android client.
- The Electron renderer may load no remote resource of any kind.
- The relay stores nothing durable about users.

---

## 6. Stated non-requirements

These are **not** defects. They are documented limits, and a review that reports them as findings
has misread this section. See `security.md` §5.

| # | Not required |
|---|---|
| NR-1 | Per-message forward secrecy. One handshake derives one key for the life of a session |
| NR-2 | Post-compromise security. A leaked session key does not heal |
| NR-3 | Metadata privacy. The relay sees who talks to whom, when, and how often |
| NR-4 | Deniability. Messages are signed, so authorship is provable to a third party |
| NR-5 | Multi-device use of one identity |
| NR-6 | Operator-assisted recovery of a lost passphrase |
| NR-7 | Group messaging |
| NR-8 | Protection of a compromised endpoint |

---

## 7. Known verification gaps

Stated so they are chosen rather than assumed:

- **FR-6** (passphrase zeroing) rests on code review.
- **FR-25** (legacy database migration) has no end-to-end coverage.
- **FR-33** (stdout carries only frames) is the property the whole engine channel rests on and is
  currently verified only by observation. It deserves a test.
- **FR-36** (malformed frame does not kill the loop) is untested.
- No automated test covers the Electron renderer's CSP or sandbox settings; NFR-S4–S7 are enforced
  by code review alone.
