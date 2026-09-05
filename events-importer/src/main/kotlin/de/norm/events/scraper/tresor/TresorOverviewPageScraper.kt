package de.norm.events.scraper.tresor

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stripArtistPrefix
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure HTML parser for Tresor's `/club/events/` listing.
 *
 * Each night is an `article.event-item` whose lineup is already grouped by floor in the markup:
 *
 * ```html
 * <article class="event-item">
 *   <div class="event-date"><a href="…/event/20260801-tresor-klubnacht/"><span>Sa 01.08</span></a></div>
 *   <a class="event-title" href="…"><span><span>Tresor Klubnacht</span></span></a>
 *   <div class="event-lineup">
 *     <div class="event-floor"><div class="floor-name">Tresor</div>
 *       <div class="floor-lineup"><div class="floor-artist"><span>Developer</span></div>…</div></div>
 *     <div class="event-floor"><div class="floor-name">Globus</div>…</div>
 *   </div>
 * </article>
 * ```
 *
 * so the floor a DJ plays maps straight onto [ScrapedArtist.stage] with no heuristics — unlike
 * Kater's rules or Renate's curated floor names. The rendered date carries no year, so the date is
 * read from the `YYYYMMDD` prefix of the permalink instead.
 *
 * @see TresorDetailPageScraper for the set times and blurb.
 * @see TresorWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://tresorberlin.com/club/events/">Tresor events</a>
 */
class TresorOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event items from the listing document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve detail links.
     * @return a list of [ScrapedEvent] instances, in listing order.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("article.event-item")
        logger.info { "Found ${items.size} event item(s) on the Tresor listing" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed items without aborting the whole import
        return items.mapNotNull { item ->
            try {
                parseItem(item, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Tresor event item, skipping" }
                null
            }
        }
    }

    /** Parses one `article.event-item`, or `null` when it has no permalink or title. */
    @Suppress("ReturnCount") // Guard clauses for the required href/title are clearer than nesting
    private fun parseItem(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val href = item.selectFirst("a[href*=/event/]")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, EVENT_PATH_PREFIX)
        val title = item.textAt(".event-title")?.let(::cleanEventTitle) ?: return null

        return ScrapedEvent(
            title = title,
            // A techno club that states no category; every listing is a club night.
            eventType = EventType.PARTY.name,
            // The card prints "Sa 01.08" with no year; the permalink states it.
            eventDate = parseSlugDate(slug) ?: UNRESOLVED_EVENT_DATE,
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.TRESOR.sourceIdPrefix}$slug",
            artists = parseLineup(item)
        )
    }
}

/** Path prefix of a Tresor event permalink, stripped to obtain the `YYYYMMDD-<slug>` identity. */
internal const val EVENT_PATH_PREFIX = "/event/"

/** The `YYYYMMDD` date the venue prefixes to every event permalink. */
private val SLUG_DATE_PATTERN = Regex("""^(\d{8})-""")

/**
 * Reads the event date from the `YYYYMMDD` prefix of a permalink slug, or `null` when the slug
 * carries none — the rendered card shows only a weekday and `DD.MM`.
 */
