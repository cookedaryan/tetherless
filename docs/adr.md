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

## ADR-8 — The relay acknowledges a registration, and the version did not move

**Status:** accepted.

The relay answers a client's opening `HELLO` with `HELLO_ACK`, and a client is not connected until
that arrives.

**Why.** Before it, `CONNECTED` meant only that the socket was up and a `HELLO` had been queued. The
relay had not necessarily registered the id yet, so anything addressed to that client in the gap
came back `RECIPIENT_OFFLINE` — and since a handshake is sent as one unit, it failed whole, with
nothing in flight to retry and nothing on screen to explain it. There was no signal to wait on
because the relay sent none. Now `CONNECTED` means routable, which is what every caller already
assumed it meant.

**What it costs.** A round trip before a client considers itself connected, and a compatibility
requirement that runs one way: a current client will not finish connecting to a relay that does not
acknowledge. An older client against a current relay is unaffected — it ignores a frame it was not
expecting.

**Why `protocolVersion` stayed at 2.** No encoded byte changed. `HELLO_ACK` was already ordinal 1
and simply unused, so the seventeen frozen vectors are untouched and both versions parse each
other's frames identically. Nothing in the codebase reads `protocolVersion`, so bumping it would
have rewritten every committed vector to signal something no implementation would act on, and made
the next real format change harder to see. The rule in [protocol.md](protocol.md) is that a change
to the *bytes* forces a bump; this was a change to a requirement, and it is recorded here instead.

**How it is known to work.** The end-to-end harness used to connect its two clients in a careful
order and retry the handshake, because doing it the obvious way failed about one run in four. Both
workarounds are gone, and it connects both clients at once and handshakes immediately. That is the
regression test: if the acknowledgement stops meaning what it says, the harness starts failing
intermittently again.

---

## ADR-9 — A registration proves it owns the id, and proves it without a round trip

**Status:** accepted.

The opening `HELLO` carries the identity public key its peer id was derived from and is signed with
the matching private key. The relay checks the binding, the signature, a sixty-second freshness
window, and that the message id has not been used before.

**Why.** The relay used to register whatever id it was asked for. A peer id is public — it is the
thing you hand someone so they can message you — so anyone could connect as anyone. The real owner
then got `ID_TAKEN` on their next login, for as long as the squatter held the socket, and one
socket per victim would lock out every user whose id was known.

**Why a self-signed frame rather than a challenge.** A server nonce is the stronger construction
and it is what a fresh design should use: it binds the proof to this relay and this connection. It
also costs a round trip before a client can register, on a path that already waits for `HELLO_ACK`,
and it needs a new message type in a frozen protocol. The freshness window plus a remembered
message id closes replay against *this* relay, which is the attack that matters here. The gap it
leaves is a registration captured and replayed to a *different* relay inside the window, because
nothing in the signature names the relay it was meant for. That is the price, and it is written
down here rather than discovered later.

**The part that was nearly wrong.** `SignatureVerifier` exempts `HELLO` from requiring a signature,
because on the client-to-client path a `HELLO` is what introduces the key and there is nothing yet
to check it against. Leaning on that shared policy for registration accepted an unsigned frame —
which left matching the id as the only test, and the id is a hash of a public key that anybody can
hold. The relay demands the signature itself. The test written for the fix is what caught it.

## ADR-10 — Nonce uniqueness is a property of a key, not of a session

**Status:** accepted. Recorded because the obvious "fix" here is a regression.

The GCM nonce is `[direction:4][counter:8]`. The counter restarts at one whenever a session adopts
a new key, so **the same nonce is used again under every renewed key, deliberately**.

**Why that is safe.** What AES-GCM cannot survive is the same nonce twice under the same key. A
renewal is a fresh Diffie–Hellman exchange, so the key underneath the repeated counter is a
different key and no pair is ever reused. The direction bit — the side whose id sorts lower
transmits on 1 — is what keeps the two peers apart within one key, since they share it and both
count from zero.

**Why it is written down.** A reader who checks nonces for global uniqueness across a session's
lifetime will find repeats and will be tempted to stop the counter resetting. That breaks two
things at once: the send budget would never lift, so a renewal would not restore it; and the
receiver's replay window also restarts from one, so it would reject everything the peer sent after
a renewal. `IvReuseTest` asserts the pair of facts that make the repeat safe — the counter restarts
*and* the key changed — rather than the uniqueness property that sounds right and is not.

