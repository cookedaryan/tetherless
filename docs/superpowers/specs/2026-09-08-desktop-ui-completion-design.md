# Desktop UI completion — design

**Date:** 2026-09-08
**Status:** approved, not yet planned

Finishing the desktop client's interface: a shared side-panel system, a working light and dark
theme, and the removal of every control that leads nowhere.

---

## 1. Why

The desktop client looks finished and is not. Three things are wrong, and they are wrong in ways a
screenshot does not show.

**The theme is half-installed.** `Theme` holds a full Telegram Day/Night palette and every
custom-painted component follows it. But no look-and-feel is ever installed — FlatLaf is declared in
`chat-desktop/build.gradle` and `UIManager.setLookAndFeel` appears nowhere in the module. Every
stock Swing component therefore renders in default Metal: popup menus, scrollbars, tooltips, text
carets and all twelve `JOptionPane` dialogs stay light whatever the toggle says.

**There is a panel system, and only one panel uses it.** `SettingsPanel.present()` slides a panel
over a scrim in the frame's layered pane, animates with `Motion`, and dismisses cleanly. All of it
is private mechanism inside a 544-line class that also draws settings rows, so nothing else can
open a panel.

**Several controls lead nowhere.** Found by reading, not by guessing:

| Dead end | Evidence |
|---|---|
| Safety number is a text blob | `ChatWindow:459` — `JOptionPane.showMessageDialog` with a `\n`-joined string. The one screen that lets a user detect a machine-in-the-middle. |
| Search results cannot be acted on | `ChatWindow:480–503` — a prompt dialog, then matches dumped as text. You search, you read, and there is nowhere to go. |
| A peer can never be marked verified | `Conversation.verified` has a getter, a setter, and is preserved across sidebar refreshes at `ConversationListPanel:408`. Nothing sets it true and nothing reads it to draw anything. |
| A failed send is invisible | `MessageBubble:379` draws a red tick for `Status.FAILED`. Nothing anywhere sets `FAILED`. |
| Delivery ticks are lost on restart | `ChatWindow` updates the bubble in memory on `DELIVERY_ACK`. `MessageRepository.updateStatus` exists, is tested, and is never called. |
| The update-check opt-out has no UI | The startup request to GitHub can only be disabled by editing `config.properties`. The one privacy control the client has is invisible to the people most likely to want it. |

No `ui/` component is dead code; every one is genuinely used. The gaps are behavioural.

---

## 2. Scope

**In scope**

1. A reusable side-panel host, extracted from `SettingsPanel`.
2. A chat info panel: identity, safety number, verification, renegotiate.
3. A search panel with results that can be clicked.
4. FlatLaf installed and driven from `Theme`, with stock components taking the existing palette.
5. Replacing the in-flow dialogs with in-app surfaces; leaving terminal ones as themed dialogs.
6. A Privacy section in Settings carrying the update-check toggle.
7. Closing the message-status and verification dead ends.

**Out of scope,** stated so it is not mistaken for oversight: attachments; an offline send queue;
a contacts panel; an editable relay address; clear-history; following the OS theme; anything mobile.

---

## 3. The side-panel host

### `ui/SidePanel.java` — new

Owns everything currently private to `SettingsPanel`:

- hosting in the frame's `JLayeredPane`, above the content
- the scrim, and dismissal when it is clicked
- the slide animation, through the existing `Motion` helper, honouring reduced motion
- dismissal on Escape, and returning focus to whatever held it before the panel opened
- a standard header: a leading `IconButton`, a title, an optional trailing action

Its API takes a side (`LEFT` or `RIGHT`), a title, and a body `JComponent`. It knows nothing about
what it contains.

### `SettingsPanel` — becomes content

Keeps `buildBody()` and its `Row` and `sectionTitle` helpers. Loses `present()`, its private
`Scrim`, and its animation code. Continues to open from the left.

### `ChatInfoPanel.java` — new, right side

- Avatar, display name, peer id with a copy action
- Connection and session state
- **The safety number as two labelled fingerprint blocks in a monospaced grid**, not a joined
  string. The point of a safety number is that two people read it aloud to each other a group at a
  time; a paragraph of hex defeats it.
