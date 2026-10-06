package de.norm.events.sitemap

import de.norm.events.common.Site
import de.norm.events.common.XmlWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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

/** One page a detail sitemap lists, and when its content last changed, where that is known. */
data class SitemapEntry(
    val slug: String,
    val lastmod: Instant? = null
)

/**
 * Renders a detail sitemap in the shape `sitemapXml()` in `events-frontend/src/lib/seo.ts` gives the
 * static pages: one `<url>` per locale, each carrying every alternate and `x-default`, because Google
 * ignores a one-way `hreflang`. The origin and the locales are [Site]'s.
 *
 * `lastmod` is `event.content_changed_at`, which moves only when the event page reads differently
 * (#2768). Venues, artists and promoters carry none: their only date is `updated_at`, which moves on
 * every write, and Google discounts a `lastmod` that moves without a change.
 */
object SitemapXml {
    private const val SITEMAP_NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9"
    private const val XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml"

    /** The W3C Datetime profile the sitemap protocol names, in UTC: `2026-10-06T20:31:40Z`. */
    private val W3C_DATETIME: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    fun render(
        kind: SitemapKind,
        entries: List<SitemapEntry>
    ): String =
        XmlWriter.document("urlset", defaultNamespace = SITEMAP_NAMESPACE, namespaces = mapOf("xhtml" to XHTML_NAMESPACE)) {
            for (entry in entries) {
                val path = "/${kind.path}/${entry.slug}"
                val lastmod = entry.lastmod?.let { W3C_DATETIME.format(it.truncatedTo(ChronoUnit.SECONDS)) }
                for (locale in Site.LOCALES) {
                    element("url") {
                        element("loc", url(locale, path))
                        if (lastmod != null) element("lastmod", lastmod)
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
