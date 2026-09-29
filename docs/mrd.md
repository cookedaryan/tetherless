# Market Requirements Document — Tetherless

**Status:** draft for review
**Audience:** product and business stakeholders deciding what the market demands of this product

Where [brd.md](brd.md) says why *we* are building this, this document says what the *market* would
require of it. It is written to be falsifiable: where a claim needs research the project has not
done, it is marked as a placeholder rather than invented.

---

## 1. The market problem

Encrypted messaging is no longer a niche. The problem has moved on from "is the content encrypted"
to three harder questions:

1. **Who is the operator, and what can they see even when content is encrypted?** Metadata — who
   talks to whom, when, how often — is frequently more revealing than content, and is generally
   retained.
2. **What is an identity tied to?** Most mainstream messengers bind identity to a phone number,
   which is a poor secret, transferable by a carrier, and often unavailable to the people who most
   need privacy.
3. **Can the claims be checked?** Users are asked to trust privacy assertions they cannot verify,
   and periodically discover the assertion was narrower than they understood.

A segment of users answers these questions by self-hosting or by choosing tools whose architecture
removes the operator from the trust equation.

---

## 2. Target segments

| Segment | Need | Fit |
|---|---|---|
| **Privacy-conscious individuals** | Messaging without a phone number, with an identity they control | Strong |
| **Small teams handling sensitive material** — journalists, legal, research | Self-hosted infrastructure, no third-party operator | Strong |
| **Technical self-hosters** | Something they can run, inspect and reason about | Strong |
| **Educators and students of security** | A complete, readable, honestly-documented implementation | Strong |
| **General consumers** | Convenience, their contacts already present | **Poor** — no discovery, no mobile parity, no network effect |

The last row is the important one. Tetherless is not currently competing for mainstream users and
should not be positioned as though it is.

---

## 3. Competitive landscape

Qualitative, and limited to properties that can be checked from public documentation.

| | Tetherless | Signal | WhatsApp | Telegram | Matrix / Element | Briar |
|---|---|---|---|---|---|---|
| E2EE by default | Yes | Yes | Yes | **No** (only Secret Chats) | Yes in private rooms | Yes |
| Requires phone number | **No** | Yes | Yes | Yes | No | No |
| Self-hostable | **Yes** | No | No | No | Yes | No server at all |
| Operator sees social graph | Yes (stated) | Reduced | Yes | Yes | Homeserver does | No |
| Forward secrecy | **No** — single handshake | Yes | Yes | Partial | Yes | Yes |
| Group messaging | **No** | Yes | Yes | Yes | Yes | Yes |
| Multi-device | **No** | Yes | Yes | Yes | Yes | No |
| Mobile client | Early | Yes | Yes | Yes | Yes | Yes |
| Deniability | **No** — messages are signed | Yes | Yes | Partial | No | Partial |

**Read the bold cells honestly.** Tetherless is behind on forward secrecy, groups, multi-device and
mobile. It is ahead on not requiring a phone number and on being self-hostable end to end. Any
positioning that does not acknowledge the first list will not survive contact with an informed
reviewer.

---

## 4. Differentiation

What Tetherless can credibly claim today:

- **No phone number, no email, no account.** Identity is a key pair the user holds. Nothing is
  issued by a service and nothing can be revoked by one.
- **Self-hostable in full.** The relay is a small, stateless service that anyone can run. There is
  no hosted component required for the product to work.
- **The relay is architecturally blind.** Not "we promise not to look" but "there is nothing to
  look at" — it routes ciphertext and holds no key material.
- **Published limitations.** The security documentation states what is *not* protected as
  prominently as what is. This is rarer than it should be and is itself a differentiator with the
  audience that reads such documents.

What it cannot yet claim: per-message forward secrecy, metadata protection, deniability, groups, or
mobile parity.

---

## 5. Market requirements

What the market would require before each segment could adopt. Priority is market priority, not
engineering priority.

| # | Requirement | Segment | Priority |
|---|---|---|---|
| MR-1 | Content unreadable by the operator, by architecture | All | Must |
| MR-2 | Identity not bound to a phone number | Individuals, at-risk users | Must |
| MR-3 | A way to verify you are talking to the right person | All | Must |
| MR-4 | Installable by a non-technical user on Windows | All | Must |
| MR-5 | Self-hostable relay with documented deployment | Teams, self-hosters | Must |
| MR-6 | Honest, published statement of limitations | Technical, educational | Must |
| MR-7 | Forward secrecy | Privacy-conscious, at-risk | **High — currently unmet** |
| MR-8 | A way to find or add a contact without prior out-of-band exchange | All | High |
| MR-9 | Mobile client at parity | All | High |
| MR-10 | Group messaging | Teams | Medium |
| MR-11 | Multi-device | All | Medium |
| MR-12 | macOS and Linux clients | Self-hosters | Medium |
| MR-13 | Deniability rather than non-repudiation | At-risk users | Medium |

**MR-7 is the most commercially significant gap.** Forward secrecy is now an expectation rather
than a feature; its absence is the first thing a knowledgeable evaluator will test for, and the
roadmap already identifies the work.

**MR-8 is the most significant adoption gap.** Requiring an out-of-band exchange before a first
message is a real barrier for every segment except the most motivated.

---

## 6. Market sizing

Not estimated. This project has done no market research, and inventing a figure here would be worse
than leaving it blank.

- Total addressable market: **[REQUIRES RESEARCH]**
- Serviceable segment: **[REQUIRES RESEARCH]**
- Evidence base: **[NONE — no user interviews or surveys conducted]**

If sizing is needed for a decision, the cheapest useful step is a handful of interviews with the
self-hosting and small-team segments, where the product's existing strengths already match the
stated need.

---

## 7. Positioning

> For people who need private conversation without renting their identity from a platform,
> Tetherless is an end-to-end encrypted messenger whose relay is built so it cannot read anything —
> and which tells you plainly what it does not protect.

Positioning constraints worth enforcing:

- Never claim metadata privacy. The relay sees who talks to whom, and this is documented.
- Never claim forward secrecy until it exists.
- Do not compete on feature count against mainstream messengers. That comparison is unwinnable and
  invites scrutiny of exactly the gaps in §3.

---

## 8. Go-to-market considerations

- **Distribution:** direct download. No app store presence, and the Windows installer is unsigned,
  which produces a publisher warning that must be explained rather than hidden.
- **Audience first reached:** technical and educational communities, where the honest documentation
  is an asset rather than a liability.
- **The network effect problem is real.** A messenger with no users is not useful. Early adoption
  will be pairs and small groups who adopt together, which favours the small-team segment over
  individuals.

---

## 9. Market risks

| Risk | Impact | Note |
|---|---|---|
| Evaluated against mainstream feature sets | High | Mitigated by narrow, honest positioning |
| The forward-secrecy gap becomes disqualifying | High | Already the top item on the technical roadmap |
| No discovery mechanism suppresses adoption | High | MR-8; a product decision, not only an engineering one |
| Category is crowded and well-funded | Medium | Competing on architecture and honesty, not on scale |
| Unsigned installer reads as untrustworthy | Medium | Ironic for a security product; worth revisiting |
