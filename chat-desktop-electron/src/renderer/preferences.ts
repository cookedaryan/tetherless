export type Theme = 'dark' | 'light';

export interface Preferences {
  theme: Theme;
  reduceMotion: boolean;
}

const KEY = 'tetherless.preferences';

const DEFAULTS: Preferences = { theme: 'dark', reduceMotion: false };

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
