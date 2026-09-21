package de.norm.events.scraper.monsterronsons

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.dropPastEvents
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.parseEnglishWeekdayAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.Month
import java.time.MonthDay

/**
 * Pure HTML parser for Monster Ronson's Webflow event listing (`/events`).
 *
 * A Webflow CMS collection: one `.grid-item` card per calendar day over a rolling window of
 * roughly twelve days. Every card carries the whole summary — host title, weekday, day + month,
 * start time, teaser line, poster — so this scraper produces complete events; the detail page
 * only adds description, price and ticket link.
 *
 * 1. **Year-less dates.** Cards state `Thu` / `6 Aug`. The year is inferred from the weekday
 * ([inferYearForWeekday]) rather than assumed current — the rule Arcanoa, gART.n and VOID Club
 * follow. Month names are English here, unlike those three.
 * 2. **Closure cards.** Dark days are cards titled `CLOSED`, with an empty time and "Sorry, we
 * are closed" as the teaser. Dropped here, before the detail fetch, so no request is spent.
 *
 * The CMS recycles entries: a card's slug (`/posts/sing-with-fauxpas-2`) often no longer matches
 * the host it advertises, and the same slug reappears on later dates as the rotation comes
 * round. `sourceId` therefore combines date and slug (`monster_ronsons:<date>-<slug>`); the slug
 * alone would upsert every night of a rotation onto one row — Bar jeder Vernunft's
 * per-performance identity reasoning (ADR-007 §"Shared Detail Pages").
 *
 * @see MonsterRonsonsDetailPageScraper for the per-event description, price and ticket link.
 * @see <a href="https://www.karaokemonster.de/events">Monster Ronson's events page</a>
 */
