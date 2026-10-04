# Academic Improvement — Ticketed Implementation Plan

**Date:** 2026-09-09
**Companion:** [academic_improvement_roadmap.md](academic_improvement_roadmap.md) (the *why* and the ranking).
This document is the *how*: discrete, dependency-ordered tickets, each with a concrete **Expected
result** you can check off and cite in a report.

## 0. How to read this document

Tickets follow the house format from [development_plan.md](development_plan.md): `AREA-NN — Title`,
grouped into phases, each leaving the build green. Fresh area prefixes are used so they do **not**
collide with the existing `CORE-NN` series:

| Prefix | Track |
|---|---|
| `PROTO` | Wire format, version/suite negotiation, session setup |
| `CRYPTO` | Cryptographic primitives and constructions |
| `VERIF` | Formal verification |
| `EVAL` | Quantitative evaluation |
| `ANALYSIS` | Written security analysis (a documented artifact *is* the deliverable) |

Effort is **S** (≤3 days), **M** (~1 week), **L** (2+ weeks) for a small team.

### Global definition of done (applies to every ticket)

1. **Test-first.** Behaviour changes have a test written and watched fail (RED) before the code
   exists (GREEN). Analysis tickets deliver a reviewed document instead.
2. **Wire changes are versioned.** Any change to a payload or the crypto suite is a negotiated
   protocol version (see `PROTO-01`); it never silently reinterprets an older peer's bytes.
   `MessageType` ordinals remain append-only.
3. **Cross-platform vectors.** A wire change adds/updates frozen vectors in `ProtocolVectors` and
   they pass on **both** desktop and Android (`ProtocolConformance`).
4. **Gates green.** `:core-shared:check`, `:chat-server:check`, `:chat-desktop:check` and
   `:chat-desktop:integTest` pass. Java 8 language level; Checkstyle and SpotBugs + find-sec-bugs
   clean; no literal secrets.
5. **Decisions recorded.** Anything that changes the protocol or the threat model gets an ADR in
   [adr.md](adr.md), and the relevant section of [security.md](security.md) is updated in the same
   ticket.

### Dependency graph

```
PROTO-01 ─┬─ CRYPTO-01 (X25519) ─┬─ CRYPTO-03 (symmetric ratchet) ── CRYPTO-04 (DH ratchet)
          │                      │                                        │
          ├─ CRYPTO-02 (Ed25519) │                                        ├─ EVAL-01
          │                      └─ PROTO-02 (X3DH) ─ PROTO-03 (MLS)      └─ VERIF-02
          └─ CRYPTO-07 (Argon2id, independent)
VERIF-01 (models current/​CRYPTO-01 AKE)      ANALYSIS-01, ANALYSIS-02 (independent)
CRYPTO-06 (PQ hybrid), ANALYSIS-03 (metadata)  ── Phase C, independent
```

---

## Phase A — Modernise and prove

The recommended minimum. Produces a paper-shaped result on its own.

### PROTO-01 — Negotiate a cryptographic suite in the key exchange

**Depends on:** none. **Effort:** M.

**Scope.** Today `protocolVersion` rides on every frame (default `2`) but nothing acts on it, and
the crypto suite is fixed (DH-2048 + RSA). Introduce an explicit, *authenticated* suite negotiation
so later tickets can add primitives without breaking older peers.

**Approach.**
- Define a `CryptoSuite` enum: `V2_DH2048_RSA` (current) and room to append (`V3_X25519_ED25519`, …).
- `KEY_EXCHANGE_INIT` advertises the initiator's supported suites; `KEY_EXCHANGE_REPLY` names the
  chosen one. Both fields sit **inside the signed key-exchange frames**, so a network attacker
  cannot strip the stronger option (downgrade resistance).
- Both peers deterministically pick the highest suite in common; store it on the `Session`.
- The relay is untouched — negotiation is peer-to-peer, matching the dumb-relay design.

**Files.** `SecureChat.java`, `Session.java`, `MessageType`/payload builders, `ProtocolVectors`,
`adr.md`, `protocol.md`.

**Expected result.**
- Two peers advertising different suite sets settle deterministically on the highest common suite;
  a v2-only peer still interoperates with a peer that also speaks v3.
