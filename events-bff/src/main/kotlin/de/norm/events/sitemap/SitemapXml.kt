package de.norm.events.sitemap

/** The four detail route families, each with its own sitemap. [path] is the URL segment in both. */
enum class SitemapKind(
    val path: String
) {
    EVENTS("events"),
    VENUES("venues"),
    ARTISTS("artists"),
    PROMOTERS("promoters")
    ;

    companion object {
        fun fromPath(path: String): SitemapKind? = entries.firstOrNull { it.path == path }
    }
}

/**
 * Renders a detail sitemap in the shape `sitemapXml()` in `events-frontend/src/lib/seo.ts` gives the
 * static pages: one `<url>` per locale, each carrying every alternate and `x-default`, because Google
 * ignores a one-way `hreflang`. The origin and the locales mirror that file's `SITE_URL` and
 * `LOCALES`; change both or neither. No `lastmod`: `updated_at` moves on every write, changed or not,
 * which is the untrustworthy signal Google discounts.
 */
object SitemapXml {
    const val SITE_URL = "https://event-junkie.de"
    val LOCALES = listOf("en", "de")
    const val DEFAULT_LOCALE = "en"

    fun render(
        kind: SitemapKind,
        slugs: List<String>
    ): String =
        buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine(
                """<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">"""
            )
            for (slug in slugs) {
                val path = "/${kind.path}/${escape(slug)}"
                for (locale in LOCALES) {
                    appendLine("  <url>")
                    appendLine("    <loc>${url(locale, path)}</loc>")
                    for (alternate in LOCALES) appendAlternate(alternate, url(alternate, path))
                    appendAlternate("x-default", url(DEFAULT_LOCALE, path))
                    appendLine("  </url>")
                }
            }
            appendLine("</urlset>")
        }

    private fun url(
        locale: String,
        path: String
    ) = "$SITE_URL/$locale$path"

    private fun StringBuilder.appendAlternate(
        hreflang: String,
        href: String
    ) {
        appendLine("""    <xhtml:link href="$href" hreflang="$hreflang" rel="alternate"/>""")
    }

    /** A slug is `[a-z0-9-]` by construction; escaped anyway, since one bad row would void the file. */
    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
