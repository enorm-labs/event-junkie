import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import type { EventDetail, VenueDetail } from '@/api/types'
import { applyPageMeta } from '@/composables/usePageMeta'
import { artistPageMeta, eventPageMeta, venuePageMeta } from '@/lib/pageMeta'
import { siteDescription, staticPathMeta } from '@/lib/staticPages'
import { updateSeoTags } from '@/lib/seoTags'
import { rewriteHead } from '../rewrite.ts'

/**
 * **The test ADR-014 §Consequences asks for.** The head is a two-writer surface: the injector writes
 * it on the server, then the client overwrites it on boot. If they disagree, a shared link previews
 * as one thing and opens as another, and `og:url` flips when JavaScript starts.
 *
 * So: rewrite the real `index.html` the way the sidecar would, load that head into the DOM, boot
 * the client's two writers over it, and assert the head did not change. Not "both say the right
 * thing" — *the same thing*, read back from the same tags.
 */

// `process.cwd()` rather than `import.meta.url`: under jsdom the module URL is not a file URL.
const shell = readFileSync(resolve(process.cwd(), 'index.html'), 'utf8')

const event: EventDetail = {
  slug: '2026-06-12-lido-test-act',
  title: 'Test Act',
  eventDate: '2026-06-12',
  description: 'A night of something, with a "quote" & an ampersand.',
  imageUrl: '/api/images/abc',
  intrinsicWidth: 800,
  intrinsicHeight: 450,
  venue: { slug: 'lido', name: 'Lido', city: 'Berlin' },
}

const venue: VenueDetail = { slug: 'lido', name: 'Lido', city: 'Berlin', address: 'Cuvrystraße 7' }

/** Everything either writer touches, read back from the DOM the way a scraper or a test would. */
function headState() {
  const meta = (selector: string) =>
    document.head.querySelector<HTMLMetaElement>(selector)?.content ?? null
  const links = [...document.head.querySelectorAll<HTMLLinkElement>('link[rel="alternate"]')].map(
    (link) => `${link.hreflang}=${link.href}`,
  )
  return {
    title: document.title,
    lang: document.documentElement.lang,
    description: meta('meta[name="description"]'),
    ogTitle: meta('meta[property="og:title"]'),
    ogDescription: meta('meta[property="og:description"]'),
    ogImage: meta('meta[property="og:image"]'),
    ogUrl: meta('meta[property="og:url"]'),
    ogLocale: meta('meta[property="og:locale"]'),
    ogLocaleAlternates: [
      ...document.head.querySelectorAll('meta[property="og:locale:alternate"]'),
    ].map((element) => element.getAttribute('content')),
    twitterTitle: meta('meta[name="twitter:title"]'),
    twitterDescription: meta('meta[name="twitter:description"]'),
    twitterImage: meta('meta[name="twitter:image"]'),
    canonical: document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]')?.href ?? null,
    alternates: links,
  }
}

function serve(html: string) {
  const head = /<head>([\s\S]*)<\/head>/.exec(html)?.[1] ?? ''
  document.head.innerHTML = head
  document.documentElement.lang = /<html lang="([^"]*)"/.exec(html)?.[1] ?? ''
}

describe('the injector and the client write the same head', () => {
  it('for an event in German, with an image and a description', () => {
    const meta = eventPageMeta(event, 'de')
    serve(
      rewriteHead(shell, {
        meta,
        image: { width: 800, height: 450 },
        locale: 'de',
        path: '/events/x',
      }),
    )
    const served = headState()

    applyPageMeta(meta)
    updateSeoTags('de', '/events/x')

    expect(headState()).toEqual(served)
    // And the served head said what the client's own module says, not something of its own.
    expect(served.title).toBe('Test Act · Event Junkie')
    expect(served.ogDescription).toContain('Fr., 12. Juni 2026 · Lido, Berlin')
    expect(served.ogImage).toBe('https://event-junkie.de/api/images/abc')
    expect(served.canonical).toBe('https://event-junkie.de/de/events/x')
  })

  it('for a venue in English, with an address and no image', () => {
    const meta = venuePageMeta(venue, 'en')
    serve(rewriteHead(shell, { meta, locale: 'en', path: '/venues/lido' }))
    const served = headState()

    applyPageMeta(meta)
    updateSeoTags('en', '/venues/lido')

    expect(headState()).toEqual(served)
    // No image of its own keeps the site card, in both writers (#1911).
    expect(served.ogImage).toBe('https://event-junkie.de/og-image.png')
  })

  it('for an artist in German with neither a description nor a picture', () => {
    const meta = artistPageMeta({ slug: 'mia-kober', name: 'Mia Kober' })
    serve(rewriteHead(shell, { meta, locale: 'de', path: '/artists/mia-kober' }))
    const served = headState()

    applyPageMeta(meta)
    updateSeoTags('de', '/artists/mia-kober')

    expect(headState()).toEqual(served)
    expect(served.lang).toBe('de')
    expect(served.ogDescription).toBe(siteDescription('de'))
    expect(served.ogImage).toBe('https://event-junkie.de/og-image.png')
  })

  it.each([
    ['de', ''],
    ['de', '/events'],
    ['en', '/venues'],
    ['de', '/legal/imprint'],
  ] as const)('for the static page %s%s, from the catalogue', (locale, path) => {
    const meta = staticPathMeta(locale, path)
    serve(rewriteHead(shell, { meta, locale, path }))
    const served = headState()

    applyPageMeta(meta)
    updateSeoTags(locale, path)

    expect(headState()).toEqual(served)
    expect(served.lang).toBe(locale)
    expect(served.canonical).toBe(`https://event-junkie.de/${locale}${path}`)
  })

  it('leaves the client a real site default to fall back to', () => {
    // The trap the `data-site-default` attribute exists for: after an event page has been served,
    // a page with no description of its own must get the site's, not the event's.
    const meta = eventPageMeta(event, 'en')
    serve(rewriteHead(shell, { meta, locale: 'en', path: '/events/x' }))
    applyPageMeta(meta)

    applyPageMeta({ title: 'Somebody · Event Junkie' })
    expect(headState().ogDescription).toMatch(/^Concerts, club nights/)
  })
})