- **Mark as verified**, which is what finally sets the verification flag
- **Renegotiate encryption**, moved here from the overflow menu
- **Search this conversation**, which opens the search panel with the query scoped to this peer

### `SearchPanel.java` — new, right side

A query field and a results list of sender, snippet and date. Selecting a hit calls a new
`TranscriptPanel.scrollTo(messageId)`.

### `ChatWindow`

Gains a small `openPanel` / `closePanel` pair and owns which panel is open, so opening one closes
the other. Loses `showSafetyNumber()` and `showChatSearch()` entirely.

---

## 4. Theme

### The look-and-feel bridge

`Theme.installLookAndFeel()` sets `FlatLightLaf` or `FlatDarkLaf` — FlatLaf 3.4.1, already on the
classpath — and then pushes the existing palette into `UIManager`: popup menu background, menu item
selection, scrollbar thumb and track, tooltip colours, text field and text area background,
foreground, caret and selection, and the component focus colour. Stock components then draw in the
same dark as the bubble beside them rather than a neighbouring one.

### Ordering

This is the part that is easy to get wrong.

- The look-and-feel must be installed **before the first Swing component exists**. In `Main` the
  first component is `IdentityDialog`, not `ChatWindow`, so the call goes at the top of `main`.
- On toggle, `Theme.setDark` must: install the other look-and-feel, re-push the palette keys, call
  FlatLaf's `updateUI` entry point to restyle every open window, and **only then** fire its existing
  listeners so custom-painted components repaint. The wrong order leaves a frame half in each theme.

### Dialogs

The twelve `JOptionPane.show*` call sites split on one principle: **in-flow feedback becomes an
in-app surface; terminal errors stay modal.**

Replaced (7):

| Call site | Becomes |
|---|---|
| `ChatWindow:459` safety number | Chat info panel |
| `ChatWindow:480` search prompt | Search panel |
| `ChatWindow:490` no matches | Empty state in the search panel |
| `ChatWindow:503` results blob | Results list in the search panel |
| `ConversationListPanel:275` new-chat prompt | Inline field in the sidebar header |
| `ConversationListPanel:285` not a peer id | Validation text under that field |
| `ConversationListPanel:292` that is your own id | Validation text under that field |

Kept as dialogs, now themed (5): every remaining one is in `Main` and every one precedes exit — no
config directory, too many failed attempts, identity will not load, migration failed, key derivation
failed. A modal is the right shape for "this is over". The fix is that it stops being light on dark.

Validation moving inline matters beyond looks: today a rejected peer id opens a second window you
must dismiss before you can correct the value you just typed.

### Accepted consequence

Installing FlatLaf restyles the sign-in screen, because `IdentityDialog` and `FormField` use stock
text fields beneath their custom chrome. That surface was deliberately redesigned in `6f5c9ca`.
Approved: consistency is worth more than pinning one screen.

---

## 5. Settings sections

Existing: Appearance, Connection, About.

Added: **Privacy**, carrying the update-check toggle. Its subtitle says plainly what the check
discloses — that GitHub, and anyone watching the network, learns this address runs Tetherless and
roughly when it started.

**Where the toggle writes needs stating, because `DesktopConfig` only reads today.** It writes to
`Preferences`, the same store `Theme` already uses for the dark-mode flag, and that becomes the
lowest rung of `DesktopConfig`'s existing precedence chain:

> command-line arguments → system properties → `config.properties` → **Preferences** → built-in default

So a deployment that sets `updates=false` in its configuration keeps it off and the toggle cannot
override it, while a user with nothing configured gets a control that works. `UpdateChecker.isEnabled`
consults the resolved value rather than reading the system property directly, as it does now.

---

## 6. Dead ends

### Failed sends become visible

`ConnectionManager.sendMessage` returns `void` and drops the message with a log line when it is not
connected; the caller cannot tell. It returns `boolean` — accepted into the outbound queue, or not.
`ChatClient.sendMessage` saves the row `SENT` or `FAILED` on that answer, so the red tick
`MessageBubble` already knows how to draw finally has a producer.

Source-compatible: callers ignoring the result still compile, so nothing else in `core-shared` and
nothing in the mobile client has to change.

### Delivery state survives a restart

