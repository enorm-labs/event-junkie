package de.norm.events.scraper.clubdervisionaere

import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalTime

/**
 * Pure HTML parser for the Club der Visionäre **homepage**, which is where the venue prints
 * the start time of each night — the programme page that [ClubDerVisionaereProgrammePageScraper]
 * reads prints the date alone.
 *
 * The homepage's `div#next` box repeats the upcoming nights as `div.theID[id^=post-]` blocks
 * carrying the same WordPress post id as their programme block, each with a `div#nextDate`
 * cell of the form `So. 13.9.   11:00 p.m.` — a twelve-hour clock with `a.m.` / `p.m.`. The
 * post id is the join key; the date is not read here because the programme already has it.
 *
 * The `div#today` box is deliberately skipped: it names the current night by title alone, with
 * no post id to join on, and that night carried its time while it stood in `#next` on the
 * previous cycles. The `#next` box showed ten nights when checked; nights further out than
 * that carry no time until they move into it.
 *
 * @see AbstractClubDerVisionaereRoomImporter for the join onto the programme's events.
 */
class ClubDerVisionaereHomePageScraper {
    private val logger = KotlinLogging.logger {}

    /** Reads the `#next` box into a map of post id (without the `post-` prefix) to start time. */
    fun scrape(document: Document): Map<String, LocalTime> {
        val blocks = document.select(NEXT_BLOCK_SELECTOR)
        val times =
            blocks
                .mapNotNull { block -> parseBlock(block) }
                .toMap()
        logger.info { "Read ${times.size} start time(s) from ${blocks.size} block(s) on the Club der Visionäre homepage" }
        return times
    }

    private fun parseBlock(block: Element): Pair<String, LocalTime>? {
        val postId = block.id().removePrefix(POST_ID_PREFIX).takeIf { it.isNotBlank() && it != block.id() } ?: return null
        val time = block.textAt(DATE_SELECTOR)?.let(::parseTwelveHourTime)
        if (time == null) logger.warn { "Club der Visionäre homepage block ${block.id()} has no readable time, skipping" }
        return time?.let { postId to it }
    }

    /** `11:00 p.m.` → 23:00, `12:30 a.m.` → 00:30, `12:00 p.m.` → 12:00; null when the pattern is absent. */
    private fun parseTwelveHourTime(text: String): LocalTime? {
        val match = TWELVE_HOUR_PATTERN.find(text) ?: return null
        val (hourText, minuteText, meridiem) = match.destructured
        val hour = hourText.toInt() % HOURS_ON_THE_DIAL + if (meridiem.equals("p", ignoreCase = true)) HOURS_ON_THE_DIAL else 0
        return runCatching { LocalTime.of(hour, minuteText.toInt()) }.getOrNull()
    }

    companion object {
        /** The upcoming nights inside the homepage's NEXT box, one WordPress post wrapper each. */
        private const val NEXT_BLOCK_SELECTOR = "div#next div.theID[id^=post-]"

        /** The block's date-and-time cell. */
        private const val DATE_SELECTOR = "div#nextDate"

        /** Prefix on the block's `id` attribute (`post-41724`). */
        private const val POST_ID_PREFIX = "post-"

        private const val HOURS_ON_THE_DIAL = 12

        /** A twelve-hour clock as the theme prints it: `11:00 p.m.`, tolerant of `pm` and `PM`. */
        private val TWELVE_HOUR_PATTERN = Regex("""(\d{1,2}):(\d{2})\s*([ap])\.?\s*m\.?""", RegexOption.IGNORE_CASE)
    }
}
