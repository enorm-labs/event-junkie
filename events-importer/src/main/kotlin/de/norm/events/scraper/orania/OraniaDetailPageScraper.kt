package de.norm.events.scraper.orania

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.textAt
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for an Orania.Berlin event page. It adds what the listing lacks: the
 * performers' biography and the full-size photo the thumbnail links to.
 *
 * The page restates the date as `02.10.2026 21:00 - Open End`; it is not read, because the
 * listing already gives date and clock. The first paragraph in bold restates the listing's
 * teaser and is dropped from the description.
 */
class OraniaDetailPageScraper {
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val content = document.selectFirst("div.calendarize")
        val title = content?.textAt("h1") ?: return null
        return ScrapedEvent(
            title = title,
            description = parseDescription(content),
            eventDate = UNRESOLVED_EVENT_DATE,
            imageUrl = content.selectFirst(".images a.glightbox[href]")?.absUrl("href")?.takeIf { it.isNotBlank() },
            sourceUrl = sourceUrl,
            sourceId = oraniaSourceId(sourceUrl)
        )
    }

    private fun parseDescription(content: Element): String? =
        content
            .select(".text p")
            .filterNot { paragraph -> paragraph.text() == paragraph.selectFirst("strong")?.text() }
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
}
