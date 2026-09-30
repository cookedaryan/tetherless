import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { EngineConversation, EngineMessage } from '../../shared/protocol';
import type { Fingerprints } from '../useEngine';
import { ShieldIcon } from './Sidebar';

export function ChatPane(props: {
  conversation: EngineConversation | undefined;
  messages: EngineMessage[];
  fingerprints: Fingerprints | null;
  peerTyping: boolean;
  onSend: (text: string) => void;
  onSetVerified: (peerId: string, verified: boolean) => void;
}): JSX.Element {
  const { conversation, messages, fingerprints, peerTyping, onSend, onSetVerified } = props;
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
        <span className="avatar small">{conversation.displayName.slice(0, 2).toUpperCase()}</span>
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
        <Transcript key={conversation.peerId} messages={messages} typing={peerTyping} />
        {showInfo && (
          <ChatInfo
            conversation={conversation}
            fingerprints={fingerprints}
            onSetVerified={onSetVerified}
          />
        )}
      </div>

      <Composer onSend={onSend} />
    </main>
  );
}

function Transcript(props: { messages: EngineMessage[]; typing: boolean }): JSX.Element {
  const { messages, typing } = props;
  const foot = useRef<HTMLDivElement>(null);

  // How many messages were on screen after the previous render. Anything past that is new.
  // Zero means this is the initial load of a conversation, where nothing should animate: fading in
  // two hundred bubbles at once reads as the screen flickering, which is the thing to avoid.
  const seen = useRef(0);
  const firstFresh = seen.current;

  useLayoutEffect(() => {
    const arrived = seen.current > 0 && messages.length > seen.current;
    // A message that just arrived glides into view; opening a conversation jumps straight to the
    // foot. Layout effect, so either happens before paint rather than as a visible correction.
    foot.current?.scrollIntoView({ block: 'end', behavior: arrived ? 'smooth' : 'auto' });
    seen.current = messages.length;
  }, [messages.length, typing]);

  return (
    <div className="transcript">
      {messages.map((m, i) => {
        const previous = i > 0 ? messages[i - 1] : undefined;
        const newDay = !previous || !sameDay(previous.timestamp, m.timestamp);
        const fresh = firstFresh > 0 && i >= firstFresh;
        return (
          <div key={m.messageId ?? i}>
            {newDay && <div className="day">{dayLabel(m.timestamp)}</div>}
            <div className={`bubble-row ${m.direction}${fresh ? ' fresh' : ''}`}>
              <div className={`bubble ${m.direction}${m.error ? ' failed' : ''}`}>
                <span className="bubble-text">{m.text}</span>
                <span className="meta">
                  <span className="time">{clock(m.timestamp)}</span>
                  {m.direction === 'out' && <Ticks status={m.status} />}
                </span>
              </div>
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

function Composer({ onSend }: { onSend: (text: string) => void }): JSX.Element {
  const [text, setText] = useState('');
  const field = useRef<HTMLInputElement>(null);

  useEffect(() => field.current?.focus(), []);

  const submit = (): void => {
    if (text.trim()) {
      onSend(text);
      setText('');
    }
  };

  return (
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
        }}
      />
      <button onClick={submit} disabled={!text.trim()}>
        Send
      </button>
    </div>
  );
}

/** Five-character groups, which is how two people read a fingerprint to each other. */
function group(fingerprint: string | null | undefined): string {
  if (!fingerprint) {
    return '';
  }
  const plain = fingerprint.replace(/[^0-9a-fA-F]/g, '');
  return (plain.match(/.{1,5}/g) ?? []).join('  ');
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
