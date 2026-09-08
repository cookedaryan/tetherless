# The Tetherless wire protocol

Everything that crosses a socket, in the order it crosses it. This describes what the code does
today, not what it might do; where a detail exists for a reason that is not obvious, the reason is
given.

The authority on the byte level is `core-shared/src/main/java/com/e2eechat/core/protocol/`, and the
frozen examples in `ProtocolVectors` are what both platforms are tested against. If this document
and those disagree, the vectors are right and this is a bug.

---

## 1. Shape of the system

Two clients, one relay. The relay accepts TLS connections, learns which client id is on which
connection, and copies frames from one connection to another. It holds no state per conversation,
stores nothing, and cannot decrypt anything it forwards.

```
 Alice ──TLS 1.3──▶ relay ──TLS 1.3──▶ Bob
        (frames)           (frames)
```

Message bodies are already ciphertext before they reach the socket. TLS protects the metadata and
the integrity of the link; it is not what protects the content.

## 2. Versioning

Two version numbers, deliberately separate.

| Version | Where | Current | Meaning |
|---|---|---|---|
| Frame version | one byte per frame | `1` | The envelope format in §3. A reader rejects anything else outright. |
| Protocol version | a field inside each message | `2` | The message semantics in §4 onwards. |

**A change to the encoded bytes is a protocol break and must bump the protocol version.** The
frozen vectors exist to make that impossible to do by accident: change what the encoder produces
and `ProtocolVectorsTest` fails, saying so.

## 3. Framing

Every message on the wire is wrapped:

```
┌────────────────┬───────────────┬─────────────────────────┐
│ length: int32  │ version: int8 │ payload: length bytes   │
│ (big-endian)   │ = 1           │ (an encoded message)    │
└────────────────┴───────────────┴─────────────────────────┘
```

`length` counts the payload only, and must be between 0 and 1 MiB (`FrameReader.MAX_FRAME_BYTES`).
A length outside that is rejected **before anything is allocated** — a reader that trusts a declared
length is one `readInt` away from an allocation the sender chose.

Frames are written whole and flushed. Writes are synchronised, so two threads cannot interleave
halves of two frames on one socket.

## 4. Message encoding

Inside the frame, a message is a flat sequence of length-prefixed fields, written with
`DataOutputStream` in big-endian order:

| # | Field | Encoding | Cap |
|---|---|---|---|
| 1 | type | int32, the enum ordinal | must name a known type |
| 2 | protocolVersion | int32 | — |
| 3 | messageId | int32 length + UTF-8, or `-1` for null | 128 bytes |
| 4 | senderId | int32 length + UTF-8, or `-1` for null | 128 bytes |
| 5 | receiverId | int32 length + UTF-8, or `-1` for null | 128 bytes |
| 6 | timestamp | int64, milliseconds since the epoch | — |
| 7 | iv | int32 length + bytes, or `-1` | exactly 12 bytes when present |
| 8 | payload | int32 length + bytes, or `-1` | 64 KiB |
| 9 | signature | int32 length + bytes, or `-1` | 512 bytes |

Two things about this are load-bearing:

- **Every variable field is capped, and the cap is checked before the array is allocated.** The caps
  are what stop a peer turning a 20-byte frame into a request for gigabytes.
- **`-1` means absent and is distinct from empty.** A null `receiverId` is not the same as `""`, and
  the vectors pin both.

There is no Java serialization anywhere on this path and no wire type implements `Serializable`.
Handing `readObject` untrusted bytes is a remote-code-execution class of bug, and the way to not have
it is to not have it.

## 5. Message types

The codec writes the enum **ordinal**, so the order of these constants is part of the wire format.
New types are appended; nothing is reordered or removed.

| Ordinal | Type | Payload | Signed | Encrypted |
|---|---|---|---|---|
| 0 | `HELLO` | identity key and display name (§6) | no | no |
| 1 | `HELLO_ACK` | — | no | no |
| 2 | `KEY_EXCHANGE_INIT` | DH public key, X.509 encoded | yes | no |
| 3 | `KEY_EXCHANGE_REPLY` | DH public key, X.509 encoded | yes | no |
| 4 | `KEY_EXCHANGE_REJECT` | — | yes | no |
| 5 | `TEXT_MESSAGE` | message body | yes | **yes** |
| 6 | `DELIVERY_ACK` | the acknowledged message id | yes | no |
| 7 | `DISCONNECT` | — | no | no |
| 8 | `ERROR` | a short reason, such as `RECIPIENT_OFFLINE` | no | no |
| 9 | `PING` | — | no | no |
| 10 | `PONG` | — | no | no |
| 11 | `TYPING` | one byte: `1` composing, `0` stopped | yes | no |
| 12 | `READ_RECEIPT` | — | yes | no |

