import { test } from 'node:test';
import assert from 'node:assert/strict';
import { conversationFor } from './conversations.ts';
import type { EngineConversation } from '../shared/protocol';

const row = (peerId: string, displayName: string): EngineConversation => ({
  peerId,
  displayName,
  lastMessage: 'hi',
  lastTimestamp: 1,
  lastFromSelf: false,
  unread: 2,
  verified: true,
  pinned: false,
  muted: false,
  archived: false,
});

const ALICE = '6ef9a21e3d7b11f6058f879a1381b0ac';

test('nothing is open, so nothing is shown', () => {
  assert.equal(conversationFor([row(ALICE, 'Alice')], null), undefined);
});

test('an open peer that is listed is shown as listed', () => {
  const listed = row(ALICE, 'Alice');
  assert.equal(conversationFor([listed], ALICE), listed);
});

test('a peer just started with New chat is shown as an empty conversation, so it can be written to', () => {
  const shown = conversationFor([], ALICE);
  assert.ok(shown, 'a conversation, so the composer is drawn');
  assert.equal(shown.peerId, ALICE);
  assert.equal(shown.displayName, ALICE.slice(0, 8));
  assert.equal(shown.lastMessage, null);
  assert.equal(shown.unread, 0);
  assert.equal(shown.verified, false);
});

test('another peer being listed does not stand in for the open one', () => {
  const shown = conversationFor([row('b'.repeat(32), 'Bob')], ALICE);
  assert.equal(shown?.peerId, ALICE);
});
