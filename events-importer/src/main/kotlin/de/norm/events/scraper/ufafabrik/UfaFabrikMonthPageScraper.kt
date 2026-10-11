package de.norm.events.scraper.ufafabrik

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.CONJUNCTION_TAIL_MARKERS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ISO_DATE_LENGTH
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.stripArtistSuffix
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
            artists = ufaFabrikArtists(title, subtitle, eventType)
        )
    }

    /** The labelled prices of a row, `Eintritt` to 20.00, `Ermäßigt` to 15.00. */
    private fun Element.prices(): Map<String, BigDecimal?> =
        select(".paragraph--type--event-price").associate { it.textAt(".field--name-field-name").orEmpty() to it.priceValue() }

    private fun Element.priceValue(): BigDecimal? = selectFirst(".field--name-field-value[content]")?.attr("content")?.toBigDecimalOrNull()

    private fun Map<String, BigDecimal?>.labelled(label: String): BigDecimal? = entries.firstOrNull { it.key.equals(label, ignoreCase = true) }?.value

    private companion object {
        const val ADMISSION_LABEL = "Eintritt"
        const val PRESALE_LABEL = "Vorverkauf"
        const val BOX_OFFICE_LABEL = "Abendkasse"

        /** The node of `/veranstaltung/40154/elsa`, shared by every date of the run. */
        val NODE_ID = Regex("""/veranstaltung/(\d+)/""")
    }
}

/**
 * The acts of a row. A concert title bills people with the German `mit` and a mid-title `Special Guest`
 * (#3106): `Ulla Meinecke mit Reinmar Henschke` is two acts, and the guest of `… – special guest: Dotschy
 * Reinhardt` is support. Local, because at most venues `mit` opens a night's guests, which is why the shared
 * `WITH_FRAME_PATTERN` is opt-in. `Lacrimosa mit Orchester` and `X mit seiner Band` stay one act.
 */
internal fun ufaFabrikArtists(
    title: String,
    subtitle: String?,
    eventType: String
): List<ScrapedArtist> {
    if (eventType != EventType.CONCERT.name) return buildArtistsForEventType(title, subtitle, eventType)
    val cut = SPECIAL_GUEST.find(title)?.takeIf { it.range.first > 0 }
    val billing = cut?.let { title.substring(0, it.range.first) } ?: title
    val guests =
        cut
            ?.let { headlinersFromTitle(title.substring(it.range.last + 1)) }
            .orEmpty()
            .map { it.copy(role = "SUPPORT") }
    val (fromTitle, fromSubtitle) = buildArtistsForEventType(billing, subtitle, eventType).partition { it.titleDerived }
    return fromTitle.flatMap(::splitAtMit) + guests.flatMap(::splitAtMit) + fromSubtitle
}

private fun splitAtMit(artist: ScrapedArtist): List<ScrapedArtist> {
    val cuts = MIT.findAll(artist.name).filterNot { it.groupValues[1].lowercase() in MIT_TAIL_MARKERS }.toList()
    if (cuts.isEmpty()) return listOf(artist)
    val bounds = listOf(0) + cuts.flatMap { listOf(it.range.first, it.range.last + 1 - it.groupValues[1].length) } + artist.name.length
    return bounds
        .chunked(2)
        .map { (from, to) -> stripArtistSuffix(artist.name.substring(from, to)) }
        .filterNot { it.isBlank() || isNonArtistName(it) }
        .map { artist.copy(name = it) }
}

/** A mid-title guest credit, after a dash, `&`, `+`, a comma or `mit`; the guests follow. */
private val SPECIAL_GUEST =
    Regex("""\s*(?:(?:[-–—&+,]|\bmit\b)\s*)?\bspecial\s+guests?\b\s*:?\s*""", RegexOption.IGNORE_CASE)

/** A ` mit ` between two acts; the group is the next word, which can keep the name whole. */
private val MIT = Regex("""\s+mit\s+(\S+)""", RegexOption.IGNORE_CASE)

/** The shared band-tail words plus German possessives: `X mit seiner Band` is one act. */
private val MIT_TAIL_MARKERS: Set<String> = CONJUNCTION_TAIL_MARKERS + setOf("seiner", "seinem", "ihrer", "ihrem", "seinen", "ihren")

/**
 * Whether a show is for children, which is out of scope (EVENT_SCOPE.md §3.5): the house prices
 * those per child, Kita child or accompanying Erzieher*in, and no evening show carries such a label.
 */
internal fun isChildrensShow(priceLabels: Collection<String>): Boolean = priceLabels.any { CHILD_PRICE_LABEL.containsMatchIn(it) }

private val CHILD_PRICE_LABEL = Regex("""^\s*(?:kinder|kita-kind|erzieher)""", RegexOption.IGNORE_CASE)

/**
 * The type for the house's free-text genre label. Comedy and Kabarett are matched first, so
 * "Comedy-Theater" and "Musikkabarett" are comedy (#2314), then stage formats before music; an
 * unknown label is [EventType.OTHER].
 */
internal fun genreType(genre: String?): String {
    val label = genre?.lowercase() ?: return EventType.OTHER.name
    return GENRE_TYPES.entries.firstOrNull { (keyword, _) -> keyword in label }?.value ?: EventType.OTHER.name
}

private val GENRE_TYPES: Map<String, String> =
    linkedMapOf(
        "comedy" to EventType.COMEDY.name,
        "kabarett" to EventType.COMEDY.name,
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