`PING`, `PONG` and `DISCONNECT` are between a client and the relay. Everything else is between
clients, and the relay only routes it.

`TYPING` and `READ_RECEIPT` are signed but not encrypted. They carry no content, and the relay
already learns who is talking to whom from the routing fields — encrypting them would buy nothing
and hide nothing.

## 6. Identity

A peer id **is** the identity key, in the only sense that matters: it is the first 16 bytes of the
SHA-256 of the X.509-encoded public key, written as 32 lowercase hex characters.

```
peerId = hex(SHA-256(publicKey.getEncoded())[0..16])
```

Consequences worth stating:

- **An id cannot be chosen.** You get the one your key hashes to.
- **A key change is an id change.** There is no rename and no account recovery.
- **A `HELLO` whose sender id is not the hash of the key it carries is rejected**, so a peer cannot
  claim someone else's address and have their own key trusted against it on first contact.

The `HELLO` payload is its own small format:

```
┌────────────────┬──────────────────┬────────────────┬────────────────────┐
│ keyLen: int32  │ key: keyLen      │ nameLen: int32 │ name: UTF-8        │
│                │ X.509 public key │ (optional)     │ max 128 bytes      │
└────────────────┴──────────────────┴────────────────┴────────────────────┘
```

The display name is metadata, in the clear, and is not part of the id. Renaming yourself does not
change your address — an earlier version got this wrong and made people unreachable by renaming.

## 7. Signatures

Signed messages carry `SHA256withRSA` over a canonical encoding that is **not** the wire encoding:

```
int32 len + type.name() as UTF-8      ← the name, not the ordinal
int32 len + messageId
int32 len + senderId
int32 len + receiverId
int64      timestamp
int32 len + iv
int32 len + payload
int32      protocolVersion
```

Absent fields contribute a zero length and no bytes. The type is signed by **name** rather than
ordinal, so appending a type cannot change what an existing message's signature covers.

`signature` is excluded from its own input, and the receiver verifies with the sender's stored
identity key. A message from a sender whose key is unknown cannot be verified and is dropped.

## 8. The handshake

```
Alice                          relay                          Bob
  │                              │                              │
  │──HELLO (register) ──────────▶│                              │
  │                              │◀────────── HELLO (register) ─│
  │                              │                              │
  │──HELLO (to Bob) ────────────▶│─────────────────────────────▶│  Bob stores Alice's key
  │                              │                              │
  │◀─────────────────────────────│◀───────── HELLO (to Alice) ──│  Alice stores Bob's key
  │                              │                              │
  │──KEY_EXCHANGE_INIT ─────────▶│─────────────────────────────▶│  signed; Bob derives
  │                              │                              │
  │◀─────────────────────────────│◀──────── KEY_EXCHANGE_REPLY ─│  signed; Alice derives
  │                              │                              │
  │══ TEXT_MESSAGE (ciphertext) ═╪═════════════════════════════▶│
```

A client's **first** `HELLO` has no `receiverId` and registers it with the relay. Any later `HELLO`
is routed like anything else.

A `HELLO` from an unknown peer is answered with one in return, so both sides end up able to verify
each other's signatures. Without that only the initiator could.

Session states are `IDLE → HANDSHAKE_SENT → ESTABLISHED`. `EXPIRED` exists in the enum and is never
set: there is no re-keying, and no way to trigger one automatically. See the limitations section of
[security.md](security.md).

A key does have a **send budget** of 100,000 messages (`Session.MAX_SENDS_PER_KEY`). Reaching it no
longer ends the conversation: the client discards the key, runs a fresh handshake, and reports the
one message that could not go so the sender can offer it again. Both sides reset their send counter
and their replay window when they adopt the new key, because nonce uniqueness is a property of a
key rather than of a session.

The budget is well below anything AES-GCM requires — the counter is 64-bit and the direction bit
keeps the peers apart. It is about key lifetime: each renewal is a fresh Diffie–Hellman exchange, so
bounding how long one key is used bounds how much its compromise reveals.

