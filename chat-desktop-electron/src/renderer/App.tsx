import { useCallback, useEffect, useRef, useState } from 'react';
import { useEngine } from './useEngine';
import { notificationContent } from './format';
import { Sidebar } from './components/Sidebar';
import { ChatPane } from './components/ChatPane';
import { SettingsPanel } from './components/SettingsPanel';
import { applyPreferences, loadPreferences, savePreferences } from './preferences';
import type { Preferences } from './preferences';
import type { EngineError, EngineMessage } from '../shared/protocol';

export function App(): JSX.Element {
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [preferences, setPreferences] = useState<Preferences>(loadPreferences);

  // Read at the moment a message arrives, not captured when the subscription was made.
  const preferencesRef = useRef(preferences);
  preferencesRef.current = preferences;
  const openRef = useRef<(peerId: string) => Promise<void>>(() => Promise.resolve());

  const e = useEngine(
    useCallback((message: EngineMessage) => {
      const p = preferencesRef.current;
      if (!p.notifications) {
        return;
      }
      // Silent while the window is in front: you can see it arrive, and the unread count covers
      // the other conversations. A notification is for when you are not looking.
      if (document.hasFocus()) {
        return;
      }
      if (typeof Notification === 'undefined' || Notification.permission !== 'granted') {
        return;
      }
      const { title, body } = notificationContent(message, p.notificationPreview);
      // Tagged by peer, so a burst from one person replaces its notification instead of stacking.
      const shown = new Notification(title, { body, tag: message.peerId });
      shown.onclick = () => {
        window.focus();
        void openRef.current(message.peerId);
      };
    }, []),
  );
  openRef.current = e.open;

  useEffect(() => {
    if (typeof Notification !== 'undefined' && Notification.permission === 'default') {
      void Notification.requestPermission();
    }
  }, []);

  const changePreferences = useCallback((next: Preferences) => {
    setPreferences(next);
    applyPreferences(next);
    savePreferences(next);
  }, []);
  const closeSettings = useCallback(() => setSettingsOpen(false), []);

  if (!e.status) {
    return <Centred>Starting…</Centred>;
  }

  if (!e.status.unlocked) {
    return (
      <SignIn
        firstRun={!e.status.identityExists}
        error={e.error}
        onError={e.setError}
        onUnlock={e.unlock}
      />
    );
  }

  const active = e.conversations.find((c) => c.peerId === e.activePeer);

  return (
    <div className="app">
      <div className="titlebar">
        <span className="muted small">{e.status.displayName}</span>
        <span className="mono small self-id" title="Your peer id — give this to someone to be reached">
          {e.status.clientId}
        </span>
        <span style={{ flexGrow: 1 }} />
        <span className={`dot ${e.connection.toLowerCase()}`} />
        <span className="muted small">{label(e.connection)}</span>
        {e.connection !== 'CONNECTED' && (
          <button className="quiet small" onClick={e.connect}>
            Connect
          </button>
        )}
      </div>

      {e.error && (
        <div className="banner" role="alert">
          {e.error}
          <button className="quiet small" onClick={() => e.setError(null)}>
            Dismiss
          </button>
        </div>
      )}

      <div className="body">
        <div className="side">
          <Sidebar
            conversations={e.conversations}
            activePeer={e.activePeer}
            selfId={e.status.clientId}
            searchQuery={e.searchQuery}
            searchResults={e.searchResults}
            searching={e.searching}
            onOpen={(id) => void e.open(id)}
            onStartChat={(id) => void e.startChat(id)}
            onSearch={e.runSearch}
            onOpenResult={e.openResult}
            onOpenSettings={() => setSettingsOpen(true)}
          />
          <SettingsPanel
            open={settingsOpen}
            status={e.status}
            connection={e.connection}
            preferences={preferences}
            onChange={changePreferences}
            onClose={closeSettings}
          />
        </div>
        <ChatPane
          conversation={active}
          messages={e.messages}
          fingerprints={e.fingerprints}
          peerTyping={e.peerTyping}
          focusId={e.focusId}
          selfId={e.status.clientId}
          replyTo={e.replyTo}
          onReply={e.beginReply}
          onCancelReply={e.cancelReply}
          onJumpTo={e.jumpToMessage}
          onSend={(text) => void e.send(text)}
          onSetVerified={(id, v) => void e.setVerified(id, v)}
        />
      </div>
    </div>
  );
}

function label(state: string): string {
  if (state === 'CONNECTED') {
    return 'connected';
  }
  if (state === 'CONNECTING' || state === 'RECONNECTING') {
    return 'connecting…';
  }
  return 'offline';
}

function SignIn(props: {
  firstRun: boolean;
  error: string | null;
  onError: (message: string | null) => void;
  onUnlock: (passphrase: string, displayName?: string) => Promise<void>;
}): JSX.Element {
  const { firstRun, error, onError, onUnlock } = props;
  const [passphrase, setPassphrase] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [busy, setBusy] = useState(false);

  const blocked = busy || passphrase.length === 0 || (firstRun && displayName.trim().length === 0);

  const submit = async (): Promise<void> => {
    if (blocked) {
      return;
    }
    setBusy(true);
    onError(null);
    try {
      await onUnlock(passphrase, firstRun ? displayName.trim() : undefined);
      setPassphrase('');
    } catch (err) {
      onError((err as EngineError).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="signin">
      <div className="signin-brand">
        <h1>Tetherless</h1>
        <p className="muted">Your keys never leave this device.</p>
        <p className="muted small">
          Messages are encrypted here and decrypted on your peer&apos;s machine. The relay in the
          middle is assumed hostile — it routes ciphertext and can read none of it.
        </p>
      </div>

      <div className="signin-form">
        <h2>{firstRun ? 'Create your identity' : 'Welcome back'}</h2>
        <p className="muted">
          {firstRun
            ? 'One passphrase protects your key and encrypts your history.'
            : 'Unlock this device to continue.'}
        </p>

        {firstRun && (
          <label>
            <span className="field-label">Display name</span>
            <input value={displayName} onChange={(ev) => setDisplayName(ev.target.value)} autoFocus />
          </label>
        )}

        <label>
          <span className="field-label">Passphrase</span>
          <input
            type="password"
            value={passphrase}
            autoFocus={!firstRun}
            onChange={(ev) => setPassphrase(ev.target.value)}
            onKeyDown={(ev) => {
              if (ev.key === 'Enter') {
                void submit();
              }
            }}
          />
        </label>

        {error && <p className="error">{error}</p>}

        <button onClick={() => void submit()} disabled={blocked}>
          {busy ? 'Working…' : firstRun ? 'Create identity' : 'Unlock'}
        </button>

        {firstRun && (
          <p className="muted small">
            This passphrase is never stored and cannot be recovered. Lose it and the message history
            is gone with it.
          </p>
        )}
      </div>
    </div>
  );
}

function Centred({ children }: { children: React.ReactNode }): JSX.Element {
  return <div className="centred">{children}</div>;
}
