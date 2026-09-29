import { useCallback, useEffect, useState } from 'react';
import type {
  ConnectionState,
  EngineConversation,
  EngineError,
  EngineStatus,
  TetherlessBridge,
} from '../shared/protocol';

declare global {
  interface Window {
    tetherless: TetherlessBridge;
  }
}

const engine = () => window.tetherless;

export function App(): JSX.Element {
  const [status, setStatus] = useState<EngineStatus | null>(null);
  const [connection, setConnection] = useState<ConnectionState>('DISCONNECTED');
  const [conversations, setConversations] = useState<EngineConversation[]>([]);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    const next = await engine().invoke('status');
    setStatus(next);
    if (next.connectionState) {
      setConnection(next.connectionState);
    }
    if (next.unlocked) {
      const { conversations: rows } = await engine().invoke('listConversations');
      setConversations(rows);
    }
  }, []);

  useEffect(() => {
    const offReady = engine().on('ready', () => void refresh());
    const offState = engine().on('connectionState', ({ state }) => setConnection(state));
    const offDown = engine().on('engineDown', () =>
      setError('The engine stopped. Messages cannot be sent until it restarts.'),
    );
    void refresh();
    return () => {
      offReady();
      offState();
      offDown();
    };
  }, [refresh]);

  if (!status) {
    return <Centred>Starting…</Centred>;
  }

  if (!status.unlocked) {
    return (
      <SignIn
        firstRun={!status.identityExists}
        error={error}
        onError={setError}
        onUnlocked={() => {
          setError(null);
          void refresh();
        }}
      />
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <header
        style={{
          height: 60,
          flex: '0 0 60px',
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          padding: '0 18px',
          background: 'var(--surface)',
          borderBottom: '1px solid var(--divider)',
        }}
      >
        <strong>Tetherless</strong>
        <span className="muted">{status.displayName}</span>
        <span style={{ flexGrow: 1 }} />
        <span className="muted">{connection.toLowerCase()}</span>
        <button
          onClick={() => {
            engine()
              .invoke('connect', {})
              .catch((e: EngineError) => setError(e.message));
          }}
          disabled={connection === 'CONNECTED'}
        >
          Connect
        </button>
      </header>

      {error && (
        <div style={{ padding: '10px 18px', background: 'var(--bubble-error)', color: 'var(--danger)' }}>
          {error}
        </div>
      )}

      <div style={{ display: 'flex', flexGrow: 1, minHeight: 0 }}>
        <aside
          style={{
            width: 320,
            flex: '0 0 320px',
            background: 'var(--surface)',
            borderRight: '1px solid var(--divider)',
            overflowY: 'auto',
          }}
        >
          {conversations.length === 0 ? (
            <p className="muted" style={{ padding: 18 }}>
              No conversations yet.
            </p>
          ) : (
            conversations.map((c) => (
              <div
                key={c.peerId}
                style={{
                  height: 68,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 11,
                  padding: '0 14px',
                  borderBottom: '1px solid var(--divider)',
                }}
              >
                <div style={{ flexGrow: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 600 }}>{c.displayName}</div>
                  <div
                    className="muted"
                    style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                  >
                    {c.lastMessage}
                  </div>
                </div>
                {c.unread > 0 && (
                  <span
                    style={{
                      background: 'var(--unread)',
                      color: 'var(--unread-ink)',
                      borderRadius: 10,
                      padding: '2px 7px',
                      fontSize: 12,
                      fontWeight: 700,
                    }}
                  >
                    {c.unread}
                  </span>
                )}
              </div>
            ))
          )}
        </aside>

        <main
          style={{
            flexGrow: 1,
            background: 'var(--chat-bg)',
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'center',
            alignItems: 'center',
            gap: 10,
          }}
        >
          <p className="muted">Your safety number</p>
          <p className="mono" style={{ maxWidth: 440, textAlign: 'center', lineHeight: 1.8 }}>
            {status.fingerprint}
          </p>
        </main>
      </div>
    </div>
  );
}

function SignIn(props: {
  firstRun: boolean;
  error: string | null;
  onError: (message: string | null) => void;
  onUnlocked: () => void;
}): JSX.Element {
  const { firstRun, error, onError, onUnlocked } = props;
  const [passphrase, setPassphrase] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (): Promise<void> => {
    setBusy(true);
    onError(null);
    try {
      await engine().invoke('unlock', {
        passphrase,
        displayName: firstRun ? displayName : undefined,
      });
      setPassphrase('');
      onUnlocked();
    } catch (e) {
      onError((e as EngineError).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Centred>
      <div style={{ width: 340, display: 'flex', flexDirection: 'column', gap: 22 }}>
        <div>
          <h1 style={{ margin: 0, fontSize: 24, letterSpacing: '-0.4px' }}>
            {firstRun ? 'Create your identity' : 'Welcome back'}
          </h1>
          <p className="muted" style={{ marginTop: 6 }}>
            {firstRun
              ? 'One passphrase protects your key and encrypts your history.'
              : 'Unlock this device to continue.'}
          </p>
        </div>

        {firstRun && (
          <label style={{ display: 'block' }}>
            <span className="muted" style={{ fontSize: 12.5, fontWeight: 600 }}>
              Display name
            </span>
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          </label>
        )}

        <label style={{ display: 'block' }}>
          <span className="muted" style={{ fontSize: 12.5, fontWeight: 600 }}>
            Passphrase
          </span>
          <input
            type="password"
            value={passphrase}
            onChange={(e) => setPassphrase(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !busy) {
                void submit();
              }
            }}
          />
        </label>

        {error && <p style={{ color: 'var(--danger)', margin: 0 }}>{error}</p>}

        <button onClick={() => void submit()} disabled={busy || passphrase.length === 0}>
          {firstRun ? 'Create identity' : 'Unlock'}
        </button>

        {firstRun && (
          <p className="muted" style={{ fontSize: 11.5, lineHeight: 1.5, margin: 0 }}>
            This passphrase is never stored and cannot be recovered. Lose it and the message history
            is gone with it.
          </p>
        )}
      </div>
    </Centred>
  );
}

function Centred({ children }: { children: React.ReactNode }): JSX.Element {
  return (
    <div
      style={{
        height: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'var(--page)',
      }}
    >
      {children}
    </div>
  );
}
