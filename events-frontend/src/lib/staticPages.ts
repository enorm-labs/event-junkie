import text from 'virtual:page-meta-text'

import type enPageDescription from '@/i18n/messages/en/pageDescription.json'
import type enPageTitle from '@/i18n/messages/en/pageTitle.json'
import type { Locale } from '@/i18n/locales'
import { type PageMeta, staticPageMeta } from '@/lib/pageMeta'
import type { INDEXABLE_PATHS } from '@/lib/seo'

/**
 * The head of each static page, read from the message catalogue as plain strings through
 * `virtual:page-meta-text`, for the injector (ADR-014 §Decision 2). The client reaches the same strings through the router's `titleKey` and
 * `descriptionKey`, and `staticPages.spec.ts` holds the two lists together.
 */

export type StaticPath = (typeof INDEXABLE_PATHS)[number]

type TitleKey = keyof typeof enPageTitle
type DescriptionKey = keyof typeof enPageDescription

/** The catalogue keys per static path. Home has no title of its own: it gets the brand title. */
export const STATIC_PAGE_KEYS: Record<
  StaticPath,
  { title?: TitleKey; description: DescriptionKey }
> = {
  '': { description: 'home' },
  '/events': { title: 'events', description: 'events' },
  '/venues': { title: 'venues', description: 'venues' },
  '/promoters': { title: 'promoters', description: 'promoters' },
  '/calendar': { title: 'calendar', description: 'calendar' },
  '/about': { title: 'about', description: 'about' },
  '/legal/imprint': { title: 'imprint', description: 'imprint' },
  '/legal/privacy': { title: 'privacy', description: 'privacy' },
  '/legal/notices': { title: 'notices', description: 'notices' },
  '/legal/for-venues': { title: 'forVenues', description: 'forVenues' },
}

/** The site's own description in `locale`: the home page's, and the fallback for any page without one. */
export function siteDescription(locale: Locale): string {
  return text[locale].pageDescription.home
}

/** The head of a static page, the value the router writes for it after boot. */
export function staticPathMeta(locale: Locale, path: StaticPath): PageMeta {
  const keys = STATIC_PAGE_KEYS[path]
  return staticPageMeta(
    keys.title ? text[locale].pageTitle[keys.title] : null,
    text[locale].pageDescription[keys.description],
  )
}
