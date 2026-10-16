package de.norm.events.scraper.acudmachtneu

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.buildArtistList
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.splitSupportActs
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Pure parser for one ACUD month page, `/programm/` or `/programm/YYYY/M/`: one `.programm-page__list__item` per event,
 * with the start date in `data-date`, a `Fr 16.10 → Concert` line and the venue's categories as classes. A running
 * exhibition is listed again in the exhibition block below the month, so the rows are kept once per event id. Its line
 * spans the run, `Do 30.4 — So 6.12`, without a year.
 */
class AcudMachtNeuOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select(".programm-page__list__item:has(a.post-item__title__link[href])")
        logger.info { "Found ${items.size} event item(s) on the ACUD month page $baseUrl" }
        return items.mapSkippingFailures(logger, "ACUD event item") { parseItem(it) }.distinctBy { it.sourceId }
    }

    private fun parseItem(item: Element): ScrapedEvent? {
        val link = item.selectFirst("a.post-item__title__link[href]") ?: return null
        val title = link.text().blankToNull() ?: return null
        val url = link.absUrl("href")
        val date = parseIsoDate(item.attr("data-date"))
        if (date == null) {
            logger.warn { "ACUD event '$title' has no readable date, skipping" }
            return null
        }
        val category = eventType(item.classNames())
        val eventType = category ?: inferUnmarkedTitleType(title)
        return ScrapedEvent(
            title = title,
            eventType = eventType,
            typeIsFallback = category == null,
            eventDate = date,
            endDate = item.selectFirst(".post-item__category--programm")?.let { runEnd(it.text(), date) },
            imageUrl = item.selectFirst(".post-item__image img[data-src]")?.absUrl("data-src").blankToNull(),
            sourceUrl = url,
            sourceId = acudMachtNeuSourceId(url),
            artists = titleActs(title, eventType)
        )
    }

    /** A concert bills the act in its title; `Burre w/ Heavenphetamine` bills the guest after `w/` as support. */
    private fun titleActs(
        title: String,
        eventType: String
    ): List<ScrapedArtist> {
        if (eventType != EventType.CONCERT.name) return emptyList()
        val billing = title.replace(CONCERT_TAIL, "").split(WITH_GUEST, limit = 2)
        return if (billing.size == 2) {
            buildArtistList(billing[0], splitSupportActs(billing[1]))
        } else {
            buildArtistsForEventType(billing[0], null, eventType)
        }
    }

    private companion object {
        /** `LEYA - concert`: the format after the act. */
        val CONCERT_TAIL = Regex("""\s+[-–]\s+(?:live\s+)?concert\s*$""", RegexOption.IGNORE_CASE)

        val WITH_GUEST = Regex("""\s+w/\s+""", RegexOption.IGNORE_CASE)

        /** The last day of a run, `— So  6.12`. */
        val RUN_END = Regex("""[—–]\s*\p{L}{2}\s+(\d{1,2})\.(\d{1,2})""")

        /** The classes the venue files an event under, most specific first; a party may also carry `concert`. */
        val CATEGORIES =
            listOf(
                setOf("exhibition", "exhibition-opening") to EventType.EXHIBITION.name,
                setOf("club-night", "dj-set", "dance", "after-party") to EventType.PARTY.name,
                setOf("concert") to EventType.CONCERT.name,
                setOf("performance") to EventType.SHOW.name,
                setOf("workshop") to EventType.OTHER.name
            )

        fun eventType(classes: Set<String>): String? = CATEGORIES.firstOrNull { (names, _) -> names.any { it in classes } }?.second

        fun runEnd(
            line: String,
            start: LocalDate
        ): LocalDate? {
            val (day, month) = RUN_END.find(line)?.destructured ?: return null
            val end =
                try {
                    LocalDate.of(start.year, month.toInt(), day.toInt())
                } catch (_: DateTimeException) {
                    return null
                }
            return if (end.isBefore(start)) end.plusYears(1) else end
        }
    }
}

/** The numeric event id in `/events/<id>/<slug>`, the same on the month page and on the event page. */
internal fun acudMachtNeuSourceId(url: String): String {
    val id = EVENT_ID.find(url)?.groupValues?.get(1) ?: error("No event id in the ACUD URL $url")
    return "${EventSource.ACUD_MACHT_NEU.sourceIdPrefix}$id"
}

private val EVENT_ID = Regex("""/events/(\d+)(?:/|$)""")
