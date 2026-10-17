package de.norm.events.scraper.brotfabrik

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.TecPage
import de.norm.events.scraper.billsChildrensShow
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hasSoldOutMarker
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.parseTecPage
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.tecDateTime
import de.norm.events.scraper.tecDescription
import de.norm.events.scraper.tecImageUrl
import de.norm.events.scraper.tecTermNames
import de.norm.events.scraper.tecText
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.net.URI

/**
 * Pure JSON parser for the Brotfabrik's **The Events Calendar** REST API; the page shape and the field readers are [parseTecPage]'s.
 *
 * - **Only the stage and the readings are the programme** ([isStageProgramme]): `Bühne` and `Literatur`. The cinema, the
 * courses, the sound project and the gallery's open days are dropped (EVENT_SCOPE.md §3.2).
 * - **The stage plays children's theatre in the morning**, billed by an age range (`4 – 8 Jahren`) or as `KINDERSHOWS`.
 * Those go (EVENT_SCOPE.md §3.5); `ab 13 Jahren` stays.
 * - **A run's dates share one `slug`**, so the key is the URL path, which carries the date of each (`trotzdem/2026-10-19`).
 * - **Every event ends two hours after it starts**, the plugin's default, so the end is not read.
 */
class BrotfabrikApiScraper {
    private val logger = KotlinLogging.logger {}

    fun scrapePage(json: String): TecPage = parseTecPage(json, EventSource.BROTFABRIK, ::toScrapedEvent)

    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val url = event.stringOrNull("url") ?: return null
        val start = event.tecDateTime("start_date") ?: return null
        val title = event.tecText("title")?.let(::cleanEventTitle)
        if (title.isNullOrBlank()) {
            logger.warn { "Brotfabrik event '$url' has no title, skipping" }
            return null
        }
        val categories = event.tecTermNames("categories")
        val description = event.tecDescription()
        if (!isStageProgramme(categories) || isChildrensShow(title, description)) {
            logger.debug { "Skipping Brotfabrik event '$title': not the evening stage programme" }
            return null
        }
        val prices = event.path("cost_details").path("values").mapNotNull { it.asString("").toBigDecimalOrNull() }

        return ScrapedEvent(
            title = title,
            description = description,
            eventType = typeOf(title, categories),
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            imageUrl = event.tecImageUrl(),
            sourceUrl = url,
            sourceId = "${EventSource.BROTFABRIK.sourceIdPrefix}${URI(url).path.removePrefix(EVENT_PATH).trim('/')}",
            ticketUrl = event.stringOrNull("website"),
            pricePresale = prices.singleOrNull()?.takeIf { it.signum() > 0 },
            priceNote = event.tecText("cost")?.takeIf { prices.size != 1 },
            free = prices.singleOrNull()?.compareTo(BigDecimal.ZERO) == 0,
            soldOut = hasSoldOutMarker(title)
        )
    }

    /** A reading is a reading wherever it sits; a stage night is theatre unless its title names another format. */
    private fun typeOf(
        title: String,
        categories: List<String>
    ): String =
        if (LITERATURE in categories) {
            EventType.READING.name
        } else {
            inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name } ?: EventType.SHOW.name
        }

    private companion object {
        const val STAGE = "Bühne"
        const val LITERATURE = "Literatur"
        const val EVENT_PATH = "/veranstaltung/"

        /** A morning show for small children: an age range up to twelve, a `Kindershow` or a show for a Kita group. */
        val CHILDRENS_SHOW_CUE = Regex("""\b[1-9]\s*[–-]\s*(?:[1-9]|1[0-2])\s+jahren?\b|kindershow|kitavorstellung""", RegexOption.IGNORE_CASE)

        /** The house's stage and literature programme, by the category it files the night under. */
        fun isStageProgramme(categories: List<String>): Boolean = categories.any { it == STAGE || it == LITERATURE }

        fun isChildrensShow(
            title: String,
            description: String?
        ): Boolean = billsChildrensShow(title, description) || CHILDRENS_SHOW_CUE.containsMatchIn("$title ${description.orEmpty()}")
    }
}
