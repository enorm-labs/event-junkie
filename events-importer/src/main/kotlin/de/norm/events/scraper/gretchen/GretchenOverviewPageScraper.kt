package de.norm.events.scraper.gretchen

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.gretchen.GretchenOverviewPageScraper.Companion.COLLAB_SEPARATOR
import de.norm.events.scraper.gretchen.GretchenOverviewPageScraper.Companion.DROP_LINE_PATTERN
import de.norm.events.scraper.gretchen.GretchenOverviewPageScraper.Companion.PARTY_TITLE_KEYWORDS
import de.norm.events.scraper.gretchen.GretchenOverviewPageScraper.Companion.PRESENTS_PREFIX
import de.norm.events.scraper.gretchen.GretchenOverviewPageScraper.Companion.RESIDUAL_DASH
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.splitBackToBack
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for Gretchen Berlin's retro hand-coded single-page listing: every upcoming
 * event on the homepage (`/`) as a `<div class="gig">` with a `.date` cell (weekday, full
 * `DD.MM.YYYY` date, `Doors:`/`Show:` times), a `.title` with the genre line then an `<h2><a
 * href="detail.php?id=…">` headline, one or more `.lineup` lists (one per stage, separated by
 * `.box` markers), a trailing pricing `<em>` (`*Vorverkauf … * Abendkasse …*`), an image, an
 * optional Resident Advisor link in `.social`, and a `.promoter` line. Every essential field is
 * on the overview, so no detail fetch.
 *
 * Two quirks: times use a dot separator (`Doors: 19.30`, `Show: 20.30`), rebuilt to `HH:mm`
 * for [parseTime]; status is signalled two ways, a rotated overlay badge (`.rotated`, "Abgesagt"
 * / "neuer Ort") and/or a `// CANCELLED` / `// verlegt …` headline suffix, folded into one
 * decision with the suffix stripped. The identity is the `detail.php?id=<n>` query id.
 *
 * @see GretchenWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.gretchen-club.de/">Gretchen Berlin</a>
 */
