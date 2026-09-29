# Product Requirements Document — Tetherless Desktop

**Status:** draft for review
**Scope:** the desktop client. Mobile is out of scope for this document.
**Upstream:** [brd.md](brd.md), [mrd.md](mrd.md) · **Downstream:** [srd.md](srd.md)

---

## 1. Vision

A desktop messenger that a careful person can install, understand, and rely on — where the identity
belongs to the user, the relay cannot read anything, and the product is honest about the rest.

## 2. Goals

| # | Goal |
|---|---|
| G-1 | A first-time user reaches their first sent message without help |
| G-2 | Two people can confirm nobody is intercepting them, and see that state afterwards |
| G-3 | Nothing in the interface leads nowhere — every control does something |
| G-4 | The user is told the truth about delivery: sent, delivered, read, or failed |
| G-5 | The client behaves predictably when the network or relay misbehaves |

## 3. Non-goals for this release

Group messaging · voice and video · file and image transfer · multi-device · server-side backup ·
operator-assisted account recovery · contact discovery by name or number.

Recovery is a non-goal by consequence, not by omission: an operator who can restore your identity
can impersonate you.

---

## 4. Personas

**Mara — the deliberate adopter.** Chooses tools on their properties, reads the security page, will
verify a safety number. Needs the claims to be precise and the limitations visible. Will forgive a
missing feature; will not forgive an overstatement.

**Devan — the small-team lead.** Runs the relay for four colleagues handling sensitive material.
Needs deployment to be boring, and needs to explain the tool to people who did not choose it.

**Priya — the invited participant.** Did not choose Tetherless; a colleague did. Not hostile to
technology, not interested in cryptography. Needs install-to-first-message to be short and
unintimidating. **Priya is the persona the product most often fails.**

---

## 5. User stories

Priority: **P0** ships in 1.0, **P1** is next, **P2** is later.

### Identity and access

| # | Story | Priority |
|---|---|---|
| US-1 | As a new user I create an identity with a passphrase, so only I can open my history | P0 |
| US-2 | As a returning user I unlock with my passphrase | P0 |
| US-3 | As a user I am told, before I choose it, that a lost passphrase cannot be recovered | P0 |
| US-4 | As a user I see my own identifier and can copy it, so I can give it to someone | P0 |
| US-5 | As a user I am warned when my passphrase is weak, while I can still change it | P1 |

### Conversations

| # | Story | Priority |
|---|---|---|
| US-6 | As a user I start a conversation by entering someone's identifier | P0 |
| US-7 | As a user I see my conversations with the most recent first | P0 |
| US-8 | As a user I send and receive text messages | P0 |
| US-9 | As a user I see whether a message was sent, delivered, read, or failed | P0 |
| US-10 | As a user I see when the other person is typing | P1 |
| US-11 | As a user I reply to a specific message | P1 |
| US-12 | As a user I search my history | P1 |
| US-13 | As a user I pin, mute or archive a conversation | P2 |

### Trust

| # | Story | Priority |
|---|---|---|
| US-14 | As a user I compare a safety number with the other person | P0 |
| US-15 | As a user I mark a contact verified, and see that afterwards | P0 |
| US-16 | As a user I am blocked from sending, and told why, when a known contact's key changes | P0 |
| US-17 | As a user I re-establish encryption for a conversation | P1 |

**US-16 is the highest-severity story in this document.** A key change is the signature of an
interception attempt. It must interrupt, not inform.

### Operation

| # | Story | Priority |
|---|---|---|
| US-18 | As a user I see whether I am connected, and the client reconnects on its own | P0 |
| US-19 | As a user my history is there after a restart | P0 |
| US-20 | As a user I choose a light or dark appearance | P1 |
| US-21 | As a user I turn off the startup update check, and am told what it discloses | P1 |
| US-22 | As a user I am notified of a message when the window is not in front | P1 |
| US-23 | As a user I point the client at a different relay | P2 |

---

## 6. Product requirements

