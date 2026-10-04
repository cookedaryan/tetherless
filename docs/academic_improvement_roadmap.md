# Academic Improvement Roadmap

**Date:** 2026-09-09
**Audience:** the project team, and anyone evaluating Tetherless as an academic artifact.
**Companion documents:** [security.md](security.md) (threat model and limitations), [protocol.md](protocol.md)
(wire format), [adr.md](adr.md) (decisions taken).

This document does not describe new features. It describes how to raise Tetherless from *a working
end-to-end-encrypted chat application* to *a project with a defensible thesis* — the difference an
academic reviewer is actually grading. It is organised so it can be folded directly into a report or
a presentation deck.

---

## 1. The thesis: turn documented limitations into contributions

Tetherless is already unusually honest about what it does not do. `security.md` §5 states plainly
that there is *no forward secrecy beyond a single handshake*, *no metadata privacy from the relay*,
and *no peer discovery*, and it ties most guarantees to a specific test. That honesty is worth
credit on its own — but it also hands you the roadmap. **The highest-value academic work is not
finding new flaws; it is converting a limitation you have already documented into either an
implemented defence or a rigorous written analysis.** Reviewers reward depth on one property far
more than breadth of features, because depth is where a thesis lives.

Everything below is ranked by *academic impact per unit of effort*, not by how much code it is.

---

## 2. What already earns credit — lead with these

State these first in any writeup; they are genuine engineering arguments, and establishing them
makes the improvement story land as "we knew where we stood," not "we missed things."

- **One shared protocol implementation.** `core-shared` holds the codec, crypto and session logic
  used by *both* the desktop and Android clients, and the frozen vectors in `ProtocolVectors` /
  `ProtocolConformance` are run on both. A desktop-versus-Android disagreement about the wire format
  is structurally impossible. This is a real design argument, not a convenience.
- **An authenticated key exchange.** `KEY_EXCHANGE_INIT` / `KEY_EXCHANGE_REPLY` are signed with the
  long-term identity key (`SecureChat.java`), and a key that changes for a known peer is reported and
  blocks sending until re-verification (`security.md` §3). Trust-on-first-use plus a safety number is
  the standard, defensible MITM story.
- **Careful symmetric hygiene.** AES-256-GCM with counter-based nonces, a **direction bit derived
  from the two peer ids** so the two sides can never collide (`IvReuseTest`), an anti-replay sliding
  window *with a low-water floor*, and a per-key send-counter ceiling that forces renewal before the
  nonce space is exhausted.
- **Sound at-rest and transport choices.** Message bodies are AES-256-GCM under a key the relay never
  sees; the database key is stretched from the passphrase with PBKDF2-HMAC-SHA256 at 210,000
  iterations (OWASP floor); transport is TLS 1.3 with a pinned certificate, explicitly *not* relied
  on for content confidentiality.
- **Process discipline.** ADRs record decisions; development is test-first with mutation checking;
  reply metadata is carried *inside* the ciphertext so the relay cannot see who quotes whom.

---

## 3. The roadmap

### Tier 1 — a headline cryptographic contribution (pick one or two)

These are what a "we engineered the standard defence for the weakness we found" narrative is built
on.

#### 1.1 Forward secrecy and post-compromise security via a ratchet — *highest impact*

**The gap.** `security.md` §5 says it exactly: one Diffie-Hellman exchange derives one key for the
life of a session; renewal is *bounded by volume, not by time, and it is not a ratchet*. Compromising
a session key exposes every message in that session. There is no *post-compromise security* — once a
key leaks, the conversation cannot heal.

**The improvement.** Adopt a **Double Ratchet** (Signal's construction: a Diffie-Hellman ratchet
combined with a symmetric-key KDF ratchet on each side). It gives *per-message* forward secrecy —
each message key is deleted after use — and *post-compromise security* — a later DH step re-randomises
the state, so a one-time key leak self-heals. If a full Double Ratchet is too much for the timeline,
a **symmetric-key ratchet with a periodic DH step** captures most of the forward-secrecy benefit and
is a legitimate, clearly-scoped middle ground to analyse.

**Why it scores.** It closes the number-one limitation you have already written down, and it is the
property that separates a modern secure messenger from a naive one. Even a partial implementation
plus an honest analysis of what it does and does not achieve is strong.