A renewal is **not** seamless. While it is in flight the session is not established, so sending is
refused, and a message already on the wire under the old key will fail authentication at the far end
and be dropped. Nothing re-keys on a timer; the budget is the only trigger.

**The relay acknowledges nothing.** A client knows its socket is up but not when the relay has
finished registering it, so a handshake aimed at a peer who connected a moment earlier can arrive
first and come back `RECIPIENT_OFFLINE`. Closing that needs an acknowledgement frame and a protocol
version bump; it has not been done.

## 9. Key agreement and encryption

**Diffie–Hellman** over RFC 3526 MODP Group 14, 2048-bit. The received public key is validated
before use: it must lie in `(1, p-1)`, and `y^q mod p` must be 1, which rejects small-subgroup
points.

The raw secret is **left-padded to 256 bytes** before derivation. It is a big integer, so it is
shorter than 256 bytes about one time in 256, and the JDK and Android providers disagreed about
whether to strip the leading zero — producing a handshake that failed roughly that often, from code
that looked correct.

```
sharedKey = HKDF-SHA256(
    ikm  = leftPad(dhSecret, 256),
    salt = 32 zero bytes,
    info = "tetherless-v1 aes-256-gcm",
    len  = 32)
```

**Encryption** is AES-256-GCM with a 128-bit tag and a 96-bit nonce:

```
nonce = [ direction: int32 ][ counter: int64 ]
```

The counter increments per message. The direction bit differs between the two peers, derived from
their ids. Both are necessary: the peers share one derived key, so without a direction bit each
side's n-th message would reuse the same key and nonce together — which breaks GCM outright and
leaks the XOR of the two plaintexts. That was a real bug here, found by checking the claim; it is
pinned now by `IvReuseTest`.

The nonce is counter-based rather than random because a random 96-bit nonce collides often enough to
matter across a long session.

## 10. Replay and reordering

Each session tracks the highest counter it has accepted, and remembers the individual counters it
has seen at or above the **window floor**, which sits 1024 below that highest.

- A counter at or below the floor is **too old to judge** and is dropped. It cannot be told apart
  from a replay.
- A counter above the floor that has been seen before is a **replay** and is dropped.
- Anything else is accepted, so genuine reordering inside the window still delivers.

The floor is what makes this correct, and the set of seen counters only separates frames inside the
window from one another. That separation matters: an earlier version had no floor at all and relied
on a set that evicted its oldest entry, so once 1024 further messages had passed the earliest
counter was simply forgotten and a captured frame carrying it was accepted a second time. Replay
protection has to be bounded by how old a frame is, not by how many have arrived since.

The set is pruned once it grows past twice the window. Nothing depends on that for correctness — it
only stops a long session accumulating every counter it has ever seen.

**What this costs.** A frame arriving more than 1024 messages behind is now dropped even if it is
genuine. That is deliberate, and the second check makes it moot: a message whose timestamp is more
than **five minutes** from local time is rejected outright, so a frame that far behind would already
have been refused on age.

Signature verification happens before any of this, so a forged frame cannot consume a counter and
cause the genuine message carrying it to be dropped.

The five-minute tolerance is also what stops ordinary clock drift breaking the app; see case 8 of
[the QA script](qa_script.md).

## 11. What the relay does

- Accepts TLS 1.3 only. A plaintext connection is dropped.
- Requires `HELLO` first. Anything else before it, and the connection is closed.
- Refuses a duplicate id, answering `ERROR` with `ID_TAKEN`.
- Routes by `receiverId`, and answers `ERROR` with `RECIPIENT_OFFLINE` when nobody is there.
- Answers `PING` with `PONG`, and disconnects idle connections.
- Rate limits per connection, and caps connections per address.

It never inspects a payload, never stores a message, and queues nothing for a peer who is offline —
a message sent to an absent peer is gone.

## 12. Conformance

`ProtocolVectors` holds 17 frozen wire-format cases covering every message type and every boundary:
empty payload, maximum payload, null `receiverId`, non-ASCII ids and content, emoji, right-to-left
text. `ProtocolConformance` is the checker both platforms run, so the two cannot drift apart in the
checks themselves.

They run on the JVM in `ProtocolVectorsTest` and `CryptoVectorsTest`, and on a real Android runtime
in `ProtocolConformanceTest` — on an emulator in CI, because Android's crypto comes from Conscrypt
rather than the JDK providers, and a divergence there should surface as a mismatch against committed
bytes rather than as a handshake that fails one time in 256.
