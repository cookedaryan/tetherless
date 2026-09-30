import { useState } from 'react';
import type { EngineConversation } from '../../shared/protocol';

/** A peer id is 16 bytes rendered as hex. Anything else cannot be an address. */
const PEER_ID = /^[0-9a-f]{32}$/i;

export function Sidebar(props: {
  conversations: EngineConversation[];
  activePeer: string | null;
  selfId: string | undefined;
  onOpen: (peerId: string) => void;
  onStartChat: (peerId: string) => void;
}): JSX.Element {
  const { conversations, activePeer, selfId, onOpen, onStartChat } = props;
  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState('');

  const trimmed = draft.trim();
  // Validated in place rather than on submit: correcting a mistyped id should not mean dismissing
  // something first.
  const problem =
    trimmed.length === 0
      ? null
      : !PEER_ID.test(trimmed)
        ? 'That is not a peer id — 32 hexadecimal characters.'
        : trimmed.toLowerCase() === selfId?.toLowerCase()
          ? 'That is your own id.'
          : null;

  const submit = (): void => {
    if (trimmed.length > 0 && !problem) {
      onStartChat(trimmed.toLowerCase());
      setDraft('');
      setAdding(false);
    }
  };

  return (
    <aside className="sidebar">
      <header className="sidebar-head">
        <strong>Tetherless</strong>
        <span style={{ flexGrow: 1 }} />
        <button className="quiet small" onClick={() => setAdding((v) => !v)}>
          New chat
        </button>
      </header>

      {adding && (
        <div className="new-chat">
          <input
            autoFocus
            placeholder="Paste a peer id"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                submit();
              }
              if (e.key === 'Escape') {
                setAdding(false);
                setDraft('');
              }
            }}
          />
          <small className={problem ? 'error' : 'muted'}>{problem ?? ' '}</small>
        </div>
      )}

      <div className="conversations">
        {conversations.length === 0 ? (
          <p className="muted empty">No conversations yet. Use New chat with someone&apos;s id.</p>
        ) : (
          conversations.map((c) => (
            <button
              key={c.peerId}
              className={`row${c.peerId === activePeer ? ' selected' : ''}`}
              onClick={() => onOpen(c.peerId)}
            >
              <span className="avatar" data-slot={slotFor(c.peerId)}>
                {initials(c.displayName)}
              </span>
              <span className="row-body">
                <span className="row-title">
                  {c.displayName}
                  {c.verified && <ShieldIcon />}
                </span>
                <span className="row-preview">{c.lastMessage ?? ''}</span>
              </span>
              {c.unread > 0 && <span className="badge">{c.unread}</span>}
            </button>
          ))
        )}
      </div>
    </aside>
  );
}

function initials(name: string): string {
  const words = name.trim().split(/[\s_.-]+/).filter(Boolean);
  if (words.length === 0) {
    return '?';
  }
  const first = words[0]?.[0] ?? '?';
  const second = words.length > 1 ? (words[1]?.[0] ?? '') : '';
  return (first + second).toUpperCase();
}

/** Stable per peer, so someone keeps the same colour across restarts. */
function slotFor(peerId: string): number {
  let hash = 0;
  for (let i = 0; i < peerId.length; i++) {
    hash = (hash * 31 + peerId.charCodeAt(i)) >>> 0;
  }
  return hash % 7;
}

export function ShieldIcon(): JSX.Element {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--accent)"
      strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-label="Verified">
      <path d="M12 2.6 19.6 6v5.4c0 4.4-3.1 8.3-7.6 9.6-4.5-1.3-7.6-5.2-7.6-9.6V6Z" />
      <path d="m8.9 11.8 2.2 2.2 4-4.3" />
    </svg>
  );
}