On `DELIVERY_ACK`, `ChatWindow` also calls `messageRepository.updateStatus(id, DELIVERED)`.

### `SENDING` is removed

It means "queued locally, the relay has not seen it", and this protocol has no such state: handing a
frame to the transport is immediate, and the only later confirmation is `DELIVERY_ACK`, which comes
from the peer rather than the relay. The gap it describes is microseconds wide.

Removed from `ChatMessage.Status`, along with the clock glyph in `MessageBubble`. `TgIcons.clock`
stays — it is a general-purpose icon and costs nothing.

Existing database rows storing `'SENDING'` are safe: `MessageRepository.readRow` already catches
`IllegalArgumentException` from `Status.valueOf` and falls back to `SENT`. That fallback is covered
by an existing test.

### Verification is persisted

`PeerDirectory` gains `setVerified` / `isVerified` beside the display names it already persists in
the same properties file. Verification is a property of a peer, not of a conversation, so it belongs
with the name. `Conversation.verified` becomes a projection of it, and `ConversationListPanel` draws
a shield when true — which is what that dormant field was always for.

---

## 7. Testing

| Area | Test | Notes |
|---|---|---|
| `SidePanel` | New, headed-only | Opens, closes, Escape dismisses, opening one closes the other. Skips where there is no display, matching `UpdateBannerTest`. |
| Theme bridge | New, headless | `UIManager` keys change with the toggle; installing twice is safe. |
| Send status | Extends `ChatClientTest` | Transport rejects → row is `FAILED`; accepts → `SENT`. The fake transport is already there. |
| Delivery persistence | Extends `ChatClientTest` | An ack reaches the repository, not only the bubble. |
| Verification | New `PeerDirectoryTest` | `PeerDirectory` has no test file today. Round-trip, and survives a restart. |
| Search | Headed-only | A selected hit scrolls the transcript to that message. |
| Regression | Existing suites | `./gradlew build` and `:chat-desktop:integTest` stay green. |

Every behaviour change gets a test written first and watched fail, per `CONTRIBUTING.md`.

---

## 8. Order of work

Large enough that the sequence matters. Each step leaves the application working.

1. **Install FlatLaf and bridge it to `Theme`.** Touches nothing structural, and every later step is
   easier to judge once stock components stop being the wrong colour.
2. **Extract `SidePanel`, with `SettingsPanel` still its only caller.** The riskiest change, made
   while nothing depends on the new abstraction. If it breaks, it breaks alone.
3. **Add the Privacy section.** Small, self-contained, exercises the settings rows.
4. **Close the dead ends** — send status, delivery persistence, remove `SENDING`, verification in
   `PeerDirectory`. No UI structure involved, and the chat info panel needs verification to exist.
5. **Build `ChatInfoPanel`** and delete `showSafetyNumber`.
6. **Build `SearchPanel`** and `TranscriptPanel.scrollTo`, and delete `showChatSearch`.
7. **Move new-chat validation inline** and delete the last replaceable dialogs.

---

## 9. Risks

**The `SettingsPanel` refactor is the largest single change.** It is a 544-line class losing its
mechanism while keeping its content. Mitigated by doing the extraction first, with Settings still
the only caller, and only then adding a second panel — so if the extraction breaks something, it
breaks in isolation with nothing else built on top.

**FlatLaf may fight the custom painting.** Custom components set their own colours and fonts through
`Theme`, so the exposure is limited to stock components inside custom containers — chiefly
`FormField` on the sign-in screen. Accepted, per section 4.

**Reduced motion must survive.** The `Motion` helper honours a reduced-motion setting; the extracted
panel must keep honouring it rather than reimplementing the animation.

---

## 10. Decisions taken

| Decision | Chosen | Rejected |
|---|---|---|
| Panel hosting | Extract a shared host | Copying the mechanism; docking as a third column |
| Search | Reuse the panel host for results | A find-in-page strip; leaving it a dialog |
| FlatLaf palette | Feed it from `Theme` | Stock FlatLight / FlatDark |
| Sign-in restyle | Accept it | Pinning its current appearance |
| `SENDING` | Remove | Keep as reserved; build an offline queue |
| "Fully working" | No dead ends in the UI | Feature additions; QA-script-complete |
