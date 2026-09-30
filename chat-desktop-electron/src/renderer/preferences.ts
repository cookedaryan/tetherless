export type Theme = 'dark' | 'light';

export interface Preferences {
  theme: Theme;
  reduceMotion: boolean;
  /** Tell me about a message that arrives while the window is not in front. */
  notifications: boolean;
  /**
   * Include the message text in that notification. Off by default: a notification can show on a
   * lock screen or be mirrored to another device by the OS.
   */
  notificationPreview: boolean;
}

const KEY = 'tetherless.preferences';

const DEFAULTS: Preferences = {
  theme: 'dark',
  reduceMotion: false,
  notifications: true,
  notificationPreview: false,
};

/**
 * Read from this window's own storage. Everything is guarded: storage can be unavailable or hold
 * something that is not ours, and a preference that fails to load must fall back to a default, not
 * stop the app opening.
 */
export function loadPreferences(): Preferences {
  try {
    const raw = window.localStorage.getItem(KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<Preferences>;
      return {
        theme: parsed.theme === 'light' ? 'light' : 'dark',
        reduceMotion: parsed.reduceMotion === true,
        // Preferences saved before these existed have neither key; that must read as the default,
        // not as "off", or upgrading would silently switch notifications off.
        notifications: parsed.notifications !== false,
        notificationPreview: parsed.notificationPreview === true,
      };
    }
  } catch {
    // Fall through to the defaults.
  }
  return { ...DEFAULTS };
}

export function savePreferences(preferences: Preferences): void {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(preferences));
  } catch {
    // Not persisted, still applied for this session. Losing a theme choice is not worth an error.
  }
}

/** Written onto the root element, which is what the stylesheet keys off. */
export function applyPreferences(preferences: Preferences): void {
  const root = document.documentElement;
  root.dataset.theme = preferences.theme;
  if (preferences.reduceMotion) {
    root.dataset.motion = 'reduced';
  } else {
    delete root.dataset.motion;
  }
}

export function motionReduced(): boolean {
  return (
    document.documentElement.dataset.motion === 'reduced' ||
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}
