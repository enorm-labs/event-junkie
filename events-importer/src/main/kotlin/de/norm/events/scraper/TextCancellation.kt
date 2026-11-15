package de.norm.events.scraper

import de.norm.events.event.EventStatus
import java.time.LocalDate

/**
 * The status a cancellation notice in an event's own text states: `CANCELLED`, `POSTPONED`, or `null`. Columbiahalle wrote
 * "dass die 2. Show von Bleachers am 10.11.2026 nicht stattfinden wird" into a card's teaser, not its sticker (#5084).
 * Applied at [ScrapedEvent.toEventEntity] to the description of a row whose badge and title state nothing, and by a scraper
 * to a line that is not the description.
 *
 * Only the first sentence with a [STRONG_CANCELLATION] phrase decides. That sentence must not name a date other than
 * [eventDate], or a year other than its year: the twin night the notice moves the tickets to carries the same text, and a
 * biography can say an earlier tour "musste abgesagt werden". A sentence that also names a new date ("fällt leider aus, wird
 * nachgeholt") is `POSTPONED`.
 */
fun parseTextCancellation(
    text: String?,
    eventDate: LocalDate
): String? {
    val sentence = text?.let(::cancellationSentences)?.firstOrNull() ?: return null
    val dates = NOTICE_DATE.findAll(sentence).toList()
    val otherDate =
        dates.any { match ->
            val (day, month, year) = match.destructured
            day.toInt() != eventDate.dayOfMonth || month.toInt() != eventDate.monthValue || !eventDate.year.toString().endsWith(year)
        }
    val otherYear = NOTICE_YEAR.findAll(sentence.replace(NOTICE_DATE, " ")).any { it.value.toInt() != eventDate.year }
    return when {
        otherDate || otherYear -> null
        NEW_DATE.containsMatchIn(sentence) -> EventStatus.POSTPONED.name
        else -> EventStatus.CANCELLED.name
    }
}

/** The sentences of [text] that carry a [STRONG_CANCELLATION] phrase, in order. A period after a digit ends no sentence: `am 10.11. nicht`. */
internal fun cancellationSentences(text: String): List<String> =
    text
        .split(SENTENCE_END)
        .map { it.trim() }
        .filter { STRONG_CANCELLATION.containsMatchIn(it) }

/**
 * The phrases that state a cancellation as a sentence does, German and English. A bare `abgesagt` or `cancel` is not one:
 * a biography says a band cancelled an earlier tour, and a badge is [parseEventStatus]'s.
 */
private val STRONG_CANCELLATION =
    Regex(
        """\b(?:wird|kann)\s+(?:\p{L}+\s+){0,2}nicht\s+stattfinden|\bnicht\s+stattfinden\s+(?:kann|wird)""" +
            """|\bfindet\s+(?:\p{L}+\s+){0,2}nicht\s+statt\b|\babgesagt\s+werden\b|\b(?:ist|wurde)\s+(?:leider\s+)?abgesagt\b""" +
            """|\bf(?:ä|ae)llt\s+leider\s+aus\b|\b(?:has\s+been|is)\s+cancell?ed\b|\bwill\s+not\s+take\s+place\b|\bhas\s+been\s+called\s+off\b""",
        RegexOption.IGNORE_CASE
    )

/** A new date beside the notice: the show is moved, not dropped. */
private val NEW_DATE =
    Regex("""\bwird\s+nachgeholt|\bneue[nmr]?\s+termin|nachholtermin|ersatztermin|verschoben|postponed|rescheduled""", RegexOption.IGNORE_CASE)

private val SENTENCE_END = Regex("""(?<!\d)[.!?]+\s+|\n+""")

/** `10.11.`, `10.11.2026`, `9.11.26`. */
private val NOTICE_DATE = Regex("""(?<!\d)(\d{1,2})\.(\d{1,2})\.(\d{4}|\d{2})?(?!\d)""")

private val NOTICE_YEAR = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
