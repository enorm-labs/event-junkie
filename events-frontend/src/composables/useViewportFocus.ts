import { onScopeDispose, ref, watch } from 'vue'

/**
 * The card hover, for a device that has no pointer.
 *
 * The poster's grayscale reveal is written as `group-hover:`, and Tailwind 4 compiles every hover
 * variant inside `@media (hover: hover)`. On a phone the rule therefore never matches and the
 * posters stay grey for the whole visit. This marks an element while it crosses the middle band of
 * the viewport instead, so scrolling is what reveals the picture.
 *
 * A pointer device is left alone: `:hover` already answers there, and two triggers for one effect
 * would fight over the same card.
 */

/** Only the middle tenth of the viewport counts, so one card is focused at a time. */
const MIDDLE_BAND = '-45% 0px -45% 0px'

// One observer for every card on the page. A per-card observer costs the same callback work and
// keeps the browser tracking dozens of roots.
let observer: IntersectionObserver | null = null
const listeners = new WeakMap<Element, (focused: boolean) => void>()

function sharedObserver(): IntersectionObserver | null {
  if (typeof IntersectionObserver === 'undefined') return null
  observer ??= new IntersectionObserver(
    (entries) => {
      for (const entry of entries) listeners.get(entry.target)?.(entry.isIntersecting)
    },
    { rootMargin: MIDDLE_BAND },
  )
  return observer
}

// A device that gains a mouse mid-visit keeps the scroll trigger until the next navigation. The
// alternative is a media-query listener per card for a case a visitor cannot hit by accident.
//
// No window, or no `matchMedia`, means a server pass or jsdom: answer "pointer", so nothing is
// observed for an element that never gets painted.
function hasPointer(): boolean {
  if (typeof window === 'undefined') return true
  return window.matchMedia?.('(hover: hover)').matches ?? true
}

export function useViewportFocus() {
  const el = ref<HTMLElement | null>(null)
  const focused = ref(false)

  if (hasPointer()) return { el, focused }

  const forget = (target: HTMLElement) => {
    sharedObserver()?.unobserve(target)
    listeners.delete(target)
  }

  watch(el, (next, previous) => {
    if (previous) forget(previous)
    if (!next) {
      focused.value = false
      return
    }
    const io = sharedObserver()
    if (!io) return
    listeners.set(next, (value) => {
      focused.value = value
    })
    io.observe(next)
  })

  onScopeDispose(() => {
    if (el.value) forget(el.value)
  })

  return { el, focused }
}
