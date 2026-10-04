import { nextTick, onMounted, readonly, ref, watch } from 'vue'

/** Requests in flight whose answer changes the height of the page. */
const pending = ref(0)

/**
 * Starts a content request and returns the call that ends it; a second call does nothing.
 * `useAsync` calls it for every request that shows a loading state, so its views need nothing more.
 */
export function beginContentLoad(): () => void {
  pending.value++
  let open = true
  return () => {
    if (!open) return
    open = false
    pending.value--
  }
}

/**
 * True once the routed view has mounted and no content request is in flight. The footer renders on
 * it: a footer added below finished content moves nothing, but content arriving above it moves it
 * (#2567). Call it from the component that holds the `RouterView`, whose `onMounted` runs after the view's.
 */
export function useViewLoaded() {
  const loaded = ref(false)
  let mounted = false
  let generation = 0

  async function update() {
    const current = ++generation
    if (!mounted || pending.value > 0) {
      loaded.value = false
      return
    }
    // A view can start a second request from a watcher on the first answer. That watcher runs in
    // the next flush, so wait for it before the content counts as complete.
    await nextTick()
    if (current === generation) loaded.value = pending.value === 0
  }

  onMounted(() => {
    mounted = true
    void update()
  })
  watch(pending, () => void update())

  return readonly(loaded)
}
