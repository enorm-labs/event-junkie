import type { Locale } from '@/i18n/locales'
import { api, unwrap } from './client'
import type { Account, EventSummary, EventSummaryPage, Follow } from './types'

/**
 * The sign-in and account calls (#3887, ADR-053). Each write needs the CSRF cookie first, so every
 * page that writes calls {@link prepareCsrf} when it mounts.
 */

/** Sets the `XSRF-TOKEN` cookie. Only these pages ask for it, so no other page stores a cookie. */
export async function prepareCsrf(): Promise<void> {
  await unwrap(api.GET('/api/auth/csrf'))
}

/** Who this browser is signed in as, or null, and whether new accounts can be created (#3906). */
export async function fetchSessionState(): Promise<{
  account: Account | null
  signUpOpen: boolean
}> {
  const session = await unwrap(api.GET('/api/auth/session'))
  return { account: session?.account ?? null, signUpOpen: session?.signUpOpen ?? true }
}

/** Who this browser is signed in as, or null. */
export async function fetchSession(): Promise<Account | null> {
  return (await fetchSessionState()).account
}

/**
 * Sends a sign-in link. The answer is the same whether or not the address has an account. [next] is
 * the page the link returns to; the BFF keeps it only when it is a page of this site (#3889).
 */
export async function requestLink(email: string, locale: Locale, next?: string): Promise<void> {
  await unwrap(api.POST('/api/auth/link', { body: { email, locale, next } }))
}

/** Uses the link's token. A 401 means it was used, expired or never issued. */
export async function signIn(token: string): Promise<Account> {
  return unwrap(api.POST('/api/auth/sign-in', { body: { token } }))
}

/** The account, or a 401 when this browser is not signed in. */
export async function fetchAccount(): Promise<Account> {
  return unwrap(api.GET('/api/me'))
}

export async function signOut(): Promise<void> {
  await unwrap(api.POST('/api/me/sign-out'))
}

export async function signOutEverywhere(): Promise<void> {
  await unwrap(api.POST('/api/me/sign-out-everywhere'))
}

export async function deleteAccount(): Promise<void> {
  await unwrap(api.DELETE('/api/me'))
}

/** A plain link, so the browser downloads the JSON with its own cookie and file name. */
export const EXPORT_URL = '/api/me/export'

/** What an account can follow (#3889). */
export type FollowKind = 'artist' | 'venue' | 'promoter'

/** Everything the account follows, by name. */
export async function fetchFollows(): Promise<Follow[]> {
  return unwrap(api.GET('/api/me/follows'))
}

export async function isFollowing(kind: FollowKind, slug: string): Promise<boolean> {
  const state = await unwrap(
    api.GET('/api/me/follows/{kind}/{slug}', { params: { path: { kind, slug } } }),
  )
  return state.following === true
}

export async function follow(kind: FollowKind, slug: string): Promise<void> {
  await unwrap(api.PUT('/api/me/follows/{kind}/{slug}', { params: { path: { kind, slug } } }))
}

export async function unfollow(kind: FollowKind, slug: string): Promise<void> {
  await unwrap(api.DELETE('/api/me/follows/{kind}/{slug}', { params: { path: { kind, slug } } }))
}

/** The bell of one follow (#3894): `instant` pushes its new dates to every browser with notifications on. */
export type Notify = 'off' | 'instant'

export async function setNotify(kind: FollowKind, slug: string, notify: Notify): Promise<void> {
  await unwrap(
    api.PUT('/api/me/follows/{kind}/{slug}/notify', {
      params: { path: { kind, slug } },
      body: { notify },
    }),
  )
}

/** Every bell at once, as the account page offers when notifications first go on. Answers how many changed. */
export async function setAllNotify(notify: Notify): Promise<number> {
  const result = await unwrap(api.PUT('/api/me/follows/notify', { body: { notify } }))
  return result.changed ?? 0
}

