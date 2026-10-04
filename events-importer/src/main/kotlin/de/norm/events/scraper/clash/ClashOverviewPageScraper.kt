package de.norm.events.scraper.clash

import de.norm.events.event.EventType
import de.norm.events.genretag.isGenreLabel
import de.norm.events.genretag.normalizeGenre
import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanShortDate
import de.norm.events.scraper.parseLabelledPrices
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.DateTimeException
import java.time.LocalTime

/**
 * Pure HTML parser for Clash Berlin's WordPress homepage event listing.
 *
 * All upcoming events render inline in the homepage `#events` section as a flat list of
 * `.gigs-container .item` blocks — no per-event page (the `event` post type is not on the WP
 * REST API, and the numeric `/events/<id>/` permalinks 404). Each block carries a full
 * `DD.MM.YY` date in a `.dateTwo` span, a title, an optional lineup subtitle, a start time, a
 * poster, and for ticketed shows a Stager ticket-shop link.
 *
 * The collapsed `.info-extra` panel holds free prose paragraphs ([Panel]): the blurb, the ticket
 * text (`VVK am Tresen 17 € … AK 20 €`), sometimes `Doors 20:00` / `Show 21:00`, and on some DJ
 * nights a `Punk//Post Punk//New Wave` style line. The header time is the doors time on a night
 * whose panel names a show time, so the panel's start wins.
 *
 * A live-music (punk/ska) club that also hosts quiz, party and festival nights, so the type is
 * inferred from the title ([inferConcertVenueType] — CONCERT by default). Acts come from the
 * lineup subtitle ([parseArtists]). A DJ night has none and names its DJs in the panel, one per
 * line after a `… with DJs` line ([parsePanelDjs]).
 *
 * @see ClashWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://clash-berlin.de/">Clash Berlin</a>
 */
class ClashOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the Clash homepage.
     *
     * @param baseUrl the URL the document was fetched from, for resolving the per-event anchor link.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select(".gigs-container .item")
        logger.info { "Found ${items.size} event item(s) on Clash homepage" }

        return items.mapSkippingFailures(logger, "Clash event item") { item ->
            parseItem(item, baseUrl)
        }
    }

    /**
     * Parses one `.item` block into a [ScrapedEvent], or `null` when title or date is missing/unparseable.
     */
    @Suppress("ReturnCount") // Null-safe early exits for the required title/date fields are clearer than nesting
    private fun parseItem(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val title = item.textAt(".gig-title")
        if (title.isNullOrBlank()) {
            logger.warn { "Clash event item has no title, skipping" }
            return null
        }

        // Prefer the full `DD.MM.YY` date in the collapsed detail (carries the year); the
        // `.date-label` above shows only day + English month abbreviation.
        val eventDate = parseGermanShortDate(item.textAt(".dateTwo"))
        if (eventDate == null) {
            logger.warn { "Could not parse event date for '$title', skipping" }
            return null
        }

        // The collapse panel id encodes DDMMYYYY + WordPress post id (e.g. "2906202619490"); stable per
        // event and doubles as the homepage deep-link anchor.
        val collapseId = item.selectFirst(".collapse.infofull")?.id()?.takeIf { it.isNotBlank() }
        val slug = collapseId ?: "$eventDate-${SlugGenerator.slugify(title)}"

        val subtitle = item.textAt("h4.sub-title")
        val panel = Panel.of(item)
        val prices = parseLabelledPrices(panel.text)
        val imageUrl = item.imgSrcAt(".flyer img")
        // The per-event ticket link points at the Stager shop's `/events/<id>` page; the bare
        // `/shop/tickets/` link in the section header is not inside an `.item`.
        val ticketUrl = item.hrefAt("a[href*=stager.co/shop/tickets/events]")

        // No category field; infer from the title (concert by default for this live-music venue,
        // quiz/party/etc. by keyword). See inferConcertVenueType.
        val eventType = inferConcertVenueType(title)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = panel.description,
            eventType = eventType,
            eventDate = eventDate,
            doorsTime = labelledClock(panel.text, DOORS_LABELS),
            startTime = labelledClock(panel.text, START_LABELS) ?: parseTime(item.textAt(".meta .time")),
            imageUrl = imageUrl,
            // No per-event pages — deep-link to the expanded event on the homepage listing.
            sourceUrl = resolveUrl(baseUrl, "#$slug"),
            sourceId = "${EventSource.CLASH.sourceIdPrefix}$slug",
            ticketUrl = ticketUrl,
            genre = panel.genre(),
            pricePresale = prices.presale,
            priceBoxOffice = prices.boxOffice,
            // An amount with no presale or door label (`Mitmachspende: 3 Euro / Person`) is kept as worded.
            priceNote = panel.lines.firstOrNull { euroAmounts(it).isNotEmpty() }.takeIf { prices.presale == null && prices.boxOffice == null },
            artists = parseArtists(subtitle, eventType).ifEmpty { parsePanelDjs(panel.lines) }
        )
    }

    /** The prose paragraphs of an item's `.info-extra` panel, without the Facebook link. */
    private class Panel(
        paragraphs: List<List<String>>
    ) {
        val lines = paragraphs.flatten()
        val text = lines.joinToString("\n")
        val description = paragraphs.joinToString("\n\n") { it.joinToString("\n") }.ifBlank { null }

        /** The known genres on a `//`-separated style line; a line of prose holds none. */
        fun genre(): String? =
            lines
                .filter { STYLE_SEPARATOR in it }
                .flatMap { normalizeGenre(it) }
                .filter(::isGenreLabel)
                .distinct()
                .joinToString(", ")
                .ifEmpty { null }

        companion object {
            fun of(item: Element) = Panel(item.select(".info-extra > p:not(.fb-event)").map { it.textLines() }.filter { it.isNotEmpty() })
        }
    }

    /**
     * The performing acts from a concert's lineup subtitle. The `h4.sub-title` lists
     * slash-separated acts, sometimes behind a "Live:" / "DJ:" label (`"Live: Cheb Balowski /
     * Cuatro Pesos de Propina"`, `"Popperklopper / Hausvabot / Ad Nauseam"`). The first act is the
     * headliner, the rest support.
     *
     * Only a subtitle that *looks* like a lineup is used — a "Live:"/"DJ:" label or an act
     * separator (`/`, `+`). A plain-prose tagline ("Last Show Ever in Berlin") has neither and
     * yields no artists. Restricted to CONCERT-typed events (quiz/party/other carry no lineup);
     * the title is deliberately not an artist source because Clash titles are frequently event
     * names ("Kneipenquiz", festival days). Non-performers (placeholders, festival/segment
     * labels) are dropped via [isNonArtistName].
     */
    private fun parseArtists(
        subtitle: String?,
        eventType: String?
    ): List<ScrapedArtist> {
        val lineup =
            subtitle
                ?.takeIf { eventType == EventType.CONCERT.name && looksLikeLineup(it) }
                ?.replaceFirst(LINEUP_LABEL_PREFIX, "")
                ?: return emptyList()

        return splitSupportActs(lineup)
            .map { stripArtistSuffix(it) }
            .filterNot { isNonArtistName(it) }
            .mapIndexed { index, name ->
                ScrapedArtist(name = name, role = if (index == 0) "HEADLINER" else "SUPPORT")
            }
    }

    /**
     * The DJs a DJ night names in its panel: each line after a `… with DJs` line, up to the first
     * line that is not a name — a style line (`Punk//Post Punk`), a time or price line
     * (`From 21:00 / 5 €`), or prose. A trailing `(…)` names a duo's members, so it is dropped:
     * `Bolide (Zanardi & Hey Mattia)` is billed as `Bolide` (#2624).
     */
    private fun parsePanelDjs(lines: List<String>): List<ScrapedArtist> {
        val intro = lines.indexOfFirst { DJ_LIST_INTRO.containsMatchIn(it) }
        if (intro < 0) return emptyList()
        return lines
            .drop(intro + 1)
            .takeWhile(::isDjNameLine)
            .map { stripArtistSuffix(it.replace(MEMBERS_NOTE, "")) }
            .filterNot { isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "DJ") }
    }

    private fun isDjNameLine(line: String): Boolean =
        line.length <= MAX_DJ_LINE_LENGTH && STYLE_SEPARATOR !in line && euroAmounts(line).isEmpty() && !TIME_PATTERN.containsMatchIn(line)

    /** Whether [subtitle] carries a lineup marker (a "Live:"/"DJ:" label or a `/`/`+` act separator). */
    private fun looksLikeLineup(subtitle: String): Boolean = LINEUP_LABEL_PREFIX.containsMatchIn(subtitle) || subtitle.contains('/') || subtitle.contains('+')

    /**
     * The start time from the meta line ("Fri 20:00", "Mon 0:00"). The hour may be a single digit
     * ("0:00"), which the strict `HH:mm` parser rejects, so the `H:mm`/`HH:mm` value is extracted
     * by regex and built directly.
     */
    private fun parseTime(text: String?): LocalTime? {
        val match = text?.let { TIME_PATTERN.find(it) } ?: return null
        return try {
            LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        } catch (_: DateTimeException) {
            null
        }
    }

    companion object {
        private const val STYLE_SEPARATOR = "//"

        /** Longer than a DJ name with its members note; a longer line is prose. */
        private const val MAX_DJ_LINE_LENGTH = 60

        /** The panel line before a DJ night's DJs: `Dance Until You Drop Dj-set night with DJs`. */
        private val DJ_LIST_INTRO = Regex("""with\s+djs?\s*:?\s*$""", RegexOption.IGNORE_CASE)

        /** A trailing parenthetical naming the members of a duo or crew. */
        private val MEMBERS_NOTE = Regex("""\s*\([^()]*\)\s*$""")

        /** An `H:mm` / `HH:mm` clock time from the meta line's "<weekday> <time>" text. */
        private val TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})""")

        /** Leading lineup label on a subtitle ("Live:", "DJ:", "DJs:", "Line-up:"), stripped before splitting acts. */
        private val LINEUP_LABEL_PREFIX = Regex("""^\s*(?:live|djs?|line[\s-]?up)\s*:\s*""", RegexOption.IGNORE_CASE)
    }
}
