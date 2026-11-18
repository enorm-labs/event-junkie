package de.norm.events.scraper.klunkerkranich

import de.norm.events.scraper.germanDecimalOrNull
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.textAt
import org.jsoup.nodes.Document
import java.math.BigDecimal

/**
 * Pure HTML parser for a Klunkerkranich `/events/<slug>` page.
 *
 * The page restates the listing's fields — title, date, opening hours, thumbnail — and adds
 * three: the blurb, the entry price, the full-size poster. Those three, and the line-up block that
 * stands in for a title without a line-up marker ([scrapeLineup]), are all this class returns,
 * which is why [KlunkerkranichWebsiteImporter] implements `EventImporter` directly
 * rather than extending
 * [AbstractTwoPageWebsiteImporter][de.norm.events.scraper.AbstractTwoPageWebsiteImporter], whose
 * detail scraper is the primary source and must return a whole event (ADR-007 §"Shared Detail
 * Pages" makes the same split for Crack Bellmer and Bar jeder Vernunft).
 *
 * 1. **The blurb is bracketed by boilerplate.** Every page opens with a schedule preamble — the
 * date, then one line per room ("*Wohnzimmer ab 17 Uhr") — repeats the title in styled
 * paragraphs, and closes with a `_*_` rule followed by the price, the standing "Please note …"
 * notice and the venue's URL. [scrapeDescription] cuts at the rule, drops the preamble and
 * title restatements, keeps the prose between.
 * 2. **The sidebar's price is a range, not a ticket price.** "5-9€" is what the door charges
 * by arrival time, as the page spells out, so a range is a
 * [priceNote][de.norm.events.scraper.ScrapedEvent.priceNote] and only a lone figure ("3€") a
 * box-office price. The standing notice after it is identical on every event and dropped.
 *
 * The sidebar also restates date and opening hours in full ("Sa. 08 Aug. 2026", "16:00 —
 * 03:00"). Neither is read: the listing resolved both, its slug carrying the ISO date, so
 * parsing the German rendering again would only add a way to disagree.
 *
 * @see KlunkerkranichOverviewPageScraper for the listing parser, which supplies every other field.
 * @see <a href="https://klunkerkranich.org/events/2026-08-09-la-maison-x-klunkerkranich/">A Klunkerkranich event page</a>
 */
class KlunkerkranichDetailPageScraper {
    /**
     * The night's blurb, or `null`.
     *
     * Paragraphs from the `_*_` rule on are the standing footer, cut off. Of the rest, four kinds
     * are dropped: the leading run of "… ab NN Uhr" schedule lines (only the leading run, so a
     * blurb mentioning a set time keeps it), the `*`-prefixed room labels used in that preamble
     * and as headings over each floor's lineup, any paragraph merely restating part of the
     * [title], and any with no word at all — the rule itself and the empty paragraphs around the
     * venue's SoundCloud embeds.
     */
    fun scrapeDescription(
        document: Document,
        title: String
    ): String? =
        document
            .select(CONTENT_PARAGRAPH)
            .map { it.text().trim() }
            .takeWhile { it != FOOTER_RULE }
            .dropWhile { SCHEDULE_LINE.containsMatchIn(it) }
            .filter { paragraph ->
                !paragraph.startsWith(ROOM_LABEL_MARKER) && paragraph.any(Char::isLetterOrDigit) && paragraph !in title
            }.joinToString("\n")
            .takeIf { it.isNotBlank() }

    /**
     * The sidebar's "Wieviel" entry charge as (box-office price, price note).
     *
     * A lone figure ("3€") is a box-office price. A range ("5-9€") is not — the venue charges by
     * arrival time — and is kept verbatim as the note. Text with no figure ("Eintritt frei")
     * becomes the note as-is, which lets [detectFree][de.norm.events.scraper.detectFree] recognise
     * a free night. Either half may be `null`; both are without a "Wieviel" block.
     */
    fun scrapePrice(document: Document): Pair<BigDecimal?, String?> {
        val text = document.textAt(PRICE_BLOCK) ?: return null to null
        val match = ENTRY_PRICE.find(text)
        return when {
            match == null -> null to text
            match.groupValues[RANGE_TOP_GROUP].isBlank() -> parseAmount(match.groupValues[AMOUNT_GROUP]) to null
            else -> null to match.value.trim()
        }
    }

    /** The full-size poster the header image links to, falling back to the rendered crop. */
    fun scrapeImageUrl(document: Document): String? = document.hrefAt("$HEADER_MEDIA a[href]") ?: document.imgSrcAt("$HEADER_MEDIA img")

