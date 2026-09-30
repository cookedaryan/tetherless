import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  ConnectionState,
  EngineConversation,
  EngineError,
  EngineMessage,
  EngineStatus,
  TetherlessBridge,
} from '../shared/protocol';

declare global {
  interface Window {
    tetherless: TetherlessBridge;
  }
}

const engine = (): TetherlessBridge => window.tetherless;

export interface Fingerprints {
  ours: string;
  theirs?: string | null;
  verified?: boolean;
}

/**
 * All of the renderer's state, in one place.
 *
 * The engine is the source of truth: after anything that changes stored state the conversation list
 * is re-read rather than patched locally, so unread counts and previews cannot drift from the
 * database. Only the open transcript is appended to optimistically, because that is the one place
 * where waiting for a round trip would be visible.
 */
export function useEngine() {
  const [status, setStatus] = useState<EngineStatus | null>(null);
  const [connection, setConnection] = useState<ConnectionState>('DISCONNECTED');
  const [conversations, setConversations] = useState<EngineConversation[]>([]);
  const [activePeer, setActivePeer] = useState<string | null>(null);
  const [messages, setMessages] = useState<EngineMessage[]>([]);
  const [fingerprints, setFingerprints] = useState<Fingerprints | null>(null);
  const [peerTyping, setPeerTyping] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Read inside event handlers, which close over the value at subscribe time otherwise.
  const activeRef = useRef<string | null>(null);
  activeRef.current = activePeer;

  const say = (e: unknown): void => setError((e as EngineError).message ?? String(e));

  const refreshConversations = useCallback(async () => {
    const { conversations: rows } = await engine().invoke('listConversations');
    setConversations(rows);
  }, []);

  const refreshStatus = useCallback(async () => {
    const next = await engine().invoke('status');
    setStatus(next);
    if (next.connectionState) {
      setConnection(next.connectionState);
    }
    if (next.unlocked) {
      await refreshConversations();
    }
  }, [refreshConversations]);

  const open = useCallback(
    async (peerId: string) => {
      setActivePeer(peerId);
      setPeerTyping(false);
      // Cleared before the history arrives. Otherwise the previous person's messages sit under the
      // new person's name for as long as the read takes, which in a chat app is worse than blank.
      setMessages([]);
      setFingerprints(null);
      try {
        await engine().invoke('setActivePeer', { peerId });
        const { messages: rows } = await engine().invoke('history', { peerId, limit: 200 });
        setMessages(rows);
        setFingerprints(await engine().invoke('fingerprint', { peerId }));
        await engine().invoke('markRead', { peerId });
        await engine().invoke('readReceipt', { peerId }).catch(() => undefined);
        await refreshConversations();
      } catch (e) {
        say(e);
      }
    },
    [refreshConversations],
  );

  const startChat = useCallback(
    async (peerId: string) => {
      try {
        await engine().invoke('startSecureChat', { peerId });
        await open(peerId);
      } catch (e) {
        say(e);
      }
    },
    [open],
  );

  const send = useCallback(
    async (text: string) => {
      const peerId = activeRef.current;
      if (!peerId || !text.trim()) {
        return;
      }
      try {
        const { sent } = await engine().invoke('send', { peerId, text: text.trim() });
        if (sent) {
          setMessages((prev) => [...prev, sent]);
        } else {
          setError('That message could not be sent.');
        }
        await refreshConversations();
      } catch (e) {
        say(e);
      }
    },
    [refreshConversations],
  );

  const unlock = useCallback(
    async (passphrase: string, displayName?: string) => {
      await engine().invoke('unlock', { passphrase, displayName });
      setError(null);
      await refreshStatus();
      // Connecting straight after unlock is what the user means by opening the app.
      engine().invoke('connect', {}).catch(say);
    },
    [refreshStatus],
  );

  const connect = useCallback(() => {
    engine().invoke('connect', {}).catch(say);
  }, []);

  const setVerified = useCallback(async (peerId: string, verified: boolean) => {
    try {
      await engine().invoke('setVerified', { peerId, verified });
      setFingerprints(await engine().invoke('fingerprint', { peerId }));
      const { conversations: rows } = await engine().invoke('listConversations');
      setConversations(rows);
    } catch (e) {
      say(e);
    }
  }, []);

  useEffect(() => {
    const off = [
      engine().on('ready', () => void refreshStatus()),
      engine().on('connectionState', ({ state }) => setConnection(state)),
      engine().on('engineDown', () =>
        setError('The engine stopped. Nothing can be sent until it restarts.'),
      ),
      engine().on('error', ({ message }) => setError(message)),
      engine().on('typing', ({ peerId, typing }) => {
        if (peerId === activeRef.current) {
          setPeerTyping(typing);
        }
      }),
      engine().on('deliveryStatus', ({ messageId, status: next }) => {
        setMessages((prev) =>
          prev.map((m) => (m.messageId === messageId ? { ...m, status: next } : m)),
        );
      }),
      engine().on('message', (incoming) => {
        if (incoming.peerId === activeRef.current) {
          setPeerTyping(false);
          setMessages((prev) => [...prev, incoming]);
          engine().invoke('markRead', { peerId: incoming.peerId }).catch(() => undefined);
          engine().invoke('readReceipt', { peerId: incoming.peerId }).catch(() => undefined);
        }
        void refreshConversations();
      }),
    ];
    void refreshStatus();
    return () => off.forEach((f) => f());
  }, [refreshStatus, refreshConversations]);

  return {
    status,
    connection,
    conversations,
    activePeer,
    messages,
    fingerprints,
    peerTyping,
    error,
    setError,
    unlock,
    connect,
    open,
    startChat,
    send,
    setVerified,
  };
}
