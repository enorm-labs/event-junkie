import { onBeforeUnmount, ref, type Ref } from 'vue'

/** Below Tailwind's `sm`, where a phone is. */
const NARROW_QUERY = '(max-width: 639.98px)'

/** Whether the viewport is narrower than `sm`, kept current. jsdom has no `matchMedia` and counts as wide. */
export function useNarrowViewport(): Ref<boolean> {
  const query = window.matchMedia?.(NARROW_QUERY)
  const narrow = ref(query?.matches ?? false)
  const onChange = (event: MediaQueryListEvent) => (narrow.value = event.matches)
  query?.addEventListener('change', onChange)
  onBeforeUnmount(() => query?.removeEventListener('change', onChange))
  return narrow
}
