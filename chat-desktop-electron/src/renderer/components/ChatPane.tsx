import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { EngineConversation, EngineMessage } from '../../shared/protocol';
import type { Fingerprints } from '../useEngine';
import { group, initials, quoteAuthor, slotFor } from '../format';
import { motionReduced } from '../preferences';
import { ShieldIcon } from './Sidebar';

export function ChatPane(props: {
  conversation: EngineConversation | undefined;
  messages: EngineMessage[];
  fingerprints: Fingerprints | null;
  peerTyping: boolean;
  focusId: string | null;
  selfId: string | undefined;
  replyTo: EngineMessage | null;
  onSend: (text: string) => void;
  onReply: (message: EngineMessage) => void;
  onCancelReply: () => void;
  onJumpTo: (messageId: string) => void;
  onSetVerified: (peerId: string, verified: boolean) => void;
}): JSX.Element {
  const {
    conversation, messages, fingerprints, peerTyping, focusId, selfId, replyTo,
    onSend, onReply, onCancelReply, onJumpTo, onSetVerified,
  } = props;
  const [showInfo, setShowInfo] = useState(false);

  if (!conversation) {
    return (
      <main className="chat empty-chat">
        <p className="muted">Select a conversation, or start one with a peer id.</p>
        <p className="muted small">Messages are end-to-end encrypted.</p>
      </main>
    );
  }

  return (
    <main className="chat">
      <header className="chat-head">
        <span className="avatar small" data-slot={slotFor(conversation.peerId)}>
          {initials(conversation.displayName)}
        </span>
        <span className="chat-head-body">
          <span className="chat-title">
            {conversation.displayName}
            {conversation.verified && <ShieldIcon />}
          </span>
          <span className="muted small">
            {peerTyping ? 'typing…' : 'encrypted'}
          </span>
        </span>
        <button className="quiet small" onClick={() => setShowInfo((v) => !v)}>
          {showInfo ? 'Close' : 'Chat info'}
        </button>
      </header>

      <div className="chat-body">
        <Transcript
          key={conversation.peerId}
          messages={messages}
          typing={peerTyping}
          focusId={focusId}
          peerName={conversation.displayName}
          selfId={selfId}
          onReply={onReply}
          onJumpTo={onJumpTo}
        />
        {showInfo && (
          <ChatInfo
            conversation={conversation}
            fingerprints={fingerprints}
            onSetVerified={onSetVerified}
          />
        )}
      </div>

      <Composer
        // Keyed by conversation, so a half-typed message does not follow you to someone else.
        key={conversation.peerId}
        onSend={onSend}
        replyTo={replyTo}
        replyAuthor={replyTo ? quoteAuthor(replyTo.direction === 'out' ? selfId : replyTo.peerId, selfId, conversation.displayName) : ''}
        onCancelReply={onCancelReply}
      />
    </main>
  );
}

function Transcript(props: {
  messages: EngineMessage[];
  typing: boolean;
  focusId: string | null;
  peerName: string;
  selfId: string | undefined;
  onReply: (message: EngineMessage) => void;
  onJumpTo: (messageId: string) => void;
}): JSX.Element {
  const { messages, typing, focusId, peerName, selfId, onReply, onJumpTo } = props;
  const foot = useRef<HTMLDivElement>(null);
  const target = useRef<HTMLDivElement>(null);

  // How many messages were on screen after the previous render. Anything past that is new.
  // Zero means this is the initial load of a conversation, where nothing should animate: fading in
  // two hundred bubbles at once reads as the screen flickering, which is the thing to avoid.
  const seen = useRef(0);
  const firstFresh = seen.current;

  useLayoutEffect(() => {
    const arrived = seen.current > 0 && messages.length > seen.current;
    // A message that just arrived glides into view; opening a conversation jumps straight to the
    // foot. Layout effect, so either happens before paint rather than as a visible correction.
    const glide = arrived && !motionReduced();
    foot.current?.scrollIntoView({ block: 'end', behavior: glide ? 'smooth' : 'auto' });
    seen.current = messages.length;
  }, [messages.length, typing]);

  // Declared after the effect above so that, on the load that opens a conversation from a search
  // hit, this one runs last and wins: the view lands on the message rather than the foot.
  // Keyed on focusId alone, so the flash being cleared later does not re-run it and yank the view
  // back down.
  useLayoutEffect(() => {
    if (focusId) {
      target.current?.scrollIntoView({ block: 'center', behavior: 'auto' });
    }
  }, [focusId]);

  return (
    <div className="transcript">
      {messages.map((m, i) => {
        const previous = i > 0 ? messages[i - 1] : undefined;
        const newDay = !previous || !sameDay(previous.timestamp, m.timestamp);
        const fresh = firstFresh > 0 && i >= firstFresh;
        return (
          <div key={m.messageId ?? i}>
            {newDay && <div className="day">{dayLabel(m.timestamp)}</div>}
            <div
              ref={m.messageId === focusId ? target : undefined}
              className={`bubble-row ${m.direction}${fresh ? ' fresh' : ''}${m.messageId === focusId ? ' hit' : ''}`}
            >
              {/* A real button, shown on hover or keyboard focus, so reply is reachable without a mouse. */}
              {m.direction === 'out' && (
                <button className="reply-btn" onClick={() => onReply(m)} aria-label="Reply to this message" title="Reply">
                  ↩
                </button>
              )}
              <div className={`bubble ${m.direction}${m.error ? ' failed' : ''}`}>
                {m.replyToId && (
                  <button
                    className="quote"
                    onClick={() => onJumpTo(m.replyToId as string)}
                    title="Go to the original message"
                  >
                    <span className="quote-author">
                      {quoteAuthor(m.replyToSender, selfId, peerName)}
                    </span>
                    <span className="quote-text">{m.replyToPreview}</span>
                  </button>
                )}
                <span className="bubble-text">{m.text}</span>
                <span className="meta">
                  <span className="time">{clock(m.timestamp)}</span>
                  {m.direction === 'out' && <Ticks status={m.status} />}
                </span>
              </div>
              {m.direction === 'in' && (
                <button className="reply-btn" onClick={() => onReply(m)} aria-label="Reply to this message" title="Reply">
                  ↩
                </button>
              )}
            </div>
          </div>
        );
      })}
      {typing && (
        <div className="bubble-row in">
          <div className="bubble in typing">
            <span /><span /><span />
          </div>
        </div>
      )}
      <div ref={foot} />
    </div>
  );
}

