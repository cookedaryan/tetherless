/**
 * The contract between the renderer and the Java engine.
 *
 * Imported by the main process, the preload bridge and the renderer, so all three agree on the
 * shape of every frame. Changing anything here changes `desktop-engine`'s `Engine.java` too — they
 * are two halves of one protocol and there is no compiler linking them.
 */

/** A message as the engine reports it. `peerId` is always the other party, never you. */
export interface EngineMessage {
  messageId: string;
  peerId: string;
  text: string;
  timestamp: number;
  direction: 'in' | 'out';
  status: 'SENT' | 'DELIVERED' | 'READ' | 'FAILED' | null;
  replyToId?: string | null;
  replyToPreview?: string | null;
  error?: boolean;
}

export interface EngineConversation {
  peerId: string;
  displayName: string;
  lastMessage: string | null;
  lastTimestamp: number;
  lastFromSelf: boolean;
  unread: number;
  verified: boolean;
  pinned: boolean;
  muted: boolean;
  archived: boolean;
}

export type ConnectionState =
  | 'DISCONNECTED'
  | 'CONNECTING'
  | 'RECONNECTING'
  | 'CONNECTED';

/** Commands the renderer may send. The engine refuses anything not named here. */
export interface Commands {
  status: { in: Record<string, never>; out: EngineStatus };
  unlock: { in: { passphrase: string; displayName?: string }; out: UnlockResult };
  connect: { in: { host?: string; port?: number }; out: { state: ConnectionState } };
  disconnect: { in: Record<string, never>; out: { state: ConnectionState } };
  listConversations: { in: Record<string, never>; out: { conversations: EngineConversation[] } };
  history: { in: { peerId: string; limit?: number }; out: { messages: EngineMessage[] } };
  send: {
    in: { peerId: string; text: string; replyToId?: string };
    out: { sent: EngineMessage | null };
  };
  startSecureChat: { in: { peerId: string }; out: Record<string, never> };
  renegotiate: { in: { peerId: string }; out: Record<string, never> };
  setActivePeer: { in: { peerId: string | null }; out: Record<string, never> };
  fingerprint: {
    in: { peerId?: string };
    out: { ours: string; theirs?: string | null; verified?: boolean };
  };
  setVerified: { in: { peerId: string; verified: boolean }; out: Record<string, never> };
  markRead: { in: { peerId: string }; out: Record<string, never> };
  readReceipt: { in: { peerId: string }; out: Record<string, never> };
  typing: { in: { peerId: string; typing: boolean }; out: Record<string, never> };
  search: { in: { query: string; limit?: number }; out: { messages: EngineMessage[] } };
  shutdown: { in: Record<string, never>; out: Record<string, never> };
}

export type CommandName = keyof Commands;

export interface EngineStatus {
  identityExists: boolean;
  unlocked: boolean;
  relay: string;
  clientId?: string;
  displayName?: string;
  fingerprint?: string;
  connectionState?: ConnectionState;
}

export interface UnlockResult {
  clientId: string;
  displayName: string;
  fingerprint: string;
  firstRun: boolean;
}

/** Events the engine pushes without being asked. */
export interface Events {
  ready: { identityExists: boolean };
  message: EngineMessage;
  deliveryStatus: { messageId: string; status: EngineMessage['status'] };
  typing: { peerId: string; typing: boolean };
  readReceipt: { peerId: string };
  connectionState: { state: ConnectionState };
  error: { message: string };
  /** The engine process died. Raised by the supervisor, not by the engine itself. */
  engineDown: { code: number | null };
}

export type EventName = keyof Events;

/** An error the engine returned. `code` is stable and meant to be branched on; `message` is not. */
export interface EngineError {
  code: string;
  message: string;
}

/** What the preload bridge exposes to the renderer. Deliberately the whole surface. */
export interface TetherlessBridge {
  invoke<K extends CommandName>(command: K, payload?: Commands[K]['in']): Promise<Commands[K]['out']>;
  on<K extends EventName>(event: K, handler: (payload: Events[K]) => void): () => void;
}
