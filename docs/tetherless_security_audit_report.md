# Tetherless — Security & Engineering Audit Report

---

## 1. Executive Summary

Tetherless exhibits commendable engineering discipline in its cryptographic foundations: message bodies are protected with AES-256-GCM, DH keys are exchanged over RFC 3526 Group 14 with subgroup validation and left-padding normalisation, and the wire format relies on an explicit binary codec rather than Java deserialisation. However, the end-to-end security guarantee is undermined by several critical protocol and implementation vulnerabilities: inbound frames are never validated against the recipient’s own client ID, signatures on control frames (`DELIVERY_ACK`, `READ_RECEIPT`, `TYPING`, `KEY_EXCHANGE_REJECT`) are completely bypassed when the signature is omitted, inbound GCM nonce direction bits are never verified, and TLS hostname verification is disabled on raw socket handshakes. Furthermore, unauthenticated relay registration permits trivial identity squatting and denial-of-service, and a null payload on acknowledgement frames crashes the client reader loop. In its current state, Tetherless is **not ready for production or hostile deployments** until these fundamental authentication, routing, and lifecycle defects are resolved.

---

## 2. Findings Report

Findings are grouped by audit area (§3.1–§3.7) and ranked by severity: **Critical**, **High**, **Medium**, **Low**.

---

### §3.1 Cryptography & Protocol

