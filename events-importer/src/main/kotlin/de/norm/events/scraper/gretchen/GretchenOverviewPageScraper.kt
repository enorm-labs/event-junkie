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
 * Pure HTML parser for Gretchen Berlin's retro hand-coded single-page listing.
 *
 * Gretchen renders every upcoming event server-side on the homepage (`/`) as a
 * `<div class="gig">` block. Each block carries a `.date` cell (weekday + full
 * `DD.MM.YYYY` date + `Doors:`/`Show:` times), a `.title` with the genre line
 * followed by an `<h2><a href="detail.php?id=…">` headline, one or more
 * `.lineup` performer lists (one per stage, separated by `.box` stage markers),
 * a trailing pricing `<em>` (`*Vorverkauf … * Abendkasse …*`), an image, an
 * optional Resident-Advisor ticket link in `.social`, and a `.promoter` line.
 * Event pages exist at `detail.php?id=…` but every essential field is already on
 * the overview, so this is a single-page scrape (no detail fetching).
 *
 * Two Gretchen-specific quirks drive the design:
 * - **Times use a dot separator** (`Doors: 19.30`, `Show: 20.30`), not the
 *   `HH:mm` the shared [parseTime] expects, so they are rebuilt to `HH:mm`.
 * - **Status is signalled two ways** — a rotated overlay badge (`.rotated`, e.g.
 *   "Abgesagt" / "neuer Ort") and/or a `// CANCELLED` / `// verlegt …` suffix
 *   appended to the headline. Both are folded into a single status decision, and
 *   the suffix is stripped so the stored title stays clean.
 *
 * The stable per-event identity is the `detail.php?id=<n>` query id, used for
 * both the `sourceId` and the `sourceUrl`.
 *
 * @see GretchenWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.gretchen-club.de/">Gretchen Berlin</a>
 */
