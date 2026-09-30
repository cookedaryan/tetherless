import { useEffect, useRef, useState } from 'react';
import type { EngineConversation, EngineMessage } from '../../shared/protocol';
import { excerpt, initials, slotFor, splitOnMatch } from '../format';

/** A peer id is 16 bytes rendered as hex. Anything else cannot be an address. */
const PEER_ID = /^[0-9a-f]{32}$/i;

export function Sidebar(props: {
  conversations: EngineConversation[];
  activePeer: string | null;
  selfId: string | undefined;
  searchQuery: string;
  searchResults: EngineMessage[] | null;
  searching: boolean;
  onOpen: (peerId: string) => void;
  onStartChat: (peerId: string) => void;
  onSearch: (query: string) => void;
  onOpenResult: (hit: EngineMessage) => void;
  onOpenSettings: () => void;
}): JSX.Element {
  const {
    conversations, activePeer, selfId, searchQuery, searchResults, searching,
    onOpen, onStartChat, onSearch, onOpenResult, onOpenSettings,
  } = props;
  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState('');
  const searchBox = useRef<HTMLInputElement>(null);

  // Ctrl/Cmd+F goes to search, which is where anyone used to a chat app reaches for it.
  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') {
        e.preventDefault();
        searchBox.current?.focus();
        searchBox.current?.select();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const nameOf = (peerId: string): string =>
    conversations.find((c) => c.peerId === peerId)?.displayName ?? peerId.slice(0, 8);

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

  const searchActive = searchQuery.trim().length > 0;

  return (
    <aside className="sidebar">
      <header className="sidebar-head">
        <strong>Tetherless</strong>
        <span style={{ flexGrow: 1 }} />
        <button className="quiet small" onClick={() => setAdding((v) => !v)}>
          New chat
        </button>
        <button className="icon" onClick={onOpenSettings} aria-label="Settings" title="Settings">
          <GearIcon />
        </button>
      </header>

      <div className="search">
        <SearchIcon />
        <input
          ref={searchBox}
          placeholder="Search messages"
          value={searchQuery}
          aria-label="Search messages"
          onChange={(e) => onSearch(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') {
              onSearch('');
              e.currentTarget.blur();
            }
          }}
        />
        {searchActive && (
          <button className="icon" onClick={() => onSearch('')} aria-label="Clear search">
            ✕
          </button>
        )}
      </div>

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
        {searchActive ? (
          <Results
            query={searchQuery}
            results={searchResults}
            searching={searching}
            nameOf={nameOf}
            onOpenResult={onOpenResult}
          />
        ) : conversations.length === 0 ? (
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

function Results(props: {
  query: string;
  results: EngineMessage[] | null;
  searching: boolean;
  nameOf: (peerId: string) => string;
  onOpenResult: (hit: EngineMessage) => void;
}): JSX.Element {
  const { query, results, searching, nameOf, onOpenResult } = props;

  // Three states that must not look alike: still looking, found nothing, and found something.
  // "No results" shown while a search is still running would be a lie.
  if (results === null || searching) {
    return <p className="muted empty">Searching…</p>;
  }
  if (results.length === 0) {
    return <p className="muted empty">No messages found.</p>;
  }

  return (
    <>
      <p className="muted small results-count">
        {results.length === 50 ? 'First 50 matches' : `${results.length} match${results.length === 1 ? '' : 'es'}`}
      </p>
      {results.map((hit) => (
        <button key={hit.messageId} className="row result" onClick={() => onOpenResult(hit)}>
          <span className="avatar small" data-slot={slotFor(hit.peerId)}>
            {initials(nameOf(hit.peerId))}
          </span>
          <span className="row-body">
            <span className="row-title">
              {nameOf(hit.peerId)}
              <span className="muted small when">{when(hit.timestamp)}</span>
            </span>
            <span className="row-preview">
              {splitOnMatch(excerpt(hit.text, query), query).map((part, i) =>
                part.hit ? <mark key={i}>{part.text}</mark> : <span key={i}>{part.text}</span>,
              )}
            </span>
          </span>
        </button>
      ))}
    </>
  );
}

function when(ts: number): string {
  const date = new Date(ts);
  return date.toDateString() === new Date().toDateString()
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString([], { day: 'numeric', month: 'short' });
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

function SearchIcon(): JSX.Element {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" aria-hidden="true">
      <circle cx="11" cy="11" r="7" />
      <path d="M16.8 16.8 21 21" />
    </svg>
  );
}

function GearIcon(): JSX.Element {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-2.9 1.2v.2a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-2.9-1.2l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A1.7 1.7 0 0 0 3 15H2.8a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.2-2.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1A1.7 1.7 0 0 0 9 4.6V4.4a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 2.9 1.2l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0 1.2 2.9h.2a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.6 1.1Z" />
    </svg>
  );
}