**Effort:** Large. **Depends on:** 1.2 is a natural prerequisite (the DH ratchet wants X25519).
**Reference:** Perrin & Marlinspike, *The Double Ratchet Algorithm* (Signal).

#### 1.2 Modern primitives: DH-2048 → X25519, RSA → Ed25519 — *best effort-to-legibility ratio*

**The gap.** `DHUtils.java` uses finite-field Diffie-Hellman over RFC 3526 MODP Group 14 (2048-bit,
roughly 112-bit security) and even carries a `@deprecated` note pointing at X25519 as FUTURE-02.
Identity signatures use RSA.

**The improvement.** Migrate key agreement to **X25519** (Curve25519) and identity signatures to
**Ed25519**. The story is crisp: 112-bit finite-field DH → 128-bit elliptic-curve DH, smaller keys,
faster, constant-time by construction, and the same primitive the ratchet's DH step needs.

**Why it scores.** Concrete, self-contained, and defensible in one sentence. It also de-risks Tier 1,
because X25519 is the DH step of the ratchet. Note the wire implication: `MessageType` ordinals may
only be *appended*, and a primitive change is a protocol-version event — document the migration path
(and dual-stack/deprecation window) as part of the contribution.

**Effort:** Medium.

---

### Tier 2 — analytical contributions (little code, high prestige)

#### 2.1 Formal verification of the handshake

**The improvement.** Model the signed-DH authenticated key exchange in **ProVerif** or **Tamarin**
under a Dolev–Yao attacker and state a result: secrecy of the session key and mutual authentication
hold, *or* here is the trace of an attack. Model the ratchet from Tier 1 if it lands.

**Why it scores.** It converts "we believe it is secure" into "we can make a checkable claim,"
which is the single biggest credibility jump available for a few days of modelling. Very high
prestige-to-effort ratio.

**Effort:** Small–Medium (modelling, not coding). **Reference:** Blanchet (ProVerif); Meier et al.
(Tamarin).

#### 2.2 Deniability analysis — *the sophisticated point most projects miss*

**The gap.** Tetherless **signs every message** with the long-term identity key
(`SecureChat.java`). Signatures give authenticity and integrity — but they also give
**non-repudiation**: a recipient can prove to a third party that *you* sent a specific message. That
is the opposite of *deniability*, and it is a property secure-messaging research treats as desirable.

**The improvement.** Analyse the trade-off explicitly, and optionally switch *message* authentication
from a signature to a **MAC derived from the shared secret** (retaining signatures only where they
are actually needed — the initial authenticated key exchange). This is how OTR and Signal achieve
deniability: anyone who could verify a message could also have forged it, so it proves nothing to an
outsider.

**Why it scores.** It demonstrates you understand a subtlety — authenticity ≠ non-repudiation, and
non-repudiation is often a *misfeature* in private messaging — that most student projects never
surface. Even the written analysis, with no code change, is worth a section.

**Effort:** Small (analysis) to Medium (if you re-architect message auth). **Reference:** Borisov,
Goldberg & Brewer, *Off-the-Record Communication*.

#### 2.3 Asynchronous session setup (X3DH) — *bridges Tier 2 and Tier 1*

**The gap.** The current handshake is a live round trip. Store-and-forward now queues *messages* for
an offline peer, but a *session* still effectively wants both parties present.

**The improvement.** Adopt an **X3DH-style prekey** model: each client publishes signed one-time and
signed prekeys to the relay, so a sender can establish a session with a peer who is offline. X3DH
also yields forward secrecy and deniability for the initial exchange, and is the natural on-ramp to
the Double Ratchet.

**Why it scores.** It is the mechanism real asynchronous messengers use, it composes with 1.1 and
2.2, and the "relay stores signed *public* prekeys, never anything it can read" framing keeps your
headline property intact. **Effort:** Medium. **Reference:** Marlinspike & Perrin, *X3DH*.

---

### Tier 3 — evaluation and rigour (often the B→A difference)

#### 3.1 Quantitative evaluation

You are already instrumented for this: the relay has `Metrics` and a `TokenBucket`. Measure and
report:

