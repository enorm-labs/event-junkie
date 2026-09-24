import { describe, expect, it } from 'vitest'

import { siteDescription } from '@/lib/staticPages'
import { escapeHtml, rewriteHead } from '../rewrite.ts'

/**
 * A reduced shell in the shape of `index.html`, including its multi-line tags: oxfmt wraps a long
 * `<meta>` across lines, and a matcher that only handled one-line tags would miss the description.
 */
const shell = `<!doctype html>
<html lang="en">
  <head>
    <title>Event Junkie — Can't get enough of Berlin</title>
    <meta
      content="Site description."
      name="description"
    />
    <meta content="Event Junkie — Can't get enough of Berlin" property="og:title" />
    <meta
      content="Site description."
      property="og:description"
    />
    <meta content="https://event-junkie.de/og-image.png" property="og:image" />
    <meta content="1200" property="og:image:width" />
    <meta content="630" property="og:image:height" />
    <meta content="Site card" property="og:image:alt" />
    <meta content="https://event-junkie.de/og-image.png" name="twitter:image" />
    <meta content="Event Junkie — Can't get enough of Berlin" name="twitter:title" />
    <meta content="Site description." name="twitter:description" />
  </head>
  <body><div id="app"></div></body>
</html>`

const content = (html: string, key: string) =>
  new RegExp(`<meta[^>]*?content="([^"]*)"[^>]*?(?:name|property)="${key}"`, 's').exec(html)?.[1]

const event = {
  title: 'Test Act · Event Junkie',
  description: 'Fri, 12 Jun 2026 · Lido, Berlin',
  image: 'https://event-junkie.de/api/images/abc',
}

