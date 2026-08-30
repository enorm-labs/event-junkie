import { readonly, ref } from 'vue'

import { api, unwrap } from '@/api/client'
import type { AppMeta } from '@/api/types'

/**
 * The running backend's version and commit, for the footer.
 *
 * Module-level state rather than `useAsync`: this is one immutable fact about the deployment, so
 * it is fetched once for the lifetime of the page and shared by every caller — not re-requested
 * per route or per component. It also has no error state on purpose. A missing version is not
 * worth an error message in a footer; the line simply does not render.
 */
const meta = ref<AppMeta | null>(null)
let inFlight: Promise<void> | null = null

function load(): Promise<void> {
  inFlight ??= unwrap(api.GET('/api/meta'))
    .then((result) => {
      meta.value = result
    })
    .catch(() => {
      // Deliberately swallowed. The backend being unreachable is already visible everywhere else
      // on the page; a broken version line should not add noise, and must never surface an error.
      meta.value = null
    })
  return inFlight
}

export function useAppMeta() {
  void load()
  return { meta: readonly(meta) }
}

/** Resets the module-level cache. Tests only — production fetches exactly once per page load. */
export function resetAppMetaForTests(): void {
  meta.value = null
  inFlight = null
}
