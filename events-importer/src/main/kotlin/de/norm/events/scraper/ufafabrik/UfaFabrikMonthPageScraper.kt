package de.norm.events.scraper.ufafabrik

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/**
 * Pure parser for one month of the Drupal `calendar_view` programme, `/spielplan.html` or
 * `/program/YYYYMM`. Each `td.current-month` is a day with its `time[datetime]`, and each
 * `li.calendar-view-day__row` in it one show: time, room, labelled prices, teaser image, the
 * house's genre label, title and subtitle, and a banner for "Karten kaufen", "Ausverkauft" or
 * "Fällt aus". Padding days from the months either side are skipped, since their own page lists them.
 *
 * One node serves every date of a run, so the `sourceId` is the node plus date and time.
 */
class UfaFabrikMonthPageScraper {
    fun scrape(document: Document): List<ScrapedEvent> =
        document.select("td.current-month").flatMap { day ->
            val date =
                day
                    .selectFirst("time[datetime]")
                    ?.attr("datetime")
                    ?.take(ISO_DATE_LENGTH)
                    ?.let(::parseIsoDate)
            if (date == null) return@flatMap emptyList()
            day.select("li.calendar-view-day__row").filterNot { isChildrensShow(it.prices().keys) }.mapNotNull { row ->
                @Suppress("TooGenericExceptionCaught") // One malformed row must not abort the month
                try {
                    parseRow(row, date)
                } catch (e: Exception) {
                    logger.warn(e) { "Skipping a ufaFabrik row on $date that failed to parse" }
                    null
                }
            }
        }

    private fun parseRow(
        row: Element,
        date: LocalDate
    ): ScrapedEvent? {
        val link = row.selectFirst(".views-field-title a[href]")
        val title = link?.text()?.trim().orEmpty()
        val node =
            link
                ?.attr("href")
                ?.let { NODE_ID.find(it) }
                ?.groupValues
                ?.get(1)
        if (link == null || node == null || title.isEmpty()) return null
        val prices = row.prices()
        val time = parseTime(row.textAt(".views-field-field-time"))
        val subtitle = row.textAt(".views-field-field-subtitle")
        val genre = row.textAt(".views-field-field-genre")
        val eventType = genreType(genre)
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            eventDate = date,
            startTime = time,
            room = row.textAt(".views-field-field-venue"),
            imageUrl = row.selectFirst(".views-field-field-teaser-image img")?.absUrl("src")?.ifEmpty { null },
            sourceUrl = link.absUrl("href"),
            sourceId = "${EventSource.UFA_FABRIK.sourceIdPrefix}$node-$date-${time?.toString()?.replace(":", "") ?: "0000"}",
            ticketUrl = row.selectFirst(".buy-tickets a[href]")?.absUrl("href"),
            genre = genre,
            pricePresale = prices.labelled(PRESALE_LABEL) ?: prices[ADMISSION_LABEL],
            priceBoxOffice = prices.labelled(BOX_OFFICE_LABEL) ?: prices[ADMISSION_LABEL],
            soldOut = row.selectFirst(".sold-out.event-banner") != null,
            status = if (row.selectFirst(".canceled.event-banner") != null) EventStatus.CANCELLED.name else EventStatus.SCHEDULED.name,
            artists = buildArtistsForEventType(title, subtitle, eventType)
        )
    }

    /** The labelled prices of a row, `Eintritt` to 20.00, `Ermäßigt` to 15.00. */
    private fun Element.prices(): Map<String, BigDecimal?> =
        select(".paragraph--type--event-price").associate { it.textAt(".field--name-field-name").orEmpty() to it.priceValue() }

    private fun Element.priceValue(): BigDecimal? = selectFirst(".field--name-field-value[content]")?.attr("content")?.toBigDecimalOrNull()

    private fun Map<String, BigDecimal?>.labelled(label: String): BigDecimal? = entries.firstOrNull { it.key.equals(label, ignoreCase = true) }?.value

    private companion object {
        const val ISO_DATE_LENGTH = 10
        const val ADMISSION_LABEL = "Eintritt"
        const val PRESALE_LABEL = "Vorverkauf"
        const val BOX_OFFICE_LABEL = "Abendkasse"

        /** The node of `/veranstaltung/40154/elsa`, shared by every date of the run. */
        val NODE_ID = Regex("""/veranstaltung/(\d+)/""")
    }
}

/**
 * Whether a show is for children, which is out of scope (EVENT_SCOPE.md §3.5): the house prices
 * those per child, Kita child or accompanying Erzieher*in, and no evening show carries such a label.
 */
internal fun isChildrensShow(priceLabels: Collection<String>): Boolean = priceLabels.any { CHILD_PRICE_LABEL.containsMatchIn(it) }

private val CHILD_PRICE_LABEL = Regex("""^\s*(?:kinder|kita-kind|erzieher)""", RegexOption.IGNORE_CASE)

/**
 * The type for the house's free-text genre label. Comedy is matched first, so "Comedy-Theater" is
 * comedy, then stage formats before music, so "Musikkabarett" is a show; an unknown label is
 * [EventType.OTHER].
 */
internal fun genreType(genre: String?): String {
    val label = genre?.lowercase() ?: return EventType.OTHER.name
    return GENRE_TYPES.entries.firstOrNull { (keyword, _) -> keyword in label }?.value ?: EventType.OTHER.name
}

private val GENRE_TYPES: Map<String, String> =
    linkedMapOf(
        "comedy" to EventType.COMEDY.name,
        "kabarett" to EventType.SHOW.name,
        "theater" to EventType.SHOW.name,
        "zauber" to EventType.SHOW.name,
        "magie" to EventType.SHOW.name,
        "hörspiel" to EventType.SHOW.name,
        "puppen" to EventType.SHOW.name,
        "mitsing" to EventType.SHOW.name,
        "varieté" to EventType.SHOW.name,
        "show" to EventType.SHOW.name,
        "lesung" to EventType.READING.name,
        "leseshow" to EventType.READING.name,
        "poetry" to EventType.READING.name,
        "slam" to EventType.READING.name,
        "film" to EventType.SCREENING.name,
        "konzert" to EventType.CONCERT.name,
        "musik" to EventType.CONCERT.name,
        "songwriter" to EventType.CONCERT.name,
        "jazz" to EventType.CONCERT.name
    )
