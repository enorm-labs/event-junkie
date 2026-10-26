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

/** Upcoming events of everything the account follows, by date, from [from] when given. */
export async function fetchFollowing(page = 0, from?: string): Promise<EventSummaryPage> {
  return unwrap(api.GET('/api/me/following', { params: { query: { page, size: 20, from } } }))
}

/** Today's events of everything the account follows, a running night included (#3922). */
export async function fetchFollowingToday(): Promise<EventSummary[]> {
  return unwrap(api.GET('/api/me/following/today'))
}
