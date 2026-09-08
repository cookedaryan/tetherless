# Architecture decisions

The decisions that would be expensive to reverse, why they were made, and — where it applies — why
they now look wrong. A decision log that records only vindicated choices is a marketing document.

Each entry states the position as it stands today. Where a decision has been superseded, the
original reasoning is kept rather than rewritten, because the reasoning is the useful part.

---

## ADR-1 — Diffie–Hellman over MODP Group 14, not X25519

**Status:** accepted, with reservations.

Session keys come from finite-field Diffie–Hellman over RFC 3526 Group 14 (2048-bit), not from
X25519.

**Why.** Both clients had to agree exactly, and one of them is Android. `KeyAgreement("DH")` is in
every JDK and at every Android level this project supports. X25519 arrived in the platform much
later — the client's `minSdk` is 24, and platform X25519 is not available anywhere near that far
back. Using it would have meant either raising the floor by several Android releases or carrying a
separate implementation on one side, and two implementations of the same primitive is exactly the
shape of bug this project's test strategy exists to prevent.

**What it costs.** Group 14 is the slower and larger choice: a 256-byte public value against 32, and
a modular exponentiation instead of a curve multiplication. On the handshake path, once per
conversation, that is not felt.

**What it nearly cost.** The shared secret is a big integer, so its byte length varies, and the JDK
and Android providers disagreed about whether to strip a leading zero. The result was a handshake
that failed roughly one time in 256, from code that read correctly on both sides. It is fixed by
left-padding to 256 bytes before derivation, and pinned by committed vectors that both platforms run.
A curve would not have had this failure mode at all.

**Revisit when** the `minSdk` can be raised far enough for platform X25519, or when bundling a
curve implementation both sides share becomes the smaller risk. The vectors make that migration
checkable rather than hopeful.

## ADR-2 — A relay, not peer-to-peer

**Status:** accepted.

Clients do not talk to each other. They connect out to a relay that copies frames between them.

**Why.** True peer-to-peer between two consumer machines means NAT traversal — STUN, TURN, hole
punching, and a fallback relay for the cases where none of it works. That fallback is this design,
so the peer-to-peer path would have been additional machinery on top of what had to exist anyway,
not instead of it.

**What it costs, honestly.** A single point of failure, and a single point of observation. The relay
cannot read messages, but it sees who talks to whom, when, and how much — and that social graph is
not protected. Anyone for whom the metadata is the sensitive part should not use this. It is stated
in the limitations section of [security.md](security.md) rather than buried here.

**What it buys.** The relay is small enough to reason about: it routes by receiver id and stores
nothing. "Assume the relay is hostile" is a claim that can be tested, and
`EndToEndExchangeTest` tests it by watching everything the relay sees.

## ADR-3 — An explicit binary codec, not Java serialization

**Status:** accepted. This one is not a trade-off.

The wire format is a hand-written length-prefixed encoding. No wire type implements `Serializable`,
and `readObject` appears nowhere on the message path.

**Why.** Java deserialization of untrusted bytes is a remote-code-execution class of bug: the
gadget chains live in whatever happens to be on the classpath, which means the vulnerability is not
even in code anyone here wrote. For an application whose entire input is frames from an untrusted
network, it is not a risk to manage. It is a feature to not have.

The original code did use serialization. Replacing it also gave the format something serialization
never offered: per-field caps checked *before* allocation, so a twenty-byte frame cannot ask for a
gigabyte, and a definition precise enough to freeze into vectors that both platforms are held to.

**What it costs.** Every field is written and read by hand, and the encoding is now part of the
compatibility surface — the field order is the format, and changing it is a protocol break. The
vectors make that break loud instead of silent. See [protocol.md](protocol.md).

## ADR-4 — Identity is the hash of the key

**Status:** accepted.

A peer id is the first 16 bytes of the SHA-256 of the identity public key, as 32 hex characters.
There are no usernames and no registration.

