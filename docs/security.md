# Security

What Tetherless protects, how, and — more importantly — what it does not protect.

This document is written to be useful to someone deciding whether to trust the system. Where a
guarantee is backed by a test, the test is named. Where something is unfinished or deliberately out
of scope, it is stated plainly rather than omitted. **The limitations section is the part worth
reading most carefully.**

Status: pre-1.0, unaudited. No independent security review has been performed.

---

## 1. What the system is

Messages are encrypted on the sending device and decrypted on the receiving one. A relay server
routes ciphertext between clients. The relay is assumed hostile: it is expected to read everything
it can, alter anything it can, and lie about anything it can, and it should still learn nothing but
routing metadata.

| Component | Trust |
|---|---|
| `chat-desktop`, `chat-mobile` | Trusted. They hold keys and see plaintext. |
| `chat-server` (relay) | **Untrusted.** Holds no key material and never sees plaintext. |
| Transport between them | Untrusted. TLS is applied in addition to end-to-end encryption, not instead of it. |

---

## 2. Cryptography

| Purpose | Choice |
|---|---|
| Message encryption | AES-256-GCM, 96-bit nonce, 128-bit tag |
| Key agreement | Finite-field Diffie-Hellman, RFC 3526 MODP Group 14 (2048-bit) |
| Key derivation | HKDF-SHA256 (session keys) - PBKDF2-HMAC-SHA256, 210,000 iterations (desktop at-rest key) |
| Local database | SQLCipher whole-file on Android, keyed from `AndroidKeyStore`; column-level on desktop |
| Identity and signatures | RSA-2048, `SHA256withRSA` |
| Peer address | First 128 bits of SHA-256 over the X.509 identity public key |
| Transport | TLS 1.3, certificate-pinned |

Details that matter:

- **Nonces are counter-based**, `[direction:4][counter:8]`, not random. A random 96-bit nonce
  collides often enough to matter over a long session, and GCM does not survive nonce reuse. The
  direction bit is derived from the two peer ids so the two sides can never collide.
  (`IvReuseTest`)
- **The shared secret is left-padded** to the modulus length before derivation. Providers disagree
  about whether to strip a leading zero byte, which happens for about one secret in 256; without
  normalising, two platforms derive different keys and the handshake fails intermittently.
  (`CryptoVectorsTest.dhDerivationMatchesVectorWhenSecretHasLeadingZero`)
- **Peer DH public keys are validated** before use: rejected unless `1 < y < p-1` and
  `y^q mod p == 1`, which blocks small-subgroup attacks.
- **HKDF is checked against the published RFC 5869 vectors**, not against our own output, so a bug
  in the implementation cannot be baked in as the expected value.

---

## 3. Identity and trust

A peer's address is a function of their identity key: `PeerId = SHA-256(publicKey)[0..16]`, rendered
as 32 hex characters. It contains no name, so renaming yourself never changes where people reach
you, and a `HELLO` whose sender id is not the hash of the key it carries is rejected outright.

Trust is **trust-on-first-use**. The first key seen for a peer is pinned. If a different key later
appears for a known peer, the client does not accept it silently: it reports a key change and blocks
sending until the user re-verifies.

**Display names prove nothing.** They are self-asserted metadata carried in `HELLO`. Anyone may
claim any name, including one already in use. Only the peer id and the safety number identify who
you are talking to. Names are stripped of control characters and bidirectional overrides before
display, because a name is painted directly into the chat list and could otherwise forge the
appearance of surrounding interface text.

To verify a contact properly, compare safety numbers over a channel you already trust.

---

## 4. Threat model

Each threat lists the control and the test that exercises it.

### T1 — Passive relay operator

*The relay reads everything passing through.*

Message bodies are AES-256-GCM ciphertext under a key the relay never sees. Reply metadata travels
inside the ciphertext rather than in header fields, so the relay cannot even see who is quoting whom.

Tested: `AdversarialRelayTest.plaintextNeverAppearsInInterceptedTraffic`.

### T2 — Active relay operator (machine-in-the-middle)

*The relay substitutes keys to insert itself into a session.*

The key exchange is signed with long-term identity keys, so a substituted Diffie-Hellman key cannot
be re-signed. Substituting the identity key inside `HELLO` fails against the id-to-key binding
before anything is stored.

Tested: `relayCannotSubstituteDhKeysDuringTheExchange`,
`relayCannotSubstituteTheIdentityKeyInHello`, `aChangedIdentityKeyIsReportedRatherThanAccepted`.

These are validated by mutation, not merely by being green: disabling the binding check or ignoring
signature-verification failures makes the corresponding tests fail.

### T3 — Network attacker

*Someone between client and relay.*

