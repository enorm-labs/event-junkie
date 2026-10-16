package de.norm.events.scraper.madmonkeyroom

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parser for the Mad Monkey Room's rausgegangen ticket shop. Each `div.item` is one dated show: the
 * header link to its own shop page, a `Di, 20.10.2026 um 20:30 Uhr` line, the place, and the cheapest
 * ticket on the buy button (`ab 14,76 €`). The voucher row carries no date and is skipped, and so is
 * a show at another place (the Spree cruise boards at a pier).
 */
class MadMonkeyRoomShopPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("div.item:has(> .content > a.header)")
        logger.info { "Found ${items.size} Mad Monkey Room shop item(s) on $baseUrl" }
        return items.mapSkippingFailures(logger, "Mad Monkey Room shop item", ::parseItem)
    }

    private fun parseItem(item: Element): ScrapedEvent? {
        val header = item.selectFirst("> .content > a.header") ?: return null
        val title = header.text().blankToNull() ?: return null
        val url = header.attr("abs:href").blankToNull() ?: return null
        val lines = item.select("> .content > .extra > span").map { it.text() }
        val schedule = lines.firstNotNullOfOrNull { SCHEDULE.find(it) } ?: return null
        val place = lines.firstOrNull { SCHEDULE.find(it) == null }
        if (place != null && !place.contains(HOUSE, ignoreCase = true)) {
            logger.debug { "Skipping Mad Monkey Room show '$title' at $place" }
            return null
        }
        val date = parseGermanDate(schedule.groupValues[1]) ?: return null
        val act = SOLO_SHOW.matchEntire(title)
        return ScrapedEvent(
            title = title,
            eventType = EventType.COMEDY.name,
            eventDate = date,
            startTime = parseTime(schedule.groupValues[2].padStart(CLOCK_LENGTH, '0')),
            imageUrl =
                item
                    .selectFirst("img.rg-header-image")
                    ?.attr("abs:src")
                    ?.blankToNull()
                    ?.replace(THUMBNAIL_SIZE, POSTER_SIZE),
            sourceUrl = url,
            sourceId = "${EventSource.MAD_MONKEY_ROOM.sourceIdPrefix}${url.trimEnd('/').substringAfterLast('/')}",
            ticketUrl = url,
            pricePresale = euroAmounts(item.selectFirst("a.ticket-buy-button")?.text()).minOrNull(),
            artists = listOfNotNull(act?.let { ScrapedArtist(name = it.groupValues[1].trim(), role = "HEADLINER") })
        )
    }

    private companion object {
        const val HOUSE = "Mad Monkey"
        const val CLOCK_LENGTH = 5
        const val POSTER_SIZE = "width=1200&height=900"

        /** The date and clock of a show: `Di, 20.10.2026 um 20:30 Uhr`. */
        val SCHEDULE = Regex("""(\d{1,2}\.\d{1,2}\.\d{4})\s+um\s+(\d{1,2}:\d{2})""")

        /** The 300-pixel square the list shows; the shop page's own poster asks imageflow for 1200 × 900. */
        val THUMBNAIL_SIZE = Regex("""width=300&height=300""")

        /** A comedian's own programme: `Philipp Leinenbach - "Unqualifiziert, aber sexy"`. */
        val SOLO_SHOW = Regex("""(.+?)\s+[-–]\s+["„“].+["“”]""")
    }
}