| # | Requirement | Traces to | Priority |
|---|---|---|---|
| PR-1 | Identity is created locally and protected by a passphrase; neither leaves the device | BR-2, MR-2 | Must |
| PR-2 | The consequence of losing the passphrase is stated before it is chosen | BR-5 | Must |
| PR-3 | A conversation is started from an identifier alone; no account or directory | BR-7 | Must |
| PR-4 | Safety numbers are presented so two people can read them aloud and compare | BR-6, MR-3 | Must |
| PR-5 | Verification state persists and is visible in the conversation list | BR-6 | Must |
| PR-6 | A key change for a known peer blocks sending until re-verified | BR-1, MR-3 | Must |
| PR-7 | Delivery state is shown accurately, including failure | G-4 | Must |
| PR-8 | History persists locally, encrypted under a key derived from the passphrase | BR-1 | Must |
| PR-9 | Connection state is always visible; reconnection is automatic and backed off | G-5 | Must |
| PR-10 | No control in the interface leads nowhere | G-3 | Must |
| PR-11 | Light and dark appearance, applied consistently to every surface | US-20 | Should |
| PR-12 | Any outbound network request other than to the relay is disclosed and can be disabled | BR-5 | Must |
| PR-13 | The client installs and runs without a separately installed Java runtime | BR-3 | Must |

**PR-12 exists because of one feature.** The startup update check contacts GitHub, which tells
GitHub — and anyone watching the network — that this address runs Tetherless and roughly when it
started. That is the only unsolicited outbound request the product makes, and it must be disclosed
in those terms and be switchable.

---

## 7. Experience requirements

- **Honesty over reassurance.** Never show a state the product cannot substantiate. A message that
  did not leave the machine must not show as sent.
- **Interrupt only for the things that warrant it.** A key change interrupts. A reconnect does not.
- **Failure is a visible state, not a silence.** Disconnected, failed to send, and no session yet
  are each distinguishable from "nothing happening".
- **Appearance:** cool graphite surfaces with a single vivid accent, used only where it carries
  meaning — the primary action, read ticks, the verified shield. Full palette in
  `docs/superpowers/plans/2026-09-29-electron-desktop-migration.md` §11.
- **Security-critical text is plain.** The passphrase warning and the key-change warning are written
  for Priya, not for Mara.

---

## 8. Release plan

| Release | Contains | Exit criteria |
|---|---|---|
| **1.0 — Desktop** | P0 stories; Electron client over the Java engine; Windows installer | Manual QA script passes end to end; two clients hold a verified conversation through a relay |
| **1.1 — Comfort** | P1 stories: typing, replies, search, appearance, notifications, update control | No P0 regressions |
| **1.2 — Trust depth** | Forward secrecy (MR-7); modern primitives | Formal model of the handshake checks; protocol version negotiated |
| **2.0 — Reach** | Mobile parity; macOS and Linux | Out of scope here |

Forward secrecy is scheduled at 1.2 rather than 1.0 deliberately: it is a protocol change, it is the
most significant market gap, and it should not be rushed into the first release.

---

## 9. Success metrics

| Metric | Target | How |
|---|---|---|
| Install → first message | Under 5 minutes unassisted | Usability sessions with Priya-like participants |
| Identity creation abandonment | **[BASELINE NEEDED]** | Not currently instrumented |
| Verified conversations | Majority of active pairs | Local measure only; not reported to any server |
| Messages shown as sent that did not send | Zero | Automated test at the client layer |
| Documented claims contradicted by review | Zero | External review |

**No telemetry is collected.** Every metric above is measured in testing or self-reported.
Instrumenting the client to report usage would contradict BR-1 and PR-12, and is not a trade this
product should make.

---

## 10. Open questions

1. **Contact discovery (MR-8).** Requiring an out-of-band exchange is the largest adoption barrier.
   A relay-hosted handle directory would fix it and would give the relay a name→key mapping it does
   not have today. That is a product *and* security decision and is not yet made.
2. **Deniability (MR-13).** Every message is signed, which means a recipient can prove to a third
   party who sent it. Moving message authentication to a shared-secret MAC would change that. It is
   a protocol change and affects the Android client.
3. **Multi-device.** Currently impossible by design. Any solution requires either key sync or a
   device-linking scheme; neither is scoped.
4. **Unsigned installer.** The publisher warning is a poor first impression for a security product.