TLS 1.3 with a pinned certificate. This protects metadata and connection integrity; it is **not**
what protects message content, which is already encrypted before it reaches the socket.

### T4 — Replay and reordering

*Captured frames re-sent, or delivered out of order.*

Each message carries a monotonic counter inside its nonce, tracked per session in a 1024-entry
sliding window, so a replay is rejected while genuine reordering still delivers. Timestamps more
than five minutes from local time are rejected.

Tested: `replayingACapturedMessageIsRejected`, `reorderedMessagesAreStillDelivered`.

### T5 — Malicious peer payload

*A peer sends something hostile.*

The wire format is an explicit length-prefixed binary codec with per-field caps, not Java
serialization — deserializing untrusted data with `readObject` is a remote-code-execution class of
bug and the type is not `Serializable` at all. Frames are rejected before allocation if they declare
an implausible length.

Tested: the codec fuzz tests, plus `ProtocolVectorsTest` for exact field round-tripping.

### T6 — Device compromise

*Someone has the device.*

Partially addressed, and still the weakest area. Both clients now encrypt their message database,
and identity keys are non-exportable on Android, so the files alone are not enough. But neither
client protects a live compromised process, and neither offers post-compromise recovery.

---

## 5. Limitations — what this does **not** protect against

This section is the honest core of the document.

### No forward secrecy beyond a single handshake

One Diffie-Hellman exchange derives one key for the life of a session. **Compromising a session key
exposes every message in that session**, and compromising an identity key allows impersonation from
that point on. There is no Double Ratchet, no per-message key evolution, and no post-compromise
recovery. Session keys are held in memory only and are lost on restart, which limits the window but
is not a substitute.

### No metadata privacy from the relay

The relay sees, and could log indefinitely: who talks to whom, when, how often, and the size of
every message. Message sizes are not padded, so lengths leak. Typing notifications and read receipts
are signed but not encrypted — they carry no content, but they do tell the relay you are active. If
the relay operator is the adversary you care about, **the social graph is not protected**.

### At-rest encryption on mobile is device-bound

The Android database is SQLCipher-encrypted as a whole file, so unlike desktop's column-level
scheme the participants, timestamps and message counts are covered too. A random 256-bit key is
generated once and wrapped with an AES key held in `AndroidKeyStore` — in a secure element where the
hardware provides one — so only the wrapped blob is written to preferences.

Two consequences follow. The history is **bound to this device and this install**: copying the files
off, or pulling them from a backup, yields nothing usable, and an uninstall destroys the history
permanently. And there is nothing to type, so **an attacker who can already run code as this app on
an unlocked device can ask the keystore to unwrap for them**. This protects the files at rest, not a
live compromised process.

Tested: `DatabaseEncryptionTest` writes a known string, then reads the raw database and its
write-ahead log back off the device and asserts the string, the peer ids, and the `SQLite format 3`
header are all absent — configuring SQLCipher and assuming it took effect is exactly the kind of
claim that turns out to be false. It also checks the wrong key cannot open the file and that the
unwrapped key never reaches preferences.

### At-rest encryption on desktop is partial

Only message bodies and quoted reply previews are encrypted. Participants, timestamps and message
counts remain visible in the database file.

The key is derived with PBKDF2-HMAC-SHA256 at 210,000 iterations over a random per-profile salt, so
an attacker holding the file pays that cost for every passphrase guess. **The strength of this rests
entirely on the passphrase**: no iteration count rescues a guessable one.

The salt and iteration count are stored in `profile.properties`, so the count can be raised later
for new profiles without making existing databases unreadable.

### Session expiry is not implemented

`Session.State.EXPIRED` exists and is never set. There is no TTL and no message-count cap forcing
re-keying, so a long-lived session keeps one key indefinitely. The nonce counter is 64-bit and will
not realistically wrap, but the absence of re-keying compounds the forward-secrecy limitation above.

### No multi-device, no groups, no attachments

One identity per device. A second device is a different peer with a different id. There is no group
messaging and no file transfer.

### The relay is a single point of failure

Despite "decentralized" in the project description, the current architecture routes everything
through one relay. It cannot read messages, but it can **deny service**, and it observes all
metadata. Federation or peer discovery is not implemented.

### Development certificate

In development the relay uses a self-signed certificate from `scripts/generate-dev-cert.sh`, pinned
by clients. Its private key is reproducible by anyone who runs that script, so it provides no
protection against interception whatsoever.

A **packaged build refuses to fall back to it.** `TlsSupport` consults `BuildInfo`, which reads a
`channel` stamp written at build time; when the stamp says `release` and no truststore is
configured, the client fails with an error naming the setting to fix rather than connecting
insecurely. Anything unrecognised or missing in the stamp reads as `dev`, so a corrupted stamp
cannot promote an artifact into being treated as packaged. The packaging tasks themselves refuse to
run without `-PreleaseBuild`, which is what writes the release stamp.

