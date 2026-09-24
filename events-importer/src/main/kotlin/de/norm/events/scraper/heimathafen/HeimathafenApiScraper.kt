package de.norm.events.scraper.heimathafen

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.genretag.isGenreLabel
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure parser for Heimathafen Neukölln's event data from its WordPress REST API
 * (`/wp-json/wp/v2/events`) — the venue's ACF-backed `events` custom post type, the most stable
 * source possible (ADR-007 §"Selector Strategy" priority 1). No HTML is scraped;
 * [HeimathafenWebsiteImporter] fetches the body and this class parses it.
 *
 * **One post holds many dated performances.** `acf.event_performances` is an array — a theatre
 * run reaches 30 entries — each with its own date, ticket link, doors note and status:
 *
 * ```json
 * { "performance_date_time": "11/27/2026 8:00 p.m.",
 * "performance_description": "Einlass ab 19:00 Uhr (Saal)",
 * "performance_ticket": "https://www.eventim.de/event/…",
 * "performance_status": "ausverkauft" }
 * ```
 *
 * so each performance is its own [ScrapedEvent], sharing the post's title, blurb, image, prices
 * and promoter. The identity is `<postId>-<date>-<HHmm>`: the **time is part of the key** because
 * a run legitimately plays twice a day (matinee plus evening), which a date-only key collapses.
 *
 * The API returns the whole archive — 400+ posts, 800+ performances, fewer than a hundred
 * upcoming — and the ACF date is not queryable server-side, so past performances are dropped
 * here rather than minting hundreds of throwaway events per run (Zenner's archive filter, same
 * reason).
 *
 * **No network I/O** — operates on the raw JSON string (Jsoup only to flatten HTML in text
 * fields), so it tests against a saved API snapshot.
 *
 * @see HeimathafenWebsiteImporter for the HTTP fetch orchestrator and pagination.
 * @see <a href="https://heimathafen-neukoelln.de/wp-json/wp/v2/events">Heimathafen events API</a>
 */
