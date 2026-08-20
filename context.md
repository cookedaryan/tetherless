# Project Context: Tetherless E2EE Chat

## What is this?
A decentralized End-to-End Encrypted Chat Application consisting of:
- `core-shared`: Shared cryptographic and protocol models (Java)
- `chat-server`: Dumb, untrusted relay server that routes ciphertext only (Java)
- `chat-desktop`: Java Swing + SQLite desktop client
- `chat-mobile`: Android + Room mobile client

## Key Documents
- [Development Plan](docs/development_plan.md): The authoritative ticket-wise plan, threat model, and definition of done.

## Current State & Next Steps
- **Status:** Scaffolded. Compiles for `core-shared`, `chat-server`, `chat-desktop`. `chat-mobile` currently fails to compile.
- **Active Phase:** Phase 0 — Build and toolchain hygiene
- **Current Ticket:** `BUILD-01` — Make every module compile from a clean checkout

## Architectural & Security Constraints
- **Threat Model:** The server is assumed hostile/compromised and must NEVER see plaintext or key material.
- **No Native Serialization:** The wire format will migrate to an explicit binary codec to avoid Java deserialization vulnerabilities.
- **Crypto:** AES-256-GCM for encryption, DH (Group 14) for key exchange, SHA256withRSA for signatures.
- **No Payload Logging:** Keys, IVs, and message payloads must never be logged.