- Handshake latency (and its breakdown), before and after any primitive change.
- Per-message size overhead (header + signature/MAC + GCM tag) as a percentage of payload.
- Client throughput and relay fan-out under load; behaviour at the queue high-water mark.
- The cost of the passphrase KDF at 210k iterations (this is a deliberate, measurable trade-off).

Graphs of these, with a sentence of interpretation each, read as real engineering evaluation.

#### 3.2 Properties comparison table

A single table comparing Tetherless against established systems is one of the highest-signal things
you can put in a report (see §5 for a starting version). It forces precision and shows you know the
landscape.

#### 3.3 A structured adversary model

You have the raw content in `security.md`; formalise it. State the attacker's capabilities explicitly
(a Dolev–Yao network attacker; an honest-but-curious relay; a relay that has been seized; a
compromised endpoint) and, for each, what is and is not protected. STRIDE per component is an
acceptable alternative framing. A structured model reads far more rigorously than prose.

---

### Tier 4 — extensions if time allows

- **Group messaging** via **MLS (RFC 9420, TreeKEM)** or Signal-style sender keys — the current
  research frontier in secure messaging, and a large but high-visibility contribution.
- **Post-quantum hybrid handshake** — X25519 **+ ML-KEM (FIPS 203)**, in the shape of Signal's PQXDH.
  Even a prototype or a written feasibility study is timely.
- **Metadata protection** — a **sealed-sender** sketch (hide the sender id from the relay), or an
  analysis of what routing over Tor / a mix would cost the design.
- **Multi-device** via device-linking (a new device is authorised by an existing one) — a systems
  contribution that fits the "mobile-ready" direction without a server-readable cloud.

---

### Smaller polish that still reads well

- **Argon2id** in place of PBKDF2 for the passphrase KDF. PBKDF2 at 210k is *fine*; Argon2id is
  memory-hard and the modern answer, and the swap is a one-paragraph justification.
- **Key-compromise impersonation (KCI)** and **unknown-key-share (UKS)** notes on the AKE — naming
  the attacks you are and are not resistant to signals maturity.

---

## 4. Limitation → improvement map

| Documented limitation (security.md §5) | Proposed work | Tier | Effort |
|---|---|---|---|
| No forward secrecy beyond a single handshake | Double Ratchet / periodic-DH ratchet (1.1) | 1 | L |
| Volume-bounded renewal, "not a ratchet" | Same as above; report the before/after (3.1) | 1 / 3 | L / S |
| Finite-field DH-2048; RSA signatures | X25519 + Ed25519 migration (1.2) | 1 | M |
| Every message signed → non-repudiable | Deniability analysis; MAC-based message auth (2.2) | 2 | S–M |
| No metadata privacy from the relay | Sealed sender / Tor analysis (Tier 4); adversary model (3.3) | 4 / 3 | M / S |
| Synchronous session setup | X3DH prekeys for async sessions (2.3) | 2 | M |
| No peer discovery / federation | Out of academic scope; note as product work | — | — |
| No groups | MLS or sender keys (Tier 4) | 4 | L |
| (Unstated) no post-quantum story | Hybrid X25519 + ML-KEM (Tier 4) | 4 | M |

---

## 5. Properties comparison table

A starting point — fill and defend each cell in the report. "Current" is Tetherless today; "Target"
is Tetherless after Tier 1–2. Claims about other systems should be footnoted to a source in the
final writeup.

| Property | Tetherless (current) | Tetherless (target) | Signal | Telegram (cloud / secret) | Matrix (Olm/Megolm) | Briar |
|---|---|---|---|---|---|---|
| End-to-end by default | Yes | Yes | Yes | No / Yes | Optional | Yes |
| Relay/server can read content | No | No | No | Yes / No | No (metadata: yes) | No server |
| Per-message forward secrecy | No (session-level only) | Yes | Yes | No / partial | Yes / forward-only | Yes |
| Post-compromise security | No | Yes | Yes | No | 1:1 yes | Partial |
| Deniability | No (signed) | Yes (if MAC-based) | Yes | No | No | Partial |
| Metadata privacy from server | No | Partial (sealed sender) | Partial | No | No | Yes (P2P/Tor) |
| Asynchronous session setup | Limited | Yes (X3DH) | Yes | Yes | Yes | Limited |
| Multi-device | No | Via linking (future) | Yes | Yes | Yes | No |
| Group messaging | No | MLS (future) | Yes | Yes | Yes | Yes |
| Formal analysis published | No | Yes (ProVerif/Tamarin) | Partial (academic) | No | Partial | Partial |

