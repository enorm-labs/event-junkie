package de.norm.events.scraper.urania

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.refineConcertVenueType
import de.norm.events.scraper.splitSupportActs
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.math.BigDecimal

// Field mapping shared by the Urania's calendar and event pages, which state the same facts in
// the same words.

/**
 * The event type from the venue's format label ("Vortrag", "Podiumsdiskussion").
 *
 * A lecture house: a talk, panel or discussion is what it programmes, and the model's nearest
 * type is [EventType.READING] — its spoken-word bucket. The shared table decides first, catching
 * a concert, film or exhibition, and everything else falls back to the spoken-word type rather
 * than `OTHER`. That fallback is deliberate: the venue invents format names freely
 * (`Schönheitssalon` is a discussion format), and `OTHER` would bury a talk among the
 * genuinely unclassifiable.
 *
 * Workshops and guided walks are the exception ([isParticipationFormat]): their leaders teach or
 * guide rather than speak to an audience, so they are `OTHER` (#1906, #1927), and the calendar
 * drops them. A [title] that names an in-scope format types the row instead: the venue files
 * `Das philosophische Pubquiz` as a `Workshop`, and it is a quiz (#2831).
 *
 * A label that opens with a film (`Film und Podium`, `Filmpremiere und Podium`) or a cinema
 * (`Weihnachtskino für Schulklassen`) is a screening, its panel an add-on (#4759). Only the opening
 * word counts: a `Vortrag mit Filmausschnitten` is still a talk.
 */
internal fun uraniaEventType(
    format: String?,
    title: String = ""
): String =
    mapEventType(format, URANIA_FORMAT_SYNONYMS)
        ?: refineConcertVenueType(EventType.OTHER.name, title).takeIf { isParticipationFormat(format) }
        ?: EventType.SCREENING.name.takeIf { format.orEmpty().trim().contains(SCREENING_FORMAT_PREFIX) }
        ?: EventType.READING.name

/**
 * Whether a format label names a workshop or a guided walk, anywhere in it (`Workshop im
 * Urania-Garten`, `Kiezspaziergang`). EVENT_SCOPE.md §3.2 keeps these out.
 */
internal fun isParticipationFormat(format: String?): Boolean = PARTICIPATION_MARKERS.any { marker -> format.orEmpty().contains(marker, ignoreCase = true) }

/**
 * The speakers of a talk, from the `"A, B, C und D"` billing line, stored as headliners: a
 * panellist is no support act, no DJ and no live set. The host the line names after `bei` is billed as a speaker too
 * (`"Jens Balzer und Victoria Reichelt bei Rabea Weihser"`, #5087). The note goes first, so a `bei` inside it names nobody.
 *
 * Two appended tails are removed rather than stored as people: a space-padded dash introducing
 * a note about the evening (`"Yoshua Yaffa - in englischer Sprache"`), and `"et al."` standing
 * in for unnamed panellists. An `OTHER` event bills nobody: a workshop leader or a walk's guide is not a performer.
 * Nor does a line that names the audience instead ([isSchoolClassAudience]).
 *
 * A moderator hosts the talk and is no speaker, so two credits bill nobody (#1843 keeps organiser credits out too):
 * - a forum's credit, `rbb24-Inforadio-Forum mit <moderator>` ([FORUM_CREDIT], #5555). The speakers come from the rest of the line.
 * - a name the prose credits under `Moderation:` ([moderators], from [uraniaModerators]). The Berlin Brains talks bill
 *   their moderator beside the speaker (`<speaker>, <moderator> et al.`). The speakers on that line stay billed.
 *
 * The calendar has no prose, so it passes no [moderators]. Its billing fills in only when the event page bills no one, so a
 * line that names only the moderator still bills them: an accepted limitation.
 */
internal fun uraniaSpeakers(
    billing: String?,
    eventType: String,
    moderators: List<String> = emptyList()
): List<ScrapedArtist> =
    billing
        ?.takeIf { eventType != EventType.OTHER.name && !isSchoolClassAudience(it) }
        ?.let { splitSupportActs(it) }
        ?.flatMap {
            it
                .substringBefore(NOTE_SEPARATOR)
                .substringBefore(EN_DASH_NOTE_SEPARATOR)
                .split(HOST_SEPARATOR)
        }?.map { it.replace(ET_AL_TAIL, "").trim() }
        ?.filter { it.isNotBlank() && !isNonArtistName(it) && !FORUM_CREDIT.containsMatchIn(it) && !isNamedIn(it, moderators) }
        ?.distinct()
        ?.map { ScrapedArtist(name = it) }
        .orEmpty()

/**
 * The text after each `Moderation:` label in an event page's prose: a paragraph or a `<br>` line that opens with the label.
 * A bare label takes the rest of its paragraph (`Moderation:<br><strong>…</strong>`), or the next paragraph when it stands alone.
 * The word `Moderator` in a biography is no label, so it names nobody.
 */