**How it is known to work.** The whole hundred-thousand-message budget is now exercised, in both
directions and across consecutive renewals. That the coverage is real was checked by mutation:
truncating the counter to sixteen bits fails the budget test and sails past the two-hundred-message
one it replaced.

## ADR-11 — The replay counter is bounded, but a forward leap is not capped

**Status:** accepted, departing from the audit's recommendation.

A received counter must lie in `[1, MAX_SENDS_PER_KEY]`. It is registered in the replay window only
after the frame decrypts.

**Why the bound.** Unbounded, one frame carrying `Long.MAX_VALUE` moved the window's high-water
mark there and put its floor beyond every counter a peer would ever send again. The conversation
was over, permanently, and nothing about it looked like an error. A sender's counter starts at one
and cannot pass the budget, so anything outside that range never came from an honest peer.

**Why the order matters.** Registering the counter before decrypting meant a frame of pure noise
still claimed its slot, so anything that could get a signed frame past the earlier checks could
spend counters the genuine traffic still needed. GCM authenticates as well as encrypts: deriving
the plaintext first means only something holding the session key can move the window.

**Why there is no leap cap.** The audit also proposed refusing a counter more than one window ahead
of the highest seen. That would break ordinary use. The relay does not queue for an absent peer, so
a sender's counter keeps climbing while the receiver sees nothing, and a gap far larger than the
window is normal after any outage — the cap would refuse everything sent afterwards, which is the
same permanent silence it was meant to prevent, arriving by a different route. The range bound
plus the decrypt-first ordering reduces the residual attack to "the peer you are talking to can
break their own session with you", which is not an escalation over that peer simply not talking.

## ADR-12 — A damaged peer key store stops the client rather than emptying it

**Status:** accepted.

Pinned peer keys are written by replacing the file, not overwriting it. On startup every stored
value is decoded back into a key, and a store that is present but damaged aborts startup.

**Why.** The old behaviour logged a warning and continued with no pinned keys. That reads like a
warning and behaves like a trust reset: every contact becomes a stranger, so the next `HELLO` from
someone known for months is trusted on sight, and the key-change alert — the one thing between a
user and an impostor — cannot fire because there is nothing left to compare against. An attacker
who can corrupt a file would get that for free. Refusing to start is recoverable; silently
re-trusting everyone is not.

**Why decoding each entry, not just catching the parse.** `Properties.load` reads ISO-8859-1, where
every byte sequence is legal text, so the common corruption — a write interrupted partway — parses
without complaint and yields entries that are merely wrong. Catching the exception guarded the rare
case and let the likely one through. The first version of this fix did exactly that, and its test
was what showed it.

**What it costs.** A user whose store is damaged cannot start the client until they restore a backup
or delete the file deliberately and re-verify every contact's safety number. The exception says so.

---

## Decisions still open

Recorded here so they are not mistaken for settled.

- **No forward secrecy beyond a single handshake, and no re-keying on a timer.** A key is replaced
  only once it has encrypted its budget of 100,000 messages, which an ordinary conversation will
  never reach. That renewal is a fresh Diffie-Hellman exchange rather than a ratchet, and it is not
  seamless: sending is refused while it is in flight. A ratchet is still the answer.
- **Desktop at-rest encryption covers message bodies only.** Participants, timestamps and message
  counts are in the clear in the local database.
- **Signatures give non-repudiation, not deniability.** Every message is signed with the sender's
  long-term RSA identity key over its ciphertext, id and timestamp. That is cryptographic proof to
  a third party that a specific identity sent a specific message at a specific time — the opposite
  of the off-the-record property a messenger is usually expected to have. Fixing it is not a patch:
  it means replacing the authentication model with a deniable exchange in the style of X3DH, where
  ephemeral keys authenticate and no long-term signature is left behind. A wire format change and a
  break with every existing peer.
- **The relay still learns who talks to whom.** Sender and receiver ids are in the routing header
  in the clear, message sizes are unpadded, and nothing is queued or mixed. Metadata privacy was
  never in scope and is not achieved by anything here.