The two columns that are already green for you — *server cannot read content* and *E2E by default* —
are exactly where Telegram and Matrix are weak. Lead the comparison there; it is your strongest
ground.

---

## 6. Suggested phased plan

Assuming a bounded term and a small team, do **not** attempt all of Tier 1–4. A coherent, defensible
selection beats a scattered one.

**Phase A — modernise and prove (recommended minimum).**
1. X25519 + Ed25519 migration (1.2), with the protocol-version/migration write-up.
2. ProVerif or Tamarin model of the current AKE (2.1).
3. Deniability analysis, written (2.2) — no code required.
4. Comparison table (3.2) + structured adversary model (3.3).

This is achievable, touches real cryptography, and produces a paper-shaped result.

**Phase B — the headline (if time and appetite).**
5. Double Ratchet, or the periodic-DH ratchet variant (1.1), on top of the X25519 work.
6. Quantitative before/after evaluation (3.1), and extend the formal model to the ratchet.

**Phase C — reach (only if A and B are solid).**
7. One of: X3DH async setup (2.3), MLS groups, or a PQ-hybrid handshake (Tier 4).

Pick the smallest set you can finish *well*. An unfinished Double Ratchet hurts the writeup; a
finished X25519 migration plus a formal model and a deniability analysis is a genuinely strong
project.

---

## 7. How to frame it in the report and deck

- **Open with the honesty.** "We built an E2E messenger, documented exactly where it was weak, and
  then engineered/analysed the fixes." That framing turns §2 strengths and §3 work into one arc.
- **Make one property the spine.** Forward secrecy is the natural choice: state the weakness, show
  the ratchet, measure the cost, prove a property. A report with one deep thread reads better than
  one with ten shallow ones.
- **Use the comparison table early.** It orients the reader and stakes your claim.
- **Be explicit about what you did not do and why.** The same honesty that got you here is a
  feature; scope stated as a decision reads as maturity, scope missing reads as oversight.

---

## 8. References and further reading

- Perrin, Marlinspike. *The Double Ratchet Algorithm.* Signal specification.
- Marlinspike, Perrin. *The X3DH Key Agreement Protocol.* Signal specification.
- Borisov, Goldberg, Brewer. *Off-the-Record Communication, or, Why Not to Use PGP.* WPES 2004.
  (Deniability, MAC-based authentication.)
- Cohn-Gordon, Cremers, Dowling, Garratt, Stebila. *A Formal Security Analysis of the Signal
  Messaging Protocol.* EuroS&P 2017.
- Barnes et al. *The Messaging Layer Security (MLS) Protocol.* RFC 9420.
- Blanchet. *ProVerif: Automatic Cryptographic Protocol Verifier.* / Meier et al. *The Tamarin
  Prover.*
- NIST FIPS 203, *Module-Lattice-Based Key-Encapsulation Mechanism (ML-KEM).*
- OWASP *Password Storage Cheat Sheet* (PBKDF2 / Argon2id parameters).

---

## Appendix — where each thing lives in the code

| Concern | File |
|---|---|
| Diffie-Hellman key agreement (migration target) | `core-shared/.../crypto/DHUtils.java` |
| AES-GCM primitives, nonce handling | `core-shared/.../crypto/AESUtils.java` |
| RSA identity keys / signatures (migration target) | `core-shared/.../crypto/RSAUtils.java` |
| Authenticated key exchange, message sign/encrypt | `core-shared/.../session/SecureChat.java` |
| Session key, replay window, send-counter ceiling | `core-shared/.../session/Session.java` |
| In-memory session-key cache (not persisted) | `core-shared/.../keys/SessionKeyCache.java` |
| Wire types (append-only ordinals) | `core-shared/.../models/MessageType.java` |
| Frozen conformance vectors | `core-shared/.../protocol/ProtocolVectors.java` |
| Passphrase KDF (PBKDF2 → Argon2id target) | `chat-desktop/.../DatabaseKeys.java` |
| Relay routing, store-and-forward, metadata surface | `chat-server/.../ClientSession.java` |
| Threat model and stated limitations | `docs/security.md` |