@Suppress("TooManyFunctions")
class GretchenOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all `<div class="gig">` blocks from the homepage.
     *
     * @param baseUrl the URL the document was fetched from, for resolving relative links.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val gigs = document.select("div.gig")
        logger.info { "Found ${gigs.size} gig block(s) on Gretchen homepage" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import
        return gigs.mapNotNull { gig ->
            try {
                parseGig(gig, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Gretchen gig block, skipping" }
                null
            }
        }
    }

    /**
     * Parses one `<div class="gig">` into a [ScrapedEvent], or `null` when the headline with its
     * `detail.php?id=…` link or a parseable date is missing.
     */
    @Suppress("ReturnCount") // Guard clauses for the required title/id/date fields are clearer than nesting
    private fun parseGig(
        gig: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val headline = gig.selectFirst(".title h2 a")
        val rawTitle = headline?.text()?.trim()
        if (rawTitle.isNullOrBlank()) {
            logger.warn { "Gretchen gig has no title, skipping" }
            return null
        }

        val eventId = headline.attr("href").substringAfter("id=", "").takeIf { it.isNotBlank() }
        if (eventId == null) {
            logger.warn { "Gretchen event '$rawTitle' has no detail id, skipping" }
            return null
        }

        val eventDate = parseEventDate(gig)
        if (eventDate == null) {
            logger.warn { "Could not parse date for Gretchen event '$rawTitle', skipping" }
            return null
        }

        // The headline may carry a "// CANCELLED" / "// verlegt …" status tail — split it off.
        val titleWithoutStatus = rawTitle.substringBefore("//").trim().ifBlank { rawTitle }
        // Drop the recurring "NN Years GRETCHEN:" anniversary-series banner so the act name remains.
        val title = titleWithoutStatus.replaceFirst(SERIES_PREFIX, "").trim().ifBlank { titleWithoutStatus }
        val statusTail = rawTitle.substringAfter("//", "")
        val status = parseStatus(gig, statusTail)
        val statusNote = "${gig.textAt(".rotated").orEmpty()} $statusTail".trim()

        val (doorsTime, startTime) = parseTimes(gig)
        val (pricePresale, priceBoxOffice, priceNote) = parsePrices(gig)

        return ScrapedEvent(
            title = title,
            eventType = inferEventType(title),
            eventDate = eventDate,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl = parseImageUrl(gig, baseUrl),
            sourceUrl = resolveUrl(baseUrl, "detail.php?id=$eventId"),
            sourceId = "${EventSource.GRETCHEN.sourceIdPrefix}$eventId",
            // The "TICKETS" button is a JS popup, so the Resident-Advisor link is the best external ticket URL.
            ticketUrl = gig.hrefAt(".social a[href*=\"ra.co\"]"),
            genre =
                gig
                    .selectFirst(".title")
                    ?.ownText()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() },
            pricePresale = pricePresale,
            priceBoxOffice = priceBoxOffice,
            priceNote = priceNote,
            status = status,
            statusNote = statusNote,
            artists = parseArtists(gig).ifEmpty { headlinerFromPresentsTitle(title) },
            promoters = parsePromoters(gig)
        )
    }

    /**
     * Best-effort [EventType] from the title, since Gretchen exposes no machine-readable category
     * (like Bi Nuu and Badehaus). A live-music-leaning club, so `CONCERT` by default; only clear
     * signals flip it: `quiz` to `QUIZ`; a word-anchored `festival` to `FESTIVAL` (`AFRO LATIN
     * FESTIVAL`, `Berlin Folk Festival …`, the boundary keeping `WRESTLEFEST` out); a
     * [PARTY_TITLE_KEYWORDS] hit (`party`, `club night`, `rave`, `karaoke`, `dj set`) to `PARTY`
     * (`… CLUB NIGHT`, `BALKANBEATS - Robert Soko DJ-Set`). Only the title, never the genre list,
     * which carries literal tokens (`90's Rave`, `House`) that would mislabel a concert. Reactive:
     * `AFRO HAUS` and `TESTOSTERONE` stay `CONCERT` until a signal is added.
     */
    private fun inferEventType(title: String): String {
        val haystack = title.lowercase()
        return when {
            "quiz" in haystack -> EventType.QUIZ.name
            FESTIVAL_MARKER.containsMatchIn(haystack) -> EventType.FESTIVAL.name
            PARTY_TITLE_KEYWORDS.any { it in haystack } -> EventType.PARTY.name
            else -> EventType.CONCERT.name
        }
    }

    /**
     * Parses the date from the `.date` cell's `<strong>`, a full `DD.MM.YYYY` ("10.07.2026").
     */
    private fun parseEventDate(gig: Element): LocalDate? = parseGermanDate(gig.textAt(".date strong"))

    /**
     * Parses doors and show times from the `.date` cell, dot-separated ("Doors: 19.30", "Show:
     * 20.30"), rebuilt to `HH:mm`. The show time is optional.
     */
    private fun parseTimes(gig: Element): Pair<LocalTime?, LocalTime?> {
        val dateText = gig.textAt(".date").orEmpty()
        val doorsTime = parseTime(DOORS_PATTERN.find(dateText)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" })
        val startTime = parseTime(SHOW_PATTERN.find(dateText)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" })
        return doorsTime to startTime
    }

    /**
     * The poster URL from `.img img`: a page-relative `src` (`./bilder_upload/…jpg`) that may
     * contain spaces, percent-encoded and resolved against [baseUrl]; unresolvable degrades to `null`.
     */
    private fun parseImageUrl(
        gig: Element,
        baseUrl: String
    ): String? {
        val src =
            gig
                .selectFirst("span.img img")
                ?.attr("src")
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { resolveUrl(baseUrl, src.replace(" ", "%20")) }.getOrNull()
    }

    /**
     * The status from the `.rotated` badge ("Abgesagt", "neuer Ort") and the headline's "//
     * CANCELLED" / "// verlegt …" tail, combined and mapped by [parseEventStatus], which reads
     * "neuer Ort" itself.
     */
    private fun parseStatus(
        gig: Element,
        statusTail: String
    ): String {
        val badge = gig.textAt(".rotated").orEmpty()
        return parseEventStatus("$badge $statusTail")
    }

    /**
     * Prices from the trailing `<em>`: `*Vorverkauf 12 €/ 18 €/ 25 € zzgl. Gebühren * Abendkasse 30
     * €*`, a tiered presale range and one box-office price (or "tba."). The lowest presale tier and
     * the box-office value become structured prices; the cleaned string is kept as the note so the
     * tiers are not lost. `.lineup` blocks without a `Vorverkauf`/`Abendkasse` marker are ignored.
     */
    private fun parsePrices(gig: Element): Triple<BigDecimal?, BigDecimal?, String?> {
        val priceText =
            gig
                .select(".lineup em")
                .map { it.text().trim() }
                .firstOrNull { PRICE_MARKER_PATTERN.containsMatchIn(it) }
                ?: return Triple(null, null, null)

        val presaleSegment = priceText.substringAfter("Vorverkauf", "").substringBefore("Abendkasse")
        val boxOfficeSegment = priceText.substringAfter("Abendkasse", "")

        val pricePresale = parsePriceValue(presaleSegment)
        val priceBoxOffice = parsePriceValue(boxOfficeSegment)
        val priceNote = priceText.trim('*', ' ').trim().takeIf { it.isNotBlank() }

        return Triple(pricePresale, priceBoxOffice, priceNote)
    }

    /**
     * The performer list from the `.lineup` blocks, the authoritative artist source (the headline is
     * often a party name). Each holds `<br>`-separated names, some with a `(country)` /
     * `(label/country)` annotation and `*live*` markers. Dropped before splitting: the pricing
     * `<em>`, and `<b>` floor headers (`AFRO FLOOR`, `RECYCLE NEOSIGNAL`), which the source renders
     * flush against the first act with no `<br>`. Each line is then dropped if a credit or note
     * ([isCreditOrNoteLine]: "Hosted by …", "Live Visuals by …", "Ersatztermin vom …", an
     * instrument-credited member list, a bare "+ guests"); stripped of a role prefix
     * ([stripCreditPrefix]: "Support:", "+ Show:", "Opening DJ-Set by"); split on `feat.`/`ft.`
     * ([splitFeaturedActs]: "Mop Mop ft. Anthony Joseph"); cleaned of country/`*live*`/`+tag`
     * decorations; stripped of a format suffix ([stripArtistSuffix]: "Acid Arab DJ-Set"); and
     * dropped if [isNonArtistName] or [isProseNote]. First survivor is headliner, the rest support.
     */
    private fun parseArtists(gig: Element): List<ScrapedArtist> {
        val names =
            gig
                .select(".lineup")
                .flatMap { lineup ->
                    // On a clone: the pricing <em> and the <b> floor headers become line breaks, not nothing,
                    // because an <em> can hold the only <br> between two lines (#1755).
                    val work = lineup.clone()
                    work.select("em, b").forEach { it.replaceWith(TextNode("\n")) }
                    work.select("br").forEach { it.replaceWith(TextNode("\n")) }
                    work.wholeText().split(LINE_BREAK)
                }.map { it.trim() }
                .filterNot { it.isBlank() || isCreditOrNoteLine(it) }
                .map { stripCreditPrefix(it) }
                .flatMap { splitFeaturedActs(it) }
                .flatMap(::splitBackToBack)
                .map { stripArtistSuffix(cleanArtistName(it)) }
                .filter { it.isNotBlank() && !isNonArtistName(it) && !isProseNote(it) }
                .distinct()

        return names.mapIndexed { index, name ->
            ScrapedArtist(name = name, role = if (index == 0) "HEADLINER" else "SUPPORT")
        }
    }

    /**
     * Fallback headliner for a concert whose `.lineup` carries no names: a few promoter-booked shows
     * list the act only in a `<promoter> presents:` title with a price-only lineup ("Landstreicher
     * presents: XAVI - Sorgenfrei Tour 2027"). The prefix is dropped ([PRESENTS_PREFIX]), the tail
     * stripped ([stripArtistSuffix], "XAVI"), the result billed sole HEADLINER. Narrow so it cannot
     * mint party names: only on an empty lineup, only with the prefix, and a candidate still
     * containing a spaced dash ([RESIDUAL_DASH]) is rejected, so "MIND Enterprises GmbH presents:
     * WRESTLEFEST Europa - Opening Night" stays artist-less; plus [isNonArtistName] /
     * [isProseNote].
     */
    private fun headlinerFromPresentsTitle(title: String): List<ScrapedArtist> {
        if (!PRESENTS_PREFIX.containsMatchIn(title)) return emptyList()
        val act = stripArtistSuffix(cleanArtistName(title.replaceFirst(PRESENTS_PREFIX, "")))
        val isCleanAct =
            act.isNotBlank() &&
                !RESIDUAL_DASH.containsMatchIn(act) &&
                !isNonArtistName(act) &&
                !isProseNote(act)
        return if (isCleanAct) listOf(ScrapedArtist(name = act, role = "HEADLINER")) else emptyList()
    }

    /**
     * Splits a line on an inline collaboration credit ([COLLAB_SEPARATOR]): "MOP MOP ft. ANTHONY
     * JOSEPH" to ["MOP MOP", "ANTHONY JOSEPH"], "Tikiman w/Scion" to ["Tikiman", "Scion"]. No
     * credit returns a singleton; a credit that would leave an empty half collapses back. Unlike the
     * ambiguous single-line `&` co-bill, `feat.`/`with`/`w/` unambiguously mark a guest.
     */
    private fun splitFeaturedActs(line: String): List<String> {
        val parts = line.split(COLLAB_SEPARATOR).map { it.trim() }.filter { it.isNotBlank() }
        return parts.ifEmpty { listOf(line) }
    }

    /**
     * Strips a lineup line to the name: a leading `+ ` (`+ Mittelmeer Monologe`, an act added
     * after the main billing), `*…*` markers and an unclosed `*live`, a trailing `(country)` /
     * `(label)`, a trailing `+<tag>` (`OKVSHO +experience` to `OKVSHO`), whitespace collapsed. The
     * `+<tag>` strip is safe because every co-billed act has its own `<br>` line.
     */
    private fun cleanArtistName(line: String): String =
        line
            .replaceFirst(LEADING_PLUS, "")
            .replace(STAR_MARKER, " ")
            .replace(LONE_TRAILING_STAR, "")
            .replace(TRAILING_PARENS, "")
            .replace(TRAILING_PLUS_TAG, "")
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * True when a line is a credit or note rather than an act: "Hosted by …" / "Live Visuals by …",
     * "Ersatztermin vom …", "verlegt vom <venue>", an instrument-credited member list ("… (Bass), …
     * (Drums)"), a bare "+ (special) guests".
     */
    private fun isCreditOrNoteLine(line: String): Boolean =
        DROP_LINE_PATTERN.containsMatchIn(line) ||
            INSTRUMENT_CREDIT_PATTERN.containsMatchIn(line) ||
            SCHEDULE_NOTE_PATTERN.containsMatchIn(line)

    /**
     * Strips a leading role prefix: "Support: Steinza" to "Steinza", "+ SHOW: Yenny Stark" to "Yenny
     * Stark", "Opening DJ-Set by Phat Fred" to "Phat Fred". The role variants require a colon and
     * "opening DJ-set" the literal "by", so "Showtek" is intact.
     */
    private fun stripCreditPrefix(line: String): String = line.replaceFirst(CREDIT_PREFIX_PATTERN, "")

    /**
     * A prose note that leaked into the lineup ("Die Show wird … verlegt.", a long blurb). Real act
     * lines top out well under ten words, so ten or more is prose, a guard that cannot touch a short
     * name ending in a dot ("moe.", "MOMO.").
     */
    private fun isProseNote(name: String): Boolean = name.split(' ').size >= PROSE_WORD_THRESHOLD

    /**
     * Promoters from the `.promoter` line, "Veranstalter*in: <name>". "Gretchen" is the venue
     * itself, dropped.
     */
    private fun parsePromoters(gig: Element): List<String> {
        val name =
            gig
                .textAt(".promoter")
                ?.substringAfter(":")
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("Gretchen", ignoreCase = true) }
        return name?.let { listOf(it) }.orEmpty()
    }

    companion object {
        /** Captures the "Doors: HH.MM" time from the `.date` cell (dot separator). */
        private val DOORS_PATTERN = Regex("""Doors:\s*(\d{1,2})\.(\d{2})""")

        /** Captures the "Show: HH.MM" time from the `.date` cell (dot separator). */
        private val SHOW_PATTERN = Regex("""Show:\s*(\d{1,2})\.(\d{2})""")

        /** A pricing `<em>` is the one carrying a "Vorverkauf" or "Abendkasse" marker. */
        private val PRICE_MARKER_PATTERN = Regex("""Vorverkauf|Abendkasse""", RegexOption.IGNORE_CASE)

        /** Splits a `.lineup` block's whole text into its `<br>`-delimited lines. */
        private val LINE_BREAK = Regex("""\r?\n""")

        /** A `*…*` decoration on a lineup line (e.g. `*live*`). */
        private val STAR_MARKER = Regex("""\*[^*]*\*""")

        /** A `*…*` decoration the page never closed, at the end of a line: `Tontom (BR) *live` (#1761). */
        private val LONE_TRAILING_STAR = Regex("""\s*\*[^*\s]+\s*$""")

        /** A trailing `(country)` / `(label/country)` annotation on a lineup line. */
        private val TRAILING_PARENS = Regex("""\s*\([^)]*\)\s*$""")

        /** A leading `+` and its space; a `+` fused to a word (`+44`) is part of the name. */
        private val LEADING_PLUS = Regex("""^\s*\+\s+""")

        /** A trailing `+<tag>` stylisation on a lineup line (e.g. `OKVSHO +experience`). */
        private val TRAILING_PLUS_TAG = Regex("""\s*\+\s*\S+\s*$""")

        /** A word-anchored `festival` marker in a title → a festival (keeps `WRESTLEFEST` out). */
        private val FESTIVAL_MARKER = Regex("""\bfestival\b""", RegexOption.IGNORE_CASE)

        /** Party/club-night phrases that, in a title, mark a non-concert night. */
        private val PARTY_TITLE_KEYWORDS = listOf("party", "club night", "clubnight", "rave", "karaoke", "dj set", "dj-set")

        /**
         * An inline collaboration credit: `feat.` / `ft.` / `featuring` / `with` need surrounding
         * whitespace; `w/` needs no trailing space, since the source writes "…w/Scion".
         */
        private val COLLAB_SEPARATOR =
            Regex("""\s+(?:feat\.?|ft\.?|featuring|with)\s+|\s+w/\s*""", RegexOption.IGNORE_CASE)

        /**
         * A `<promoter> presents:` / `… präsentiert:` prefix whose remainder is the act ("Landstreicher
         * presents: XAVI …"). Non-greedy, stopping at the first `presents:` colon.
         */
        private val PRESENTS_PREFIX =
            Regex("""^.*?\bpr(?:e|ä)sent(?:s|ed|iert|ieren)?\s*:\s*""", RegexOption.IGNORE_CASE)

        /** A residual spaced dash in a fallback act name — the signature of a compound event label, not an act. */
        private val RESIDUAL_DASH = Regex("""\s[-–—]\s""")

        /**
         * The "NN Years GRETCHEN:" anniversary banner ("15 Years GRETCHEN: BOTTICELLI BABY"), anchored
         * on "gretchen" so "Recycle: 15 Years FLEXOUT AUDIO" is intact.
         */
        private val SERIES_PREFIX = Regex("""^\d+\s+years\s+gretchen\s*:\s*""", RegexOption.IGNORE_CASE)

        /** A lineup line of this many words or more is prose (a note/blurb), not a performer name. */
        private const val PROSE_WORD_THRESHOLD = 10

        /**
         * A whole line that is a credit or note: "Hosted by …" / "Live Visuals by …", "Ersatztermin vom
         * …", a bare "+ (special) guests". Anchored at the line start.
         */
        private val DROP_LINE_PATTERN =
            Regex(
                """^(?:hosted\s+by\b|live\s+visuals?\b|ersatztermin\b|\+\s*(?:special\s+)?guests?\s*$)""",
                RegexOption.IGNORE_CASE
            )

        /**
         * A line carrying a scheduling note rather than an act ("verlegt vom Frannz", "verschoben auf
         * den 12.03."): [isProseNote] catches only the ten-word sentence form, so the terse form was
         * billed as support. Matched anywhere, since the note is written with and without a subject;
         * `ersatztermin` / `nachholtermin` are here as well as in [DROP_LINE_PATTERN].
         */
        private val SCHEDULE_NOTE_PATTERN =
            Regex(
                """\b(?:verlegt|verschoben|ersatztermin|nachholtermin)\b""",
                RegexOption.IGNORE_CASE
            )

        /** A parenthesised instrument credit ("… (Bass), … (Drums)"), the signature of a band-member list line. */
        private val INSTRUMENT_CREDIT_PATTERN =
            Regex(
                """\((?:bass|keys|drums|guitar|gitarre|vocals?|voc|perc(?:ussion)?|synth|sax|trumpet|trompete|piano)\)""",
                RegexOption.IGNORE_CASE
            )

        /**
         * A leading role prefix: "Opening DJ-Set by " (literal "by"), or "Support:" / "Special
         * Guest(s):" / "Show:" (colon required), each optionally preceded by "+".
         */
        private val CREDIT_PREFIX_PATTERN =
            Regex(
                """^\s*\+?\s*(?:opening\s+dj[\s-]?set\s+by\s+|(?:support|special\s+guests?|show)\s*:\s*)""",
                RegexOption.IGNORE_CASE
            )
    }
}