- A test simulating a MITM that rewrites the advertised suite list causes the handshake to **fail**
  (the signature no longer verifies) rather than silently downgrading. This downgrade-resistance
  test is the headline evidence.
- New vectors pass on both clients; all gates green; ADR records the negotiation design.

### CRYPTO-01 — X25519 key agreement

**Depends on:** `PROTO-01`. **Effort:** M.

**Scope.** Add X25519 (XDH) as the key-agreement half of a new suite, alongside the existing
finite-field DH. Closes the FUTURE-02 note in `DHUtils.java`.

**Approach.** Introduce a `KeyAgreement` seam with two implementations (DH-2048, X25519); `SecureChat`
selects by the negotiated suite. Derive the shared secret via HKDF as today.

**Files.** `crypto/DHUtils.java` (or a new `X25519Utils.java` + interface), `SecureChat.java`,
`ProtocolVectors.java`.

**Expected result.**
- A session negotiated to the X25519 suite round-trips plaintext **identically** to the DH path
  (same `SecureChat` API, same tests, different suite).
- New frozen X25519 vectors pass on desktop **and** Android; the DH-2048 vectors still pass.
- RED-before-GREEN evidence for the agreement tests; gates green.
- A benchmarking hook is left for `EVAL-01` (handshake timing).

### CRYPTO-02 — Ed25519 identity signatures

**Depends on:** `PROTO-01`. **Effort:** M.

**Scope.** Add Ed25519 identity keys and signatures as the signing half of the v3 suite; keep RSA
for v2.

**Approach.** Add an `IdentitySigner` seam (RSA, Ed25519); `MessageSigner`/`SignatureVerifier` and
`IdentityKeyStore` select by suite. Note the consequence up front: `PeerId` is a hash of the identity
public key (`PeerId.java`), so an Ed25519 identity is a **different peer id** than the same user's RSA
identity — identities do not migrate across suites.

**Files.** `crypto/RSAUtils.java` + new `Ed25519Utils.java`, `protocol/MessageSigner.java`,
`protocol/SignatureVerifier.java`, `keys/IdentityKeyStore.java`, `identity/PeerId.java`, `adr.md`.

**Expected result.**
- Messages signed with Ed25519 verify; a forged/mismatched signature is rejected exactly as for RSA.
- A peer id derived from an Ed25519 key is stable across restarts and distinct from an RSA-derived one.
- The safety-number / fingerprint surface renders correctly for Ed25519 keys.
- An ADR states "changing identity suite changes the peer id; identities are not portable" as a
  deliberate decision. Gates green.

### VERIF-01 — Formal model of the authenticated key exchange

**Depends on:** best done after `CRYPTO-01` (model the X25519 suite); can start against v2. **Effort:** M.

**Scope.** Model the signed key exchange in **ProVerif** or **Tamarin** under a Dolev–Yao attacker.

**Approach.** Encode the two identity keys, the (ephemeral) agreement keys, the signed
`INIT`/`REPLY`, and the suite negotiation from `PROTO-01`. State queries for session-key secrecy,
mutual authentication, and downgrade resistance.

**Files.** New `formal/` directory (model + a README explaining how to run it); a results summary in
`security.md`.

**Expected result.**
- A checked-in model that the tool runs to completion.
- A results table: *secrecy → holds*, *authentication → holds*, *downgrade resistance → holds* — or,
  where a property fails, the attack trace, documented honestly.
- `security.md` cites the model and its result.

### ANALYSIS-01 — Deniability analysis

**Depends on:** none. **Effort:** S (analysis). **Spawns (optional):** `CRYPTO-05`.

**Scope.** Every message is signed with the long-term identity key, giving **non-repudiation** — a
recipient can prove to a third party that you sent a given message, the opposite of deniability.
Analyse the trade-off and make a recommendation.

**Expected result.**
- A documented section stating precisely what a recipient can and cannot prove today, compared to
  OTR and Signal (which authenticate messages with a shared-secret MAC to stay deniable).
- A decision: keep signatures, or move *message* authentication to a MAC derived from the session
  secret while retaining signatures only on the key exchange. If the decision is to change, it opens
  `CRYPTO-05 — Deniable message authentication (MAC)` with its own Expected result (messages carry a
  MAC not a signature; a recipient cannot produce third-party-verifiable proof of authorship; AKE
  still signed; vectors + gates green).

