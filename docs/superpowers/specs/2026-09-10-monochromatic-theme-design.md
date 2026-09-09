# Warm neutral monochromatic theme — design

**Date:** 2026-09-10
**Status:** approved, not yet planned

A monochromatic palette for the desktop client: warm stone neutrals with a single desaturated
accent, replacing the cool blue-slate Telegram-derived palette in `chat-desktop/.../ui/Theme.java`.

---

## 1. Why

The current palette is cool and blue-dominant, and carries three separate hue families: the blue
accent, a coral unread badge, and seven rainbow avatar gradients. The request is a calmer, more
tonal interface. This design takes the furthest of the three monochromatic directions considered —
warm neutrals rather than a single-hue blue or a neutral grey — because it is the only one that
changes the product's character rather than merely desaturating it.

---

## 2. The accent decision

Three accents were considered inside the warm-neutral direction:

| Accent | Verdict |
|---|---|
| Muted ochre | Rejected. Fully tonal and the prettiest, but functionally weak — an ochre unread badge on warm stone is hard to spot, and this UI uses colour for genuine signals (delivered / read / failed / verified). |
| Desaturated indigo | Rejected. Preserves a thread back to the existing blue, but fights the warm neutrals instead of resolving with them. |
| **Desaturated teal / verdigris** | **Chosen.** A cool, low-chroma counterpoint that supplies the one piece of contrast the UI needs — read ticks, verified shield, primary button — without breaking the calm. The classic pairing with warm stone. |

---

## 3. Tokens

Every entry maps one-for-one onto an existing accessor in `Theme.java`, so nothing new is introduced
and nothing is left without a value.

| Token | Night | Day |
|---|---|---|
| `pageBg` | `#141210` | `#F2EEE7` |
| `sidebarBg` | `#1C1916` | `#F6F2EB` |
| `sidebarHover` | `#262119` | `#ECE6DD` |
| `sidebarSelected` | `#322B24` | `#FFFDF9` |
| `sidebarSelectedText` | `#F5F0E8` | `#1F1B17` |
| `headerBg` | `#1C1916` | `#FFFDF9` |
| `divider` | `#2B2621` | `#E2DAD0` |
| `chatBg` | `#181513` | `#FFFDF9` |
| `bubbleIn` | `#272119` | `#EFEAE1` |
| `bubbleOut` | `#3B332A` | `#E3DACB` |
| `bubbleError` | `#3E2622` | `#F6E3DE` |
| `textPrimary` | `#F0EAE1` | `#1F1B17` |
| `textSecondary` | `#9B9187` | `#8A8177` |
| `timeIn` | `#8B8177` | `#9C9389` |
| `timeOut` | `#B6A997` | `#8A7E6C` |
| `tick` | `#86ACA2` | `#4F7F74` |
| `accent` | `#7FA39A` | `#4F7F74` |
| `accentHover` | `#6D9188` | `#416B62` |
| `badge` | `#B5745C` | `#B5745C` |
| `badgeText` | `#FFF8F2` | `#FFFFFF` |
| `inputBg` | `#262119` | `#EFEAE1` |
| `composerBg` | `#1C1916` | `#FFFDF9` |
| `icon` | `#9B9187` | `#8A8177` |
| `iconHover` | `#CAC0B3` | `#57504A` |
| `danger` | `#C4695A` | `#A8503F` |

Outgoing bubbles become warm sand/umber rather than blue, so a transcript reads as tonal paper with
teal appearing only where it carries meaning.

---

## 4. Two decisions worth stating

**The unread badge stays deliberately non-accent.** `Theme.java` documents the existing choice
plainly — *"Coral rather than accent, so an unread count is not another blue."* That reasoning is
preserved rather than discarded: the badge becomes **terracotta** `#B5745C`, which keeps it inside
the warm family while still refusing to be another teal.

**Avatars keep seven slots, spanning lightness rather than hue.** Collapsing them to a single hue
would make peers nearly indistinguishable at 54px with no photographs, so the seven gradients become
warm tones across a wide lightness range with mild hue drift:

| Slot | From | To |
|---|---|---|
| Sand | `#D8C4A4` | `#BCA382` |
| Olive | `#BFBE9A` | `#9C9B72` |
| Clay | `#D6AE96` | `#B98A6E` |
| Rose clay | `#D2A9A2` | `#B4837B` |
| Umber | `#B39A80` | `#8E7458` |
| Stone | `#BDB3A6` | `#978C7D` |
| Cocoa | `#A98D7C` | `#836654` |

---

## 5. Accepted cost

**Glanceability drops.** The current rainbow avatars let a peer be identified by hue before the name
is read. Warm-mono avatars are separable by value only, so identification leans harder on initials
and position. This is the deliberate price of the direction and is accepted, not overlooked.

---

## 6. Scope

**In scope:** the palette itself, proven first as variant artboards on the existing design canvas so
it can be compared against the current palette before any code changes.

**Out of scope,** stated so it is not mistaken for oversight: changing `ChatWallpaper`, the aurora on
the sign-in screen, typography, spacing, iconography, or any layout. This is a colour change only.

---

## 7. How it gets applied

The palette is validated on the canvas first. If it holds up there, applying it to the product is a
separate, planned change touching:

- `chat-desktop/.../ui/Theme.java` — the `pick(lightRgb, darkRgb)` values.
- `chat-desktop/.../ui/Avatars.java` — the `GRADIENTS` table.
- `chat-desktop-compose/.../theme/AppTheme.kt` — `LightColors` / `DarkColors`, which mirror the same
  tokens and must not be allowed to drift from the Java source of truth.
- `ThemeLookAndFeelTest` and any test asserting a specific colour value.