/** The key a browser subscribes with, or null when this cluster sends no push (ADR-055). */
export async function fetchPushKey(): Promise<string | null> {
  const settings = await unwrap(api.GET('/api/me/push'))
  return settings.publicKey ?? null
}

/** Hands this browser's subscription to the BFF, in the shape `PushSubscription.toJSON()` gives. */
export async function savePushSubscription(subscription: PushSubscriptionJSON): Promise<void> {
  await unwrap(
    api.PUT('/api/me/push', {
      body: {
        endpoint: subscription.endpoint ?? '',
        keys: { p256dh: subscription.keys?.p256dh ?? '', auth: subscription.keys?.auth ?? '' },
      },
    }),
  )
}

export async function forgetPushSubscription(endpoint: string): Promise<void> {
  await unwrap(api.POST('/api/me/push/unsubscribe', { body: { endpoint } }))
}

/** Upcoming events of everything the account follows, by date, from [from] when given. */
export async function fetchFollowing(page = 0, from?: string): Promise<EventSummaryPage> {
  return unwrap(api.GET('/api/me/following', { params: { query: { page, size: 20, from } } }))
}

/** Today's events of everything the account follows, a running night included (#3922). */
export async function fetchFollowingToday(): Promise<EventSummary[]> {
  return unwrap(api.GET('/api/me/following/today'))
}

/** A saved search (#3890): the events list's query string under a name. */
export interface SavedSearch {
  id: string
  name: string
  query: string
}

function savedSearch(item: { id?: string; name?: string; query?: string }): SavedSearch[] {
  return item.id && item.name && item.query
    ? [{ id: item.id, name: item.name, query: item.query }]
    : []
}

export async function fetchSearches(): Promise<SavedSearch[]> {
  return (await unwrap(api.GET('/api/me/searches'))).flatMap(savedSearch)
}

/** Saves [query]; saving the same filters again answers the first search. */
export async function saveSearch(name: string, query: string): Promise<SavedSearch> {
  const [saved] = savedSearch(await unwrap(api.POST('/api/me/searches', { body: { name, query } })))
  if (!saved) throw new Error('The saved search came back incomplete')
  return saved
}

export async function renameSearch(id: string, name: string): Promise<void> {
  await unwrap(api.PATCH('/api/me/searches/{id}', { params: { path: { id } }, body: { name } }))
}

export async function deleteSearch(id: string): Promise<void> {
  await unwrap(api.DELETE('/api/me/searches/{id}', { params: { path: { id } } }))
}

/** Interested or going (#3892): private to the account. */
export type RsvpStatus = 'interested' | 'going'

/** The account's RSVPs as event slug to status. */
export async function fetchRsvps(): Promise<Record<string, RsvpStatus>> {
  return (await unwrap(api.GET('/api/me/rsvps'))) as Record<string, RsvpStatus>
}

export async function setRsvp(slug: string, status: RsvpStatus): Promise<void> {
  await unwrap(api.PUT('/api/me/rsvps/{slug}', { params: { path: { slug } }, body: { status } }))
}

export async function withdrawRsvp(slug: string): Promise<void> {
  await unwrap(api.DELETE('/api/me/rsvps/{slug}', { params: { path: { slug } } }))
}

/** RSVPed events not over yet, by date. */
export async function fetchRsvpUpcoming(page = 0): Promise<EventSummaryPage> {
  return unwrap(api.GET('/api/me/rsvps/upcoming', { params: { query: { page, size: 20 } } }))
}

/** RSVPed events that are over, latest first. */
export async function fetchRsvpPast(page = 0): Promise<EventSummaryPage> {
  return unwrap(api.GET('/api/me/rsvps/past', { params: { query: { page } } }))
}

/** RSVPed events in an inclusive date range, for the calendar view. */
export async function fetchRsvpCalendar(from: string, to: string): Promise<EventSummary[]> {
  return unwrap(api.GET('/api/me/rsvps/calendar', { params: { query: { from, to } } }))
}
