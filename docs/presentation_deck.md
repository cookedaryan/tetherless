# Tetherless presentation deck

## Current edition: 10 concise slides

- [PowerPoint](tetherless_presentation_concise.pptx)
- [Matching interactive slide PDF](tetherless_presentation_concise.pdf)
- [Five-person speaking guide PDF](speech_deck_5_person_concise.pdf)
- [Editable speaking guide](speech_deck_5_person_concise.md)

The PDF and PowerPoint use the same slide content and layout. Chapter links and previous/next controls provide navigation; the closing slide links to source specifications. PowerPoint diagrams and text are editable. The original 15-slide files and older speaking guide are retained as historical source material.

| Slide | Title | Speaker |
|---|---|---|
| 01 | Tetherless | 1 |
| 02 | Privacy lives at the endpoints | 1 |
| 03 | A handshake creates the session key | 2 |
| 04 | Every message needs a unique nonce | 2 |
| 05 | Verify the person behind the key | 3 |
| 06 | Encryption has clear boundaries | 3 |
| 07 | Slow clients get separate queues | 4 |
| 08 | One core, two client experiences | 4 |
| 09 | Security tests target failure modes | 5 |
| 10 | Ship a focused, reviewable MVP | 5 |

## Slide summaries

### 01. Tetherless

Messages are encrypted and decrypted by the clients. The relay forwards ciphertext. The current design uses a central relay, while end-to-end encryption keeps message content at the endpoints.

Sources: docs/security.md

### 02. Privacy lives at the endpoints

Follow desktop → relay → Android. Each client owns its plaintext and E2EE keys. core-shared supplies the crypto, binary codec and session state. Pinned TLS adds protection on each client-to-relay connection.

Sources: settings.gradle, docs/protocol.md

### 03. A handshake creates the session key

Authenticate the identity, exchange validated Diffie-Hellman values, normalize the shared secret, then use HKDF-SHA256 to derive an AES-256-GCM key. These are separate responsibilities in one session setup.

Sources: core-shared/src/main/java/com/e2eechat/core/crypto/DHUtils.java, docs/protocol.md, https://www.rfc-editor.org/rfc/rfc5869, https://www.rfc-editor.org/rfc/rfc3526

### 04. Every message needs a unique nonce

The nonce contains four direction bytes and eight counter bytes. Peer-ID ordering gives the two senders opposite directions. Counters advance within a key lifetime. A 100,000-send budget triggers renewal, while replay checks reject duplicates and stale counters.

Sources: core-shared/src/main/java/com/e2eechat/core/session/Session.java, core-shared/src/main/java/com/e2eechat/core/session/SessionManager.java, core-shared/src/main/java/com/e2eechat/core/session/SecureChat.java, https://csrc.nist.gov/pubs/sp/800/38/d/final

### 05. Verify the person behind the key

A peer ID binds an address to a public key. Trust on first use remembers that key. To connect the key to a person, compare the safety number through a trusted separate channel. A key change blocks sending until it is reviewed.

Sources: docs/security.md

### 06. Encryption has clear boundaries

Content protection does not hide the communication graph, guarantee relay availability, or secure a compromised live device. The design renews session keys, but does not include a per-message ratchet. Groups and multi-device sync remain outside the current scope.

Sources: docs/security.md

### 07. Slow clients get separate queues

Each recipient has a dedicated writer and a bounded 256-frame queue. A slow recipient cannot block another writer. Overflow closes the affected connection. Conditional registry removal protects reconnects; heartbeats detect dead peers. Frame lengths are checked before allocation.

Sources: docs/protocol.md, docs/adr.md

### 08. One core, two client experiences

Desktop uses Swing with work off the event dispatch thread, SQLite WAL and encrypted fields. Android uses Room, SQLCipher and KeyStore protection. Both share the same protocol and crypto. Release configuration requires an external TLS keystore and restricted runtime privileges.

Sources: docs/deployment.md, docs/security.md

### 09. Security tests target failure modes

The code defines 13 adversarial test methods, 20,000 fuzz iterations and 17 wire vectors. These cover attacks, malformed inputs and stable encoding. They describe test coverage, not a fresh passing run. Current CI results and security review remain the evidence to present before release.

Sources: core-shared/src/test/java/com/e2eechat/core/session/AdversarialRelayTest.java, core-shared/src/test/java/com/e2eechat/core/protocol/CodecFuzzTest.java, core-shared/src/main/java/com/e2eechat/core/protocol/ProtocolVectors.java, core-shared/src/test/java/com/e2eechat/core/session/IvReuseTest.java

### 10. Ship a focused, reviewable MVP

Deliver the relay and Windows desktop through a sequence of tests, CI checks, security review, packaging and manual QA. Future federation, group messaging and ratcheting need separate protocol work. Close with the principle: private messages, explicit limits, and evidence before release.

Sources: docs/development_plan.md, docs/qa_script.md

## Accuracy notes

- “Relay-based” replaces the older “decentralized” description for the current topology.
- Nonce direction follows peer-ID ordering; it is not assigned by handshake initiator/responder role.
- 100,000 is the configured send budget per key. The current IvReuseTest checks 20 pairs of opposite-direction messages and 200 messages from one sender, not 100,000 sends.
- 20,000 fuzz iterations comprise two loops of 10,000, not 20,000 single-bit mutations.
- Test coverage and release sequencing are separated from claims of current test execution or production approval.
