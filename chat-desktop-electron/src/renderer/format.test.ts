import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  excerpt,
  friendlyError,
  group,
  initials,
  notificationContent,
  quoteAuthor,
  slotFor,
  splitOnMatch,
} from './format.ts';

test('with previews off, the message text never reaches the notification', () => {
  // The whole point of the setting: a notification can appear on a lock screen or be mirrored to
  // another device by the OS, so what it says has to be a choice, and the default must be silence.
  const n = notificationContent({ displayName: 'Bob', text: 'the launch code is 0000' }, false);
  assert.equal(n.title, 'Bob');
  assert.equal(n.body, 'New message');
  assert.ok(!JSON.stringify(n).includes('launch'), 'text leaked into a preview-off notification');
});

test('with previews on, the text is shown flattened and cut to a sensible length', () => {
  const short = notificationContent({ displayName: 'Bob', text: 'see\nyou  soon' }, true);
  assert.equal(short.body, 'see you soon');

  const long = notificationContent({ displayName: 'Bob', text: 'x'.repeat(500) }, true);
  assert.ok(long.body.length <= 121, 'body should be cut, got ' + long.body.length);
  assert.ok(long.body.endsWith('…'));
});

test('a peer with no name still produces a usable notification', () => {
  assert.equal(notificationContent({ displayName: '', text: 'hi' }, false).title, 'Tetherless');
  assert.equal(notificationContent({ displayName: undefined, text: 'hi' }, false).title, 'Tetherless');
});

test('a quote is attributed to you when you wrote it, otherwise to the other person', () => {
  assert.equal(quoteAuthor('me-id', 'me-id', 'Bob'), 'You');
  assert.equal(quoteAuthor('bob-id', 'me-id', 'Bob'), 'Bob');
  // Ids compare case-insensitively: they are hex, and one side may have lower-cased it.
  assert.equal(quoteAuthor('ME-ID', 'me-id', 'Bob'), 'You');
  assert.equal(quoteAuthor(null, 'me-id', 'Bob'), 'Bob');
  assert.equal(quoteAuthor('bob-id', undefined, 'Bob'), 'Bob');
});

test('initials come from the first two words, and never throw on an odd name', () => {
  assert.equal(initials('Aria Chen'), 'AC');
  assert.equal(initials('bob'), 'B');
  assert.equal(initials('  mary-jane  smith '), 'MJ');
  assert.equal(initials(''), '?');
  assert.equal(initials('   '), '?');
});

test('a peer always gets the same avatar slot, and it is one of the seven', () => {
  const id = '197c935712025cfa2351a981f2a5bedf';
  assert.equal(slotFor(id), slotFor(id));
  for (const sample of [id, 'a', '', 'ffffffffffffffffffffffffffffffff', 'Bob']) {
    const slot = slotFor(sample);
    assert.ok(Number.isInteger(slot) && slot >= 0 && slot <= 6, `slot ${slot} out of range`);
  }
});

test('different peers do not all land on one slot', () => {
  const slots = new Set(
    Array.from({ length: 40 }, (_, i) => slotFor(i.toString(16).padStart(32, '0'))),
  );
  assert.ok(slots.size >= 4, 'expected a spread of colours, got ' + slots.size);
});

test('a relay error code is turned into a sentence, and unknown text passes through', () => {
  assert.match(friendlyError('RECIPIENT_OFFLINE'), /offline/i);
  assert.doesNotMatch(friendlyError('RECIPIENT_OFFLINE'), /RECIPIENT_OFFLINE/);
  assert.equal(friendlyError('Some other problem'), 'Some other problem');
});

test('a fingerprint is read in groups of five', () => {
  assert.equal(group('EB38:057D:EB22'), 'EB380  57DEB  22');
  assert.equal(group(null), '');
  assert.equal(group(undefined), '');
});

test('matching ignores case and keeps the original casing in the output', () => {
  assert.deepEqual(splitOnMatch('Hello HELLO hello', 'hello'), [
    { text: 'Hello', hit: true },
    { text: ' ', hit: false },
    { text: 'HELLO', hit: true },
    { text: ' ', hit: false },
    { text: 'hello', hit: true },
  ]);
});

test('the query is matched literally, never as a pattern', () => {
  // A regex built from these would throw or match everything. They must simply be text.
  assert.deepEqual(splitOnMatch('a.*b (c)', '.*'), [
    { text: 'a', hit: false },
    { text: '.*', hit: true },
    { text: 'b (c)', hit: false },
  ]);
  assert.deepEqual(splitOnMatch('f(x)', '('), [
    { text: 'f', hit: false },
    { text: '(', hit: true },
    { text: 'x)', hit: false },
  ]);
});

test('an empty or whitespace query highlights nothing', () => {
  assert.deepEqual(splitOnMatch('anything', ''), [{ text: 'anything', hit: false }]);
  assert.deepEqual(splitOnMatch('anything', '   '), [{ text: 'anything', hit: false }]);
});

test('no match returns the text whole', () => {
  assert.deepEqual(splitOnMatch('nothing here', 'zzz'), [{ text: 'nothing here', hit: false }]);
});

test('markup in a message is preserved as text, never interpreted', () => {
  const segments = splitOnMatch('<img src=x onerror=alert(1)> hi', 'hi');
  assert.equal(segments.map((s) => s.text).join(''), '<img src=x onerror=alert(1)> hi');
});

test('the segments always reassemble the original text', () => {
  for (const [text, query] of [
    ['the quick brown fox', 'quick'],
    ['aaaa', 'aa'],
    ['overlap overlap', 'lap o'],
    ['émoji \u{1F512} lock', '\u{1F512}'],
  ] as const) {
    assert.equal(splitOnMatch(text, query).map((s) => s.text).join(''), text);
  }
});

test('a short message is returned as it is, with whitespace flattened', () => {
  assert.equal(excerpt('one\n two', 'two'), 'one two');
});

test('a match deep in a long message stays visible in the excerpt', () => {
  const long = 'x'.repeat(200) + ' NEEDLE ' + 'y'.repeat(200);
  const out = excerpt(long, 'needle', 60);
  assert.ok(out.toLowerCase().includes('needle'), 'the match must survive truncation: ' + out);
  assert.ok(out.startsWith('…'), 'a mid-message excerpt is marked as cut at the front');
});

test('a match near the start is shown from the start', () => {
  const out = excerpt('needle ' + 'z'.repeat(300), 'needle', 60);
  assert.ok(out.startsWith('needle'));
  assert.ok(out.endsWith('…'));
});