function Ticks({ status }: { status: EngineMessage['status'] }): JSX.Element | null {
  // Queued on this machine, not yet handed to the relay. Drawn as a clock, never as a tick: a tick
  // says "sent", and this message has not left.
  if (status === 'PENDING') {
    return <span className="tick pending" title="Waiting to send">◷</span>;
  }
  if (status === 'FAILED') {
    return <span className="tick failed" title="Not delivered">!</span>;
  }
  if (status === 'READ') {
    return <span className="tick read" title="Read">✓✓</span>;
  }
  if (status === 'DELIVERED') {
    return <span className="tick" title="Delivered">✓✓</span>;
  }
  return <span className="tick" title="Sent">✓</span>;
}

function ChatInfo(props: {
  conversation: EngineConversation;
  fingerprints: Fingerprints | null;
  onSetVerified: (peerId: string, verified: boolean) => void;
}): JSX.Element {
  const { conversation, fingerprints, onSetVerified } = props;
  const theirs = fingerprints?.theirs;

  return (
    <aside className="info">
      <h3>Safety number</h3>
      <p className="muted small">
        Read these aloud to each other. If every group matches, nobody is sitting in the middle.
      </p>

      <label className="fp-label">You</label>
      <p className="mono fp">{group(fingerprints?.ours)}</p>

      <label className="fp-label">{conversation.displayName}</label>
      <p className="mono fp">{theirs ? group(theirs) : 'No key received yet.'}</p>

      <button
        className={conversation.verified ? 'quiet' : ''}
        disabled={!theirs}
        onClick={() => onSetVerified(conversation.peerId, !conversation.verified)}
      >
        {conversation.verified ? 'Verified — undo' : 'Mark as verified'}
      </button>
      {!theirs && (
        <p className="muted small">
          Nothing to compare until they have sent something.
        </p>
      )}

      <h3>Peer id</h3>
      <p className="mono small wrap">{conversation.peerId}</p>
    </aside>
  );
}

function Composer(props: {
  onSend: (text: string) => void;
  replyTo: EngineMessage | null;
  replyAuthor: string;
  onCancelReply: () => void;
}): JSX.Element {
  const { onSend, replyTo, replyAuthor, onCancelReply } = props;
  const [text, setText] = useState('');
  const field = useRef<HTMLInputElement>(null);

  useEffect(() => field.current?.focus(), []);
  // Starting a reply puts the cursor in the box, so the next thing typed is the reply.
  useEffect(() => {
    if (replyTo) {
      field.current?.focus();
    }
  }, [replyTo]);

  const submit = (): void => {
    if (text.trim()) {
      onSend(text);
      setText('');
    }
  };

  return (
    <div className="composer-wrap">
      {replyTo && (
        <div className="reply-bar">
          <span className="quote-bar">
            <span className="quote-author">Replying to {replyAuthor}</span>
            <span className="quote-text">{replyTo.text}</span>
          </span>
          <button className="icon" onClick={onCancelReply} aria-label="Cancel reply" title="Cancel reply">
            ✕
          </button>
        </div>
      )}
      <div className="composer">
        <input
          ref={field}
          placeholder="Message"
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              submit();
            }
            if (e.key === 'Escape' && replyTo) {
              onCancelReply();
            }
          }}
        />
        <button onClick={submit} disabled={!text.trim()}>
          Send
        </button>
      </div>
    </div>
  );
}

function clock(ts: number): string {
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function sameDay(a: number, b: number): boolean {
  return new Date(a).toDateString() === new Date(b).toDateString();
}

function dayLabel(ts: number): string {
  const date = new Date(ts);
  const today = new Date();
  if (date.toDateString() === today.toDateString()) {
    return 'Today';
  }
  return date.toLocaleDateString([], { day: 'numeric', month: 'short' });
}
