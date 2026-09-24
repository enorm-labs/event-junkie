import { beforeEach, describe, expect, it } from 'vitest'

import { applyPageMeta, pageTitle } from '@/composables/usePageMeta'
import { HOME_TITLE } from '@/lib/pageMeta'
import { siteDescription } from '@/lib/staticPages'

/**
 * The head is shared mutable state that no route resets, so the bug this guards against is not a
 * wrong tag — it is a *stale* one. A page with no description of its own must fall back to the
 * site default, not silently keep the previous page's, or opening an event and then the imprint
 * describes the imprint as a club night.
 */

const SITE_DESCRIPTION = siteDescription('en')

const content = (selector: string) =>
  document.head.querySelector<HTMLMetaElement>(selector)?.content

beforeEach(() => {
  // The description and title tags `index.html` ships with. The site image is left out here, so the
  // image cases below meet a shell without one; `usePageMeta.siteImage.spec.ts` covers the other.
  document.documentElement.lang = 'en'
  document.head.innerHTML = `
    <meta name="description" content="${SITE_DESCRIPTION}" />
    <meta property="og:description" content="${SITE_DESCRIPTION}" />
    <meta name="twitter:description" content="${SITE_DESCRIPTION}" />
    <meta property="og:title" content="Event Junkie" />
    <meta name="twitter:title" content="Event Junkie" />
  `
})

describe('applyPageMeta', () => {
  it('sets the document title and both social title tags', () => {
    applyPageMeta({ title: 'Test Act · Event Junkie' })

    expect(document.title).toBe('Test Act · Event Junkie')
    expect(content('meta[property="og:title"]')).toBe('Test Act · Event Junkie')
    expect(content('meta[name="twitter:title"]')).toBe('Test Act · Event Junkie')
  })

  it('exposes the title reactively for the screen-reader route announcer', () => {
    applyPageMeta({ title: 'Venues · Event Junkie' })
    expect(pageTitle.value).toBe('Venues · Event Junkie')
  })

  it('writes a page description to all three description tags', () => {
    applyPageMeta({ title: HOME_TITLE, description: 'Fri, 12 Jun 2026 · Lido, Berlin' })

    const written = [
      'meta[name="description"]',
      'meta[property="og:description"]',
      'meta[name="twitter:description"]',
    ].map(content)

    // Asserted as a set rather than one at a time: the failure then names which of the three was
    // missed, instead of stopping at the first.
    expect(written).toEqual(Array(3).fill('Fri, 12 Jun 2026 · Lido, Berlin'))
  })

  it('restores the site description for a page that has none of its own', () => {
    // Without it this page would keep describing itself as the event you were looking at a moment ago.
    applyPageMeta({ title: HOME_TITLE, description: 'An event.' })
    applyPageMeta({ title: HOME_TITLE })

    expect(content('meta[name="description"]')).toBe(SITE_DESCRIPTION)
    expect(content('meta[property="og:description"]')).toBe(SITE_DESCRIPTION)
  })

  it("restores it in the page's own language, after a switch inside the app", () => {
    document.documentElement.lang = 'de'
    applyPageMeta({ title: HOME_TITLE })
    expect(content('meta[property="og:description"]')).toBe(siteDescription('de'))

    document.documentElement.lang = 'en'
    applyPageMeta({ title: HOME_TITLE })
    expect(content('meta[property="og:description"]')).toBe(siteDescription('en'))
  })

  it('creates the image tags on demand, when the shell carries none', () => {
    applyPageMeta({ title: HOME_TITLE, image: 'https://example.test/poster.jpg' })

    expect(content('meta[property="og:image"]')).toBe('https://example.test/poster.jpg')
    expect(content('meta[name="twitter:image"]')).toBe('https://example.test/poster.jpg')
  })

  it('removes the image again rather than leaving the last one behind, when there is no site image', () => {
    // An event poster still attached to the imprint is an outright wrong preview, not a vague one.
    applyPageMeta({ title: HOME_TITLE, image: 'https://example.test/poster.jpg' })
    applyPageMeta({ title: HOME_TITLE })

    expect(document.head.querySelector('meta[property="og:image"]')).toBeNull()
    expect(document.head.querySelector('meta[name="twitter:image"]')).toBeNull()
  })

  it('replaces the image instead of accumulating one per navigation', () => {
    applyPageMeta({ title: HOME_TITLE, image: 'https://example.test/a.jpg' })
    applyPageMeta({ title: HOME_TITLE, image: 'https://example.test/b.jpg' })

    expect(document.head.querySelectorAll('meta[property="og:image"]')).toHaveLength(1)
    expect(content('meta[property="og:image"]')).toBe('https://example.test/b.jpg')
  })
})
