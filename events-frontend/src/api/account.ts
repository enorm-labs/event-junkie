import type { Locale } from '@/i18n/locales'
import { api, unwrap } from './client'
import type { Account } from './types'

/**
 * The sign-in and account calls (#3887, ADR-053). Each write needs the CSRF cookie first, so every
 * page that writes calls {@link prepareCsrf} when it mounts.
 */

/** Sets the `XSRF-TOKEN` cookie. Only these pages ask for it, so no other page stores a cookie. */
export async function prepareCsrf(): Promise<void> {
  await unwrap(api.GET('/api/auth/csrf'))
}

/** Who this browser is signed in as, or null. */
export async function fetchSession(): Promise<Account | null> {
  const session = await unwrap(api.GET('/api/auth/session'))
  return session?.account ?? null
}

/** Sends a sign-in link. The answer is the same whether or not the address has an account. */
export async function requestLink(email: string, locale: Locale): Promise<void> {
  await unwrap(api.POST('/api/auth/link', { body: { email, locale } }))
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