### ANALYSIS-02 — Structured adversary model and comparison table

**Depends on:** none. **Effort:** S.

**Scope.** Formalise the threat model and finish the properties comparison table from the roadmap.

**Expected result.**
- A `threat_model` section enumerating attacker classes — Dolev–Yao network attacker,
  honest-but-curious relay, **seized** relay, compromised endpoint — each with its capabilities and a
  clear statement of what is and is not protected.
- The Tetherless-vs-Signal/Telegram/Matrix/Briar table with every cell sourced, reviewable as a
  standalone artifact and cited in the report.

---

## Phase B — The headline: forward secrecy

Do this only once Phase A is solid. It rewrites the number-one documented limitation.

### CRYPTO-03 — Symmetric-key ratchet (per-message forward secrecy)

**Depends on:** `CRYPTO-01`. **Effort:** L.

**Scope.** Replace the single per-session key with a KDF chain: each message gets a fresh key derived
from a chain key, and the message key is deleted after use. Support out-of-order delivery with a
bounded skipped-message-key cache.

**Files.** `session/Session.java` (chain keys, message-key derivation, skipped-key store),
`SecureChat.java`, `ProtocolVectors.java`.

**Expected result.**
- Each message uses a distinct key; a test asserts two messages never share a key.
- A captured single message key decrypts **only** its own message.
- Out-of-order and dropped messages within the window still decrypt (skipped-key test).
- **Forward secrecy test:** after a message is processed and its key deleted, that key cannot be
  re-derived from current session state. Reverting the deletion fails the test (mutation-checked).
- Vectors pass on both clients; gates green.

### CRYPTO-04 — DH ratchet step (complete the Double Ratchet)

**Depends on:** `CRYPTO-03`. **Effort:** L.

**Scope.** Ratchet the root key with a fresh X25519 DH on each round trip, giving **post-compromise
security**. The volume-bounded renewal (`MAX_SENDS_PER_KEY`) is subsumed and retired.

**Files.** `session/Session.java`, `SecureChat.java`, `ProtocolVectors.java`; update the renewal
tests; `security.md` §5.

**Expected result.**
- **Post-compromise security test:** given a simulated full compromise of one party's session state,
  once both parties complete a subsequent DH ratchet step the attacker can no longer derive later
  message keys.
- Interoperates with `CRYPTO-03`; the old renewal path and its tests are removed or updated with no
  loss of coverage.
- `security.md` §5's "no forward secrecy beyond a single handshake" is rewritten to describe the
  ratchet's guarantees and their limits; an ADR records the construction.
- Gates green; `VERIF-02` extends the model.

### EVAL-01 — Quantitative evaluation harness and before/after results

**Depends on:** `CRYPTO-01`/`CRYPTO-02` for a before/after; richer with `CRYPTO-03`/`04`. **Effort:** M.

**Scope.** A reproducible harness measuring the numbers a reviewer expects.

**Approach.** JMH (or a documented timed harness) for crypto operations; a load harness against a
local relay reusing `Metrics`/`TokenBucket`.

**Expected result.**
- A runnable benchmark producing: handshake latency (DH-2048 vs X25519), sign/verify cost (RSA vs
  Ed25519), per-message byte overhead (header + auth + GCM tag) as a percentage of payload, client
  throughput, relay behaviour at the queue high-water mark, and the PBKDF2/Argon2id KDF cost.
- A results section with a graph per metric and one sentence of interpretation each. Numbers are
  reproducible from a documented command.

### VERIF-02 — Extend the formal model to the ratchet

**Depends on:** `VERIF-01`, `CRYPTO-04`. **Effort:** M.

**Expected result.** The model expresses forward secrecy and post-compromise security as queries;
both are verified, or the limits of the symbolic model are stated honestly (PCS often needs a
computational argument — say so rather than over-claim).

---

## Phase C — Reach (choose at most one, only after A and B are solid)

### PROTO-02 — X3DH asynchronous session setup

**Depends on:** `CRYPTO-01`, `PROTO-01`. **Effort:** L.

**Scope.** Signed prekeys + one-time prekeys published to the relay (public material only) so a
session can be established with an offline peer.

**Expected result.** Alice establishes a session and sends a first message while Bob is offline; Bob
comes online and completes it using only his locally held private prekeys; the initial exchange keeps
forward secrecy and deniability; a test asserts **no private key material ever leaves the client** —
the relay stores only signed public prekeys.

