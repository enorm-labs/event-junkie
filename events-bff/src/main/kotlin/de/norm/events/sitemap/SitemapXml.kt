package de.norm.events.sitemap

import de.norm.events.common.Site
import de.norm.events.common.XmlWriter

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
 * ignores a one-way `hreflang`. The origin and the locales are [Site]'s. No `lastmod`: `updated_at`
 * moves on every write, changed or not, which is the untrustworthy signal Google discounts.
 */
object SitemapXml {
    private const val SITEMAP_NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9"
    private const val XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml"

    fun render(
        kind: SitemapKind,
        slugs: List<String>
    ): String =
        XmlWriter.document("urlset", defaultNamespace = SITEMAP_NAMESPACE, namespaces = mapOf("xhtml" to XHTML_NAMESPACE)) {
            for (slug in slugs) {
                val path = "/${kind.path}/$slug"
                for (locale in Site.LOCALES) {
                    element("url") {
                        element("loc", url(locale, path))
                        for (alternate in Site.LOCALES) alternate(alternate, url(alternate, path))
                        alternate("x-default", url(Site.DEFAULT_LOCALE, path))
                    }
                }
            }
        }

    private fun url(
        locale: String,
        path: String
    ) = "${Site.URL}/$locale$path"

    private fun XmlWriter.alternate(
        hreflang: String,
        href: String
    ) {
        emptyElement("xhtml", XHTML_NAMESPACE, "link", "href" to href, "hreflang" to hreflang, "rel" to "alternate")
    }
}
