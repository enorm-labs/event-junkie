import { type Locale, LOCALES } from '../src/i18n/locales.ts'
import type { PageMeta } from '../src/lib/pageMeta.ts'
import { alternatesFor, canonicalUrl, OG_LOCALES } from '../src/lib/seo.ts'
import type { ImageSize } from './meta.ts'

/**
 * Rewrites the head of the built `index.html` for one detail page. Pure: a string in, a string
 * out. It writes exactly what the client writes after boot, `usePageMeta.ts`'s set and
 * `seoTags.ts`'s set, and `__tests__/parity.spec.ts` holds the two writers together. Every tag
 * rewritten here keeps its shipped value in `data-site-default`, so the client can fall back to
 * the site's description; every tag added carries `data-seo`, the marker `seoTags.ts` replaces.
 * String surgery rather than a DOM: the container ships one file with no parser in it.
 */

export interface RewriteInput {
  meta: PageMeta
  locale: Locale
  /** Locale-relative path, `/events/<slug>` — see `DetailRoute.path`. */
  path: string
  /** Set when the BFF knows the image's dimensions. Ignored when `meta.image` is absent. */
  image?: ImageSize
}

const SITE_DEFAULT_ATTRIBUTE = 'data-site-default'
const MANAGED_ATTRIBUTE = 'data-seo'

/** The five characters that matter inside a double-quoted attribute or a text node. */
export function escapeHtml(value: string): string {
  return value.replace(
    /[&<>"']/g,
    (char) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char] as string,
  )
}

/** The whole `<meta …>` element whose `name` or `property` is `key`, wherever its attributes sit. */
function metaTag(key: string): RegExp {
  return new RegExp(`<meta\\s[^>]*?(?:name|property)="${key}"[^>]*?/?>`, 'g')
}

/**
 * Rewrites `content` on one `<meta>`, remembering what it said; leaves a missing tag missing.
 * Every `replace` here takes a function, never a string built from a value: a string replacement
 * is scanned for `$&`, `` $` ``, `$'` and `$n`, so a title ending in `$'` would splice the rest of
 * the document into the tag (#1426).
 */
function setContent(html: string, key: string, value: string): string {
  return html.replace(metaTag(key), (tag) => {
    const previous = /\scontent="([^"]*)"/.exec(tag)?.[1] ?? ''
    const withContent = tag.replace(/\scontent="[^"]*"/, () => ` content="${escapeHtml(value)}"`)
    return withContent.replace(/\s*\/?>$/, () => ` ${SITE_DEFAULT_ATTRIBUTE}="${previous}" />`)
  })
}

function removeTag(html: string, key: string): string {
  return html.replace(metaTag(key), '')
}

/** Builds the `data-seo` set `seoTags.ts` would build for the same route, in the same order. */
function managedTags(locale: Locale, path: string): string {
  const canonical = canonicalUrl(locale, path)
  const link = (attributes: string) => `<link ${MANAGED_ATTRIBUTE} ${attributes} />`
  const meta = (attributes: string) => `<meta ${MANAGED_ATTRIBUTE} ${attributes} />`

  return [
    link(`rel="canonical" href="${escapeHtml(canonical)}"`),
    ...alternatesFor(path).map((alternate) =>
      link(`rel="alternate" hreflang="${alternate.hreflang}" href="${escapeHtml(alternate.href)}"`),
    ),
    meta(`property="og:url" content="${escapeHtml(canonical)}"`),
    meta(`property="og:locale" content="${OG_LOCALES[locale]}"`),
    ...LOCALES.filter((other) => other !== locale).map((other) =>
      meta(`property="og:locale:alternate" content="${OG_LOCALES[other]}"`),
    ),
  ].join('\n    ')
}

/** The per-page head for `input`, written into the served shell. */
export function rewriteHead(shell: string, input: RewriteInput): string {
  const { meta, locale, path, image } = input
  let html = shell

  html = html.replace(/<html\s+lang="[^"]*"/, () => `<html lang="${locale}"`)
  html = html.replace(/<title>[^<]*<\/title>/, () => `<title>${escapeHtml(meta.title)}</title>`)

  html = setContent(html, 'og:title', meta.title)
  html = setContent(html, 'twitter:title', meta.title)

  // The client restores the site description on a page without one, and so does this, by not
  // touching the tags.
  if (meta.description) {
    for (const key of ['description', 'og:description', 'twitter:description']) {
      html = setContent(html, key, meta.description)
    }
  }

  // Mirrors `applyPageMeta`: an image replaces the site card, no image removes it, and the site
  // card's width, height and alt go with it either way.
  for (const key of ['og:image:width', 'og:image:height', 'og:image:alt']) html = removeTag(html, key)
  if (meta.image) {
    html = setContent(html, 'og:image', meta.image)
    html = setContent(html, 'twitter:image', meta.image)
    if (image) {
      html = html.replace(
        metaTag('og:image'),
        (tag) =>
          `${tag}\n    <meta content="${image.width}" property="og:image:width" />` +
          `\n    <meta content="${image.height}" property="og:image:height" />`,
      )
    }
  } else {
    html = removeTag(html, 'og:image')
    html = removeTag(html, 'twitter:image')
  }

  return html.replace('</head>', () => `    ${managedTags(locale, path)}\n  </head>`)
}
