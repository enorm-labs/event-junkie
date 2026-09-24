package de.norm.events.scraper.urania

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.splitSupportActs
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
 * A workshop is the one exception, matched anywhere in the label (`Workshop im Urania-Garten`,
 * `Film und Workshop für Schulklassen`): its leaders teach rather than speak to an audience, so
 * it is `OTHER` (#1906).
 */
fun uraniaEventType(format: String?): String =
    mapEventType(format, URANIA_FORMAT_SYNONYMS)
        ?: EventType.OTHER.name.takeIf { format.orEmpty().contains(WORKSHOP_MARKER, ignoreCase = true) }
        ?: EventType.READING.name

/**
 * The speakers of a talk, from the `"A, B, C und D"` billing line, stored as headliners: the
 * roles are `HEADLINER`, `SUPPORT` and `DJ`, and a panellist is neither of the latter.
 *
 * Two appended tails are removed rather than stored as people: a space-padded dash introducing
 * a note about the evening (`"Yoshua Yaffa - in englischer Sprache"`), and `"et al."` standing
 * in for unnamed panellists. An `OTHER` event bills nobody: a workshop leader is not a performer.
 */
fun uraniaSpeakers(
    billing: String?,
    eventType: String
): List<ScrapedArtist> =
    billing
        ?.takeIf { eventType != EventType.OTHER.name }
        ?.let { splitSupportActs(it) }
        ?.map {
            it
                .substringBefore(NOTE_SEPARATOR)
                .substringBefore(EN_DASH_NOTE_SEPARATOR)
                .replace(ET_AL_TAIL, "")
                .trim()
        }?.filter { it.isNotBlank() && !isNonArtistName(it) }
        ?.distinct()
        ?.map { ScrapedArtist(name = it) }
        .orEmpty()

/**
 * The cheapest published admission, from the `"Eintritt: 8 €, ermäßigt: 5 €, Mitglieder: 3 €"`
 * line. The **first** figure is the full price, the rest concessions, so the full price is
 * stored and the whole line kept as the note. A free event states `"Eintritt frei"` with no
 * figure, yielding no price and left to the shared free-entry detection.
 */
fun uraniaPrice(admissionLine: String?): BigDecimal? =
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

/** The word that marks a workshop in any of the venue's format labels. */
private const val WORKSHOP_MARKER = "workshop"

/** The label introducing the admission figures, before which any prose is ignored. */
private const val ADMISSION_LABEL = ":"

/** A space-padded dash, with which the venue appends a note to its billing line. */
private const val NOTE_SEPARATOR = " - "

/** The same, typeset with an en dash. */
private const val EN_DASH_NOTE_SEPARATOR = " – "

/** The venue's stand-in for unnamed panellists, appended to the last one it did name. */
private val ET_AL_TAIL = Regex("""\s+et\.?\s*al\.?\s*$""", RegexOption.IGNORE_CASE)