The relay is held to the same rule. `dev-keystore.p12` was its default keystore path and was
packaged into its jar, so an unconfigured relay would have *served* that key. A packaged relay now
refuses to start unless a keystore is configured, and the development one is excluded from both the
fat JAR and the Docker image.

A deployment supplies its own certificate through `truststore` in `config.properties`, the
`tetherless.truststore` system property, or `TETHERLESS_TRUSTSTORE`. A configured-but-unreadable
truststore is a hard failure, never a silent fallback. See [deployment.md](deployment.md).

Tested: `TlsSupportTest` and `ServerConfigTest` each load a second copy of the classes through an
isolated class loader with a fabricated release stamp and assert the refusal, because that branch
cannot otherwise be reached from a test running in a development build. Both were checked by
removing the guard and confirming that exactly the corresponding test fails.

The truststore password defaults to the well-known `changeit`. That is acceptable: it protects a
file of public certificates, guarding integrity rather than confidentiality. The relay's *keystore*
password, which protects a private key, is a different matter and must be set.

### Not audited

No independent review. The tests described here were written by the same effort that wrote the code,
which is a real limitation regardless of how thorough they are.

---

## 6. What an attacker gains at each level

| Attacker | Can | Cannot |
|---|---|---|
| Network observer | See that you contact the relay | Read messages, identify peers |
| Relay operator | See the social graph, timing, message sizes; deny service | Read or forge messages, join a session |
| Peer you talk to | Read what you send them; claim any display name | Impersonate a third party to you |
| Device thief (Android) | **Read your entire message history** | Extract the identity key |
| Device thief (desktop) | See who you talked to and when; attack the passphrase offline at 210,000 PBKDF2 iterations per guess | Read message bodies or use the identity key without the passphrase |

---

## 7. Key storage

- **Desktop:** identity in a PKCS#12 keystore, unlocked by a passphrase. The keystore file is
  protected by the JCE provider's own password-based encryption at its default parameters; the
  project does not configure an iteration count of its own.
- **Android:** identity generated in `AndroidKeyStore`, hardware-backed where available. The private
  key is **non-exportable**, so it cannot be extracted — and equally cannot be backed up.
  Uninstalling the app destroys the identity permanently, and every contact will see a key change.
- **Session keys:** memory only, never written to disk, discarded on restart.

---

## 8. Reporting a vulnerability

Please report privately rather than opening a public issue, and allow time for a fix before
disclosure. Include what you did, what happened, and what you expected. Findings that come with a
failing test against `MaliciousRelay` are especially welcome.

Contact: the repository owner via GitHub.

---

## 9. History

Real issues found and fixed during development, recorded because a security document that lists only
successes is not informative:

- **Nonce reuse across directions.** Both peers built their GCM nonce with the same direction bit
  while sharing one key, so each side's n-th message reused a key/nonce pair — which breaks GCM
  outright. Found while writing this document, by checking a claim rather than assuming it. Fixed
  by deriving the direction from the peer ids; pinned by `IvReuseTest`.
- **Peer ids carried the display name**, so renaming yourself changed your address and made you
  unreachable. They are now derived from the identity key alone.
- **Peer ids were 28 bits** of fingerprint, colliding around 16,000 identities — and because the
  relay rejects duplicate ids, a collision would lock a user out of their own account. Now 128 bits.
- **The desktop at-rest key was derived with HKDF**, which has no work factor and let an attacker
  holding the database guess passphrases at hash speed. Now PBKDF2-HMAC-SHA256 at 210,000
  iterations, with existing databases re-encrypted in place on first launch.
- **The relay's private key shipped inside its own jar**, and `dev-keystore.p12` was also the
  default keystore path - so an operator who simply started the server would have served TLS with a
  keypair anyone can regenerate. Found while packaging it, by looking in the jar rather than
  assuming. The relay now refuses to start in a release build unless a keystore is configured, and
  the development one is excluded from both the fat JAR and the Docker image.
- **A packaged client would have trusted the development certificate**, whose private key anyone can
  regenerate from a script in this repository - TLS in name only. Release builds now refuse to
  connect unless a truststore is configured, verified against the actual packaged artifact rather
  than only in a test.
- **The Android message database was plaintext**, readable by anything with access to the app's data
  directory. Confirmed by grepping message bodies straight out of the file on a running emulator,
  not inferred from the code. Now SQLCipher-encrypted whole-file with a key wrapped in
  `AndroidKeyStore`; existing history is migrated across on first launch.
- **Nothing bound a peer id to its key**, so on first contact a peer could claim someone else's
  address and have their own key trusted against it. Now checked on every `HELLO`.
