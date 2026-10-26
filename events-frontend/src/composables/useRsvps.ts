import { ref, watch } from 'vue'
import { fetchRsvps, prepareCsrf, type RsvpStatus, setRsvp, withdrawRsvp } from '@/api/account'
import { useSession } from './useSession'

/**
 * The account's RSVPs (#3892), for the event page's toggle and the cards' marker. Module state and
 * one request per visit, as `useSession` does, so a page of cards asks once, not once per card.
 * Signed out, it stays empty and asks nothing.
 */
const statuses = ref<Record<string, RsvpStatus>>({})
let loaded: Promise<void> | null = null
let watching = false

async function load(): Promise<void> {
  try {
    statuses.value = await fetchRsvps()
  } catch {
    // The marks are a help on a public page; without them the page still works.
    statuses.value = {}
  }
}

export function useRsvps() {
  const { account, ready } = useSession()
  if (!watching) {
    watching = true
    // A sign-in or a sign-out on another page of this visit changes whose RSVPs these are.
    watch(account, (now, before) => {
      if (now === undefined || now?.email === before?.email) return
      statuses.value = {}
      loaded = now ? load() : Promise.resolve()
    })
  }
  loaded ??= ready().then(() => (account.value ? load() : undefined))

  /** Sets, changes or (with null) withdraws the RSVP at once, and goes back if the BFF refuses. */
  async function choose(slug: string, status: RsvpStatus | null): Promise<void> {
    const before = statuses.value[slug]
    const next = { ...statuses.value }
    if (status) next[slug] = status
    else delete next[slug]
    statuses.value = next
    try {
      await prepareCsrf()
      if (status) await setRsvp(slug, status)
      else await withdrawRsvp(slug)
    } catch (e) {
      const reverted = { ...statuses.value }
      if (before) reverted[slug] = before
      else delete reverted[slug]
      statuses.value = reverted
      throw e
    }
  }

  return { statuses, choose, ready: () => loaded ?? Promise.resolve() }
}
