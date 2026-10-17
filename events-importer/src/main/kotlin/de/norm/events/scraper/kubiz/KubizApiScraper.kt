package de.norm.events.scraper.kubiz

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WpRestPage
import de.norm.events.scraper.billsChildrensShow
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.inferVenueFormatType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/**
 * Reads the nights from KuBiZ's WordPress posts (`/wp/v2/posts`). A post is an event only when its
 * title opens with its date (`7.3.26 Jazzkonzert: …`, `Mi,18.12.24: …`, `8.3. …`) or ends with
 * `am 18.09.2026`. A post without one is news and is skipped with a warning; the publish date is
 * never the event date, and only lends the year to a date printed without one. A range
 * (`3. – 4. 7.2025`) or an `Ab 11.9.26` series start is not one date and is skipped too. The first
 * heading of the body holds the start time and the room (`20 Uhr, Aula`).
 */
class KubizApiScraper(
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}
    private val jsonMapper = JsonMapper.builder().build()

    @Suppress("TooGenericExceptionCaught") // A malformed payload must degrade to an empty page, never abort the import.
    fun scrape(json: String): KubizPage {
        val posts =
            try {
                jsonMapper.readTree(json).takeIf { it.isArray }?.toList()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse the KuBiZ post listing page" }
                null
            }
        if (posts == null) {
            logger.warn { "KuBiZ post listing page is not a JSON array; stopping" }
            return KubizPage(emptyList(), postCount = 0)
        }
        val today = LocalDate.now(clock)
        val events =
            posts
                .mapSkippingFailures(logger, "KuBiZ post") { parsePost(it) }
                .filterNot { it.eventDate.isBefore(today) }
        return KubizPage(events, postCount = posts.size)
    }

    private fun parsePost(post: JsonNode): ScrapedEvent? {
        val id = post.path("id").asLong(0).takeIf { it > 0 } ?: return null
        val rawTitle = post.path("title").stringOrNull("rendered")?.let(::plainText) ?: return null
        val published = post.stringOrNull("date")?.let(::parseIsoDate)
        val dated = splitTitleDate(rawTitle, published)
        if (dated == null) {
            logger.warn { "KuBiZ post $id '$rawTitle' names no readable date in its title, skipping" }
            return null
        }
        val title = cleanEventTitle(dated.second).blankToNull() ?: return null
        if (PARTICIPATION_FORMAT.containsMatchIn(title) || billsChildrensShow(title, null)) {
            logger.debug { "KuBiZ post $id '$title' is not a stage programme, skipping" }
            return null
        }
        val body = Jsoup.parseBodyFragment(post.path("content").stringOrNull("rendered").orEmpty())
        val heading = body.selectFirst("h1, h2, h3, h4, h5, h6")
        val headingText = heading?.text().orEmpty()
        heading?.remove()
        val description = htmlParagraphText(body.body().html())
        val priceNote = description?.lineSequence()?.firstNotNullOfOrNull { PRICE_LINE.find(it)?.groupValues?.get(1) }
        val eventType = inferVenueFormatType(title, null, KUBIZ_FORMATS)
        return ScrapedEvent(
            title = title,
            description = description,
            eventType = eventType,
            eventDate = dated.first,
            startTime = startTimeOf(headingText),
            room = ROOM.find(headingText.substringAfter("Uhr"))?.groupValues?.get(1),
            imageUrl = body.selectFirst("img[src]")?.absUrl("src").blankToNull(),
            sourceUrl = post.stringOrNull("link") ?: "$SITE/?p=$id",
            sourceId = "${EventSource.KUBIZ.sourceIdPrefix}$id",
            priceNote = priceNote,
            free = detectFree(priceNote = priceNote, title = title),
            artists = if (eventType == EventType.CONCERT.name) concertArtists(title) else emptyList()
        )
    }

    /** The event date and the title without it, or null when the title names no single readable date. */
    private fun splitTitleDate(
        title: String,
        published: LocalDate?
    ): Pair<LocalDate, String>? {
        val leading = LEADING_DATE.find(title)
        val trailing = if (leading == null) TRAILING_DATE.find(title) else null
        val match = leading ?: trailing ?: return null
        val (day, month, year) = match.destructured
        val date = resolveDate(day.toInt(), month.toInt(), year.blankToNull()?.toInt(), published) ?: return null
        val rest = if (leading != null) title.substring(match.range.last + 1) else title.substring(0, match.range.first)
        return date to rest.trim()
    }

    private fun resolveDate(
        day: Int,
        month: Int,
        year: Int?,
        published: LocalDate?
    ): LocalDate? =
        try {
            when {
                year != null -> LocalDate.of(if (year < CENTURY) CURRENT_CENTURY + year else year, month, day)

                published == null -> null

                // A post goes up before its night, so a date before the publish month belongs to the next year.
                else -> LocalDate.of(published.year, month, day).let { if (it.isBefore(published.minusDays(BACKDATE_DAYS))) it.plusYears(1) else it }
            }
        } catch (_: DateTimeException) {
            null
        }

    private fun startTimeOf(heading: String): LocalTime? {
        val match = HEADING_TIME.find(heading.trim()) ?: return null
        val (hour, minute, uhr) = match.destructured
        if (minute.isEmpty() && uhr.isEmpty()) return null
        val h = hour.toInt()
        val m = minute.ifEmpty { "0" }.toInt()
        return if (h < HOURS_PER_DAY && m < MINUTES_PER_HOUR) LocalTime.of(h, m) else null
    }

    /**
     * The act a concert title names after its series label (`Jazzkonzert: ENIVRANCE`, `Jazz im Kubiz: X`), or
     * the whole title when it carries no label. A tribute, a series or a quoted programme name bills no act.
     */
    private fun concertArtists(title: String): List<ScrapedArtist> {
        val stripped = title.replaceFirst(SERIES_LABEL, "").trim()
        val act = QUOTED.matchEntire(stripped)?.groupValues?.get(1) ?: stripped
        return act
            .substringBefore(':')
            .blankToNull()
            ?.takeUnless { NOT_AN_ACT.containsMatchIn(it) }
            ?.let { headlinersFromTitle(it, splitOnSlash = false) }
            .orEmpty()
    }

    private fun plainText(html: String): String = Jsoup.parseBodyFragment(html.replace(BR, " ")).text()

    private companion object {
        const val SITE = "https://www.kubiz-wallenberg.de"
        const val CENTURY = 100
        const val CURRENT_CENTURY = 2000
        const val BACKDATE_DAYS = 31L
        const val HOURS_PER_DAY = 24
        const val MINUTES_PER_HOUR = 60

        val BR = Regex("""(?i)<br\s*/?>""")

        /** `7.3.26`, `15. 11.25`, `Mi,18.12.24:`, `Fr. 17.05.24 –`, `8.3.`; a range or a series start does not match. */
        val LEADING_DATE =
            Regex("""^(?:(?:mo|di|mi|do|fr|sa|so)\.?,?\s*)?(\d{1,2})\.\s?(\d{1,2})\.(\d{4}|\d{2})?(?![\d.\-–])\s*[:–-]?""", RegexOption.IGNORE_CASE)

        /** `Jazz im KuBiZ am 18.09.2026`: a trailing date counts only with its year. */
        val TRAILING_DATE = Regex("""\s+am\s+(\d{1,2})\.(\d{1,2})\.(\d{4}|\d{2})\s*$""", RegexOption.IGNORE_CASE)

        /** `20 Uhr`, `20.30 Uhr Offener Raum`, `20.30 Open Air`: a bare number with neither minutes nor `Uhr` is no time. */
        val HEADING_TIME = Regex("""^(\d{1,2})(?:[.:](\d{2}))?\s*(Uhr)?(?!\d)""", RegexOption.IGNORE_CASE)

        val ROOM = Regex("""^[\s,]*(Aula|Offener Raum|Kiezraum)\b""")

        val PRICE_LINE = Regex("""^(?:Tickets|Eintritt)\s*:?\s*(.+)$""", RegexOption.IGNORE_CASE)

        /** What visitors take part in rather than watch (EVENT_SCOPE.md §3.2), and the house's organising posts. */
        val PARTICIPATION_FORMAT =
            Regex("""\b(?:workshop|schnuppertermin|flohmarkt|infoveranstaltung|einweihung|kurs)\b""", RegexOption.IGNORE_CASE)

        val SERIES_LABEL =
            Regex("""^(?:jazz\s+im\s+kubiz|jazz\s*-?\s*konzert|jazzwoche|jazz\s+vom|konzert|balfolk\s+mit)\s*[:–-]?\s*""", RegexOption.IGNORE_CASE)

        val QUOTED = Regex("""^[„"»“]\s*(.+?)\s*[“"«”]$""")

        val NOT_AN_ACT = Regex("""\b(?:hommage|reihe|reunion)\b|[„"»“]""", RegexOption.IGNORE_CASE)

        /** The house's own format words, first match wins: a film with a discussion is a screening. */
        val KUBIZ_FORMATS: Map<String, String> =
            linkedMapOf(
                "film" to EventType.SCREENING.name,
                "kino" to EventType.SCREENING.name,
                "vernissage" to EventType.EXHIBITION.name,
                "ausstellung" to EventType.EXHIBITION.name,
                "lesung" to EventType.READING.name,
                "theater" to EventType.SHOW.name,
                "jazz" to EventType.CONCERT.name,
                "konzert" to EventType.CONCERT.name,
                "balfolk" to EventType.CONCERT.name,
                "quintett" to EventType.CONCERT.name,
                "quartett" to EventType.CONCERT.name,
                "quartet" to EventType.CONCERT.name,
                "trio" to EventType.CONCERT.name,
                "diskussion" to EventType.READING.name,
                "vortrag" to EventType.READING.name
            )
    }
}

/** One page of the post listing: the upcoming events, and how many posts the response held. */
data class KubizPage(
    val events: List<ScrapedEvent>,
    override val postCount: Int
) : WpRestPage<ScrapedEvent> {
    override val items: List<ScrapedEvent> get() = events
}
