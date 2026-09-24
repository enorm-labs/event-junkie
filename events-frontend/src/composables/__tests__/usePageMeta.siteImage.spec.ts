import { describe, expect, it } from 'vitest'

import { applyPageMeta } from '@/composables/usePageMeta'
import { HOME_TITLE } from '@/lib/pageMeta'

/**
 * The shell ships a site image, and a page without an image of its own falls back to it rather
 * than losing it (#1911): most apps render a link without an image as bare text. A file of its own,
 * because the module remembers each tag's default on first use and `usePageMeta.spec.ts` meets a
 * shell without an image first.
 */

const SITE_IMAGE = 'https://event-junkie.de/og-image.png'

const content = (selector: string) =>
  document.head.querySelector<HTMLMetaElement>(selector)?.content

describe('applyPageMeta with a site image in the shell', () => {
  it("falls back to the site image after a page with its own, never keeping the page's", () => {
    document.head.innerHTML = `
      <meta property="og:image" content="${SITE_IMAGE}" />
      <meta name="twitter:image" content="${SITE_IMAGE}" />
    `

    applyPageMeta({ title: HOME_TITLE, image: 'https://example.test/poster.jpg' })
    expect(content('meta[property="og:image"]')).toBe('https://example.test/poster.jpg')

    applyPageMeta({ title: HOME_TITLE })
    expect(content('meta[property="og:image"]')).toBe(SITE_IMAGE)
    expect(content('meta[name="twitter:image"]')).toBe(SITE_IMAGE)
  })
})