@Suppress("LongComment") // 6 of these lines are the payload, which names the ACF fields the parser reads.
class HeimathafenApiScraper(
    /** Clock for the past-performance cut-off, in the venue's time zone; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    /**
     * Parses one page of the WP REST listing response [json].
     *
     * @param json the raw JSON body of a `/wp-json/wp/v2/events` response (a JSON array).
     * @return the page's post count (tells the caller whether another page may follow) and one
     * [ScrapedEvent] per **upcoming** performance; empty if absent, unparseable or not an array.
     */
    fun scrape(json: String): HeimathafenPage {
        val root = parseRoot(json) ?: return HeimathafenPage(postCount = 0, events = emptyList())
        val today = LocalDate.now(clock)

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed posts without aborting the whole import
        val events =
            root.flatMap { post ->
                try {
                    parsePost(post, today)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Heimathafen event post, skipping" }
                    emptyList()
                }
            }
        logger.info { "Parsed ${events.size} upcoming performance(s) from ${root.size()} Heimathafen post(s)" }
        return HeimathafenPage(postCount = root.size(), events = events)
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: any malformed body degrades to "no events", never a failed import
    private fun parseRoot(json: String): JsonNode? =
        try {
            jsonMapper.readTree(json).takeIf { it.isArray }
        } catch (e: Exception) {
            logger.warn(e) { "Heimathafen API response is not parseable JSON" }
            null
        }

    /** Expands one event post into one [ScrapedEvent] per **upcoming** performance. */
    @Suppress("ReturnCount") // Guard clauses for the required id / title are clearer than nesting
    private fun parsePost(
        post: JsonNode,
        today: LocalDate
    ): List<ScrapedEvent> {
        val postId = post.path("id").asInt(0).takeIf { it > 0 } ?: return emptyList()
        val title = htmlToText(post.path("title").path("rendered").asString(""))?.let(::cleanEventTitle) ?: return emptyList()

        val acf = post.path("acf")
        val classes = post.path("class_list").mapNotNull { it.asString(null) }
        val eventType = resolveEventType(classes, title)
        val genre = resolveGenre(classes)
        val subtitle = htmlToText(post.path("excerpt").path("rendered").asString(""))
        val (presale, boxOffice, priceNote) = parsePrices(acf.path("event_prices"))

        val shared =
            SharedPostFields(
                title = title,
                subtitle = subtitle,
                description = htmlToText(post.path("content").path("rendered").asString("")),
                eventType = eventType,
                imageUrl = post.path("featured_images").path("large").asString(null),
                sourceUrl = post.path("link").asString(""),
                pricePresale = presale,
                priceBoxOffice = boxOffice,
                priceNote = priceNote,
                genre = genre,
                artists = castActs(acf.path("event_cast").asString(""), title) ?: buildArtistsForEventType(title, subtitle, eventType),
                promoters = parsePromoters(acf.path("event_organiser").asString(""))
            )

        return acf
            .path("event_performances")
            .mapNotNull { performance -> parsePerformance(performance, postId, shared) }
            .filterNot { it.eventDate.isBefore(today) }
    }

    /**
     * The billed acts from the venue's own `event_cast`, or `null` when it does not name them.
     *
     * Each act opens a paragraph of the cast as a lead `<strong>`, with its personnel under it:
     * `<p><strong>Jamie Baum Quartet</strong><br>Jamie Baum: flutes …</p>`. Reading those leads is
     * what removes the guess from a title like `JAMIE BAUM QUARTET, ANKE HELFRICH TRIO & JUDITH
     * OWEN SEPTETT`, whose comma no rule over the string can safely cut (#1789).
     *
     * **A lead is not always an act.** The same field carries section labels for a theatre bill —
     * `Die Rixdorfer Perlen sind`, `In weiteren Rollen`, `Regie,Text:` — so the cast is trusted
     * only when it corroborates the title: every lead has to appear in it, and there have to be at
     * least two. A cast that names someone the title does not is a credit list, and the title wins.
     */
    private fun castActs(
        castHtml: String,
        title: String
    ): List<ScrapedArtist>? {
        val leads =
            Jsoup
                .parseBodyFragment(castHtml)
                .select("p")
                .mapNotNull { paragraph ->
                    paragraph
                        .selectFirst("> strong")
                        ?.text()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
        val corroborated = leads.size >= MIN_CAST_ACTS && leads.all { title.contains(it, ignoreCase = true) }
        return leads
            .takeIf { corroborated }
            ?.map { ScrapedArtist(name = it, role = "HEADLINER") }
    }

    /** One event from a single `event_performances` entry, or `null` when its date is unparseable. */
    private fun parsePerformance(
        performance: JsonNode,
        postId: Int,
        shared: SharedPostFields
    ): ScrapedEvent? {
        val startedAt = parsePerformanceDateTime(performance.path("performance_date_time").asString("")) ?: return null
        val status = performance.path("performance_status").asString("").lowercase()
        val note = performance.path("performance_description").asString("")

        return ScrapedEvent(
            title = shared.title,
            subtitle = shared.subtitle,
            description = shared.description,
            eventType = shared.eventType,
            eventDate = startedAt.toLocalDate(),
            doorsTime = parseTime(DOORS_PATTERN.find(note)?.groupValues?.get(1)),
            startTime = startedAt.toLocalTime(),
            imageUrl = shared.imageUrl,
            sourceUrl = shared.sourceUrl,
            // The time is part of the key: a run can legitimately play twice on the same day.
            sourceId = "${EventSource.HEIMATHAFEN.sourceIdPrefix}$postId-${startedAt.toLocalDate()}-${startedAt.toLocalTime().format(HH_MM)}",
            ticketUrl = performance.path("performance_ticket").asString(null)?.takeIf { it.startsWith("http") },
            pricePresale = shared.pricePresale,
            priceBoxOffice = shared.priceBoxOffice,
            priceNote = shared.priceNote,
            genre = shared.genre,
            soldOut = status == SOLD_OUT_STATUS,
            free = status == FREE_ENTRY_STATUS,
            status = mapPerformanceStatus(status),
            artists = shared.artists,
            promoters = shared.promoters
        )
    }

    /**
     * Splits the ACF `event_prices` repeater into presale, box-office and a free-form note.
     *
     * The venue prices by audience, not sales channel: beside `VVK` / `Vorverkauf` / `Abendkasse`
     * it lists `Regulär`, `Ermäßigt`, `Studierende`, `Mit Berlin-Pass` and a pay-it-forward
     * `ZUGABE TICKET`. Only the two with columns are mapped — `Abendkasse` to box office, the first
     * general-admission label to presale — and the whole `label: price` list is kept verbatim as
     * the note whenever it carries more than that single general price, so no concession tier is
     * silently lost.
     */
    @Suppress("ReturnCount") // Guard clauses for the absent / empty price repeater are clearer than nesting
    private fun parsePrices(prices: JsonNode): Triple<BigDecimal?, BigDecimal?, String?> {
        if (!prices.isArray || prices.isEmpty) return Triple(null, null, null)
        val entries =
            prices.mapNotNull { entry ->
                val label = entry.path("event_prices_label").asString("").trim()
                val value = entry.path("event_prices_price").asString("").trim()
                if (label.isBlank() && value.isBlank()) null else label to value
            }
        if (entries.isEmpty()) return Triple(null, null, null)

        val general = entries.filterNot { CONCESSION_LABEL.containsMatchIn(it.first) }
        val boxOffice = general.firstOrNull { BOX_OFFICE_LABEL.containsMatchIn(it.first) }
        val presale = general.firstOrNull { PRESALE_LABEL.containsMatchIn(it.first) } ?: general.firstOrNull { it != boxOffice }
        val note = entries.joinToString(" · ") { (label, value) -> listOf(label, value).filter { it.isNotBlank() }.joinToString(": ") }

        return Triple(
            parsePriceValue(presale?.second),
            parsePriceValue(boxOffice?.second),
            note.takeIf { entries.size > 1 || FEE_NOTE.containsMatchIn(it) }
        )
    }

    /**
     * The promoter named by the ACF `event_organiser` blurb, or none. Only the unambiguous
     * `"Eine Veranstaltung von <name>"` phrasing is read. The field is free prose whose other
     * shapes name no promoter: `"Eine Veranstaltung des Heimathafen Neukölln in Kooperation mit …"`
     * credits the venue, `"Heimathafen Neukölln mit Sophia Keßen und Margret Schütz"` names
     * *performers*, some entries are only a sponsor logo. Guessing would mint performers and
     * partners as promoters, so they are skipped.
     */
    private fun parsePromoters(organiserHtml: String): List<String> {
        val text = htmlToText(organiserHtml) ?: return emptyList()
        val name =
            ORGANISER_INTRO
                .find(text)
                ?.let { text.substring(it.range.last + 1) }
                ?.substringBefore('/')
                ?.trim()
                ?.trimEnd(',', '.', '&')
                ?.trim()
        return listOfNotNull(name?.takeIf { it.isNotBlank() })
    }

    /**
     * The genres among the venue's `events_tag-*` slugs, which `class_list` inlines beside the
     * category (#313). The vocabulary mixes genres with formats, rooms and access notes (`konzert`,
     * `saal`, `gebaerdensprache`), so a slug counts only when [isGenreLabel] knows it — 48 of the
     * venue's 562 terms. The slug is lossy once: `rb` for R&B, mapped by hand. Resolving the
     * taxonomy would give the names but not the decision, at a request per import for the same 48 words.
     */
    private fun resolveGenre(classes: List<String>): String? =
        classes
            .filter { it.startsWith(TAG_CLASS_PREFIX) }
            .map { it.removePrefix(TAG_CLASS_PREFIX) }
            .map { slug -> LOSSY_TAG_SLUGS[slug] ?: slug.replace('-', ' ') }
            .filter(::isGenreLabel)
            .distinct()
            .joinToString(", ")
            .ifBlank { null }

    /**
     * Maps the venue's `events_cat-*` taxonomy slug — inlined on every post by `class_list`, so no
     * second request — onto an [EventType], falling back to the title without a category.
     */
    private fun resolveEventType(
        classes: List<String>,
        title: String
    ): String {
        val category = classes.firstOrNull { it.startsWith(CATEGORY_CLASS_PREFIX) }?.removePrefix(CATEGORY_CLASS_PREFIX)
        return mapEventType(category, CATEGORY_TYPES) ?: mapEventType(title) ?: EventType.OTHER.name
    }

    /** Flattens an HTML fragment to plain text, or `null` when it holds none. */
    private fun htmlToText(html: String): String? =
        Jsoup
            .parse(html)
            .text()
            .trim()
            .takeIf { it.isNotBlank() }

    /**
     * One parsed page: how many posts it carried (the caller's signal for another page) and the
     * upcoming performances parsed out of them.
     */
    data class HeimathafenPage(
        val postCount: Int,
        val events: List<ScrapedEvent>
    )

    /** The post-level fields every performance of one event post shares. */
    private data class SharedPostFields(
        val title: String,
        val subtitle: String?,
        val description: String?,
        val eventType: String,
        val imageUrl: String?,
        val sourceUrl: String,
        val pricePresale: BigDecimal?,
        val priceBoxOffice: BigDecimal?,
        val priceNote: String?,
        val genre: String?,
        val artists: List<ScrapedArtist>,
        val promoters: List<String>
    )

    private companion object {
        /** `class_list` prefix carrying the venue's own tag slugs (`events_tag-soul`). */
        const val TAG_CLASS_PREFIX = "events_tag-"

        /** Tag slugs WordPress flattened past recognition; the tag's name, by hand. */
        val LOSSY_TAG_SLUGS = mapOf("rb" to "R&B", "rnb" to "R&B", "singer-songwriterin" to "Singer-Songwriter")

        /** `class_list` prefix carrying the venue's own category slug (`events_cat-musik`). */
        const val CATEGORY_CLASS_PREFIX = "events_cat-"

        /**
         * The venue's category vocabulary. `musik` is its concert programme; `theater`, `amusemang`
         * (its comedy/variety strand) and the three production labels are staged shows; `literatur` is
         * readings. `tacheles` (talks and panels), `jugendclub` and `kiezklub` (community formats) have
         * no closer type than `OTHER`.
         */
        val CATEGORY_TYPES: Map<String, String> =
            mapOf(
                "musik" to EventType.CONCERT.name,
                "theater" to EventType.SHOW.name,
                "amusemang" to EventType.SHOW.name,
                "eigenproduktionen" to EventType.SHOW.name,
                "gastspiel" to EventType.SHOW.name,
                "ko-produktionen" to EventType.SHOW.name,
                "literatur" to EventType.READING.name,
                "tacheles" to EventType.OTHER.name,
                "jugendclub" to EventType.OTHER.name,
                "kiezklub" to EventType.OTHER.name
            )

        /** ACF `performance_status` marking a sold-out performance. */
        const val SOLD_OUT_STATUS = "ausverkauft"

        /** ACF `performance_status` marking a free-entry performance. */
        const val FREE_ENTRY_STATUS = "freier_eintritt"

        /**
         * The `performance_status` values that change the scheduling status. `entfallt` is the venue's
         * spelling of *entfällt* (cancelled), `verlegt` a move to another date or room. Everything else
         * — `default`, `premiere`, `restkarten` (few tickets left), `diskussion`, `custom`, `nktag` —
         * is a badge on a scheduled performance.
         */
        val STATUS_TYPES: Map<String, String> =
            mapOf(
                "entfallt" to EventStatus.CANCELLED.name,
                "verlegt" to EventStatus.RELOCATED.name
            )

        /** The US-format timestamp ACF stores, e.g. `11/27/2026 8:00 p.m.`. */
        val PERFORMANCE_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/uuuu h:mm a", Locale.ENGLISH)

        /** `HH:mm` rendering used inside the `sourceId`, without the colon. */
        val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")

        /**
         * The doors time inside a performance note. Three qualifier spellings — "Einlass ab 19:00 Uhr
         * (Saal)", "Einlass ca. 18:30 Uhr", plain "Einlass 19:00 Uhr (Saal)" — so a short run of
         * non-digits is allowed between label and time rather than enumerating them.
         */
        val DOORS_PATTERN = Regex("""Einlass\b[^\d]{0,10}(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

        /**
         * Price labels naming a **concession** rather than a general-admission tier, excluded from
         * both price columns before anything else is matched: the venue prices by audience and labels
         * its social tiers with the channel too — "Mit Berlin-Pass (Abendkasse)" is €3 and "Für
         * Geflüchtete (Abendkasse)" is €0, either of which would otherwise be *the* box-office price
         * (and the latter would mark the event free). The pay-it-forward "ZUGABE TICKET" is excluded
         * for the mirror-image reason — priced *above* general admission. All survive in the price note.
         */
        val CONCESSION_LABEL =
            Regex(
                """erm(?:ä|ae)(?:ß|ss)igt|studierende|sch(?:ü|ue)ler|berlin-?pass|gefl(?:ü|ue)chtete|zugabe|sozial""",
                RegexOption.IGNORE_CASE
            )

        /** Price labels naming the general box-office (door) tier — anchored, so a concession that merely mentions the channel cannot match. */
        val BOX_OFFICE_LABEL = Regex("""^\s*(?:abendkasse|ak)\b""", RegexOption.IGNORE_CASE)

        /** Price labels naming a general-admission presale tier — anchored for the same reason. */
        val PRESALE_LABEL = Regex("""^\s*(?:vvk|vorverkauf|tickets?|regul(?:ä|ae)r)\b""", RegexOption.IGNORE_CASE)

        /** A booking-fee qualifier, which makes even a single-tier price worth keeping as a note. */
        val FEE_NOTE = Regex("""geb(?:ü|ue)hr""", RegexOption.IGNORE_CASE)

        /** A cast naming one act corroborates nothing the title does not already say. */
        const val MIN_CAST_ACTS = 2

        /** The one organiser phrasing that unambiguously names a promoter. */
        val ORGANISER_INTRO = Regex("""^\s*eine\s+veranstaltung\s+von\s+""", RegexOption.IGNORE_CASE)

        /** Maps a `performance_status` onto an [EventStatus] name, defaulting to `SCHEDULED`. */
        fun mapPerformanceStatus(status: String): String = STATUS_TYPES[status] ?: EventStatus.SCHEDULED.name

        /** Parses ACF's `MM/dd/yyyy h:mm a.m.` timestamp; the dotted meridiem is normalised first. */
        fun parsePerformanceDateTime(raw: String): LocalDateTime? {
            val normalized = raw.trim().replace("a.m.", "AM", ignoreCase = true).replace("p.m.", "PM", ignoreCase = true)
            return try {
                LocalDateTime.parse(normalized, PERFORMANCE_DATE_TIME)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
