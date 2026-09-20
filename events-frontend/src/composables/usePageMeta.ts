import { type MaybeRefOrGetter, ref, toValue, watchEffect } from 'vue'

import { HOME_TITLE, type PageMeta } from '@/lib/pageMeta'

/**
 * Writes the current page's title, description and image into the document head. What the tags
 * say is `lib/pageMeta.ts`, free of Vue and the DOM so the meta injector (ADR-014 §Decision 3)
 * shares it.
 */

/**
 * The full document title, shared by the browser tab, the Open Graph tags and the route
 * announcer (App.vue).
 */
export const pageTitle = ref(HOME_TITLE)

/**
 * The site-level values `index.html` ships with, captured before anything overwrites them:
 * restoring a remembered default is the only way a per-page tag can be un-set, or the imprint
 * would keep describing itself as the club night before it.
 */
const SITE_DEFAULTS = new Map<string, string>()

/** Tags this module owns. `og:image`/`twitter:image` are absent from `index.html` by design. */
const DESCRIPTION_SELECTORS = [
  'meta[name="description"]',
  'meta[property="og:description"]',
  'meta[name="twitter:description"]',
]
const TITLE_SELECTORS = ['meta[property="og:title"]', 'meta[name="twitter:title"]']
const IMAGE_TAGS: [attribute: string, name: string][] = [
  ['property', 'og:image'],
  ['name', 'twitter:image'],
]

function rememberDefault(selector: string): string {
  const existing = SITE_DEFAULTS.get(selector)
  if (existing !== undefined) return existing

  // On a detail page the served HTML already carries that page's values, written by the meta
  // injector, which left the shipped value in `data-site-default`. Reading `content` would remember
  // an event's description as the site's.
  const element = document.head.querySelector<HTMLMetaElement>(selector)
  const content = element?.dataset.siteDefault ?? element?.content ?? ''
  SITE_DEFAULTS.set(selector, content)
  return content
}

/** Sets `content` on an existing `<meta>`, or restores the site default when it is undefined. */
function setMeta(selector: string, content: string | undefined): void {
  const fallback = rememberDefault(selector)
  document.head
    .querySelector<HTMLMetaElement>(selector)
    ?.setAttribute('content', content ?? fallback)
}

/**
 * Applies a page's meta. Every tag is written on every call, including back to its default,
 * because a partial update leaves a page describing the last one you visited.
 */
export function applyPageMeta(meta: PageMeta): void {
  pageTitle.value = meta.title
  document.title = meta.title
  for (const selector of TITLE_SELECTORS) setMeta(selector, meta.title)
  for (const selector of DESCRIPTION_SELECTORS) setMeta(selector, meta.description)

  // Images are created and removed rather than reset: `index.html` carries no site-level image, and
  // an event poster left on the imprint is an outright wrong preview.
  for (const [attribute, name] of IMAGE_TAGS) {
    const selector = `meta[${attribute}="${name}"]`
    const existing = document.head.querySelector<HTMLMetaElement>(selector)

    if (!meta.image) {
      existing?.remove()
      continue
    }
    if (existing) {
      existing.content = meta.image
      continue
    }
    const element = document.createElement('meta')
    element.setAttribute(attribute, name)
    element.content = meta.image
    document.head.append(element)
  }
}

/**
 * Keeps the head in sync with a reactive per-view meta. A getter, because detail views mount
 * before their entity arrives.
 */
export function usePageMeta(meta: MaybeRefOrGetter<PageMeta>): void {
  watchEffect(() => applyPageMeta(toValue(meta)))
}