describe('rewriteHead', () => {
  it('writes the title into the tab title and both social titles', () => {
    const html = rewriteHead(shell, { meta: event, locale: 'en', path: '/events/x' })
    expect(html).toContain('<title>Test Act · Event Junkie</title>')
    expect(content(html, 'og:title')).toBe('Test Act · Event Junkie')
    expect(content(html, 'twitter:title')).toBe('Test Act · Event Junkie')
  })

  it('writes the description into all three description tags, across line breaks', () => {
    const html = rewriteHead(shell, { meta: event, locale: 'en', path: '/events/x' })
    const keys = ['description', 'og:description', 'twitter:description']
    expect(keys.map((key) => content(html, key))).toEqual(
      Array(3).fill('Fri, 12 Jun 2026 · Lido, Berlin'),
    )
  })

  it('keeps the site value in data-site-default, for the client to fall back to', () => {
    const html = rewriteHead(shell, { meta: event, locale: 'en', path: '/events/x' })
    // The site description is the catalogue's, which the shell is localised to before the page's own.
    expect(html).toContain(
      `property="og:description" data-site-default="${escapeHtml(siteDescription('en'))}" />`,
    )
    expect(html).toMatch(
      /property="og:title" data-site-default="Event Junkie — Can't get enough of Berlin" \/>/,
    )
  })

  it('gives a page without a description the site description in its own locale', () => {
    for (const locale of ['en', 'de'] as const) {
      const html = rewriteHead(shell, {
        meta: { title: 'Somebody · Event Junkie' },
        locale,
        path: '/artists/x',
      })
      for (const key of ['description', 'og:description', 'twitter:description']) {
        expect(content(html, key)).toBe(escapeHtml(siteDescription(locale)))
      }
      // The client's fallback, so it must be the tag's own content rather than a remembered one.
      expect(html).not.toContain('data-site-default="Site description."')
    }
  })

  it('remembers the localised site description, not the shipped English one, under a page description', () => {
    const html = rewriteHead(shell, { meta: event, locale: 'de', path: '/events/x' })
    expect(html).toContain(`data-site-default="${escapeHtml(siteDescription('de'))}"`)
  })

  it('writes the language of the page, not the shell', () => {
    expect(rewriteHead(shell, { meta: event, locale: 'de', path: '/events/x' })).toContain(
      '<html lang="de"',
    )
  })

  it("replaces the site card with the entity's image and drops the card's width, height and alt", () => {
    const html = rewriteHead(shell, { meta: event, locale: 'en', path: '/events/x' })
    expect(content(html, 'og:image')).toBe(event.image)
    expect(content(html, 'twitter:image')).toBe(event.image)
    expect(html).not.toContain('og:image:width')
    expect(html).not.toContain('og:image:height')
    expect(html).not.toContain('og:image:alt')
  })

  it('states the dimensions when the BFF knows them', () => {
    const html = rewriteHead(shell, {
      meta: event,
      image: { width: 800, height: 450 },
      locale: 'en',
      path: '/events/x',
    })
    expect(content(html, 'og:image:width')).toBe('800')
    expect(content(html, 'og:image:height')).toBe('450')
  })

  it('keeps the site card, with its size and alt, for a page without an image, as the client does', () => {
    const html = rewriteHead(shell, {
      meta: { title: 'Somebody · Event Junkie' },
      locale: 'en',
      path: '/promoters/x',
    })
    expect(content(html, 'og:image')).toBe('https://event-junkie.de/og-image.png')
    expect(content(html, 'twitter:image')).toBe('https://event-junkie.de/og-image.png')
    expect(content(html, 'og:image:width')).toBe('1200')
    expect(content(html, 'og:image:height')).toBe('630')
    expect(content(html, 'og:image:alt')).toBe('Site card')
  })

  it('appends the canonical set marked data-seo, so the client replaces rather than duplicates it', () => {
    const html = rewriteHead(shell, { meta: event, locale: 'de', path: '/events/x' })
    expect(html).toContain(
      '<link data-seo rel="canonical" href="https://event-junkie.de/de/events/x" />',
    )
    expect(html).toContain('hreflang="en" href="https://event-junkie.de/en/events/x"')
    expect(html).toContain('hreflang="x-default" href="https://event-junkie.de/en/events/x"')
    expect(html).toContain(
      '<meta data-seo property="og:url" content="https://event-junkie.de/de/events/x" />',
    )
    expect(html).toContain('<meta data-seo property="og:locale" content="de_DE" />')
    expect(html).toContain('<meta data-seo property="og:locale:alternate" content="en_GB" />')
    expect(html).toContain('<html lang="de">')
  })

  it('escapes what the data says, so a title cannot close the tag', () => {
    const hostile = { title: `"><script>alert(1)</script> & <b>`, description: `a "quoted" one` }
    const html = rewriteHead(shell, { meta: hostile, locale: 'en', path: '/events/x' })
    expect(html).not.toContain('<script>alert')
    expect(html).toContain('&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt; &amp; &lt;b&gt;')
    expect(content(html, 'og:description')).toBe('a &quot;quoted&quot; one')
  })

  it('inserts a value with $-patterns as it is, rather than splicing the document into the tag', () => {
    // `$'` in a string replacement is "everything after the match" — the rest of the page (#1426).
    const dollars = `Tickets 12$' VVK $& $\` $1`
    const meta = { title: dollars, description: dollars, image: `https://img.example/a$'b.jpg` }
    const html = rewriteHead(shell, { meta, locale: 'en', path: '/events/x' })
    expect(html).toContain(`<title>Tickets 12$&#39; VVK $&amp; $\` $1</title>`)
    expect(content(html, 'og:description')).toBe(`Tickets 12$&#39; VVK $&amp; $\` $1`)
    expect(content(html, 'og:image')).toBe(`https://img.example/a$&#39;b.jpg`)
    expect(html.match(/<\/title>/g)).toHaveLength(1)
    expect(html.match(/<body>/g)).toHaveLength(1)
  })

  it('leaves the body untouched', () => {
    const html = rewriteHead(shell, { meta: event, locale: 'en', path: '/events/x' })
    expect(html).toContain('<body><div id="app"></div></body>')
  })
})

describe('escapeHtml', () => {
  it('covers the five characters that matter in an attribute or a text node', () => {
    expect(escapeHtml(`&<>"'`)).toBe('&amp;&lt;&gt;&quot;&#39;')
  })
})
