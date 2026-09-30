import { useCallback, useEffect, useRef, useState } from 'react';
import { friendlyError } from './format';
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
export function useEngine(
  /** Called for every incoming message. What to do about it is the caller's policy. */
  onIncoming?: (message: EngineMessage) => void,
) {
  const [status, setStatus] = useState<EngineStatus | null>(null);
  /** The message being replied to, shown above the composer until sent or cancelled. */
  const [replyTo, setReplyTo] = useState<EngineMessage | null>(null);
  // Held in a ref so the event subscription, made once, always calls the current callback.
  const incomingRef = useRef(onIncoming);
  incomingRef.current = onIncoming;
  const replyRef = useRef<EngineMessage | null>(null);
  replyRef.current = replyTo;
  const messagesRef = useRef<EngineMessage[]>([]);
  const [connection, setConnection] = useState<ConnectionState>('DISCONNECTED');
  const [conversations, setConversations] = useState<EngineConversation[]>([]);
  const [activePeer, setActivePeer] = useState<string | null>(null);
  const [messages, setMessages] = useState<EngineMessage[]>([]);
  messagesRef.current = messages;
  const [fingerprints, setFingerprints] = useState<Fingerprints | null>(null);
  const [peerTyping, setPeerTyping] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<EngineMessage[] | null>(null);
  const [searching, setSearching] = useState(false);
  /** The message to scroll to and flash after opening a conversation from a search hit. */
  const [focusId, setFocusId] = useState<string | null>(null);

  // Read inside event handlers, which close over the value at subscribe time otherwise.
  const activeRef = useRef<string | null>(null);
  activeRef.current = activePeer;

  // A search response is only used if no newer search has started since. Without this a slow
  // search for "he" can land after the one for "hello" and put the wrong results on screen.
  const searchSeq = useRef(0);
  const searchTimer = useRef<number | undefined>(undefined);
  const focusTimer = useRef<number | undefined>(undefined);

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
    async (peerId: string, jumpTo?: string) => {
      setActivePeer(peerId);
      setPeerTyping(false);
      window.clearTimeout(focusTimer.current);
      setFocusId(null);
      // A reply drafted in one conversation must not follow you into another and quote a message
      // to someone who never wrote it.
      setReplyTo(null);
      // Cleared before the history arrives. Otherwise the previous person's messages sit under the
      // new person's name for as long as the read takes, which in a chat app is worse than blank.
      setMessages([]);
      setFingerprints(null);
      try {
        await engine().invoke('setActivePeer', { peerId });
        // A search hit can be older than the usual window. Load far enough back to reach it rather
        // than opening the conversation and silently showing nothing.
        const { messages: rows } = await engine().invoke('history', {
          peerId,
          limit: jumpTo ? 5000 : 200,
        });
        setMessages(rows);
        if (jumpTo) {
          if (rows.some((m) => m.messageId === jumpTo)) {
            setFocusId(jumpTo);
            focusTimer.current = window.setTimeout(() => setFocusId(null), 2600);
          } else {
            setError('That message is older than the history this window can load.');
          }
        }
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

  const runSearch = useCallback((query: string) => {
    setSearchQuery(query);
    window.clearTimeout(searchTimer.current);
    const trimmed = query.trim();
    if (!trimmed) {
      searchSeq.current += 1; // Invalidates anything still in flight.
      setSearchResults(null);
      setSearching(false);
      return;
    }
    setSearching(true);
    const mine = ++searchSeq.current;
    // Debounced: the engine decrypts row by row, so a search per keystroke would queue work behind
    // the one the user actually wants.
    searchTimer.current = window.setTimeout(() => {
      engine()
        .invoke('search', { query: trimmed, limit: 50 })
        .then(({ messages: found }) => {
          if (mine === searchSeq.current) {
            setSearchResults(found);
          }
        })
        .catch((e: unknown) => {
          if (mine === searchSeq.current) {
            say(e);
          }
        })
        .finally(() => {
          if (mine === searchSeq.current) {
            setSearching(false);
          }
        });
    }, 200);
  }, []);

  /**
   * Scrolls to a message in the conversation already open - what clicking a quote does. If it is
   * not among the loaded messages it says so; a click that does nothing is a dead end.
   */
  const jumpToMessage = useCallback((messageId: string) => {
    if (!messagesRef.current.some((m) => m.messageId === messageId)) {
      setError('That message is older than the history this window has loaded.');
      return;
    }
    window.clearTimeout(focusTimer.current);
    // Cleared first, then set on the next frame: setting the id it already has would do nothing,
    // so a second click on the same quote while it was still highlighted would not scroll.
    setFocusId(null);
    window.requestAnimationFrame(() => {
      setFocusId(messageId);
      focusTimer.current = window.setTimeout(() => setFocusId(null), 2600);
    });
  }, []);

  const openResult = useCallback(
    (hit: EngineMessage) => {
      runSearch(''); // Selecting a hit ends the search, as picking a conversation would.
      void open(hit.peerId, hit.messageId);
    },
    [open, runSearch],
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
      const quoting = replyRef.current;
      try {
        const { sent } = await engine().invoke('send', {
          peerId,
          text: text.trim(),
          ...(quoting ? { replyToId: quoting.messageId } : {}),
        });
        if (sent) {
          setMessages((prev) => [...prev, sent]);
          setReplyTo(null);
        } else {
          setError('That message could not be sent.');
        }
        await refreshConversations();
      } catch (e) {
        // A quote that can no longer be found is dropped from the composer, so the next attempt is
        // not refused for the same reason. Any other failure keeps the draft reply.
        if ((e as EngineError).code === 'reply_target_missing') {
          setReplyTo(null);
        }
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
      engine().on('error', ({ message }) => setError(friendlyError(message))),
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
        incomingRef.current?.(incoming);
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
    searchQuery,
    searchResults,
    searching,
    focusId,
    replyTo,
    beginReply: setReplyTo,
    cancelReply: () => setReplyTo(null),
    jumpToMessage,
    runSearch,
    openResult,
    unlock,
    connect,
    open,
    startChat,
    send,
    setVerified,
  };
}