### PROTO-03 — Group messaging (MLS or sender keys)

**Depends on:** `CRYPTO-03`. **Effort:** L+.

**Expected result.** A group of ≥3 members exchanges E2E messages; adding or removing a member
re-keys the group (forward secrecy and post-compromise security at the group level); per-message
overhead scales acceptably with group size; the design is documented against RFC 9420 (MLS/TreeKEM)
concepts.

### CRYPTO-06 — Post-quantum hybrid handshake (X25519 + ML-KEM)

**Depends on:** `CRYPTO-01`, `PROTO-01`. **Effort:** M–L.

**Expected result.** The session key is derived from **both** an X25519 shared secret and an ML-KEM
encapsulation, so it is no weaker than X25519 alone (hybrid); negotiated as its own suite; overhead
measured under `EVAL-01`. If a full implementation is out of reach, the deliverable is a written
feasibility study naming the exact integration point and the size/latency cost.

### ANALYSIS-03 — Sealed sender and metadata analysis

**Depends on:** none. **Effort:** M.

**Expected result.** An analysis of what the relay learns (who ↔ whom, timing, size), a sealed-sender
design that hides the sender id from the relay (sketch or prototype), and a quantification of the
residual metadata that remains even so.

---

## Polish

### CRYPTO-07 — Argon2id passphrase KDF

**Depends on:** none (independent of the suite negotiation). **Effort:** S–M.

**Scope.** Replace PBKDF2-HMAC-SHA256 with **Argon2id** for the passphrase-derived database key,
with a transparent migration for existing databases.

**Files.** `chat-desktop/.../DatabaseKeys.java`, `ProfileStore.java` (already carries the
"derived with PBKDF2, needs migrating" signal), `ui/StrengthMeter.java` justification text.

**Expected result.**
- New identities derive the key with Argon2id at documented parameters (memory, iterations,
  parallelism).
- An existing PBKDF2 database still opens and is transparently re-wrapped to Argon2id on the next
  successful unlock; a test covers both open-old and create-new.
- Justification text and an ADR updated; gates green.

---

## Summary

| Ticket | Phase | Depends on | Effort | Expected result (one line) |
|---|---|---|---|---|
| PROTO-01 | A | — | M | Authenticated, downgrade-resistant suite negotiation; v2 peers still interoperate |
| CRYPTO-01 | A | PROTO-01 | M | X25519 suite round-trips identically; vectors pass on both clients |
| CRYPTO-02 | A | PROTO-01 | M | Ed25519 identities and signatures; peer-id consequence documented |
| VERIF-01 | A | (CRYPTO-01) | M | Checked model: secrecy, authentication, downgrade resistance |
| ANALYSIS-01 | A | — | S | Deniability trade-off documented; decision (may spawn CRYPTO-05) |
| ANALYSIS-02 | A | — | S | Structured adversary model + sourced comparison table |
| CRYPTO-03 | B | CRYPTO-01 | L | Per-message forward secrecy; deleted keys unrecoverable |
| CRYPTO-04 | B | CRYPTO-03 | L | Post-compromise security via DH ratchet; §5 limitation rewritten |
| EVAL-01 | B | CRYPTO-01/02 | M | Reproducible before/after benchmarks with interpretation |
| VERIF-02 | B | VERIF-01, CRYPTO-04 | M | Model verifies FS and PCS (or states symbolic limits) |
| PROTO-02 | C | CRYPTO-01 | L | Async session with an offline peer; no private material leaves client |
| PROTO-03 | C | CRYPTO-03 | L+ | E2E group with re-keying on membership change |
| CRYPTO-06 | C | CRYPTO-01 | M–L | Hybrid X25519 + ML-KEM handshake, no weaker than X25519 |
| ANALYSIS-03 | C | — | M | Sealed-sender design + residual-metadata quantification |
| CRYPTO-07 | polish | — | S–M | Argon2id KDF with transparent migration from PBKDF2 |

**Recommended cut for a bounded term:** Phase A in full, then `CRYPTO-03`/`CRYPTO-04` + `EVAL-01`
from Phase B. That is a finished, measured, formally-modelled forward-secrecy story — a strong
project. Reach into Phase C only if A and B are genuinely done.
