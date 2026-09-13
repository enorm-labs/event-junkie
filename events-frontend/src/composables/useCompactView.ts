import { ref } from 'vue'

/**
 * The compact view: a reader's choice to see the lists as text rows instead of posters (#1371).
 *
 * The preference lives in `localStorage` and on `<html>` as a class, and the inline script in
 * `index.html` applies it before first paint. That is the same shape as the theme, for the same
 * reason: a cold load must not draw posters and then collapse them. This composable mirrors the
 * class the script already set, so Vue starts from the state the document is in.
 *
 * It is a display preference, never a filter. It stays out of the URL, so a link a visitor shares
 * opens in the recipient's own view.
 */

const STORAGE_KEY = 'view'
const COMPACT_CLASS = 'compact'

// Module state, so every view and the header's toggle read one answer rather than one each.
const compact = ref(
  typeof document !== 'undefined' && document.documentElement.classList.contains(COMPACT_CLASS),
)

export function useCompactView() {
  function toggle() {
    compact.value = !compact.value
    document.documentElement.classList.toggle(COMPACT_CLASS, compact.value)
    try {
      localStorage.setItem(STORAGE_KEY, compact.value ? 'compact' : 'poster')
    } catch {
      // Ignore storage failures (e.g. private mode); persistence is best-effort, as for the theme.
    }
  }

  return { compact, toggle }
}
