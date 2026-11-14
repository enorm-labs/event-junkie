import { useRoute, useRouter } from 'vue-router'
import { signInAction, type SignInAction } from '@/lib/signInNext'

/**
 * The action a sign-in came back to do (#4969): `do` in the URL, put there by the button the
 * visitor pressed signed out. The page does it at once, without asking: a follow, an RSVP and a
 * saved search are all visible and undone with one press.
 */
export function useSignInAction() {
  const route = useRoute()
  const router = useRouter()
  // The URL keeps `do` until the replace lands, so a second look in between must not act again.
  let taken = false

  /** Whether the URL asks for [action]. If it does, `do` leaves the URL, so a reload does not repeat it. */
  function take(action: SignInAction): boolean {
    if (taken || signInAction(route.query.do) !== action) return false
    taken = true
    void router.replace({
      path: route.path,
      query: { ...route.query, do: undefined },
      hash: route.hash,
    })
    return true
  }

  return { take }
}
