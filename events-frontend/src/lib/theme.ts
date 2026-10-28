/**
 * The colour theme the header's display settings switch (#2568). The inline script in index.html
 * reads the same key before paint. System stores nothing (#3715): no key means "follow the OS",
 * which is also the first-visit default.
 */
export type Theme = 'light' | 'dark' | 'system'

const THEME_KEY = 'theme'

export function storedTheme(): Theme {
  try {
    const value = localStorage.getItem(THEME_KEY)
    return value === 'light' || value === 'dark' ? value : 'system'
  } catch {
    return 'system'
  }
}

/** The OS preference. jsdom has no `matchMedia`; there the OS counts as light. */
export function osDarkQuery(): MediaQueryList | undefined {
  return window.matchMedia?.('(prefers-color-scheme: dark)')
}

export function applyTheme(theme: Theme) {
  const dark = theme === 'system' ? (osDarkQuery()?.matches ?? false) : theme === 'dark'
  document.documentElement.classList.toggle('dark', dark)
}

export function saveTheme(theme: Theme) {
  applyTheme(theme)
  try {
    if (theme === 'system') localStorage.removeItem(THEME_KEY)
    else localStorage.setItem(THEME_KEY, theme)
  } catch {
    // Ignore storage failures (e.g. private mode); persistence is best-effort.
  }
}