internal fun uraniaModerators(textBox: Element?): List<String> = textBox?.select("p").orEmpty().flatMap { moderationCredits(it) }

private fun moderationCredits(paragraph: Element): List<String> {
    val lines = paragraph.html().split(LINE_BREAK).map { Jsoup.parseBodyFragment(it).text().trim() }
    return lines.indices
        .filter { MODERATION_LABEL.containsMatchIn(lines[it]) }
        .map { index ->
            lines[index]
                .replace(MODERATION_LABEL, "")
                .ifBlank { lines.drop(index + 1).joinToString(" ") }
                .ifBlank { paragraph.nextElementSibling()?.text().orEmpty() }
        }.filter { it.isNotBlank() }
}

/** Whether one of the [credits] names [name] as a whole name: `Max Muster` is not named in `Max Musterfrau`. */
private fun isNamedIn(
    name: String,
    credits: List<String>
): Boolean {
    if (credits.isEmpty()) return false
    val whole = Regex("""(?<!\p{L})${Regex.escape(name)}(?!\p{L})""", RegexOption.IGNORE_CASE)
    return credits.any { whole.containsMatchIn(it) }
}

/**
 * Whether a billing line names the audience, not a speaker: `Weihnachtskino für Schulklassen` stands where a talk names its
 * speakers (events 13274, 13275). The event page passes it as the format when it has no format label.
 */
internal fun isSchoolClassAudience(line: String?): Boolean = SCHOOL_CLASS_AUDIENCE.containsMatchIn(line.orEmpty())

/**
 * The cheapest published admission, from the `"Eintritt: 8 €, ermäßigt: 5 €, Mitglieder: 3 €"`
 * line. The **first** figure is the full price, the rest concessions, so the full price is
 * stored and the whole line kept as the note. A free event states `"Eintritt frei"` with no
 * figure, yielding no price and left to the shared free-entry detection.
 */
internal fun uraniaPrice(admissionLine: String?): BigDecimal? =
    admissionLine
        ?.substringAfter(ADMISSION_LABEL, admissionLine)
        ?.let { parsePriceValue(it) }

/**
 * Format labels the shared table does not carry: the house's own strands and a film night,
 * labelled in German rather than the shared table's `public viewing`.
 */
private val URANIA_FORMAT_SYNONYMS =
    mapOf(
        "film" to EventType.SCREENING.name,
        "kino" to EventType.SCREENING.name,
        "filmvorführung" to EventType.SCREENING.name,
        "ausstellungseröffnung" to EventType.EXHIBITION.name
    )

/** A format label that opens with a film or a cinema night, or a word that ends in one: `Weihnachtskino für Schulklassen`. */
private val SCREENING_FORMAT_PREFIX = Regex("""^(?:film|\p{L}*kino)""", RegexOption.IGNORE_CASE)

/** An audience of school classes, which the venue names where a talk names its speakers. */
private val SCHOOL_CLASS_AUDIENCE = Regex("""\bf(?:ü|ue)r\s+Schulklassen\b""", RegexOption.IGNORE_CASE)

/**
 * Words that mark a workshop or a guided walk in any of the venue's format labels. `führung` is
 * left out: it is also the tail of `Aufführung` and `Einführung`.
 */
private val PARTICIPATION_MARKERS = listOf("workshop", "spaziergang", "rundgang")

/** The label introducing the admission figures, before which any prose is ignored. */
private const val ADMISSION_LABEL = ":"

/** A space-padded dash, with which the venue appends a note to its billing line. */
private const val NOTE_SEPARATOR = " - "

/** The same, typeset with an en dash. */
private const val EN_DASH_NOTE_SEPARATOR = " – "

/**
 * The word introducing the evening's host. Lower-case and space-padded only, so a name that holds it (`Beier`) or
 * is it (`Bei Dao`) stays whole.
 */
private val HOST_SEPARATOR = Regex("""\s+bei\s+""")

/** The venue's stand-in for unnamed panellists, appended to the last one it did name. */
private val ET_AL_TAIL = Regex("""\s+et\.?\s*al\.?\s*$""", RegexOption.IGNORE_CASE)

/** A forum series' credit, which names its moderator after `mit`: `rbb24-Inforadio-Forum mit <moderator>`. */
private val FORUM_CREDIT = Regex("""\bforum\b.*\smit\s""", RegexOption.IGNORE_CASE)

/** The label of the prose's moderation credit, at the start of a paragraph or a line. */
private val MODERATION_LABEL = Regex("""^Moderation\s*:\s*""", RegexOption.IGNORE_CASE)

/** A `<br>` in a paragraph's HTML, which ends a line of the prose. */
private val LINE_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