**Why.** It removes a whole category of problem: there is no name to squat, no directory to
compromise, and no first-contact question of whether a key belongs to the id claiming it — the id
*is* the key. A `HELLO` whose sender id is not the hash of the key it carries is rejected.

**What it costs.** Ids are unmemorable and must be exchanged out of band. Losing the key loses the
identity, permanently: there is no recovery, and every contact sees a key change. On Android the key
is non-exportable, so an uninstall destroys it.

**Two earlier versions of this were wrong**, and both are worth recording. The id once contained the
display name, so renaming yourself changed your address and made you unreachable. And it was once 28
bits, which collides at around 16,000 identities — and since the relay refuses a duplicate id, a
collision would have locked someone out of their own account.

## ADR-5 — One protocol implementation, shared by both clients

**Status:** accepted.

`core-shared` holds the codec, the crypto and the session logic. Neither client has its own.

**Why.** The defect this project is most likely to ship is a desktop-versus-Android disagreement
about bytes, and it would surface as an intermittent handshake failure rather than a compile error.
One implementation makes most of that class impossible; committed vectors, run on the JVM and on a
real Android runtime in CI, catch what is left — including provider differences, since Android's
crypto comes from Conscrypt rather than the JDK.

**What it costs.** `core-shared` compiles at a language level both platforms accept, so it is
written in an older dialect of Java than the desktop client alone would need.

## ADR-6 — Notify about updates; never install them

**Status:** accepted, given no code signing.

The desktop client checks for a newer release at startup and shows a banner. It downloads nothing
and installs nothing.

**Why.** An updater that fetches and runs a binary is asking the user to trust that binary. Without
code signing there is nothing for them to check it against, so an auto-updater would be a
privileged, automated channel for running unverified code — strictly worse than telling someone a
version exists and letting them decide.

**What it costs.** One HTTPS request to GitHub at startup, which tells GitHub and anyone watching
the network that this address runs Tetherless and roughly when. For a project whose point is
metadata resistance that is a real cost, so it is listed in the limitations of
[security.md](security.md) and can be switched off with `updates=false`.

**Revisit when** the client is code signed. A verified update path would then be worth building.

## ADR-7 — Generate identity keys in process, not with keytool

**Status:** accepted. Supersedes the original implementation.

Identity keys are generated with `KeyPairGenerator`, and the certificate PKCS#12 requires is
hand-encoded in `SelfSignedCertificate`.

**Why.** The original ran `keytool` in a subprocess with the user's passphrase as a command-line
argument, where any process running as the same user can read it. That passphrase also derives the
message database key. It was also unshippable: a jpackage app image contains one executable, and it
is not `keytool`, so a user who installed a build rather than running from a checkout would have
had their first launch fail with nothing to explain it.

**What it costs.** Hand-encoded DER, which is the kind of code that fails in ways that surface much
later. It is bounded — X.509 v1, one CN, one signature algorithm — and every field is asserted,
including that the certificate verifies against its own key, which is what proves the encoding of
the signed half is right.

**What was considered.** Feeding the passphrase to `keytool` on stdin would have closed the
disclosure but not the packaging failure. Adding Bouncy Castle would have closed both, at the cost
of a large dependency in a client whose whole security argument benefits from being small.

---

## Decisions still open

Recorded here so they are not mistaken for settled.

- **No forward secrecy beyond a single handshake, and no re-keying.** A session holds one key for as
  long as it lasts. There is a hard ceiling at 100,000 sends, at which point sending simply stops
  working. A ratchet is the answer and has not been built.
- **The relay acknowledges nothing after a client's HELLO**, so a client cannot tell when it has
  become routable. Fixing it means an acknowledgement frame and a protocol version bump.
- **The replay set evicts rather than floors.** See §10 of [protocol.md](protocol.md). The fix is
  small and has not been made.
- **Desktop at-rest encryption covers message bodies only.** Participants, timestamps and message
  counts are in the clear in the local database.
