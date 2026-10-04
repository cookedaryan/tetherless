import type { EngineConversation } from '../shared/protocol';

/**
 * The conversation to show for the open peer.
 *
 * The engine lists a conversation only once a message exists, since the list is built from stored
 * messages. A chat just started with **New chat** has none yet, and the pane used to treat that as
 * "nothing selected", which left no composer and no way to send the first message. A peer that is
 * open but not yet listed is shown as an empty conversation instead; the real row replaces it as
 * soon as the first message is stored.
 */
export function conversationFor(
  rows: EngineConversation[],
  peerId: string | null,
): EngineConversation | undefined {
  if (!peerId) {
    return undefined;
  }
  return (
    rows.find((c) => c.peerId === peerId) ?? {
      peerId,
      displayName: peerId.slice(0, 8),
      lastMessage: null,
      lastTimestamp: 0,
      lastFromSelf: false,
      unread: 0,
      verified: false,
      pinned: false,
      muted: false,
      archived: false,
    }
  );
}
