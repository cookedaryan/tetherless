# Business Requirements Document — Tetherless

**Status:** draft for review
**Owner:** [PROJECT OWNER]
**Audience:** sponsors, stakeholders, anyone deciding whether this work continues

This document states *why* the business is building Tetherless and what would count as success. It
deliberately contains no design or implementation. For what the product does see
[prd.md](prd.md); for how it is specified see [srd.md](srd.md).

---

## 1. Business problem

Private communication is now mediated almost entirely by platforms whose business model depends on
knowing who is talking to whom. Even where message *content* is encrypted, the operator typically
retains the social graph, the timing, and an account identity tied to a phone number.

Three consequences matter commercially:

1. **Trust is asserted, not demonstrated.** Users are asked to believe a privacy claim they cannot
   inspect. Every few years an incident shows the claim was narrower than understood.
2. **Identity is rented.** An account tied to a phone number can be lost, recycled, SIM-swapped, or
   revoked by an operator or a carrier.
3. **Deployments cannot be self-hosted.** Organisations that need to control their own
   communications infrastructure generally cannot, without abandoning usability entirely.

Tetherless addresses a narrow slice: a chat system where the server is *architecturally* unable to
read messages, where identity is a key the user holds, and where the whole thing can be run by the
people using it.

---

## 2. Business objectives

| # | Objective | Why it matters |
|---|---|---|
| BO-1 | Ship a desktop client that a non-technical person can install and use | An unusable secure tool protects nobody |
| BO-2 | Keep the relay architecturally unable to read message content | The single differentiating claim; if it weakens, the product has no reason to exist |
| BO-3 | Make the security claims independently checkable | Documentation, open protocol, reproducible tests — trust that can be verified rather than asserted |
| BO-4 | Keep operating cost near zero at low scale | The relay stores nothing; there is no per-user infrastructure cost to recover |
| BO-5 | Establish credibility as engineering work | The project doubles as a demonstrable body of work for its authors |

---

## 3. Business requirements

Numbered so later documents can trace to them.

| # | Requirement | Priority |
|---|---|---|
| BR-1 | Message content must be unreadable by the relay operator, by design and not by policy | Must |
| BR-2 | A user's identity must be held by the user, not issued by the service | Must |
| BR-3 | The product must run on Windows without the user installing a Java runtime | Must |
| BR-4 | Anyone must be able to run their own relay from published artefacts | Must |
| BR-5 | Security properties, **and their limits**, must be published in plain language | Must |
| BR-6 | Two people must be able to verify they are not being intercepted | Must |
| BR-7 | The product must not require a phone number or email address | Must |
| BR-8 | The product should support macOS and Linux | Should |
| BR-9 | The product should offer a mobile client | Should |
| BR-10 | The project should not incur recurring per-user cost | Should |

**BR-5 is unusual and deliberate.** Publishing the limitations is a business requirement, not a
courtesy. The product's only durable asset is credibility; overstating the guarantees would be the
fastest way to destroy it, and the limitations are already documented in `docs/security.md` §5.

---

## 4. Scope

**In scope.** One-to-one text messaging between two people who already know each other's identity;
a desktop client; a self-hostable relay; identity creation and local encrypted history.

**Out of scope for this phase**, stated so it is not mistaken for oversight: group messaging, voice
and video, file transfer, a directory or discovery service, message backup to a server, multi-device
use of one identity, and any form of account recovery by the operator. The last is not a gap but a
consequence of BR-2: an operator who can restore your identity is an operator who can impersonate
you.

---

## 5. Stakeholders

| Stakeholder | Interest |
|---|---|
| Project owner / sponsor | Delivery, credibility, cost |
| Engineering team | Buildable scope, defensible decisions |
| End users | Privacy they can rely on; software they can actually use |
| Relay operators | Simple deployment, low running cost, minimal liability |
| Reviewers and assessors | Evidence that the claims hold |

---

## 6. Success criteria

| # | Criterion | Measure |
|---|---|---|
| SC-1 | A first-time user completes install → identity → first message | Under 5 minutes, unassisted, in usability testing |
| SC-2 | The relay demonstrably learns nothing it should not | Automated end-to-end test asserting no plaintext crosses the relay |
| SC-3 | Documented security claims match the implementation | External review finds no claim the code does not deliver |
| SC-4 | A third party can stand up a relay from the docs alone | Verified by someone outside the team |
| SC-5 | The published limitations are accurate and complete | No Critical or High finding that contradicts documentation |

SC-3 and SC-5 are the ones that matter most. A defect is recoverable; a false claim about
encryption is not.

---

## 7. Constraints

- **Team:** small, part-time. Scope is bounded by this more than by anything technical.
- **Budget:** no code-signing certificate, no paid infrastructure, no paid app-store presence.
  Windows will therefore show an unsigned-publisher warning at install.
- **Platform:** Windows first. macOS packaging additionally requires Apple developer enrolment.
- **No server-side state.** Nothing that requires the relay to store user data is affordable or
  desirable.
- **Existing protocol.** The wire format is frozen and shared with the Android client; changes are
  versioned events, not edits.

---

## 8. Assumptions

- Users can exchange an identifier out of band (in person, or over another channel).
- Users accept that losing the passphrase loses the history. This follows from BR-2.
- A single relay is sufficient at the scale envisaged; federation is not required.
- The threat model is a hostile relay and a network attacker — **not** a compromised endpoint. A
  device with malware on it is out of scope for any messenger.

---

## 9. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| A security claim proves overstated | Existential — the product has one asset and this is it | Publish limitations; keep claims tied to tests; external review |
| Cryptography ages faster than the project | High | Roadmap already identifies the migration path (`academic_improvement_roadmap.md`) |
| Unsigned installer deters adoption | Medium | Document the warning honestly; revisit signing if adoption justifies it |
| Onboarding friction from out-of-band identity exchange | Medium | Treated as a first-class UX problem in the PRD, not an afterthought |
| Small team, broad surface | Medium | Ruthless scope control; mobile and groups explicitly deferred |
| Key loss is unrecoverable by design | Medium | Set expectations at identity creation, prominently, before the passphrase is chosen |

---

## 10. Out-of-scope business decisions still open

1. Whether the project is ever monetised, and if so how without weakening BR-1.
2. Whether to pursue code signing, and at what adoption threshold.
3. Whether to operate a public relay as a convenience, and who carries the liability for it.