    /**
     * The billing lines of the page's line-up block, for a night whose title names no acts (#5264).
     *
     * The block runs from the last `*<Room>:` heading to the `_*_` rule, one billing per line. Four kinds
     * of line are no billing and are dropped: a web address, a credit ("hosted by Gieza Poke", "presented
     * by …"), the venue's `no clouds` note for an act without a SoundCloud, and the night's own name. A
     * trailing `[affiliation]` is cut ("JaMoll [Sumpfkrüge]"). The `*live` marker and a `DJ Set by` label
     * stay for the caller, which reads the role from them as it does on the listing's title.
     *
     * Empty when the page has no room heading followed by a block.
     */
    fun scrapeLineup(
        document: Document,
        title: String
    ): List<String> {
        val paragraphs =
            document
                .select(CONTENT_PARAGRAPH)
                .map { it.text().trim() }
                .takeWhile { it != FOOTER_RULE }
        val heading = paragraphs.indexOfLast { it.startsWith(ROOM_LABEL_MARKER) && it.endsWith(':') }
        if (heading < 0) return emptyList()
        return paragraphs
            .drop(heading + 1)
            .map { it.replace(AFFILIATION, "").trim() }
            .filter { line ->
                line.any(Char::isLetterOrDigit) &&
                    !WEB_ADDRESS.containsMatchIn(line) &&
                    !CREDIT.containsMatchIn(line) &&
                    !line.equals(NO_SOUNDCLOUD, ignoreCase = true) &&
                    !namesTheNight(line, title)
            }
    }

    /** A euro amount with a German comma or a dot decimal separator. */
    private fun parseAmount(text: String): BigDecimal? = text.germanDecimalOrNull()

    /**
     * Whether [line] restates the night: it is the whole [title], or it opens with a part of the title and goes on
     * ("HEAP Album Release Show" after `HEAP Album Release`, "HIVE TEA DRAG SHOW *live" after `HIVE TEA`). A line
     * equal to a part is an act the title also names — `Django Lassi` in `Django Lassi – HEAP Album Release` — and stays.
     * A part counts only with two words or more, so a one-word part like "Gypsy" never swallows an act that starts with it.
     */
    private fun namesTheNight(
        line: String,
        title: String
    ): Boolean {
        val folded = fold(LIVE_MARKER.replace(line, ""))
        if (folded == fold(title)) return true
        return title
            .split(TITLE_PART_SEPARATOR)
            .filter { part -> part.trim().split(WHITESPACE).size >= MIN_NIGHT_NAME_WORDS }
            .map(::fold)
            .any { part -> part.isNotEmpty() && folded.length > part.length && folded.startsWith(part) }
    }

    /** Lowercase letters and digits only, so the line's spacing and `*` decoration do not hide a restated title. */
    private fun fold(text: String): String = text.lowercase().filter(Char::isLetterOrDigit)

    private companion object {
        /** The event page's prose block; the sidebar beside it holds date and price. */
        const val CONTENT_PARAGRAPH = ".c-article__content p"

        /** The page header's image, linked to the unresized original. */
        const val HEADER_MEDIA = ".o-page-header__media"

        /** The sidebar's entry-charge paragraph, found by its "Wieviel" heading, not by position. */
        const val PRICE_BLOCK = ".c-article__sidebar h2:containsOwn(Wieviel) + p"

        /** The typographic rule closing every blurb, before the standing footer. */
        const val FOOTER_RULE = "_*_"

        /** The `*` prefixed to each room — "*Wohnzimmer", "*Ostflügel", "*Club „Hinter den Alpen“". */
        const val ROOM_LABEL_MARKER = "*"

        /** A trailing `[affiliation]` on a line-up line — "JaMoll [Sumpfkrüge]". */
        val AFFILIATION = Regex("""\s*\[[^\]]*]\s*$""")

        /** A web address in the line-up block — the act's Instagram or Discogs page. */
        val WEB_ADDRESS = Regex("""https?://|www\.""", RegexOption.IGNORE_CASE)

        /** A credit opening a line-up line: a host or presenter is a person or a crew, not an act on the bill. */
        val CREDIT = Regex("""^(?:hosted|presented)\s+by\b|^präsentiert\s+von\b""", RegexOption.IGNORE_CASE)

        /** The venue's note under an act without a SoundCloud profile to embed. */
        const val NO_SOUNDCLOUD = "no clouds"

        /** The venue's `*live` marker, ignored when a line is compared with the title. */
        val LIVE_MARKER = Regex("""\*\s*live\b""", RegexOption.IGNORE_CASE)

        /** The separators between a title's parts — "Django Lassi – HEAP Album Release | DJ Seraphim", "HIVE TEA * DRAG SHOWS". */
        val TITLE_PART_SEPARATOR = Regex("""[|–—*]""")

        /** The fewest words a title part needs before a line that opens with it counts as the night's name. */
        const val MIN_NIGHT_NAME_WORDS = 2

        val WHITESPACE = Regex("""\s+""")

        /** A schedule-preamble line stating when the night or a room opens — "Samstag 8. August 2026, ab 16 Uhr". */
        val SCHEDULE_LINE = Regex("""\bab\s+\d{1,2}(?:[:.]\d{2})?\s*Uhr\b""", RegexOption.IGNORE_CASE)

        /**
         * The entry charge opening the "Wieviel" text: an amount, optionally the upper end of a range
         * ("3€", "5-9€"). Anchored at the start so the "Please note …" notice after it stays out of
         * value and note.
         */
        val ENTRY_PRICE = Regex("""^(\d+(?:[.,]\d{1,2})?)(?:\s*[-–—]\s*(\d+(?:[.,]\d{1,2})?))?\s*€""")

        /** [ENTRY_PRICE]'s amount group. */
        const val AMOUNT_GROUP = 1

        /** [ENTRY_PRICE]'s upper-range group — blank for a single price. */
        const val RANGE_TOP_GROUP = 2
    }
}