class MonsterRonsonsOverviewPageScraper(
    /** Clock for year inference and the past-event cutoff; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every event card on the listing.
     *
     * @param sourceUrl the URL the document was fetched from, for resolving relative card links.
     * @return the upcoming karaoke nights (today onward); closure cards and past dates are dropped.
     */
    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val cards = document.select(CARD_SELECTOR)
        logger.info { "Found ${cards.size} event card(s) on page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed cards without aborting the import
        val events =
            cards.mapNotNull { card ->
                try {
                    parseCard(card, sourceUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse event card, skipping" }
                    null
                }
            }

        // The window starts today, so this normally drops nothing; it guards a card lingering past midnight.
        return events.dropPastEvents(clock) { dropped ->
            logger.info { "Dropped $dropped past event(s) from Monster Ronson's listing" }
        }
    }

    /**
     * Parses one `.grid-item` card into a [ScrapedEvent], or null for a closure notice or no usable date.
     */
    @Suppress("ReturnCount") // Early exits for a closure card and an unparseable date are clearer than nested lets
    private fun parseCard(
        card: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val title = card.textAt(TITLE_SELECTOR) ?: error("No title found")
        if (isClosureCard(title)) {
            logger.debug { "Skipping closure card '$title'" }
            return null
        }

        // The card links relatively (`/posts/<slug>`), so the raw attribute is read and resolved here;
        // `hrefAt` only returns already-absolute URLs.
        val href = card.attrAt(CARD_LINK_SELECTOR, "href") ?: error("No card link found")
        val eventDate =
            parseCardDate(card) ?: run {
                logger.warn { "Could not parse date for '$title', skipping event" }
                return null
            }

        val eventUrl = resolveUrl(sourceUrl, href)
        val slug = href.substringAfterLast('/')

        return ScrapedEvent(
            title = title,
            // The teaser ("Sing on stage!") is a standing call to action, not a description: it reads as
            // a subtitle and the detail page supplies the prose.
            subtitle = card.textAt(TEASER_SELECTOR),
            // Every night is a hosted karaoke night — the venue programmes nothing else — so the type is
            // asserted, not inferred from a title that never says "karaoke".
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            startTime = parseTime(card.textAt(TIME_SELECTOR)),
            imageUrl = card.imgSrcAt(IMAGE_SELECTOR),
            sourceUrl = eventUrl,
            sourceId = "${EventSource.MONSTER_RONSONS.sourceIdPrefix}$eventDate-$slug",
            artists = hostsFromTitle(title)
        )
    }

    /**
     * Resolves `Thu` / `6 Aug` into a full date: only a year whose `6 Aug` falls on a Thursday is
     * eligible, nearest to today wins. A missing or unrecognised weekday still resolves —
     * [inferYearForWeekday] then takes the nearest occurrence.
     */
    private fun parseCardDate(card: Element): LocalDate? {
        val monthDay = parseMonthDay(card.textAt(DATE_SELECTOR)) ?: return null
        val weekday = parseEnglishWeekdayAbbreviation(card.textAt(WEEKDAY_SELECTOR)?.lowercase()?.take(WEEKDAY_ABBREVIATION_LENGTH))
        return inferYearForWeekday(monthDay, weekday, clock)
    }

    /** Parses the card's `6 Aug` / `16 Aug` text into a [MonthDay]. */
    @Suppress("ReturnCount") // Null-safe early exits per date component are clearer than a let-chain
    private fun parseMonthDay(text: String?): MonthDay? {
        val match = DAY_MONTH_PATTERN.find(text?.trim().orEmpty()) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = ENGLISH_MONTH_ABBREVIATIONS[match.groupValues[2].lowercase()] ?: return null
        return runCatching { MonthDay.of(month, day) }.getOrNull()
    }

    /**
     * The night's host(s) from a `SING WITH <HOST>` title — the only named performers the source
     * publishes. A title off the pattern (`BOXHOPPING!`) names a format, not a person, and yields
     * no artists. Hosts are [de.norm.events.event.ArtistRole.DJ]: they run the night from the
     * booth rather than performing a set.
     */
    private fun hostsFromTitle(title: String): List<ScrapedArtist> {
        val hosts = HOST_TITLE_PATTERN.find(title)?.groupValues?.get(1) ?: return emptyList()
        return hosts
            .split(HOST_SEPARATOR_PATTERN)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { ScrapedArtist(name = it, role = "DJ") }
    }

    /** A card announcing a dark day rather than a night's programme. */
    private fun isClosureCard(title: String): Boolean = title.trim().equals(CLOSED_TITLE, ignoreCase = true)

    companion object {
        /** One Webflow CMS collection item per calendar day. */
        private const val CARD_SELECTOR = ".grid-container .grid-item"

        /** The card's headline, e.g. "SING WITH IVANKA TRAMP". */
        private const val TITLE_SELECTOR = ".event-overview-hp-head"

        /**
         * The card's detail link; poster and "More" button share the href. Quoted because Jsoup's
         * attribute-prefix syntax needs it for a value containing `/`.
         */
        private const val CARD_LINK_SELECTOR = """a[href^="/posts/"]"""

        /** Weekday abbreviation ("Thu") in the card's date strip. */
        private const val WEEKDAY_SELECTOR = ".when-time-parent .text-block-9"

        /** Day and month ("6 Aug") in the card's date strip. */
        private const val DATE_SELECTOR = ".when-time-parent .date-text"

        /** Start time ("20:00"); empty on closure cards. */
        private const val TIME_SELECTOR = ".when-time-parent .text-block-8"

        /** One-line teaser under the title. */
        private const val TEASER_SELECTOR = ".short-descr"

        /** Poster image. */
        private const val IMAGE_SELECTOR = "img.grid-img"

        /** Title the venue gives its dark days. */
        private const val CLOSED_TITLE = "CLOSED"

        /** Length of the weekday abbreviations the site prints ("Thu"). */
        private const val WEEKDAY_ABBREVIATION_LENGTH = 3

        /** Matches the card's `6 Aug` day-and-month text. */
        private val DAY_MONTH_PATTERN = Regex("""(\d{1,2})\s+([A-Za-z]{3,})""")

        /** The host part of a `SING WITH <HOST>` title, including the `SING ON STAGE WITH` variant. */
        private val HOST_TITLE_PATTERN = Regex("""^SING\s+(?:ON\s+STAGE\s+)?WITH\s+(.+)$""", RegexOption.IGNORE_CASE)

        /** Splits a co-hosted night's `A & B` host list. */
        private val HOST_SEPARATOR_PATTERN = Regex("""\s*(?:&|\+|,)\s*""")

        /**
         * English month abbreviations. The shared [de.norm.events.scraper.parseGermanMonthAbbreviation]
         * covers the German spellings every other venue uses; this venue writes English, and the two
         * disagree on Mar/Mär, May/Mai, Oct/Okt and Dec/Dez.
         */
        private val ENGLISH_MONTH_ABBREVIATIONS: Map<String, Month> =
            mapOf(
                "jan" to Month.JANUARY,
                "feb" to Month.FEBRUARY,
                "mar" to Month.MARCH,
                "apr" to Month.APRIL,
                "may" to Month.MAY,
                "jun" to Month.JUNE,
                "jul" to Month.JULY,
                "aug" to Month.AUGUST,
                "sep" to Month.SEPTEMBER,
                "oct" to Month.OCTOBER,
                "nov" to Month.NOVEMBER,
                "dec" to Month.DECEMBER
            )
    }
}
