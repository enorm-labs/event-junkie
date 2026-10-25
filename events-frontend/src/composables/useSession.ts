import { ref } from 'vue'
import { fetchSession } from '@/api/account'
import type { Account } from '@/api/types'

/**
 * Who this browser is signed in as, for the header entry and the follow buttons (#3889). One request
 * per visit: `/api/auth/session` is a 200 either way, so a signed-out visitor sees no console error,
 * and it sets no cookie.
 *
 * Module state, so every component reads one answer. `undefined` while unknown, `null` signed out.
 * The sign-in, sign-out and delete pages call {@link setSession}, so the header changes with them.
 */
const account = ref<Account | null | undefined>(undefined)
let pending: Promise<void> | null = null

export function setSession(next: Account | null) {
  account.value = next
  pending = Promise.resolve()
}

export function useSession() {
  if (!pending) {
    pending = fetchSession()
      .then((found) => {
        account.value = found
      })
      .catch(() => {
        // Unknown stays unknown: the header then shows the signed-out entry, which still works.
        account.value = null
        pending = null
      })
  }
  return { account, ready: () => pending ?? Promise.resolve() }
}