#### Finding 3.1.1 — Inbound Receiver ID Is Never Validated Across the Client Stack
- **Location:** [`core-shared/src/main/java/com/e2eechat/core/session/SessionManager.java:42-113`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SessionManager.java#L42-L113), [`core-shared/src/main/java/com/e2eechat/core/session/SecureChat.java:189-220`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SecureChat.java#L189-L220), [`chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java:223-289`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java#L223-L289)
- **What is wrong:** When a client receives a frame, neither [`SessionManager`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SessionManager.java), [`SecureChat`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SecureChat.java), nor [`ChatClient`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java) checks whether `msg.getReceiverId()` equals the local device's `clientId`.
- **Concrete failure scenario:** 
  1. Bob sends a valid `KEY_EXCHANGE_INIT` or unencrypted control frame (`DELIVERY_ACK`, `READ_RECEIPT`) intended for Charlie (`receiverId = Charlie`, `senderId = Bob`).
  2. A malicious or misconfigured relay forwards this frame to Alice (`clientId = Alice`).
  3. Alice verifies Bob's signature against `msg.canonicalBytesForSigning()` (which passes, as Bob genuinely signed `receiverId = Charlie`).
  4. Alice's client does not verify that `msg.getReceiverId()` matches Alice, processes the frame under Alice's session with Bob, advances Alice's handshake or marks Alice's messages as delivered/read, and can trigger cross-session handshake collision or state corruption.
- **Why it matters:** Message recipient binding is a fundamental invariant of any secure messaging protocol. Failing to enforce it permits cross-session reflection, misrouting attacks, and state confusion.
- **How to fix:** In [`SessionManager.onMessage`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SessionManager.java#L42), add a strict assertion rejecting any frame where `msg.getReceiverId() != null && !localClientId.equals(msg.getReceiverId())`.
- **Severity:** Critical
- **Confidence:** Confirmed

---

#### Finding 3.1.2 — Signature Verification Completely Bypassed on Control & Ack Frames When Signature Is Absent
- **Location:** [`core-shared/src/main/java/com/e2eechat/core/protocol/SignatureVerifier.java:19-27`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/protocol/SignatureVerifier.java#L19-L27)
- **What is wrong:** In [`SignatureVerifier.verify()`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/protocol/SignatureVerifier.java#L19):
  ```java
  boolean requiresSignature = (msg.getType() == MessageType.TEXT_MESSAGE || 
                               msg.getType() == MessageType.KEY_EXCHANGE_INIT || 
                               msg.getType() == MessageType.KEY_EXCHANGE_REPLY);
                               
  if (msg.getSignature() == null || msg.getSignature().length == 0) {
      return requiresSignature ? VerificationResult.MISSING_SIGNATURE : VerificationResult.VALID;
  }
  ```
  If `msg.getSignature()` is null or empty, `SignatureVerifier` returns `VerificationResult.VALID` for any message type other than `TEXT_MESSAGE`, `KEY_EXCHANGE_INIT`, and `KEY_EXCHANGE_REPLY`.
- **Concrete failure scenario:** 
  1. An active relay operator fabricates an unsigned frame with `type = DELIVERY_ACK`, `senderId = Bob`, `receiverId = Alice`, `payload = <messageId>`, and `signature = null`.
  2. Alice receives the frame. [`SessionManager.onMessage`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SessionManager.java#L60) invokes [`SignatureVerifier.verify`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/protocol/SignatureVerifier.java#L19), which returns `VALID`.
  3. [`ChatClient.onDelivered`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java#L284) executes `messageRepository.updateStatus(messageId, Status.DELIVERED)`.
  4. Alice sees two checkmarks indicating Bob received the message, when Bob never received it. A hostile relay can similarly forge `READ_RECEIPT` (falsely marking conversations read), spoof `TYPING`, or send `KEY_EXCHANGE_REJECT` to terminate handshakes without holding Bob's private key.
- **Why it matters:** Directly contradicts [`docs/protocol.md §5`](file:///c:/Users/aryan/Projects/tetherless/docs/protocol.md#L91) and [`docs/security.md §5`](file:///c:/Users/aryan/Projects/tetherless/docs/security.md#L184), which claim all control frames are signed. Destroys peer authentication for message delivery and receipt status.
- **How to fix:** Include all inter-peer control types (`DELIVERY_ACK`, `READ_RECEIPT`, `TYPING`, `KEY_EXCHANGE_REJECT`) in `requiresSignature`.
- **Severity:** Critical
- **Confidence:** Confirmed

---

#### Finding 3.1.3 — Nonce Direction Bit Is Ignored on Inbound Messages
- **Location:** [`core-shared/src/main/java/com/e2eechat/core/session/SessionManager.java:96-102`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SessionManager.java#L96-L102)
- **What is wrong:** [`SessionManager`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SessionManager.java#L97) parses `int direction = bb.getInt();` from the 12-byte IV, but never verifies `direction` against the expected peer direction bit.
- **Concrete failure scenario:** 
  1. Alice (`lowSide = true`, transmitting on `direction = 1`) sends message $M$ to Bob.
  2. If an adversary reflects Alice's message or if a corrupted/malicious peer sends frames with `direction = 1` rather than `direction = 0`, [`SessionManager`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SessionManager.java) ignores the mismatch.
  3. The unverified counter is fed into `session.registerReceivedCounter(counter)` in [`SessionManager.java:100`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SessionManager.java#L100), consuming a counter slot in the receiver's replay window regardless of the direction bit used.
- **Why it matters:** The direction bit was explicitly designed to prevent GCM nonce collision between peers sharing a single key ([`docs/protocol.md §9`](file:///c:/Users/aryan/Projects/tetherless/docs/protocol.md#L248)). Failing to enforce the expected direction bit on the receive path permits direction-confusion and cross-direction counter exhaustion.
- **How to fix:** In [`SessionManager.onMessage`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SessionManager.java#L97), compute `int expectedDirection = (localClientId.compareTo(msg.getSenderId()) > 0) ? 1 : 0;` and drop the frame if `direction != expectedDirection`.
- **Severity:** High
- **Confidence:** Confirmed

---

#### Finding 3.1.4 — TLS Hostname Verification Is Disabled on Client Sockets
- **Location:** [`core-shared/src/main/java/com/e2eechat/core/network/TlsSupport.java:235-241`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/network/TlsSupport.java#L235-L241), [`core-shared/src/main/java/com/e2eechat/core/network/ConnectionManager.java:228-231`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/network/ConnectionManager.java#L228-L231)
- **What is wrong:** Sockets are created via `(SSLSocket) factory.createSocket(host, port)` with TLSv1.3 enabled, but `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` is never called. Standard JSSE `SSLSocket` instances do **not** perform hostname verification against Subject Alternative Names (SAN) or CN by default.
- **Concrete failure scenario:** 
  1. An organization deploys a relay using a certificate from a trusted CA (e.g., Let's Encrypt, as instructed in [`docs/deployment.md §2`](file:///c:/Users/aryan/Projects/tetherless/docs/deployment.md#L40) and [`docs/tls_provisioning.md`](file:///c:/Users/aryan/Projects/tetherless/docs/tls_provisioning.md#L40)).
  2. The client is configured with a truststore pinning the Let's Encrypt Root CA.
  3. A network attacker on the client's local network (or upstream DNS) intercepts traffic to `relay.example.org` and presents any valid certificate issued by Let's Encrypt for `attacker-relay.com`.
  4. Because endpoint identification is unset, the client verifies that the certificate chains to Let's Encrypt, ignores that the domain does not match `relay.example.org`, and establishes the TLS connection to the attacker's proxy.
  5. Furthermore, this directly contradicts [`docs/deployment.md:64-65`](file:///c:/Users/aryan/Projects/tetherless/docs/deployment.md#L64-L65), which claims: *"A certificate without a matching SAN fails the handshake regardless of pinning."*
- **Why it matters:** CA-pinned configurations are vulnerable to MITM interception by anyone possessing any certificate issued by the same CA.
- **How to fix:** Configure `SSLParameters params = socket.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS"); socket.setSSLParameters(params);` before starting the TLS handshake.
- **Severity:** High
- **Confidence:** Confirmed

---

#### Finding 3.1.5 — Replay Window Denial-of-Service via Counter Jump & Non-Positive Counter Acceptance
- **Location:** [`core-shared/src/main/java/com/e2eechat/core/session/Session.java:115-127`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/Session.java#L115-L127)
- **What is wrong:** In [`Session.registerReceivedCounter(long counter)`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/Session.java#L115):
  ```java
  if (counter <= highestReceivedCounter - REPLAY_WINDOW) {
      return false;
  }
  if (!receivedCounters.add(counter)) {
      return false;
  }
  if (counter > highestReceivedCounter) {
      highestReceivedCounter = counter;
      forgetCountersBelowTheWindow();
  }
  return true;
  ```
  The counter is not checked for `counter > 0` or bounded by the session send budget (`MAX_SENDS_PER_KEY = 100000`). When `highestReceivedCounter == 0`, negative counters and counter 0 satisfy `counter <= -1024 == false` and are accepted. More critically, if a peer transmits a validly signed message with counter `Long.MAX_VALUE`, `highestReceivedCounter` jumps to `Long.MAX_VALUE`.
- **Concrete failure scenario:** An attacker (or compromised peer) injects a single validly signed message with `counter = Long.MAX_VALUE`. The recipient updates `highestReceivedCounter = Long.MAX_VALUE`. Thereafter, `floor = Long.MAX_VALUE - 1024`. All legitimate subsequent messages with normal counters ($1, 2, 3 \dots$) satisfy `counter <= floor` and are discarded permanently, completely bricking the session.
- **Why it matters:** Allows permanent denial of service against a session using a single out-of-order counter jump.
- **How to fix:** Enforce `if (counter <= 0 || counter > MAX_SENDS_PER_KEY) return false;` and restrict maximum forward counter leap (`counter > highestReceivedCounter + REPLAY_WINDOW`).
- **Severity:** Medium
- **Confidence:** Confirmed

---

#### Finding 3.1.6 — Non-Atomic Peer Key Storage & Silent Loss of Pinning on Parsing Failure
- **Location:** [`core-shared/src/main/java/com/e2eechat/core/keys/JceKeyStoreManager.java:47-54, 110-116`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/keys/JceKeyStoreManager.java#L47-L54)
- **What is wrong:** In [`JceKeyStoreManager.storePeerKey`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/keys/JceKeyStoreManager.java#L113), the file `peers.properties` is overwritten directly using `new FileOutputStream(peersFile)`. If the JVM terminates mid-write, the file is corrupted or zeroed. On the next startup (lines 47–54), if reading `peers.properties` throws an exception, it logs `Could not read the stored peer keys... starting with none` and initializes an empty `peerProperties`.
- **Concrete failure scenario:** A power failure or process crash occurs during `storePeerKey()`. On restart, `peers.properties` fails to parse. The client starts with an empty peer store. When known contacts subsequently reconnect and send `HELLO`, because `known.isPresent()` is false, the client silently re-trusts their presented keys on first use (TOFU) via [`SecureChat.onHello`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SecureChat.java#L236) instead of raising `PEER_KEY_CHANGED`. If an active MITM occurs during this window, the impostor's key is pinned without any warning.
- **Why it matters:** Silent reset of trust stores destroys the core TOFU guarantee.
- **How to fix:** Write to a temporary file (`peers.properties.tmp`) and atomically move it (`Files.move(..., ATOMIC_MOVE)`). If the file exists but fails to load, fail closed (abort startup or alert user) rather than silently discarding all pinned keys.
- **Severity:** Medium
- **Confidence:** Confirmed

---

#### Finding 3.1.7 — Sensitive In-Memory DH Key Material Not Zeroed in Heap
- **Location:** [`core-shared/src/main/java/com/e2eechat/core/crypto/DHUtils.java:78-91`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/crypto/DHUtils.java#L78-L91)
- **What is wrong:** `byte[] rawSecret = keyAgreement.generateSecret();` and `byte[] paddedSecret` in [`DHUtils.generateSharedSecret`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/crypto/DHUtils.java#L78) are discarded without calling `Arrays.fill(..., (byte) 0)`.
- **Concrete failure scenario:** The un-zeroed 2048-bit Diffie-Hellman shared secret remains in JVM heap memory until collected and overwritten by garbage collection. In a multi-tenant process, crash dump, or heap inspection scenario, raw shared secrets can be recovered.
- **Why it matters:** Incomplete memory hygiene for ephemeral master secrets.
- **How to fix:** Enclose derivation in a `try ... finally` block and execute `Arrays.fill(rawSecret, (byte) 0)` and `Arrays.fill(paddedSecret, (byte) 0)`.
- **Severity:** Medium
- **Confidence:** Confirmed

---

#### Finding 3.1.8 — Non-Repudiation vs. Deniability Trade-off in Protocol Design
- **Location:** [`core-shared/src/main/java/com/e2eechat/core/models/Message.java:34-58`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/models/Message.java#L34-L58), [`core-shared/src/main/java/com/e2eechat/core/protocol/MessageSigner.java:11-30`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/protocol/MessageSigner.java#L11-L30)
- **What is wrong:** Every text message is signed with the sender's long-term RSA identity private key (`SHA256withRSA`) over canonical bytes containing the timestamp, message ID, sender ID, receiver ID, and ciphertext.
- **Concrete failure scenario:** A recipient (or anyone obtaining the recipient’s decrypted database and the signed wire frames) can mathematically prove to a third party (e.g., in a legal or public context) that the specific sender identity signed that exact message at that timestamp. This provides strict cryptographic **non-repudiation**, which is the antithesis of **off-the-record deniability** (as provided by Signal’s X3DH / Double Ratchet using MAC subkeys).
- **Why it matters:** Architectural mismatch for users expecting plausible deniability in an end-to-end encrypted chat application.
- **How to fix:** Transition to a deniable authenticated key exchange (e.g., Signal-style X3DH with ephemeral Diffie-Hellman authenticator keys rather than long-term RSA digital signatures on individual chat payloads).
- **Severity:** Low
- **Confidence:** Confirmed

---

#### Finding 3.1.9 — Dead Code: Orphaned and Incomplete `SessionKeyCache` Class
- **Location:** [`core-shared/src/main/java/com/e2eechat/core/keys/SessionKeyCache.java:1-26`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/keys/SessionKeyCache.java#L1-L26)
- **What is wrong:** [`SessionKeyCache`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/keys/SessionKeyCache.java) is listed in documentation and project audit prompt anchors, but is never referenced, instantiated, or used by any class across `core-shared`, `chat-server`, or `chat-desktop`.
- **Concrete failure scenario:** Maintenance overhead and false assumption by auditors that key caching logic is managed by this class, whereas [`SessionManager`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SessionManager.java) directly maintains sessions.
- **Why it matters:** Incomplete architecture and dead code cause confusion for security reviewers and increase maintenance debt.
- **How to fix:** Remove the class or integrate it into `SessionManager`.
- **Severity:** Low
- **Confidence:** Confirmed

---

### §3.2 Relay / Server

#### Finding 3.2.1 — Unauthenticated Client Registration Permitting Trivial DoS & Peer ID Squatting
- **Location:** [`chat-server/src/main/java/com/e2eechat/server/ClientSession.java:202-221`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/ClientSession.java#L202-L221), [`core-shared/src/main/java/com/e2eechat/core/network/ConnectionManager.java:246-253`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/network/ConnectionManager.java#L246-L253)
- **What is wrong:** In [`ClientSession.run`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/ClientSession.java#L202), when the relay receives the opening `HELLO`, it extracts `clientId = message.getSenderId()` and calls `registry.register(clientId, this)`. The registration `HELLO` is unsigned ([`ConnectionManager.java:252`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/network/ConnectionManager.java#L252) uses `buildUnsigned()`), carries no public key, and includes no proof of possession of the private key matching the `PeerId`.
- **Concrete failure scenario:** 
  1. An attacker determines Alice’s public 32-character hexadecimal `PeerId`.
  2. The attacker connects to the relay over TLS and sends a 28-byte raw frame containing `HELLO` with `senderId = Alice` and `payload = null`.
  3. The relay registers the attacker's socket under `Alice`.
  4. When the legitimate Alice launches her client and attempts to connect, the relay finds `Alice` in [`ClientRegistry`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/ClientRegistry.java#L26), rejects Alice's connection with `ID_TAKEN`, and disconnects her socket.
  5. The attacker can script this for all known peer IDs, permanently locking all legitimate users out of the relay with minimal resources.
- **Why it matters:** Severe denial-of-service and identity squatting on the relay.
- **How to fix:** Require the registration `HELLO` to carry a challenge response, nonce signature, or self-signed payload proving possession of the private key whose public key hashes to `PeerId`.
- **Severity:** High
- **Confidence:** Confirmed

---

#### Finding 3.2.2 — Relay Routes Frames Without Verifying Sender Identity
- **Location:** [`chat-server/src/main/java/com/e2eechat/server/ClientSession.java:238-240, 258, 272-296`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/ClientSession.java#L238-L240)
- **What is wrong:** In [`ClientSession.java:258`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/ClientSession.java#L258), once a session is established, `routeFrame(message.getReceiverId(), frame)` forwards the raw encoded bytes without checking if `message.getSenderId().equals(this.clientId)`.
- **Concrete failure scenario:** A connected client authenticated with `clientId = Mallory` sends a frame with `senderId = Alice` and `receiverId = Bob`. Combined with Finding 3.1.2 (where `DELIVERY_ACK`, `READ_RECEIPT`, and `TYPING` do not require signatures), Mallory can directly impersonate Alice’s acknowledgements and typing events through the relay to Bob.
- **Why it matters:** The relay fails to enforce sender authenticity at the routing boundary.
- **How to fix:** In `ClientSession.run()`, before calling `routeFrame`, assert `if (!this.clientId.equals(message.getSenderId())) { disconnect(); return; }`.
- **Severity:** High
- **Confidence:** Confirmed

---

#### Finding 3.2.3 — Prometheus Metrics Server Binds Wildcard (`0.0.0.0`) by Default
- **Location:** [`chat-server/src/main/java/com/e2eechat/server/MetricsServer.java:25`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/MetricsServer.java#L25), [`chat-server/src/main/java/com/e2eechat/server/ChatServer.java:52, 59`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/ChatServer.java#L52)
- **What is wrong:** In [`MetricsServer.start()`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/MetricsServer.java#L25), `HttpServer.create(new InetSocketAddress(port), 0)` binds to the wildcard interface (`0.0.0.0`), exposing `/metrics` over unauthenticated HTTP on all network interfaces.
- **Concrete failure scenario:** An operator runs `tetherless-relay` on a public VPS without a local firewall. Anyone on the internet connecting to `http://<relay-ip>:<PORT+1>/metrics` can read real-time operational telemetry: active client count, messages routed, buffer overflows, and rejection counters. This contradicts [`docs/deployment.md:142-144`](file:///c:/Users/aryan/Projects/tetherless/docs/deployment.md#L142-L144), which warns that metrics must not be accessible externally.
- **Why it matters:** Unauthenticated information disclosure of operational and traffic patterns.
- **How to fix:** Bind explicitly to loopback: `new InetSocketAddress(InetAddress.getLoopbackAddress(), port)`, or expose a `METRICS_HOST` configuration property defaulting to `127.0.0.1`.
- **Severity:** Medium
- **Confidence:** Confirmed

---

#### Finding 3.2.4 — Hardcoded Default Keystore Password in ServerConfig
- **Location:** [`chat-server/src/main/java/com/e2eechat/server/ServerConfig.java:25, 28, 63-65`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/ServerConfig.java#L25)
- **What is wrong:** [`ServerConfig.java`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/ServerConfig.java#L25) defines `static final String DEFAULT_KEYSTORE_PASSWORD = "changeit";` as a literal, and does not synchronize with `TlsSupport.trustStorePassword()`.
- **Concrete failure scenario:** If a developer sets `TETHERLESS_TRUSTSTORE_PASSWORD` in the environment as instructed by [`README.md:75`](file:///c:/Users/aryan/Projects/tetherless/README.md#L75), [`ServerConfig`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/ServerConfig.java) does not read that variable. It falls back to literal `"changeit"`, failing to start if the keystore was generated with that password.
- **Why it matters:** Inconsistency between client/server configuration and reliance on hardcoded password literals.
- **How to fix:** Refactor [`ServerConfig`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/ServerConfig.java) to delegate fallback password retrieval to [`TlsSupport.trustStorePassword()`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/network/TlsSupport.java#L87).
- **Severity:** Low
- **Confidence:** Confirmed

---

### §3.3 Client Correctness

#### Finding 3.3.1 — Unguarded Message Status Transition Allows Status Regression from `READ` to `DELIVERED`
- **Location:** [`chat-desktop/src/main/java/com/e2eechat/desktop/MessageRepository.java:87-100`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/MessageRepository.java#L87-L100), [`chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java:279-287`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java#L279-L287)
- **What is wrong:** In [`MessageRepository.updateStatus`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/MessageRepository.java#L87):
  ```java
  PreparedStatement pstmt = conn.prepareStatement(
          "UPDATE messages SET status = ? WHERE message_id = ?");
  pstmt.setString(1, status.name());
  pstmt.setString(2, messageId);
  pstmt.executeUpdate();
  ```
  There is no guard against backward state transitions in the delivery lifecycle.
- **Concrete failure scenario:** 
  1. Alice sends message $M_1$ to Bob.
  2. Bob reads the message; Bob’s client sends a `READ_RECEIPT`.
  3. Alice’s client receives the receipt, executing `markOutgoingRead`, which transitions $M_1$ from `SENT` to `READ`.
  4. A delayed or replayed `DELIVERY_ACK` for $M_1$ arrives later (due to network latency, reconnection, or relay retry).
  5. [`ChatClient.onDelivered`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java#L284) executes `messageRepository.updateStatus(mId, Status.DELIVERED)`.
  6. $M_1$’s status in the local SQLite database is overwritten from `READ` back to `DELIVERED`. The UI downgrades the double checkmark back to a single/delivered checkmark.
- **Why it matters:** Violates state machine monotonicity and corrupts message status presentation.
- **How to fix:** Guard the SQL update:
  `UPDATE messages SET status = ? WHERE message_id = ? AND (status != 'READ' OR ? = 'READ')` or enforce monotonic ordinal progression (`status_rank(new) > status_rank(current)`).
- **Severity:** High
- **Confidence:** Confirmed

---

#### Finding 3.3.2 — Null Payload on Control/Ack Frames Crashes Reader Loop & Drops Network Connection
- **Location:** [`chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java:284-286`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java#L284-L286), [`core-shared/src/main/java/com/e2eechat/core/network/ConnectionManager.java:303-311`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/network/ConnectionManager.java#L303-L311), [`chat-desktop/src/main/java/com/e2eechat/desktop/ChatWindow.java:286, 313`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/ChatWindow.java#L286)
- **What is wrong:** [`MessageCodec.decode`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/protocol/MessageCodec.java#L53) permits null payloads (`payload length == -1`). However, [`ChatClient.onDelivered`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java#L284) directly invokes `new String(msg.getPayload(), StandardCharsets.UTF_8)` when handling `DELIVERY_ACK`. Similarly, [`ChatWindow.handleMessage`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/ChatWindow.java#L286) calls `new String(msg.getPayload(), StandardCharsets.UTF_8)` on `ERROR` and `DELIVERY_ACK`.
- **Concrete failure scenario:** 
  1. A peer (or the relay) transmits a wire frame of type `DELIVERY_ACK` with `payload = null` (`-1` in wire encoding).
  2. [`ChatClient.onMessageReceived`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java#L223) runs on the `Client-Reader` thread and calls `onDelivered()`.
  3. `new String(null, ...)` throws a `NullPointerException`.
  4. The unhandled exception bubbles up to [`ConnectionManager.readerLoop`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/network/ConnectionManager.java#L307), which catches `Exception`, logs `Reader error: null`, and terminates by calling `own.close()`.
  5. The entire network connection is severed. A malicious peer can continuously disconnect a user by sending a single 20-byte null-payload frame.
- **Why it matters:** Remotely triggerable denial of service and client connection drop.
- **How to fix:** Add null checks (`if (msg.getPayload() == null) return;`) in [`ChatClient.onDelivered`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java#L284) and [`ChatWindow.handleMessage`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/ChatWindow.java#L286).
- **Severity:** High
- **Confidence:** Confirmed

---

#### Finding 3.3.3 — Delimiter Injection in Quoted Reply Encoding
- **Location:** [`chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java:549-579`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java#L549-L579)
- **What is wrong:** In `Body.encode`:
  ```java
  return REPLY_MARKER + replyId + FIELD_SEP
          + (replySender == null ? "" : replySender) + FIELD_SEP
          + (replyPreview == null ? "" : replyPreview.replace('\n', ' ')) + BODY_SEP
          + text;
  ```
  `FIELD_SEP` (`\u001F`) and `BODY_SEP` (`\u001E`) are used as framing delimiters. `replyPreview.replace('\n', ' ')` sanitizes newlines, but does **not** escape or strip `\u001F` or `\u001E` from `replyPreview`, `replySender`, or `replyId`.
- **Concrete failure scenario:** A user sends a message containing `\u001E` or `\u001F`. When a contact quotes that message in a reply, `Body.encode` embeds the raw delimiter. On the receiving end, `Body.parse` splits on `FIELD_SEP` or matches `BODY_SEP` prematurely, corrupting the message text or displacing the reply preview into the message body.
- **Why it matters:** Message formatting corruption and potential display text injection via quoted reply metadata.
- **How to fix:** Strip or escape `\u001E` and `\u001F` from all metadata fields in `Body.encode`.
- **Severity:** Medium
- **Confidence:** Confirmed

---

### §3.4 Input Validation & Injection

#### Finding 3.4.1 — Missing POSIX File Permissions on Private Key & Peer Stores
- **Location:** [`core-shared/src/main/java/com/e2eechat/core/keys/JceKeyStoreManager.java:103, 113`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/keys/JceKeyStoreManager.java#L103), [`chat-desktop/src/main/java/com/e2eechat/desktop/PeerDirectory.java:150, 160`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/PeerDirectory.java#L150)
- **What is wrong:** When creating `identity.p12`, `peers.properties`, and `peer-verification.properties`, standard `FileOutputStream` is used without setting POSIX permissions (e.g. `0600`).
- **Concrete failure scenario:** On multi-user Linux or macOS systems, files created with default umask (e.g., `0644`) are world-readable. Any local user account on the machine can read `identity.p12` to mount an offline dictionary attack against the passphrase, or read `peers.properties` to inspect the user's entire contact list.
- **Why it matters:** Local privilege boundary leakage on multi-user systems.
- **How to fix:** Use `File.setReadable(false, false); File.setReadable(true, true);` or POSIX file attribute permissions (`PosixFilePermissions.asFileAttribute(...)`) when creating sensitive files and parent configuration directories.
- **Severity:** Medium
- **Confidence:** Confirmed

---

### §3.5 Build, Supply Chain & Secrets

#### Finding 3.5.1 — Known Remote Code Execution Vulnerability in `sqlite-jdbc` (CVE-2023-32697)
- **Location:** [`gradle/libs.versions.toml:10, 24`](file:///c:/Users/aryan/Projects/tetherless/gradle/libs.versions.toml#L10)
- **What is wrong:** The build pins `sqlite-jdbc = "3.41.2.1"`. Version 3.41.2.1 is vulnerable to **CVE-2023-32697** (High severity), an arbitrary code execution vulnerability affecting `sqlite-jdbc` versions through 3.41.2.1. The flaw was resolved in version **3.41.2.2**.
- **Concrete failure scenario:** Any path where connection properties or database URLs can be influenced by configuration or untrusted input exposes the JVM to arbitrary code execution via crafted JDBC URLs.
- **Why it matters:** Packaging a dependency known to be vulnerable to Remote Code Execution violates supply chain security standards.
- **How to fix:** Update `sqlite-jdbc` in `gradle/libs.versions.toml` to `3.45.1.0` or later (at minimum `3.41.2.2`).
- **Severity:** High
- **Confidence:** Confirmed

---

#### Finding 3.5.2 — Known Vulnerabilities in Declared `logback-classic` Dependency
- **Location:** [`gradle/libs.versions.toml:4, 18`](file:///c:/Users/aryan/Projects/tetherless/gradle/libs.versions.toml#L4)
- **What is wrong:** The build pins `logback = "1.5.0"`. Logback 1.5.0 contains known security vulnerabilities (including CVE-2025-11226 and related configuration/deserialization issues) patched in subsequent 1.5.x and 1.6.x releases.
- **Concrete failure scenario:** Exploitation of logback configuration parsing or appender vulnerabilities in environments where logging configuration or environment variables are controlled by external entities.
- **Why it matters:** Supply chain hygiene for core logging dependencies.
- **How to fix:** Update `logback` to `1.5.19` or higher.
- **Severity:** Medium
- **Confidence:** Confirmed

---

#### Finding 3.5.3 — Blanket Global Suppressions in SpotBugs Filter Contradicting Stated Security Policy
- **Location:** [`config/spotbugs/exclude.xml:15-17, 23-25`](file:///c:/Users/aryan/Projects/tetherless/config/spotbugs/exclude.xml#L15-L17), [`docs/security.md:337-340`](file:///c:/Users/aryan/Projects/tetherless/docs/security.md#L337-L340)
- **What is wrong:** [`docs/security.md §8`](file:///c:/Users/aryan/Projects/tetherless/docs/security.md#L337) states:
  > *"Three suppressions exist, each scoped to a single pattern or method and each carrying its reason in config/spotbugs/exclude.xml; there is no blanket exclusion, because on a security detector a pattern-wide suppression silently covers code nobody has looked at yet."*
  
  In reality, [`config/spotbugs/exclude.xml`](file:///c:/Users/aryan/Projects/tetherless/config/spotbugs/exclude.xml#L15) contains blanket exclusions:
  ```xml
  <Match>
      <Bug pattern="OBJECT_DESERIALIZATION" />
  </Match>
  <Match>
      <Bug pattern="DM_DEFAULT_ENCODING" />
  </Match>
  ```
  Neither match is scoped to a class or method.
- **Concrete failure scenario:** A developer introduces a call to `ObjectInputStream.readObject()` on an untrusted stream anywhere in the codebase. SpotBugs fails to flag it because `OBJECT_DESERIALIZATION` is globally suppressed.
- **Why it matters:** Discrepancy between published security guarantees and static analysis enforcement.
- **How to fix:** Remove the blanket `<Bug pattern="OBJECT_DESERIALIZATION" />` suppression. If a specific class genuinely needs an exclusion, scope it explicitly by `<Class name="..." />` and `<Method name="..." />`.
- **Severity:** Medium
- **Confidence:** Confirmed

---

### §3.6 Tests & Verification

#### Finding 3.6.1 — Test Suite Fails to Exercise Tampering on Control & Ack Frames
- **Location:** [`core-shared/src/test/java/com/e2eechat/core/session/AdversarialRelayTest.java:174-186`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/test/java/com/e2eechat/core/session/AdversarialRelayTest.java#L174-L186)
- **What is wrong:** In `AdversarialRelayTest.strippingTheSignatureDropsTheMessage`:
  ```java
  relay.setTamper(message -> message.getType() == MessageType.TEXT_MESSAGE
          ? MaliciousRelay.stripSignature(message)
          : message);
  ```
  The test only verifies stripping signatures on `TEXT_MESSAGE`. It completely omits testing whether unsigned `DELIVERY_ACK`, `READ_RECEIPT`, or `TYPING` frames are dropped.
- **Concrete failure scenario:** Because tests only tested `TEXT_MESSAGE`, the failure of [`SignatureVerifier.java:26`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/protocol/SignatureVerifier.java#L26) to enforce signatures on control frames went unnoticed and passed all CI checks.
- **Why it matters:** False sense of test coverage on adversarial relay capabilities.
- **How to fix:** Extend `AdversarialRelayTest` to assert that stripping signatures on `DELIVERY_ACK`, `READ_RECEIPT`, `TYPING`, and `KEY_EXCHANGE_REJECT` causes them to be dropped.
- **Severity:** Medium
- **Confidence:** Confirmed

---

#### Finding 3.6.2 — `IvReuseTest` Iteration Count Is Orders of Magnitude Below Key Budget
- **Location:** [`core-shared/src/test/java/com/e2eechat/core/session/IvReuseTest.java:43-58`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/test/java/com/e2eechat/core/session/IvReuseTest.java#L43-L58), [`core-shared/src/main/java/com/e2eechat/core/session/Session.java:88`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/Session.java#L88)
- **What is wrong:** [`IvReuseTest.java`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/test/java/com/e2eechat/core/session/IvReuseTest.java#L43) runs only 20 iterations across directions and 200 iterations for a single sender. While mathematically the counter increments deterministically, it does not test behavior near `MAX_SENDS_PER_KEY` (100,000) or verify IV generation across multiple session renewal cycles in a single test.
- **Concrete failure scenario:** A regression in counter reset or direction assignment across multiple consecutive key renewals could go undetected.
- **Why it matters:** Inadequate stress testing of the primary AES-GCM invariant.
- **How to fix:** Add a parameterized test simulating renewal transitions across several consecutive keys, asserting distinct nonces across the entire lifecycle.
- **Severity:** Low
- **Confidence:** Confirmed

---

### §3.7 Architecture, Maintainability & Docs

#### Finding 3.7.1 — Discrepancies Between Documentation Guarantees and Code Implementation
- **Location:** [`docs/deployment.md:64-65`](file:///c:/Users/aryan/Projects/tetherless/docs/deployment.md#L64-L65), [`docs/security.md:184, 337`](file:///c:/Users/aryan/Projects/tetherless/docs/security.md#L184), [`docs/protocol.md:91-106`](file:///c:/Users/aryan/Projects/tetherless/docs/protocol.md#L91-L106)
- **What is wrong:** 
  1. [`docs/deployment.md:64`](file:///c:/Users/aryan/Projects/tetherless/docs/deployment.md#L64) claims: *"A certificate without a matching SAN fails the handshake regardless of pinning."* In reality, hostname verification is not enabled on the client socket (Finding 3.1.4).
  2. [`docs/security.md:184`](file:///c:/Users/aryan/Projects/tetherless/docs/security.md#L184) and [`docs/protocol.md §5`](file:///c:/Users/aryan/Projects/tetherless/docs/protocol.md#L91) state that typing notifications and read receipts are signed. In reality, unsigned versions are accepted as valid by `SignatureVerifier` (Finding 3.1.2).
  3. [`docs/security.md:337`](file:///c:/Users/aryan/Projects/tetherless/docs/security.md#L337) claims every SpotBugs suppression is scoped to a single pattern or method without blanket exclusions. In reality, blanket exclusions exist (Finding 3.5.3).
- **Concrete failure scenario:** Security auditors and operators relying on documentation make incorrect security assumptions regarding MITM protection, signature integrity, and static analysis rigor.
- **Why it matters:** Over-claiming in security documentation misleads users and auditors.
- **How to fix:** Align documentation with code or implement the missing checks.
- **Severity:** Medium
- **Confidence:** Confirmed

---

## 3. Confirmed Strengths

The following controls were independently verified in the codebase:

1. **No Java Serialization on the Wire:** 
   [`core-shared/src/main/java/com/e2eechat/core/protocol/MessageCodec.java`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/protocol/MessageCodec.java) implements a hand-rolled binary codec with strict length checks before any allocation (`readByteArray` enforces bounds before array creation). No wire models implement `java.io.Serializable`.
2. **MODP Group 14 Subgroup Validation:**
   [`core-shared/src/main/java/com/e2eechat/core/crypto/DHUtils.java:62-72`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/crypto/DHUtils.java#L62-L72) strictly enforces bounds $1 < y < p-1$ and tests $y^q \pmod p == 1$, preventing small-subgroup attacks.
3. **Leading Zero Normalisation in Diffie-Hellman Secret Derivation:**
   [`core-shared/src/main/java/com/e2eechat/core/crypto/DHUtils.java:80-89`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/crypto/DHUtils.java#L80-L89) correctly left-pads the raw DH secret to 256 bytes, preventing intermittent 1-in-256 handshake derivation failures between JDK and Conscrypt providers.
4. **Frozen Protocol Vectors & Cross-Platform Conformance:**
   [`core-shared/src/main/java/com/e2eechat/core/protocol/ProtocolVectors.java`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/protocol/ProtocolVectors.java) and [`ProtocolConformance.java`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/protocol/ProtocolConformance.java) verify 17 frozen protocol test vectors and RFC 5869 HKDF vectors, preventing silent protocol drift.
5. **Release Build Certificate Gating:**
   [`core-shared/src/main/java/com/e2eechat/core/network/TlsSupport.java:123-129`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/network/TlsSupport.java#L123-L129) and [`chat-server/src/main/java/com/e2eechat/server/ServerConfig.java:149-156`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/ServerConfig.java#L149-L156) actively inspect `BuildInfo.isRelease()`. Packaged release builds hard-fail if unconfigured rather than falling back to the development keystore.
6. **In-Process Identity Key Generation:**
   [`core-shared/src/main/java/com/e2eechat/core/keys/JceKeyStoreManager.java:88-107`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/keys/JceKeyStoreManager.java#L88-L107) generates RSA identity keys and self-signed certificates entirely in-process using [`SelfSignedCertificate.java`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/keys/SelfSignedCertificate.java), eliminating command-line argument exposure via `keytool`.
7. **Display Name Sanitization:**
   [`core-shared/src/main/java/com/e2eechat/core/protocol/HelloPayload.java:130-145`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/protocol/HelloPayload.java#L130-L145) strips ASCII control characters, Unicode bidirectional overrides (`U+202A..U+202E`), and isolates (`U+2066..U+2069`) before rendering.

---

## 4. Documented Limitations Confirmed Present

The following limitations disclosed in [`docs/security.md §5`](file:///c:/Users/aryan/Projects/tetherless/docs/security.md#L169) were verified in the code:

1. **No Forward Secrecy / No Ratchet:**
   Confirmed in [`SecureChat.java:132-155`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SecureChat.java#L132-L155) and [`Session.java:88`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/Session.java#L88). A single DH exchange generates a key that persists for up to 100,000 messages. There is no Double Ratchet or per-message key rotation.
2. **Absence of Metadata Privacy from the Relay:**
   Confirmed in [`chat-server/src/main/java/com/e2eechat/server/ClientSession.java:272-296`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/ClientSession.java#L272-L296). The relay observes full communication metadata (sender, receiver, frame length, and timestamps).
3. **Startup GitHub Update Check:**
   Confirmed in [`chat-desktop/src/main/java/com/e2eechat/desktop/UpdateChecker.java:108-144`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/UpdateChecker.java#L108-L144). Performs an outbound HTTPS request to GitHub's release API on startup unless disabled via `updates=false`.
4. **Partial At-Rest Encryption on Desktop:**
   Confirmed in [`chat-desktop/src/main/java/com/e2eechat/desktop/MessageRepository.java:55-84`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/MessageRepository.java#L55-L84). Only message text and reply preview columns are AES-GCM encrypted; `sender`, `receiver`, `timestamp`, and message counts remain plaintext in SQLite.
5. **No Session Expiry / Time-to-Live:**
   Confirmed in [`Session.java:11`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/Session.java#L11). `Session.State.EXPIRED` is never assigned.
6. **Single Centralized Relay Architecture:**
   Confirmed across [`chat-server`](file:///c:/Users/aryan/Projects/tetherless/chat-server). No peer-to-peer or federated relay capabilities are implemented.

---

## 5. Top 5 Priorities to Fix First

| Priority | Finding ID | Severity | File Reference | Action Required |
|---|---|---|---|---|
| **1** | **3.1.2** | **Critical** | [`SignatureVerifier.java:20-27`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/protocol/SignatureVerifier.java#L20-L27) | Enforce signatures on all control frame types (`DELIVERY_ACK`, `READ_RECEIPT`, `TYPING`, `KEY_EXCHANGE_REJECT`). |
| **2** | **3.1.1** | **Critical** | [`SessionManager.java:42-66`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/session/SessionManager.java#L42-L66) | Assert `localClientId.equals(msg.getReceiverId())` on all incoming frames to prevent cross-session misrouting. |
| **3** | **3.2.1** | **High** | [`ClientSession.java:202-221`](file:///c:/Users/aryan/Projects/tetherless/chat-server/src/main/java/com/e2eechat/server/ClientSession.java#L202-L221) | Authenticate registration `HELLO` with a cryptographic signature/proof-of-possession to prevent trivial identity squatting. |
| **4** | **3.1.4** | **High** | [`TlsSupport.java:235-241`](file:///c:/Users/aryan/Projects/tetherless/core-shared/src/main/java/com/e2eechat/core/network/TlsSupport.java#L235-L241) | Enable TLS endpoint identification (`params.setEndpointIdentificationAlgorithm("HTTPS")`) on all raw `SSLSocket` instances. |
| **5** | **3.3.1 & 3.3.2** | **High** | [`MessageRepository.java:92-96`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/MessageRepository.java#L92-L96), [`ChatClient.java:284-286`](file:///c:/Users/aryan/Projects/tetherless/chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java#L284-L286) | Guard status updates against regression from `READ` to `DELIVERED`, and null-check payloads to prevent connection drops. |