@Suppress("TooManyFunctions")
class GretchenOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the Gretchen homepage document.
     *
     * Each event is a `<div class="gig">` block in the programme listing.
     *
     * @param baseUrl the URL the document was fetched from, used for resolving relative links.
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
     * Parses a single `<div class="gig">` element into a [ScrapedEvent].
     *
     * Skips (returns `null`) when the two required fields — a headline with a
     * `detail.php?id=…` link and a parseable date — cannot be resolved, so
     * malformed blocks never reach persistence.
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
            artists = parseArtists(gig).ifEmpty { headlinerFromPresentsTitle(title) },
            promoters = parsePromoters(gig)
        )
    }

    /**
     * Best-effort inference of an event's [EventType] from its title, since Gretchen
     * exposes **no machine-readable category** anywhere on the overview (like Bi Nuu
     * and Badehaus). Gretchen is a live-music-leaning club, so the default is
     * `CONCERT`; only the signals that clearly mark a non-concert flip it:
     * 1. `quiz` in the title → `QUIZ`.
     * 2. A word-anchored `festival` → `FESTIVAL` (`AFRO LATIN FESTIVAL`, `Berlin Folk
     *    Festival …`); the `fest` boundary keeps compounds like `WRESTLEFEST` out.
     * 3. A party/club-night keyword ([PARTY_TITLE_KEYWORDS]: `party`, `club night`,
     *    `rave`, `karaoke`, `dj set`) → `PARTY` — catches the DJ nights
     *    (`… CLUB NIGHT`, `BALKANBEATS - Robert Soko DJ-Set`).
     *
     * Only the **title** is scanned, never the genre list: at this venue the genre
     * field carries literal genre tokens (`90's Rave`, `House`) that would mislabel a
     * concert as a party. Like every curated heuristic this is reactive — a party that
     * names itself without a keyword (`AFRO HAUS`, `TESTOSTERONE`) stays `CONCERT`
     * until a signal is added. Consistent with `inferBinuuEventType` and Badehaus.
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
     * Parses the event date from the `.date` cell's `<strong>` element.
     *
     * Gretchen renders a full `DD.MM.YYYY` date with a four-digit year (e.g.
     * "10.07.2026"), so no year inference is needed. Returns `null` when the
     * element is absent or unparseable.
     */
    private fun parseEventDate(gig: Element): LocalDate? = parseGermanDate(gig.textAt(".date strong"))

    /**
     * Parses doors and show times from the `.date` cell.
     *
     * Times are rendered with a dot separator ("Doors: 19.30", "Show: 20.30");
     * each is rebuilt to `HH:mm` and handed to the shared [parseTime]. The show
     * time is optional — many club nights list only a doors time.
     */
    private fun parseTimes(gig: Element): Pair<LocalTime?, LocalTime?> {
        val dateText = gig.textAt(".date").orEmpty()
        val doorsTime = parseTime(DOORS_PATTERN.find(dateText)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" })
        val startTime = parseTime(SHOW_PATTERN.find(dateText)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" })
        return doorsTime to startTime
    }

    /**
     * Resolves the poster image URL from the gig's `.img img` element.
     *
     * The `src` is a page-relative path (e.g. `./bilder_upload/…jpg`) that may
     * contain spaces, so it is percent-encoded and resolved against [baseUrl]
     * defensively — an unresolvable image degrades to `null` rather than failing
     * the whole event.
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
     * Decides the event status from the rotated overlay badge and the headline's
     * status tail.
     *
     * A `.rotated` badge carries "Abgesagt" (cancelled) or "neuer Ort" (new
     * location → relocated); the headline may append "// CANCELLED" / "// verlegt
     * …". Both signals are combined, "neuer Ort" is normalised to the "verlegt"
     * term the shared [parseEventStatus] understands, and the result is mapped to
     * a status name (defaulting to `SCHEDULED`).
     */
    private fun parseStatus(
        gig: Element,
        statusTail: String
    ): String {
        val badge = gig.textAt(".rotated").orEmpty()
        val combined = "$badge $statusTail".replace("neuer ort", "verlegt", ignoreCase = true)
        return parseEventStatus(combined)
    }

    /**
     * Parses presale and box-office prices from the trailing pricing `<em>`.
     *
     * Gretchen prices read `*Vorverkauf 12 €/ 18 €/ 25 € zzgl. Gebühren *
     * Abendkasse 30 €*` — a tiered presale range and a single box-office price
     * (or "tba."). The **lowest** presale tier (the first value) and the
     * box-office value are captured as structured prices, while the full cleaned
     * string is preserved as the note so the tier breakdown is not lost. `.lineup`
     * blocks without a `Vorverkauf`/`Abendkasse` marker (e.g. free-text notes) are
     * ignored.
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
     * Extracts the performer list from the gig's `.lineup` blocks.
     *
     * The lineup is the authoritative artist source (the headline is often a
     * party name, not an act). Each `.lineup` holds `<br>`-separated names, some
     * decorated with a `(country)` / `(label/country)` annotation and `*live*`
     * markers. Two element types are dropped before splitting: the trailing
     * pricing `<em>`, and `<b>` **floor/section headers** (`AFRO FLOOR`, `RECYCLE
     * NEOSIGNAL`, …) which the source renders flush against the first act's name
     * with no `<br>`, so leaving them would fuse header and act into one entry.
     *
     * Each resulting line is then: dropped if it is a non-performer credit or note
     * ([isCreditOrNoteLine] — "Hosted by …", "Live Visuals by …", "Ersatztermin
     * vom …", an instrument-credited member list, a bare "+ guests"); stripped of a
     * leading role prefix ([stripCreditPrefix] — "Support:", "+ Show:", "Opening
     * DJ-Set by") to recover the real name; split on an inline `feat.`/`ft.` credit
     * ([splitFeaturedActs] — "Mop Mop ft. Anthony Joseph" → "Mop Mop" + "Anthony
     * Joseph"); cleaned of its country/`*live*`/`+tag` decorations; stripped of a
     * trailing performance-format suffix ([stripArtistSuffix] — "Acid Arab DJ-Set" →
     * "Acid Arab"); and finally dropped if it is a placeholder/label ([isNonArtistName])
     * or prose ([isProseNote]). The first surviving act is billed as headliner, the
     * rest as support.
     */
    private fun parseArtists(gig: Element): List<ScrapedArtist> {
        val names =
            gig
                .select(".lineup")
                .flatMap { lineup ->
                    // Work on a clone so the shared document is left untouched; drop the trailing
                    // pricing <em> and the <b> floor/section headers, then turn <br> into newlines
                    // to recover the per-name lines.
                    val work = lineup.clone()
                    work.select("em, b").remove()
                    work.select("br").forEach { it.replaceWith(TextNode("\n")) }
                    work.wholeText().split(LINE_BREAK)
                }.map { it.trim() }
                .filterNot { it.isBlank() || isCreditOrNoteLine(it) }
                .map { stripCreditPrefix(it) }
                .flatMap { splitFeaturedActs(it) }
                .map { stripArtistSuffix(cleanArtistName(it)) }
                .filter { it.isNotBlank() && !isNonArtistName(it) && !isProseNote(it) }
                .distinct()

        return names.mapIndexed { index, name ->
            ScrapedArtist(name = name, role = if (index == 0) "HEADLINER" else "SUPPORT")
        }
    }

    /**
     * Fallback headliner recovery for a concert whose `.lineup` carries no performer
     * names — used only when [parseArtists] returns empty.
     *
     * A handful of promoter-booked shows list the act *only* in a `<promoter> presents:`
     * title while the lineup block holds nothing but the pricing `<em>` (e.g.
     * "Landstreicher presents: XAVI - Sorgenfrei Tour 2027", whose lineup is price-only).
     * Here the title's remainder after `presents:` *is* the headliner, so it is recovered:
     * the prefix is dropped ([PRESENTS_PREFIX]), the tour/live tail is stripped
     * ([stripArtistSuffix] → "XAVI"), and the result is billed as the sole HEADLINER.
     *
     * Kept deliberately narrow so it can't mint event/party names as artists (the venue's
     * lineup-not-title rule exists precisely because titles are often party names): it fires
     * only on an empty lineup, requires the `presents:` prefix, and rejects a candidate that
     * still contains a spaced dash ([RESIDUAL_DASH]) — the signature of a compound event
     * label rather than a clean act, so "MIND Enterprises GmbH presents: WRESTLEFEST Europa
     * - Opening Night" is left artist-less while "XAVI" is recovered — as well as the usual
     * [isNonArtistName] / [isProseNote] guards.
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
     * Splits a lineup line on an inline collaboration credit ([COLLAB_SEPARATOR]) into the
     * main act followed by its guest/partner(s) — "MOP MOP ft. ANTHONY JOSEPH" → ["MOP MOP",
     * "ANTHONY JOSEPH"], "NORLYZ feat. MALIKA ALAOUI" → ["NORLYZ", "MALIKA ALAOUI"], "Tikiman
     * w/Scion" → ["Tikiman", "Scion"]. Both halves are billed as separate acts (the caller
     * orders them so the main act keeps the higher billing). A line with no such credit is
     * returned unchanged as a singleton, and a credit that would leave an empty half (a
     * leading "feat." handled earlier by [stripCreditPrefix]) collapses back to the whole
     * line. Unlike the ambiguous single-line `&` co-bill (kept as one act, see class KDoc),
     * `feat.`/`with`/`w/` unambiguously mark a guest, so splitting is safe.
     */
    private fun splitFeaturedActs(line: String): List<String> {
        val parts = line.split(COLLAB_SEPARATOR).map { it.trim() }.filter { it.isNotBlank() }
        return parts.ifEmpty { listOf(line) }
    }

    /**
     * Strips a scraped lineup line down to the performer name.
     *
     * Removes `*…*` markers (e.g. `*live*`), a trailing `(country)` / `(label)`
     * annotation, a trailing `+<tag>` stylisation (`OKVSHO +experience` → `OKVSHO`),
     * and collapses whitespace. The `+<tag>` strip is safe here because Gretchen lists
     * every co-billed act on its own `<br>` line, so a `+` *within* a line is a
     * decoration, never a second act.
     */
    private fun cleanArtistName(line: String): String =
        line
            .replace(STAR_MARKER, " ")
            .replace(TRAILING_PARENS, "")
            .replace(TRAILING_PLUS_TAG, "")
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * True when a lineup line is a non-performer credit or note rather than an act:
     * a "Hosted by …" / "Live Visuals by …" credit, an "Ersatztermin vom …"
     * rescheduled-date note, a "verlegt vom <venue>" relocation note, an
     * instrument-credited member list ("… (Bass), … (Drums)"), or a bare
     * "+ (special) guests" placeholder. These share the trailing `.lineup` with the
     * real acts, so they must be filtered before billing.
     */
    private fun isCreditOrNoteLine(line: String): Boolean =
        DROP_LINE_PATTERN.containsMatchIn(line) ||
            INSTRUMENT_CREDIT_PATTERN.containsMatchIn(line) ||
            SCHEDULE_NOTE_PATTERN.containsMatchIn(line)

    /**
     * Strips a leading role/credit prefix so the billed performer remains — "Support:
     * Steinza" → "Steinza", "+ SHOW: Yenny Stark" → "Yenny Stark", "Opening DJ-Set by
     * Phat Fred" → "Phat Fred". The role/show variants require a colon and "opening
     * DJ-set" the literal "by", so a real name that merely starts with one of these
     * words (e.g. "Showtek") is left intact.
     */
    private fun stripCreditPrefix(line: String): String = line.replaceFirst(CREDIT_PREFIX_PATTERN, "")

    /**
     * Detects a prose note that leaked into the lineup rather than a performer name.
     *
     * Cancelled/relocated events and expo-style listings occasionally drop a full
     * sentence into a `.lineup` span (e.g. "Die Show wird … verlegt.", or a long
     * event blurb). Real Gretchen act lines top out well under ten words, so a
     * candidate of ten or more words is treated as prose and excluded — a simpler
     * and more general guard than keyword matching, and one that can't touch a
     * short act name that merely ends in a dot (e.g. "moe.", "MOMO.").
     */
    private fun isProseNote(name: String): Boolean = name.split(' ').size >= PROSE_WORD_THRESHOLD

    /**
     * Extracts promoter names from the `.promoter` line.
     *
     * The line reads "Veranstalter*in: <name>". A value of "Gretchen" means the
     * venue itself organises the night — that is not an external promoter, so it
     * is dropped. Anything else is kept as a single promoter.
     */
    private fun parsePromoters(gig: Element): List<String> {
        val name =
            gig
                .textAt(".promoter")
                ?.substringAfter(":")
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("Gretchen", ignoreCase = true) }
        return name?.let { listOf(it) } ?: emptyList()
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

        /** A trailing `(country)` / `(label/country)` annotation on a lineup line. */
        private val TRAILING_PARENS = Regex("""\s*\([^)]*\)\s*$""")

        /** A trailing `+<tag>` stylisation on a lineup line (e.g. `OKVSHO +experience`). */
        private val TRAILING_PLUS_TAG = Regex("""\s*\+\s*\S+\s*$""")

        /** A word-anchored `festival` marker in a title → a festival (keeps `WRESTLEFEST` out). */
        private val FESTIVAL_MARKER = Regex("""\bfestival\b""", RegexOption.IGNORE_CASE)

        /** Party/club-night phrases that, in a title, mark a non-concert night. */
        private val PARTY_TITLE_KEYWORDS = listOf("party", "club night", "clubnight", "rave", "karaoke", "dj set", "dj-set")

        /**
         * An inline collaboration credit joining a main act to its guest/partner: a
         * `feat.` / `ft.` / `featuring` guest, or a `with` / `w/` collaborator
         * ("Tikiman w/Scion" → "Tikiman" + "Scion"). The `feat.`/`ft.`/`featuring`/`with`
         * word forms require surrounding whitespace so a real name is untouched; the `w/`
         * shorthand needs no trailing space (the source writes it flush: "…w/Scion").
         */
        private val COLLAB_SEPARATOR =
            Regex("""\s+(?:feat\.?|ft\.?|featuring|with)\s+|\s+w/\s*""", RegexOption.IGNORE_CASE)

        /**
         * A `<promoter> presents:` / `… präsentiert:` prefix on a title, whose *remainder*
         * is the booked act ("Landstreicher presents: XAVI …" → "XAVI …"). Non-greedy from
         * the line start so it stops at the first `presents:` colon.
         */
        private val PRESENTS_PREFIX =
            Regex("""^.*?\bpr(?:e|ä)sent(?:s|ed|iert|ieren)?\s*:\s*""", RegexOption.IGNORE_CASE)

        /** A residual spaced dash in a fallback act name — the signature of a compound event label, not an act. */
        private val RESIDUAL_DASH = Regex("""\s[-–—]\s""")

        /**
         * The recurring "NN Years GRETCHEN:" anniversary-series banner prefixing an act title
         * ("15 Years GRETCHEN: BOTTICELLI BABY"). Anchored on "gretchen" so a different
         * "NN Years <act>" title (e.g. "Recycle: 15 Years FLEXOUT AUDIO") is left intact.
         */
        private val SERIES_PREFIX = Regex("""^\d+\s+years\s+gretchen\s*:\s*""", RegexOption.IGNORE_CASE)

        /** A lineup line of this many words or more is prose (a note/blurb), not a performer name. */
        private const val PROSE_WORD_THRESHOLD = 10

        /**
         * A whole lineup line that is a non-performer credit or note: a "Hosted by …" /
         * "Live Visuals by …" credit, an "Ersatztermin vom …" rescheduled-date note, or a
         * bare "+ (special) guests" placeholder. Anchored at the line start.
         */
        private val DROP_LINE_PATTERN =
            Regex(
                """^(?:hosted\s+by\b|live\s+visuals?\b|ersatztermin\b|\+\s*(?:special\s+)?guests?\s*$)""",
                RegexOption.IGNORE_CASE
            )

        /**
         * A lineup line carrying a scheduling note rather than an act — "verlegt vom Frannz",
         * "verschoben auf den 12.03.".
         *
         * [isProseNote] only catches the venue's *sentence* form ("Die Show wird aus dem Gretchen
         * in das Metropol verlegt." — ten words), so the terse form slipped through and was billed
         * as a support act. Matched anywhere in the line, since the note is written with and
         * without a leading subject; the verbs are German scheduling terms that do not occur in act
         * names, and `ersatztermin` / `nachholtermin` are listed here as well as in
         * [DROP_LINE_PATTERN] so a note prefixed with a subject is caught too.
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
         * A leading role/credit prefix to strip off an act: "Opening DJ-Set by " (literal
         * "by"), or "Support:" / "Special Guest(s):" / "Show:" (colon required), each
         * optionally preceded by a "+". The colon/`by` requirement keeps a real name that
         * merely starts with one of these words untouched.
         */
        private val CREDIT_PREFIX_PATTERN =
            Regex(
                """^\s*\+?\s*(?:opening\s+dj[\s-]?set\s+by\s+|(?:support|special\s+guests?|show)\s*:\s*)""",
                RegexOption.IGNORE_CASE
            )
    }
}
