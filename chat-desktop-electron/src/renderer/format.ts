/**
 * The letters inside an avatar: the first letter of the first two words.
 *
 * Shared, not repeated per component. The conversation list and the chat header both draw an
 * avatar for the same person, and when each worked out its own the two disagreed - one showed "B",
 * the other "BO", in different colours - so one person looked like two.
 */
export function initials(name: string): string {
  const words = name.trim().split(/[\s_.-]+/).filter(Boolean);
  if (words.length === 0) {
    return '?';
  }
  const first = words[0]?.[0] ?? '?';
  const second = words.length > 1 ? (words[1]?.[0] ?? '') : '';
  return (first + second).toUpperCase();
}

/** Which of the seven avatar colours a peer gets. Stable, so nobody changes colour on restart. */
export function slotFor(peerId: string): number {
  let hash = 0;
  for (let i = 0; i < peerId.length; i++) {
    hash = (hash * 31 + peerId.charCodeAt(i)) >>> 0;
  }
  return hash % 7;
}

/**
 * Turns a relay error code into something a person can act on.
 *
 * The relay's reason arrives as a bare protocol constant. Anything not recognised is passed through
 * unchanged, because hiding an error that has no friendlier form is worse than showing it raw.
 */
export function friendlyError(reason: string): string {
  if (reason === 'RECIPIENT_OFFLINE') {
    // Deliberately no promise about what happens to the message. The relay's error carries no
    // message id, so it cannot be tied to one bubble, and whether the message is queued or lost
    // depends on whether a session existed. What is certain is that the person is not there.
    return 'The person you wrote to is offline. Your message may not reach them until they reconnect.';
  }
  return reason;
}

/** Five-character groups, which is how two people read a fingerprint to each other. */
export function group(fingerprint: string | null | undefined): string {
  if (!fingerprint) {
    return '';
  }
  const plain = fingerprint.replace(/[^0-9a-fA-F]/g, '');
  return (plain.match(/.{1,5}/g) ?? []).join('  ');
}

/**
 * Splits `text` around case-insensitive occurrences of `query`, for highlighting a search hit.
 *
 * Returns segments rather than an HTML string on purpose: message text is attacker-controlled, and
 * the caller renders each segment as a text node. The query is matched literally, never as a
 * pattern, so a search for `(` or `.*` cannot throw or match everything.
 */
export function splitOnMatch(text: string, query: string): Array<{ text: string; hit: boolean }> {
  const needle = query.trim().toLowerCase();
  if (!needle) {
    return [{ text, hit: false }];
  }
  const haystack = text.toLowerCase();
  const out: Array<{ text: string; hit: boolean }> = [];
  let from = 0;
  for (;;) {
    const at = haystack.indexOf(needle, from);
    if (at < 0) {
      break;
    }
    if (at > from) {
      out.push({ text: text.slice(from, at), hit: false });
    }
    out.push({ text: text.slice(at, at + needle.length), hit: true });
    from = at + needle.length;
  }
  if (from < text.length) {
    out.push({ text: text.slice(from), hit: false });
  }
  return out.length > 0 ? out : [{ text, hit: false }];
}

/**
 * A short excerpt of `text` centred on the first match, so a hit deep in a long message is still
 * visible in a one-line result rather than pushed off the end by the ellipsis.
 */
export function excerpt(text: string, query: string, width = 90): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  if (flat.length <= width) {
    return flat;
  }
  const at = flat.toLowerCase().indexOf(query.trim().toLowerCase());
  if (at < 0 || at < width / 2) {
    return flat.slice(0, width) + '…';
  }
  const start = Math.min(at - Math.floor(width / 3), flat.length - width);
  return '…' + flat.slice(Math.max(0, start), Math.max(0, start) + width) + (start + width < flat.length ? '…' : '');
}
