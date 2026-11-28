package de.norm.events.scraper.colosseum

import de.norm.events.common.deshout
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.WixEventsWarmupData
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.parseWixSchedule
import de.norm.events.scraper.parseWixTicketPrice
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.wixParagraphs
import de.norm.events.scraper.wixTicketPriceRange
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.time.LocalTime

/**
 * Pure parser for one Colosseum event page (`/details-registrierung/<slug>`), read for its times,
 * its ticket price and its description.
 *
 * **The page states two Einlass/Beginn pairs, and the field they sit in tells them apart.** The
 * payload's `about` is a cloned block the house never rewrites: all 18 live events carry the same
 * `Einlass: 19 Uhr / Beginn: 20 Uhr` there, over a Dustin O'Halloran biography. The rich-content
 * `longDescription` carries the event's own lines, and all 18 have them. Reading the field rather
 * than the formatting is what gets the hard rows right: the Gene Krupa Show states `Einlass: 17
 * Uhr` without minutes, and Peter Sandberg states the boilerplate's own clocks as its own text
 * (#1684).
 *
 * **The listing's time is the Einlass more often than the Beginn** — 10 of 18 against 8 — so it
 * cannot be relabelled without this page, and the pair read here replaces it whole.
 *
 * **The price is the ticket's own, the figure the page labels `Preis`** (#1954): face value plus
 * the presale fee, as other venues publish it. The listing's `lowestTicketPrice` is the checkout
 * total, with Wix's service fee and, on some events, VAT added on top — 21,72 € for a 19,80 €
 * ticket. An event sold through an outside shop has no `tickets`, and keeps the listing's figure.
 * Several tiers give the lowest as the price and the range as the note.
 *
 * **The event's own text also names its type**, where the listing gives none: a comedian, a
 * concert or a composer, or a podcast recorded live ([typeOf]). That text is also the description, `Einlass` and `Beginn` lines
 * included, as the venue wrote them (#4686). A festival's text bills its bands in a `>>>` list under each `LIVE MUSIK`
 * heading ([liveMusicActs]). The cloned `about` block stays out, for the reason in
 * [ColosseumWebsiteImporter]'s KDoc.
 *
 * @see ColosseumWebsiteImporter for the fetch orchestration.
 */
class ColosseumDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the doors and start times of one event page, or null when the payload cannot be read.
     *
     * The returned event carries the times and the page's own title and date; everything else is
     * the listing's, which [ColosseumWebsiteImporter] merges back in.
     */
    fun scrape(
        document: Document,
        url: String
    ): ScrapedEvent? {
        val state = WixEventsWarmupData.pageState(document, EventSource.COLOSSEUM)
        val event = state?.let { WixEventsWarmupData.eventOf(it, EventSource.COLOSSEUM) } ?: return null
        val prices = state.path("tickets").mapNotNull { parseWixTicketPrice(it.path("price")) }
        val schedule = parseWixSchedule(event.path("scheduling").path("config"))
        val text = wixParagraphs(event.path("longDescription"))
        val lines = timeLines(text)
        val start = lines[BEGINN] ?: schedule.startTime
        // An Einlass equal to the start is the listing's own time read back, not a door time.
        val doors = lines[EINLASS]?.takeIf { it != start }
        if (lines.isEmpty()) {
            logger.debug { "Colosseum event page states no Einlass or Beginn time: $url" }
        }
        return ScrapedEvent(
            title = event.stringOrNull("title").orEmpty(),
            description = text.mapNotNull { it.blankToNull() }.joinToString("\n\n").blankToNull(),
            eventDate = schedule.date ?: UNRESOLVED_EVENT_DATE,
            eventType = typeOf(text),
            doorsTime = doors,
            startTime = start,
            pricePresale = prices.minOrNull(),
            priceNote = wixTicketPriceRange(prices),
            sourceUrl = url,
            sourceId = "${EventSource.COLOSSEUM.sourceIdPrefix}${event.stringOrNull("slug").orEmpty()}",
            artists = liveMusicActs(text)
        )
    }

    /**
     * The clocks the event's own text states, by their label.
     *
     * A label with nothing after it yields no entry, and the listing's time stands. No live event
     * writes one today; the house edits these lines by hand, so the case is covered rather than
     * assumed away.
     */
    private fun timeLines(paragraphs: List<String>): Map<String, LocalTime> =
        paragraphs
            .mapNotNull { text -> TIME_LINE.find(text) }
            .associate { match ->
                val (label, hour, minute) = match.destructured
                label.lowercase() to LocalTime.of(hour.toInt(), minute.ifEmpty { "0" }.toInt())
            }

    internal companion object {
        /**
         * The type the event's own text names, or null: a comedian or stand-up first, then a concert,
         * a composer or a pianist, then a podcast recorded on stage as a show. The listing has no category, so
         * [ColosseumWebsiteImporter] uses it only where the title and the strapline give no cue (#2992).
         */
        fun typeOf(paragraphs: List<String>): String? {
            val text = paragraphs.joinToString(" ")
            return when {
                COMEDY_TEXT.containsMatchIn(text) -> EventType.COMEDY.name
                CONCERT_TEXT.containsMatchIn(text) -> EventType.CONCERT.name
                PODCAST_TEXT.containsMatchIn(text) -> EventType.SHOW.name
                else -> null
            }
        }

        /**
         * The bands of each `>>> DIE HÖCHSTE EISENBAHN, DRANGSAL (TRIO)` list that follows a `LIVE MUSIK` heading.
         * A list under another heading names podcasts, talks or comedians, so it stays unread.
         */
        fun liveMusicActs(paragraphs: List<String>): List<ScrapedArtist> =
            paragraphs
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .zipWithNext()
                .filter { (heading, list) -> LIVE_MUSIC_HEADING.matches(heading) && list.startsWith(LIST_MARK) }
                .flatMap { (_, list) -> list.removePrefix(LIST_MARK).split(',') }
                .map { it.replace(ACT_SUFFIX, "").trim() }
                .filter { it.isNotEmpty() }
                .map { if (it.any(Char::isLowerCase)) it else deshout(it) }
                .distinctBy { it.lowercase() }
                .map { ScrapedArtist(name = it, role = ArtistRole.HEADLINER) }

        private const val LIST_MARK = ">>>"

        /** `🎶 LIVE MUSIK`, with or without its emoji. */
        private val LIVE_MUSIC_HEADING = Regex("""^\W*live[ -]?musik\W*$""", RegexOption.IGNORE_CASE)

        /** A format note after the band: `DRANGSAL (TRIO)`, `JOSI MILLER (LIVE)`, `LOTTE UNPLUGGED DUO SHOW`. */
        private val ACT_SUFFIX = Regex("""\s*\([^)]*\)\s*$|\s+unplugged\b.*$""", RegexOption.IGNORE_CASE)

        private const val EINLASS = "einlass"
        private const val BEGINN = "beginn"

        /** `Stand-up-Comedy`, `Comedians`, `Kabarett`: the act is a comedian. */
        private val COMEDY_TEXT = Regex("""stand-?up|comedian|comedy|kabarett""", RegexOption.IGNORE_CASE)

        /** `Konzert`, `concert`, `composer and pianist`: the night is music. */
        private val CONCERT_TEXT = Regex("""konzert|concert|composer|komponist|pianist""", RegexOption.IGNORE_CASE)

        /**
         * `Der Podcast bietet …`: the night is a podcast recorded live. A concert that names a sponsor podcast types as a concert first,
         * and a guest's bio (`Autor, Podcaster`) is not the word.
         */
        private val PODCAST_TEXT = Regex("""(?<!\p{L})podcast(?!\p{L})""", RegexOption.IGNORE_CASE)

        /** `Einlass: 18:30 Uhr`, and `Beginn: 20 Uhr` — the house writes the minutes only sometimes. */
        private val TIME_LINE = Regex("""^\s*(Einlass|Beginn)\s*:?\s*(\d{1,2})(?::(\d{2}))?\s*Uhr""", RegexOption.IGNORE_CASE)
    }
}
