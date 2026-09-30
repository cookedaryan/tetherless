import { useEffect, useRef, useState } from 'react';
import type { ConnectionState, EngineStatus } from '../../shared/protocol';
import { group } from '../format';
import type { Preferences, Theme } from '../preferences';

declare const __APP_VERSION__: string;

/**
 * Slides over the conversation list rather than replacing the chat, so what you were reading is
 * still there when you close it.
 *
 * Every control here does something. An update-check switch is deliberately absent: this client has
 * no update check, and a switch wired to nothing is exactly the kind of dead end worth not building.
 */
export function SettingsPanel(props: {
  open: boolean;
  status: EngineStatus;
  connection: ConnectionState;
  preferences: Preferences;
  onChange: (next: Preferences) => void;
  onClose: () => void;
}): JSX.Element {
  const { open, status, connection, preferences, onChange, onClose } = props;
  const closeButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    closeButton.current?.focus();
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const setTheme = (theme: Theme): void => onChange({ ...preferences, theme });

  return (
    <section
      className={`settings${open ? ' open' : ''}`}
      aria-label="Settings"
      aria-hidden={!open}
    >
      <header className="settings-head">
        <button ref={closeButton} className="icon" onClick={onClose} aria-label="Close settings">
          ←
        </button>
        <strong>Settings</strong>
      </header>

      <div className="settings-body">
        <h4>Appearance</h4>

        <div className="setting">
          <div className="setting-text">
            <span>Theme</span>
            <small className="muted">Applies to the whole window straight away.</small>
          </div>
          <div className="segmented" role="group" aria-label="Theme">
            <button
              className={preferences.theme === 'dark' ? 'on' : ''}
              aria-pressed={preferences.theme === 'dark'}
              onClick={() => setTheme('dark')}
            >
              Dark
            </button>
            <button
              className={preferences.theme === 'light' ? 'on' : ''}
              aria-pressed={preferences.theme === 'light'}
              onClick={() => setTheme('light')}
            >
              Light
            </button>
          </div>
        </div>

        <div className="setting">
          <div className="setting-text">
            <span>Reduce motion</span>
            <small className="muted">Turns off the animations throughout the app.</small>
          </div>
          <Switch
            on={preferences.reduceMotion}
            label="Reduce motion"
            onChange={(reduceMotion) => onChange({ ...preferences, reduceMotion })}
          />
        </div>

        <h4>Identity</h4>

        <div className="setting stack">
          <span>{status.displayName}</span>
          <small className="muted">
            This name is a label peers see. It proves nothing on its own — only the peer id and the
            safety number identify you.
          </small>
        </div>

        <div className="setting stack">
          <span className="label">Peer id</span>
          <p className="mono wrap small">{status.clientId}</p>
          <CopyButton text={status.clientId ?? ''} />
        </div>

        <div className="setting stack">
          <span className="label">Your safety number</span>
          <p className="mono fp">{group(status.fingerprint)}</p>
        </div>

        <h4>Relay</h4>

        <div className="setting">
          <div className="setting-text">
            <span className="mono">{status.relay}</span>
            <small className="muted">Pinned certificate · TLS 1.3</small>
          </div>
          <span className={`pill ${connection.toLowerCase()}`}>{stateLabel(connection)}</span>
        </div>
        <p className="muted small note">
          To use a different relay, set <span className="mono">host</span> and{' '}
          <span className="mono">port</span> in <span className="mono">config.properties</span> in
          your profile folder, then restart.
        </p>

        <h4>About</h4>

        <div className="setting stack">
          <span>Tetherless {__APP_VERSION__}</span>
          <small className="muted">
            Messages are encrypted on this device and decrypted on your peer&apos;s. The relay routes
            ciphertext and can read none of it. It can see who talks to whom, and when.
          </small>
        </div>
      </div>
    </section>
  );
}

function Switch(props: {
  on: boolean;
  label: string;
  onChange: (on: boolean) => void;
}): JSX.Element {
  return (
    <button
      role="switch"
      aria-checked={props.on}
      aria-label={props.label}
      className={`switch${props.on ? ' on' : ''}`}
      onClick={() => props.onChange(!props.on)}
    >
      <span />
    </button>
  );
}

function CopyButton({ text }: { text: string }): JSX.Element {
  const [done, setDone] = useState(false);

  const copy = async (): Promise<void> => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      // The async clipboard API can be refused for a file:// origin. Selecting a scratch field and
      // copying works without any permission, so fall back to that rather than fail silently.
      const scratch = document.createElement('textarea');
      scratch.value = text;
      scratch.style.position = 'fixed';
      scratch.style.opacity = '0';
      document.body.appendChild(scratch);
      scratch.select();
      document.execCommand('copy');
      scratch.remove();
    }
    setDone(true);
    window.setTimeout(() => setDone(false), 1600);
  };

  return (
    <button className="quiet small" onClick={() => void copy()} disabled={!text}>
      {done ? 'Copied' : 'Copy'}
    </button>
  );
}

function stateLabel(state: ConnectionState): string {
  if (state === 'CONNECTED') {
    return 'connected';
  }
  if (state === 'CONNECTING' || state === 'RECONNECTING') {
    return 'connecting…';
  }
  return 'offline';
}