internal fun parseSlugDate(slug: String): LocalDate? {
    val digits = SLUG_DATE_PATTERN.find(slug)?.groupValues?.get(1) ?: return null
    return try {
        LocalDate.parse(digits, DateTimeFormatter.BASIC_ISO_DATE)
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * Reads the DJs grouped by the floor they play on.
 *
 * Both pages mark the lineup up the same way — a floor block naming the floor and listing its
 * artists — so this is shared between them. An unannounced slot is billed `???`, which names
 * nobody and is dropped; an act billed on two floors of one night is kept once, because
 * `event_artist` is `UNIQUE (event_id, artist_id)` and a second row would fail the whole import.
 */
internal fun parseLineup(root: Element): List<ScrapedArtist> =
    root
        .select(".event-floor, .floor")
        .flatMap { floor ->
            val stage = floor.textAt(".floor-name")?.let(::normalizeFloor)
            floor.select(".floor-artist, .lineup-name").flatMap { slot ->
                splitActs(slot.text().trim()).map { ScrapedArtist(name = it, role = "DJ", stage = stage) }
            }
        }.distinctBy { it.name.lowercase() }

/**
 * Splits one billed slot into the acts it names and drops what is not one.
 *
 * A slot may bill a back-to-back pair (`pschukk b2b Robert We`), decorate the act with its set
 * format (`Ngly [LIVE]`, `The Ghost [All Night Long]`, `Shackleton Live` — stripped so the same DJ
 * is one artist across nights), or credit the collective curating the floor (`hosted by HARD WAX`),
 * which names a host rather than a performer and is already visible in the floor label.
 */
private fun splitActs(slot: String): List<String> =
    slot
        .split(B2B_SEPARATOR)
        .map(::stripSetFormatNote)
        // The venue also bills a format in *front* of the act ("Listening Session: Drexciya —
        // Neptune's Lair"); that names the slot, not the performer.
        .map(::stripFormatLabel)
        .map(::stripReleaseTitle)
        .filter { it.isNotBlank() && !isUnannouncedAct(it) && !HOST_CREDIT.containsMatchIn(it) }

/** Removes a trailing set-format note, unless it is all the slot says — then the name stands. */
private fun stripSetFormatNote(act: String): String = act.replace(SET_FORMAT_NOTE, "").trim().ifBlank { act.trim() }

/**
 * Strips a leading format label, with or without the room in front of it: the Globus programme
 * line reads `Globus Listening Session: The Fear Ratio 'Slinky'` (#1133). The room is dropped only
 * when a label follows it, so an act whose name opens with a room's name is left whole.
 */
private fun stripFormatLabel(act: String): String {
    val withoutRoom = act.replaceFirst(ROOM_PREFIX, "")
    val stripped = stripArtistPrefix(withoutRoom)
    return if (stripped != withoutRoom) stripped else stripArtistPrefix(act)
}

/** Strips a trailing quoted release — `The Fear Ratio 'Slinky'` — unless the quote is all there is. */
private fun stripReleaseTitle(act: String): String = act.replace(RELEASE_TITLE, "").trim().ifBlank { act.trim() }

/** One of the venue's rooms at the head of a slot, as the `Globus …` programme line writes it. */
private val ROOM_PREFIX = Regex("""^(?:aurora\s+bar|tresor|globus)\s+""", RegexOption.IGNORE_CASE)

/** A quoted release title trailing the act that presents it: `'Slinky'`, `"Neptune's Lair"`. */
private val RELEASE_TITLE = Regex("""\s+['"‘’“„]([^'"‘’“”„]+)['"’”“]\s*$""")

/** The back-to-back marker joining two DJs into one slot. */
private val B2B_SEPARATOR = Regex("""\s+b2b\s+""", RegexOption.IGNORE_CASE)

/**
 * A trailing set-format note decorating an act name. The venue writes it both bracketed
 * (`Ngly [LIVE]`) and bare (`Shackleton Live`); the vocabulary is curated and anchored at the end
 * so a stylised name keeps its own words.
 */
private val SET_FORMAT_NOTE =
    Regex(
        """\s*\[\s*(?:live(?:\s+set)?|dj\s+set|all\s+night\s+long|closing|opening)\s*]\s*$""" +
            """|\s+(?:live(?:\s+set)?|all\s+night\s+long)\s*$""",
        RegexOption.IGNORE_CASE
    )

/** A `hosted by …` credit for the collective curating a floor. */
private val HOST_CREDIT = Regex("""\bhosted\s+by\b""", RegexOption.IGNORE_CASE)

/**
 * Reduces a floor label to the room it names.
 *
 * The venue often brands the label with the night hosted there — `Globus x Black Rave Culture`,
 * `Tresor New Faces hosted by Grab The Groove / 23h`, `Globus Stage: Büro Siebzig / 21h` — which
 * would fragment the stage vocabulary into a new value per event. A label opening with one of the
 * three real rooms is reduced to that room; anything else is kept verbatim, so a genuinely new
 * space still comes through.
 */
private fun normalizeFloor(label: String): String = FLOORS.firstOrNull { label.startsWith(it, ignoreCase = true) } ?: label

/** The venue's three rooms, longest first so `Aurora Bar` is not shadowed by a prefix. */
private val FLOORS = listOf("Aurora Bar", "Tresor", "Globus")

/** The venue's placeholder for a slot it has not announced yet, alongside the shared non-artist filter. */
private fun isUnannouncedAct(name: String): Boolean = UNANNOUNCED_SLOT.matches(name) || isNonArtistName(name)

/** `???` — the venue's own "act to be announced" billing. */
private val UNANNOUNCED_SLOT = Regex("""\?{2,}""")
