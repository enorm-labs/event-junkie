@file:Suppress("TooManyFunctions") // Cohesive collection of small, single-purpose artist-name mapping utilities.

package de.norm.events.scraper

import de.norm.events.event.EventType
import java.text.Normalizer

// Artist-name resolution for scraped events: extracts and cleans performer names
// from titles and support lines, and filters out non-artist labels (placeholders,
// role labels, event/segment names). Event-type classification lives in
// EventTypeMapping.kt.

/**
 * Extracts support act names from a subtitle's `"… + <marker>: A & B"` pattern,
 * where `<marker>` is any support-billing label — `Support`, `Opener`, or
 * `Special Guest(s)` (see [SUPPORT_INTRO_PATTERN]).
 *
 * Captures everything after the *first* marker and delegates to [splitSupportActs],
 * so the names are split on commas, `+` and `/`, with `&` / `and` / `und` handled
 * per boundary — a backing-band tail stays attached to its act. A subtitle that
 * stacks two markers (`"Opener: Warwolf + Special Guest: Motorjesus"`) is split on
 * the `+`, then any remaining leading marker on a later act is stripped via
 * [ROLE_LABEL_PREFIX]. Returns an empty list when no support line is present.
 * Shared across venue scrapers (e.g. Privatclub, Astra, Hole 44) whose subtitles
 * follow this convention.
 */
@Suppress("ReturnCount") // Guard clauses for blank subtitle and missing support line are clearer than nesting
fun extractSupportFromSubtitle(subtitle: String?): List<String> {
    if (subtitle.isNullOrBlank()) return emptyList()
    val match = SUPPORT_INTRO_PATTERN.find(subtitle) ?: return emptyList()
    return splitSupportActs(match.groupValues[1])
        .map { it.replaceFirst(ROLE_LABEL_PREFIX, "").trim() }
        .filter { it.isNotBlank() }
}

/**
 * Matches the first support-billing marker in a subtitle — `Support:`, `Opener:`,
 * or `Special Guest(s):` — capturing the acts after it to end of line. A second
 * marker within the captured tail (e.g. the `Special Guest:` in
 * `"Opener: Warwolf + Special Guest: Motorjesus"`) is stripped per-act by
 * [ROLE_LABEL_PREFIX] after [splitSupportActs].
 */
private val SUPPORT_INTRO_PATTERN =
    Regex("""(?:supports?|openers?|special\s+guests?)\s*:\s*(.+)""", RegexOption.IGNORE_CASE)

/**
 * Picks the subtitle line carrying a support-billing marker (see
 * [SUPPORT_INTRO_PATTERN]) from already-split subtitle [lines], or `null` if none.
 *
 * Venues whose subtitle stacks a support line and trailing notes across separate
 * lines (e.g. an "ABGESAGT …" cancellation notice) must isolate the support line
 * before handing it to [extractSupportFromSubtitle]; otherwise the note's text —
 * which `.text()` flattens onto the same line — would be captured as a support
 * act. Pair with [textLinesAt][de.norm.events.scraper.textLinesAt] to obtain the
 * lines. Shared by the Kulturhäuser-platform scrapers (Astra, Lido).
 */
fun supportSubtitleLine(lines: List<String>): String? = lines.firstOrNull { SUPPORT_INTRO_PATTERN.containsMatchIn(it) }

/**
 * Common placeholder names used by venues when the artist has not been
 * announced yet (e.g. "TBA", "TBD", "TBC", "N.N."). These should not be
 * created as artist entries in the database.
 */
private val PLACEHOLDER_NAMES =
    setOf("tba", "tbd", "tbc", "tba.", "tbd.", "tbc.", "nn", "n.n.", "nn.")

/**
 * A "more acts to come" lineup continuation — `+ more`, `& more`, `and more`, `+ more tba`,
 * `more tba`, `und mehr`, `many more`. Venues close an unfinished billing with one of these, and a
 * lineup parser that splits on `+` hands it over as if it were the next act (Kater stored `+ more`
 * and `+ more Tba` as artists).
 *
 * A **bare** "More" deliberately does not match: it is a real band name (the NWOBHM act). The
 * placeholder is only recognised with a lead-in (`+`/`&`/`and`/`und`), a `many`/`viele` quantifier,
 * or a trailing `tba`/`tbc`/`tbd` — each of which marks it as a continuation rather than a name.
 * The quantified form is what a comma-split lineup ends on, once [headlinersFromTitle] has unpacked
 * a `w/` billing into separate acts.
 */
private val MORE_TO_COME_PATTERN =
    Regex(
        """^(?:[+&]|and|und)\s*(?:many\s+|viele\s+)?(?:more|mehr)(?:\s+(?:tba|tbc|tbd))?\.*$""" +
            """|^(?:more|mehr)\s+(?:tba|tbc|tbd)\.*$""" +
            """|^(?:many|viele)\s+(?:more|mehr)\.*$""",
        RegexOption.IGNORE_CASE
    )

/**
 * Checks whether [name] is a placeholder ([PLACEHOLDER_NAMES]) or a lineup continuation
 * ([MORE_TO_COME_PATTERN]) rather than a real artist name. Case-insensitive, and dots are ignored,
 * so "T.B.A." and "tba" are both placeholders.
 */
fun isPlaceholderName(name: String): Boolean {
    val trimmed = name.trim().lowercase()
    val dotFree = trimmed.replace(".", "")
    return dotFree in PLACEHOLDER_NAMES ||
        trimmed in PLACEHOLDER_NAMES ||
        MORE_TO_COME_PATTERN.containsMatchIn(trimmed)
}

/**
 * A leading lineup **role label** ("Support:", "Opener:", "Special Guest(s):",
 * "div. Supports", "feat.", "featuring", "w/"), optionally followed by a colon. Used
 * both to strip the label off an act ("Special Guest: FUCK" → "FUCK") and, when a
 * chunk is nothing but the label, to recognize it as a non-artist via
 * [isNonArtistLabel]. Shared with the SO36 detail scraper, whose support subtitles
 * carry these inline, and with [extractSupportFromSubtitle]'s stacked-marker strip.
 */
val ROLE_LABEL_PREFIX =
    Regex("""^(?:div\.?\s*supports?|special\s+guests?|supports?|openers?|feat\.?|featuring|w/)\s*:?\s*""", RegexOption.IGNORE_CASE)

/**
 * Checks whether [name] is a bare [ROLE_LABEL_PREFIX] label rather than a real act name. These leak
 * in when a venue lists an unnamed slot — a subtitle `"Support: Special Guest"` captures the label
 * as the act. **Matching is exact**, the whole trimmed value being the label, so a real name that
 * merely contains a label word (`"Special Guest Foo"`) is kept. Filtered out alongside
 * [isPlaceholderName] wherever support and headliner names are resolved.
 */
fun isNonArtistLabel(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() && trimmed.replaceFirst(ROLE_LABEL_PREFIX, "").isBlank()
}

private val WHITESPACE = Regex("""\s+""")

/**
 * Curated event-*segment* labels — an aftershow/afterparty/warm-up slot a venue lists in the
 * lineup, which is a part of the night rather than a performer. Any leading qualifier is allowed
 * (`ACID AFTERSHOW`, `TECHNO AFTERPARTY`), and the `aftershow`/`after show`/`after-show` spellings
 * are accepted.
 *
 * **Matched fully anchored** by [isEventSegmentLabel], so it cannot touch a real band whose name
 * contains or resembles a segment word: `"AFTERHOURS"` (a band) and the venue's `"Warm Up im
 * Franken"` are both kept. Curated on purpose — there is no structural signal separating a segment
 * from an act in flat lineup text, so new families are added here as they appear.
 */
private val EVENT_SEGMENT_PATTERN =
    Regex("""(?:\S+ )*after[ -]?show(?: party)?|(?:\S+ )*after[ -]?party|warm[ -]?up""", RegexOption.IGNORE_CASE)

/**
 * Checks whether [name] is an [EVENT_SEGMENT_PATTERN] label rather than a performer. The whole
 * trimmed, whitespace-collapsed value must be the segment phrase.
 */
fun isEventSegmentLabel(name: String): Boolean {
    val normalized = name.trim().replace(WHITESPACE, " ")
    return normalized.isNotEmpty() && EVENT_SEGMENT_PATTERN.matches(normalized)
}

/**
 * Event names that are not performers: a festival ("Shred Fest", "Canarias Calling
 * Festival"), a festival slot/edition ("Grey City Fest Opener", "Sommer Festival
 * Special", "Grobes Fest 2026"), a festival-ticket label ("… Festivalticket"), a
 * `Hoffest` (courtyard festival — `hof` prefix defeats the `\bfest\b` boundary, so it
 * needs its own marker), or a leading "<n> Jahre/Years …" anniversary celebration
 * ("36 Jahre Schokoladen - Hoffest"). The `fest`/`festival` markers are word-anchored
 * and may carry any trailing content (a year, an "Opener"/"Special" slot label, …), so
 * a festival titled with a slot or edition is caught while one-word names ("Infest",
 * "Manifest") and compounds ("Sommerfest" — no standalone `fest` boundary) stay safe.
 * The anniversary marker is anchored to the title start, so a real act carrying an
 * "… - 30 Jahre" tour tail (already trimmed by [stripArtistSuffix] before this runs) is
 * never mistaken for one.
 */
private val NON_ARTIST_EVENT_PATTERN =
    Regex(
        """.*\bfest\b.*|.*\bfestival\b.*|.*\bfestivalticket\b.*""" +
            """|.*\bhoffest\b.*""" +
            """|\d+\.?\s+(?:jahre|jahr|years?)\b.*""",
        RegexOption.IGNORE_CASE
    )

/**
 * Checks whether [name] is a [NON_ARTIST_EVENT_PATTERN] label rather than a performer. The whole
 * whitespace-collapsed value must match.
 */
fun isNonArtistEvent(name: String): Boolean {
    val normalized = name.trim().replace(WHITESPACE, " ")
    return normalized.isNotEmpty() && NON_ARTIST_EVENT_PATTERN.matches(normalized)
}

/**
 * Trailing suffixes that decorate a real act name, stripped by [stripArtistSuffix] to recover the
 * performer. Every case is asserted in `ArtistNameMappingTest`, which is where the examples live.
 *
 * Hyphen-separated tails: a tour name, an anniversary ("<n> Years/Jahre"), a set count ("<n> Sets"),
 * a tour or edition ending in a four-digit year, and the German compound "Releaseshow" that the
 * separate "<format> Release" rules below do not cover. Trailing tails: "Live" or "Live in <city>",
 * a performance-format annotation either parenthesized or a bare "DJ-Set", a German relocation note
 * ("Nachholtermin vom <date>", "Hochverlegung"), a "singt <repertoire>" tribute framing, and an
 * "<Album/EP/…> Release" or "Release Party" promo tag.
 *
 * **The boundaries are what keep real names intact**, and each guards a specific collision:
 * - Hyphen tails need a `<space>-<space>` boundary and a recognised marker, so an undecorated
 *   hyphenated name ("BAD COMPANY LEGACY - Dave Colwell") is left alone.
 * - The year is anchored at the very end, so a stylised number in a band name ("Blink - 182",
 *   "Front 242") is untouched.
 * - "Live" needs a preceding whitespace boundary, so the band named Live is never matched.
 * - The bare "Release" tag needs a format word before it or a "Party"/"Show" tail, so a band named
 *   just "Release" survives.
 * - The parenthetical is keyed on the format word, so an alias in parentheses is kept.
 * - The relocation marker is word-anchored with an optional leading dash, so "… -Nachholtermin" and
 *   "… Nachholtermin" are both caught.
 */
private val ARTIST_SUFFIX_PATTERN =
    Regex(
        """\s+[-–—]\s+(?:\S.*\btour\b|\d+\s+(?:years?|jahre|sets?)\b).*$""" +
            """|\s+[-–—]\s+\S.*\b(?:19|20)\d{2}\s*$""" +
            """|\s+[-–—]\s*release\s?show\s*$""" +
            """|\s+live(?:\s+in\s+\S.*)?$""" +
            """|\s*\((?:dj[\s-]?set|live|acoustic|akustik|unplugged|solo)\)\s*$""" +
            """|\s+dj[\s-]?set$""" +
            """|\s+[-–—(]*\s*(?:nachholtermin|hochverlegung|verschoben)\b.*$""" +
            """|\s+singt\s+\S.*$""" +
            """|\s+(?:album|ep|single|mixtape|record|tape)\s+release(?:\s+(?:party|show|special))?$""" +
            """|\s+release\s+(?:party|show)$""",
        RegexOption.IGNORE_CASE
    )

/**
 * Strips a trailing tour/live/anniversary suffix or performance-format annotation from
 * a scraped act name to recover the performer.
 *
 * Returns the input unchanged when there is no such suffix, or when stripping would leave nothing —
 * so a bare `"Live"` (the band) and a parenthesized alias like `"Sickboyrari (Black Kray)"` survive.
 * [ARTIST_SUFFIX_PATTERN] has the boundaries and what each one protects.
 */
fun stripArtistSuffix(name: String): String {
    val stripped = name.trim().replace(ARTIST_SUFFIX_PATTERN, "").trim()
    return stripShoutedTourTail(stripped.ifBlank { name.trim() })
}

/** Minimum words in a shouted tail before it reads as a tour/album name rather than an act. */
private const val MIN_SHOUTED_TAIL_WORDS = 2

/**
 * The space-padded dash boundary a venue puts between an act and the tour/album name it is
 * touring. All three dashes count: an en dash is the spelling LARK uses
 * (`Greg Mendez – BEAUTY LAND TOUR`), and recognising only the ASCII hyphen let that tail
 * survive into the artist name.
 */
private val DASH_SEPARATOR = Regex("""\s[-–—]\s""")

/**
 * Strips a trailing `" - <SHOUTED TOUR/ALBUM NAME>"` from an act name, the spelling venues
 * use when the tour is named after a record rather than labelled "Tour" — `"Tigercub - NETS
 * TO CATCH THE WIND"` → `"Tigercub"`.
 *
 * Casing is the whole signal, so it is fenced on three sides to keep a genuinely hyphenated
 * name intact:
 *  - the **tail must be fully shouted** (no lowercase letter), so `"BAD COMPANY LEGACY -
 *    Dave Colwell"` and `"Sinem - Hatun"` keep their second half;
 *  - the **head must contain a lowercase letter**, so an all-caps co-bill written with a
 *    hyphen — `"DZ - DEATHRAY"` — is never cut down to its first token;
 *  - the tail must be at least [MIN_SHOUTED_TAIL_WORDS] words, so a one-word alias or
 *    initialism after a hyphen is left alone.
 *
 * Measured against every event title in a 29-venue seed (1240 titles), the rule fires on
 * two — both genuine tour tails — so it is deliberately narrow rather than a general
 * casing heuristic.
 */
private fun stripShoutedTourTail(name: String): String {
    val separator = DASH_SEPARATOR.findAll(name).lastOrNull() ?: return name
    val head = name.substring(0, separator.range.first).trim()
    val tail = name.substring(separator.range.last + 1).trim()
    val isShoutedTail =
        tail.split(WHITESPACE).size >= MIN_SHOUTED_TAIL_WORDS &&
            tail.any { it.isUpperCase() } &&
            tail.none { it.isLowerCase() }
    return if (isShoutedTail && head.any { it.isLowerCase() }) head else name
}

/**
 * Manually curated one-off titles that are not performers but that no structural
 * rule safely catches — a warm-up slot at a specific room, a package-tour name, a
 * recurring themed night, or a venue's own party/DJ series that its structured
 * data lists as the "performer" (Bi Nuu). Entries are lowercase, accent-free, and
 * whitespace-collapsed; before matching, a title is normalized the same way
 * ([isDenylistedNonArtist]) — diacritics stripped, and a trailing edition number
 * or `Berlin` locality ignored — so one entry folds every surface form of a series:
 * the plain `… 5` form (`FEMALE-FRONTED IS NOT A GENRE 5`), the `… N°<n>` form
 * (`Boheme Sauvage N°141`), and the accented, city-suffixed form
 * (`Bohème Sauvage Berlin`). Add exact titles here as they surface.
 */
private val NON_ARTIST_NAMES: Set<String> =
    setOf(
        "warm up im franken",
        "the revival tour",
        "female-fronted is not a genre",
        "music quiz",
        "open mic l. j. fox",
        "feinster hiphop",
        "karrera klub",
        "the swag jam",
        "groovejet",
        "ultra night",
        "boheme sauvage",
        "jazz after dark",
        "future bash reloaded",
        "a dead moon night",
        // Bare event-format words a co-billed title splits off as if they were acts — Säälchen's
        // `10 Jahre "The Big Brassers" – Jubiläumskonzert & Party` yields both of these.
        "party",
        "jubiläumskonzert"
    )

/**
 * A trailing edition number on a recurring event title, ignored when matching
 * [NON_ARTIST_NAMES]. Covers both the plain `… 5` form and the `… N°141` form
 * (optional `n°`/`nº` before the digits), so every edition of a series folds onto
 * one denylist entry.
 */
private val TRAILING_EDITION = Regex("""\s+(?:n[°º]\s*)?\d+$""", RegexOption.IGNORE_CASE)

/**
 * A trailing `Berlin` locality on a recurring-series title (`GrooveJet Berlin`,
 * `Bohème Sauvage Berlin`), ignored when matching [NON_ARTIST_NAMES] so a series
 * folds onto one city-free entry. Matching-only — a real act merely ending in
 * `Berlin` (`Isolation Berlin`) drops the suffix too but is still absent from the
 * denylist, so it is kept.
 */
private val TRAILING_CITY = Regex("""\s+berlin$""")

/** Combining diacritical marks left by NFD normalization; stripped so accents can't defeat a denylist match. */
private val DIACRITICS = Regex("""\p{Mn}+""")

/**
 * Record labels and promoters that sometimes **lead a title with their own name** — a
 * label anniversary or showcase, where every following segment names a part of the
 * programme rather than an act: `"aufnahme + wiedergabe - Fünfzehn Jahre + Zweiter Akt"`
 * is the label's fifteen-year night ("Second Act"), and there is no performer in the title
 * at all.
 *
 * Kept separate from [NON_ARTIST_NAMES] because the two are matched differently and must
 * stay that way. A `NON_ARTIST_NAMES` entry is compared against a *single already-split
 * act*, so a co-bill like `"Karrera Klub + Some Band"` still yields `Some Band`. An entry
 * here suppresses the artists for the **whole title**, which is only correct for a name
 * that cannot also appear as one act among several — so a label that ever bills a real act
 * after its own name (`"<label> presents <act>"`) does **not** belong in this set.
 *
 * Entries are lowercase and whitespace-collapsed; a title matches when it *is* the entry or
 * opens with it followed by a [TITLE_LEAD_SEPARATOR].
 */
private val NON_ARTIST_TITLE_LEADS: Set<String> = setOf("aufnahme + wiedergabe")

/** The punctuation a leading label uses to introduce the event name that follows it. */
private val TITLE_LEAD_SEPARATOR = Regex("""\s*[-–—:|]\s*""")

/**
 * True when [title] is nothing but a [NON_ARTIST_TITLE_LEADS] label, or opens with one
 * followed by a separator — in which case the title names the label's own event and no
 * part of it is a performer.
 */
private fun isLedByNonArtistLabel(title: String): Boolean {
    val normalized = title.trim().replace(WHITESPACE, " ").lowercase()
    return NON_ARTIST_TITLE_LEADS.any { lead ->
        normalized == lead || (normalized.startsWith(lead) && TITLE_LEAD_SEPARATOR.matchesAt(normalized, lead.length))
    }
}

/**
 * A subtitle that is **nothing but** a `"<X> presents"` / `"<X> präsentiert"` billing frame,
 * capturing the presenter. The marker must end the line: a subtitle that names something *after* it
 * ("Zeppelin Entertainment Presents - Joy of Little Things Tour") is billing a tour, not standing on
 * its own as the presenter's credit, and the title beside it is still the act.
 */
private val PRESENTER_CREDIT_PATTERN =
    Regex("""^(.+?)\s+(?:presents|pr(?:ä|ae)sentiert)\s*[-–—:|.]*\s*$""", RegexOption.IGNORE_CASE)

/**
 * The same marker anywhere in a **title**, which disqualifies the whole rule — see
 * [isPresenterOwnEventTitle].
 */
private val PRESENTER_MARKER_IN_TITLE = Regex("""\b(?:presents|pr(?:ä|ae)sentiert)\b""", RegexOption.IGNORE_CASE)

/**
 * Trailing words that say what a presenter *is* rather than what it is called — a label, an agency,
 * a promoter, a legal form. A venue writes the presenter's full business name in the credit line and
 * its short name in the event title (`Corrupted Blood Records` presents `Corrupted Blood Club
 * Show`), so the tail is dropped before the two are compared.
 */
private val PRESENTER_DESCRIPTOR_TAIL =
    Regex(
        """(?:\s+(?:records?|recordings?|rec\.?|music|musik|entertainment|productions?|bookings?""" +
            """|agency|promotions?|events?|concerts?|konzerte|gmbh|ug|ltd\.?|inc\.?|e\.\s?v\.))+\s*$""",
        RegexOption.IGNORE_CASE
    )

/** The separators a venue stacks independent subtitle lines with, split before matching a credit. */
private val SUBTITLE_LINE_SEPARATOR = Regex("""\s*[\n|]\s*""")

/**
 * True when the [subtitle] credits a presenter whose own name **opens the [title]** — so the title
 * names that presenter's event and no part of it is a performer.
 *
 * The structural counterpart of [isLedByNonArtistLabel], which encodes the same idea against a
 * curated list. Here nothing is curated: the venue states the label in one field and repeats it in
 * the other, and the pair is the signal. Huxleys' `Corrupted Blood Club Show`, subtitled `Corrupted
 * Blood Records presents`, is a label showcase — reading it as an act mints an artist playing nowhere else.
 *
 * Three fences keep it off the far more common billing where the presenter names a *real* act:
 *  - the credit must be a **whole subtitle line** ([PRESENTER_CREDIT_PATTERN]) — a subtitle that
 *    continues past the marker is naming the tour or the act, not standing alone as a credit;
 *  - the **title must not carry the marker itself**. `<X> presents: <act>` in a title is the
 *    opposite case, and a frequent one — Gretchen bills 20 shows that way and Zenner's
 *    `Analogue Foundation presents: David August w/ MFO` would lose David August. Such a title
 *    trivially starts with `<X>`, so without this fence the rule would fire on exactly the shows it
 *    must leave alone;
 *  - the title must **continue past** the presenter's name. A title that *is* the name is as likely
 *    to be an act that runs its own label as it is to be a label night, and there is no structural
 *    way to tell; a title that adds to it ("… Club Show") is naming an event.
 *
 * Measured across the seeded database, nine events carry a `presents` / `präsentiert` subtitle and
 * exactly one satisfies all three fences — the Huxleys row this exists for. The other eight are
 * presenter credits for named acts and are untouched.
 */
private fun isPresenterOwnEventTitle(
    title: String,
    subtitle: String?
): Boolean {
    if (subtitle.isNullOrBlank() || PRESENTER_MARKER_IN_TITLE.containsMatchIn(title)) return false
    val normalizedTitle = title.trim().replace(WHITESPACE, " ")
    return subtitle
        .split(SUBTITLE_LINE_SEPARATOR)
        .mapNotNull { line -> PRESENTER_CREDIT_PATTERN.find(line.trim())?.groupValues?.get(1) }
        .any { presenter -> normalizedTitle.startsWithPresenter(presenter) }
}

/**
 * True when this title opens with [presenter] — or with [presenter] stripped of its
 * [descriptor tail][PRESENTER_DESCRIPTOR_TAIL] — and then continues. The match ends on a word
 * boundary, so `Corrupted Bloodline` is not read as opening with `Corrupted Blood`.
 */
private fun String.startsWithPresenter(presenter: String): Boolean {
    val candidates =
        listOf(presenter, presenter.replace(PRESENTER_DESCRIPTOR_TAIL, ""))
            .map { it.trim().replace(WHITESPACE, " ") }
            .filter { it.isNotBlank() }
    return candidates.any { name ->
        length > name.length &&
            startsWith(name, ignoreCase = true) &&
            !this[name.length].isLetterOrDigit()
    }
}

private fun isDenylistedNonArtist(name: String): Boolean =
    Normalizer
        .normalize(name.trim().replace(WHITESPACE, " ").lowercase(), Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .replace(TRAILING_EDITION, "")
        .replace(TRAILING_CITY, "")
        .trim() in NON_ARTIST_NAMES

/**
 * A bare "DJ set" performance-format label, optionally carrying a `/ <origin>` tail
 * — `DJ-Set`, `DJ Set`, `DJ-Set / Berlin`. Venues occasionally push this format/city
 * descriptor into a performer slot (e.g. a Madame Claude detail-page heading), where
 * it must not be minted as an artist. Anchored: the whole trimmed value must be the
 * label (± origin), so a real act whose name merely starts with "DJ Set…" — or any
 * `DJ <handle>` name like `DJ Koze` — is untouched.
 */
private val DJ_SET_LABEL_PATTERN = Regex("""dj[\s-]?set(?:\s*/.*)?""", RegexOption.IGNORE_CASE)

/**
 * Checks whether [name] is a bare `DJ set` format label ("DJ-Set", "DJ-Set / Berlin")
 * rather than a performer. See [DJ_SET_LABEL_PATTERN]; matching is fully anchored so
 * `DJ Koze` / `DJ Set Sail` are kept.
 */
fun isDjSetFormatLabel(name: String): Boolean = DJ_SET_LABEL_PATTERN.matches(name.trim().replace(WHITESPACE, " "))

/**
 * An unannounced-guest support slot: a bare "Guest(s)"/"Gäste" collective, optionally
 * written with a leading "+" and optionally naming the format it fills ("Guest DJs" —
 * Club der Visionäre's spelling for an unbooked slot). Venues (Wild at Heart) list a
 * yet-unnamed support act as "+ Guest" in the lineup. It is a billing placeholder for an
 * unnamed act, not a performer, mirroring the "Guests"/"Gäste"
 * [CONJUNCTION_TAIL_COLLECTIVES] that keep an "X & Guests" boundary joined onto one act.
 * Anchored: the whole trimmed, whitespace-collapsed value must be the collective, so a
 * real act whose name merely contains the word (e.g. "Special Guest DJ Foo") is
 * untouched. Kept tight to the guest forms — a standalone "Friends"/"Band" is a plausible
 * real act name, so it is not listed.
 */
private val GUEST_SLOT_PATTERN = Regex("""\+?\s*(?:guests?|gäste|gaeste)(?:\s+djs?)?""", RegexOption.IGNORE_CASE)

/**
 * Checks whether [name] is a bare unannounced-guest support slot ("+ Guest", "Guests",
 * "Gäste", "Guest DJs") rather than a performer. See [GUEST_SLOT_PATTERN]; matching is
 * fully anchored.
 */
fun isGuestSlotLabel(name: String): Boolean = GUEST_SLOT_PATTERN.matches(name.trim().replace(WHITESPACE, " "))

/**
 * True when [name] must never be stored as an artist: a placeholder ("TBA"), a bare
 * role label ("Special Guest"), an event-segment label ("Acid Aftershow"), an event
 * label ("Shred Fest"), a bare "DJ set" format label ("DJ-Set / Berlin"), an unannounced
 * guest slot ("+ Guest"), or a curated one-off non-artist title ("The Revival Tour"). The
 * single predicate applied wherever scraped headliner/support names are resolved.
 */
fun isNonArtistName(name: String): Boolean =
    isPlaceholderName(name) || isNonArtistLabel(name) || isEventSegmentLabel(name) ||
        isNonArtistEvent(name) || isDjSetFormatLabel(name) || isGuestSlotLabel(name) || isDenylistedNonArtist(name)

/**
 * Well-known single acts whose name legitimately contains a conjunction that
 * [splitHeadlinerTitle] would otherwise read as a co-bill delimiter. Matched
 * case-insensitively against the whole trimmed title, so such a title is kept
 * intact as one headliner. `AC/DC` and similar are already protected by the
 * space-padding requirement and need no entry here — this list is only for the
 * ambiguous `" & "` / `" and "` / `" und "` cases the heuristics below can't
 * catch structurally. Entries are written in `&` form; comparison normalizes
 * the title's conjunctions to `&` first, so an `"… and …"` source spelling of a
 * listed act still matches.
 */
private val KNOWN_SINGLE_ACTS: Set<String> =
    setOf(
        "simon & garfunkel",
        "earth, wind & fire",
        "blood, sweat & tears",
        "mumford & sons",
        "hall & oates",
        "above & beyond",
        "sam & dave",
        "chas & dave",
        "angus & julia stone",
        "matt & kim",
        "blood & sun",
        "pure obsessions & red nights",
        "scala & kolacny brothers"
    )

/**
 * Leading words that mark the right-hand side of a conjunction as a band-name
 * tail rather than a second act, so the boundary is kept joined. Two families,
 * unioned in [CONJUNCTION_TAIL_MARKERS]:
 * - articles/possessives opening a backing band ("X & **the** Ys", "X and **his**
 *   Ys", "X und **die** Ys"); and
 * - collective nouns naming an unnamed supporting cast ("X & **Friends**", "X &
 *   **Guests**", "X & **Gäste**", "X & **Band**"), a billing convention where the
 *   act is "X", not a separate act literally called "Friends" or "Band".
 */
private val CONJUNCTION_TAIL_ARTICLES: Set<String> =
    setOf("the", "his", "her", "their", "los", "las", "die", "der", "das", "el", "la")

private val CONJUNCTION_TAIL_COLLECTIVES: Set<String> = setOf("friends", "guests", "gäste", "freunde", "band")

/** Right-hand-side opener words that keep a conjunction boundary joined — see the two source sets. */
private val CONJUNCTION_TAIL_MARKERS: Set<String> = CONJUNCTION_TAIL_ARTICLES + CONJUNCTION_TAIL_COLLECTIVES

/** Space-padded `/` or `+` — unambiguous co-bill separators once whitespace is required on both sides. */
private val SAFE_TITLE_SEPARATOR = Regex("""\s+[/+]\s+""")

/**
 * Space-padded `+` only — the co-bill separator for venues (Madame Claude) that use
 * `/` *inside* a single act name (`Morimoto / Wong duo`), where splitting on `/` would
 * fragment one act into two. Selected via `splitOnSlash = false`.
 */
private val PLUS_ONLY_TITLE_SEPARATOR = Regex("""\s+\+\s+""")

/**
 * Space-padded conjunction (`&`, `and`, `und`) — split only at boundaries that
 * pass the [splitSegmentOnConjunctions] guardrails. The `and`/`und` word forms
 * are space-padded so they match only the standalone conjunction, never a
 * substring (e.g. the "and" in "Portland"), and case-insensitive for `AND`/`UND`.
 */
private val CONJUNCTION_SEPARATOR = Regex("""\s+(?:&|and|und)\s+""", RegexOption.IGNORE_CASE)

/**
 * True when [name] is a [KNOWN_SINGLE_ACTS] entry — an act whose own name contains a
 * conjunction. The name's conjunctions are normalized to `&` first, so an `"… and …"`
 * source spelling still matches the `&`-spelled denylist.
 *
 * Checked at the **segment** level (not only against a whole title), so a denylisted act
 * is kept whole even when it co-bills — `"BLOOD & SUN + SOCIETY OF THE SILVER CROSS"`
 * splits at the `+` into two acts, not three — and so a support line ("Support: Simon &
 * Garfunkel") is protected the same way.
 */
private fun isKnownSingleAct(name: String): Boolean = name.trim().replace(CONJUNCTION_SEPARATOR, " & ").lowercase() in KNOWN_SINGLE_ACTS

/**
 * Splits a title segment into acts at its conjunctions, deciding **per boundary**
 * so a real co-bill still splits even when another conjunction in the same title
 * is a band-name tail. Conservative: a comma anywhere suppresses splitting (the
 * "Earth, Wind & Fire" member-list pattern), and a boundary is kept joined when
 * its right-hand side opens with a [tail marker][CONJUNCTION_TAIL_MARKERS] — an
 * article/possessive (the "X and the Ys" pattern) or a collective like "Friends" —
 * so `CARL CARLTON & MELANIE WIEGMANN AND THE GREAT BAND` cuts only at the `&`.
 * Each act keeps its original conjunction spelling (no rewrite).
 *
 * Splits **only** on `&`/`and`/`und` — never on `/` or `+` — so a venue that uses
 * `/` inside a single act name (e.g. Madame Claude's `Morimoto / Wong duo`) can
 * pre-split its co-bills on its own separator and hand each segment here to safely
 * break just the conjunctions. Public for that reuse; [splitSupportActs] and
 * [splitHeadlinerTitle] apply it after their own hard-separator split.
 */
@Suppress("ReturnCount") // Guard clauses for the comma and no-cut cases are clearer than nesting
fun splitSegmentOnConjunctions(segment: String): List<String> {
    if (isKnownSingleAct(segment)) return listOf(segment)
    if (segment.contains(',')) return listOf(segment)

    val cuts =
        CONJUNCTION_SEPARATOR
            .findAll(segment)
            .filter { !isInsideBrackets(segment, it.range.first) }
            .filter { match ->
                segment
                    .substring(match.range.last + 1)
                    .trimStart()
                    .substringBefore(' ')
                    .lowercase() !in
                    CONJUNCTION_TAIL_MARKERS
            }.map { it.range }
            .toList()
    return cutAt(segment, cuts)
}

/**
 * True when [index] sits inside a `(…)` or `[…]` group in [text].
 *
 * A separator inside brackets belongs to an act's own parenthetical — a band affiliation
 * (`David J (Bauhaus / Love & Rockets)`), a member list (`Los Refrescos (Dandy Jack & Argenis
 * Brito)`) or a format note — never to a co-bill, so splitting there tears one act into fragments
 * and leaves an unbalanced bracket behind (`Rockets)`). Club der Visionäre's lineup parser has
 * always had this guard; this is the shared title-level counterpart.
 *
 * An unclosed bracket only ever makes the guard *more* conservative (everything after it is treated
 * as inside), which keeps a malformed title whole rather than fragmenting it.
 */
private fun isInsideBrackets(
    text: String,
    index: Int
): Boolean {
    var depth = 0
    for (i in 0 until index) {
        when (text[i]) {
            '(', '[' -> depth++
            ')', ']' -> if (depth > 0) depth--
        }
    }
    return depth > 0
}

/** Splits [text] at the given separator [cuts], dropping the separators themselves. */
private fun cutAt(
    text: String,
    cuts: List<IntRange>
): List<String> {
    if (cuts.isEmpty()) return listOf(text)

    val parts = mutableListOf<String>()
    var start = 0
    for (range in cuts) {
        parts.add(text.substring(start, range.first))
        start = range.last + 1
    }
    parts.add(text.substring(start))
    return parts
}

/** Hard separators that always delimit acts in a support/lineup line: comma, plus, slash. */
private val SUPPORT_HARD_SEPARATOR = Regex("""\s*[,+/]\s*""")

/**
 * Splits a support/lineup line into individual act names.
 *
 * Hard separators (comma, `+`, `/`) always delimit acts; the `&` / `and` / `und`
 * conjunctions are then split **per boundary** via [splitSegmentOnConjunctions],
 * the same guardrails [splitHeadlinerTitle] uses — so a backing-band tail stays
 * attached to its act (`Scott Hepple & The Sun Band` is one act via the article
 * guard, while `High On Fire & Gnome` is two). Blank fragments are dropped;
 * role-label stripping and placeholder filtering are left to the caller.
 */
fun splitSupportActs(text: String): List<String> =
    text
        .split(SUPPORT_HARD_SEPARATOR)
        .flatMap { splitSegmentOnConjunctions(it) }
        .map { it.trim() }
        .filter { it.isNotBlank() }

/**
 * Splits a headliner title into its individual co-billed acts.
 *
 * Titles frequently pack a whole lineup into one string (`TOTAL CHAOS + RUMKICKS + THE DOLLHEADS`,
 * `LAGWAGON / THE VIRGINMARYS`, `BLACK STAR RIDERS & TYKETTO`). This splits on unambiguous,
 * space-padded separators only, so band names that legitimately contain those characters survive:
 * - `" / "` and `" + "` are co-bill separators; the padding is what protects `AC/DC` and
 *   `dance/electronic`.
 * - a `" & "` / `" and "` / `" und "` conjunction is split per boundary via
 *   [splitSegmentOnConjunctions], and never for a title in [KNOWN_SINGLE_ACTS]. The article-tail
 *   guard keeps `X and the Ys` band names whole while still splitting a real co-bill beside them.
 *
 * A title with no recognised separator returns a singleton list of the trimmed title. Placeholder
 * filtering is left to the caller.
 *
 * @param splitOnSlash when false, `/` is *not* a co-bill separator (only ` + ` is) — for venues that
 *   use `/` inside a single act name (Madame Claude's `Morimoto / Wong duo`). Defaults to true.
 *
 * Every case above is asserted in `ArtistNameMappingTest`.
 */
@Suppress("ReturnCount") // Guard clauses for blank and denylisted titles are clearer than nesting
fun splitHeadlinerTitle(
    title: String,
    splitOnSlash: Boolean = true
): List<String> {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return listOf(title)
    // A whole title that is one denylisted act is kept intact before any separator split;
    // co-billed occurrences are protected per segment inside splitSegmentOnConjunctions.
    if (isKnownSingleAct(trimmed)) return listOf(trimmed)

    val separator = if (splitOnSlash) SAFE_TITLE_SEPARATOR else PLUS_ONLY_TITLE_SEPARATOR
    // Bracket-aware: a `/` or `+` inside a parenthetical belongs to that act's own affiliation
    // list, not to a co-bill — see [isInsideBrackets].
    val hardCuts =
        separator
            .findAll(trimmed)
            .filter { !isInsideBrackets(trimmed, it.range.first) }
            .map { it.range }
            .toList()

    val acts =
        cutAt(trimmed, hardCuts)
            .flatMap { splitSegmentOnConjunctions(it) }
            .map { it.trim() }
            .filter { it.isNotBlank() }

    return acts.ifEmpty { listOf(trimmed) }
}

/**
 * A leading recurring-series label ending in an edition marker "#<n>:" —
 * "OFF THE RAILS #5: …", "Off the Rails #4: …". The series name is not a performer;
 * the acts follow the colon. Non-greedy up to the first "#<n>:", and requires a
 * non-blank series name before it, so a plain "9:3" or "H2:O" (no `#`) is untouched.
 */
private val SERIES_PREFIX_PATTERN = Regex("""^.+?#\s*\d+\s*:\s*""")

/**
 * Strips a leading "<series> #<n>:" recurring-series label from a title so the acts
 * billed after the colon are what remains — `"OFF THE RAILS #5: Blake Harley &
 * Superior Motive"` → `"Blake Harley & Superior Motive"`. Returns the input unchanged
 * when there is no such prefix, or when stripping would leave nothing.
 */
fun stripSeriesPrefix(title: String): String {
    val stripped = title.trim().replaceFirst(SERIES_PREFIX_PATTERN, "").trim()
    return stripped.ifBlank { title.trim() }
}

/**
 * A leading "A night with" / "An evening with" / "Ein Abend mit" event-framing phrase,
 * stripped from a title-derived headliner so the billed act remains ("A night with
 * GULVØSS II" → "GULVØSS II"). Title-scoped (see [headlinersFromTitle]): these phrases
 * frame a whole event, never appear inside a lineup entry, and the stored event title is
 * left untouched — only the derived artist name is recovered.
 */
private val ARTIST_FRAMING_PREFIX =
    Regex("""^(?:a\s+night\s+with|an\s+evening\s+with|ein\s+abend\s+mit)\s+""", RegexOption.IGNORE_CASE)

/** Strips a leading [ARTIST_FRAMING_PREFIX], keeping the input when stripping would leave nothing. */
private fun stripFramingPrefix(name: String): String {
    val stripped = name.replaceFirst(ARTIST_FRAMING_PREFIX, "").trim()
    return stripped.ifBlank { name.trim() }
}

/**
 * A leading role label that specifically marks a **support** billing, as opposed to the wider
 * [ROLE_LABEL_PREFIX] (which also covers `feat.` / `w/` guest credits). Used to decide the role of
 * a title segment before its label is stripped.
 */
private val SUPPORT_ROLE_PREFIX =
    Regex("""^(?:div\.?\s*supports?|special\s+guests?|supports?|openers?)\s*:""", RegexOption.IGNORE_CASE)

/**
 * A leading role or event-**format** label the venue puts in front of the act it bills —
 * `Support:`, `Opener:`, `Record Release:`, `Listening Session:`. It names the slot or what the
 * night *is*, not who plays, so it must not become part of the artist name (Admiralspalast stored
 * `Support: A.A. Williams`, Loge `Record Release: Pair`, Tresor
 * `Listening Session: Drexciya - Neptune's Lair`).
 *
 * The colon is **required**, unlike [ROLE_LABEL_PREFIX] which makes it optional. That is safe where
 * that pattern is used — on names already extracted from a `Support:` subtitle, where a bare lead-in
 * is expected — but not against an arbitrary title, where it would maim a real act whose name opens
 * with one of these words (`Support Lesbiens`, `Session Victim`).
 */
private val ARTIST_LABEL_PREFIX =
    Regex(
        """^(?:div\.?\s*supports?|special\s+guests?|supports?|openers?""" +
            """|listening\s+session|record\s+release|record\s+launch|album\s+release|release\s+show)\s*:\s*""",
        RegexOption.IGNORE_CASE
    )

/**
 * Strips a leading role or event-format label ([ARTIST_LABEL_PREFIX]) from a scraped act name, so
 * the performer remains.
 *
 * The title-level counterpart of [stripArtistSuffix], and applied in the same places: to headliners
 * derived from a title, and to a lineup line at a venue that bills a format in front of the act.
 * Returns the input unchanged when there is no such prefix, or when stripping would leave nothing.
 * **The colon is what makes it safe**: without it, the band Support Lesbiens would be stripped to
 * "Lesbiens".
 */
fun stripArtistPrefix(name: String): String {
    val stripped = name.trim().replaceFirst(ARTIST_LABEL_PREFIX, "").trim()
    return stripped.ifBlank { name.trim() }
}

/**
 * Turns an event title into its headliner artist entries: strip a recurring-series
 * "#<n>:" prefix via [stripSeriesPrefix] so the billed acts remain, split co-billed
 * acts via [splitHeadlinerTitle], strip an "A night with …" framing prefix and any
 * tour/live/note suffix ([stripArtistSuffix]) to recover the performer, then drop
 * anything that is not an artist ([isNonArtistName] — placeholders, role labels,
 * segments, festivals). Returned in billing order (title order); the caller appends
 * support acts.
 *
 * @param splitOnSlash forwarded to [splitHeadlinerTitle]: pass false for a venue that
 *   uses `/` inside a single act name (Madame Claude) so the name isn't torn apart.
 * @param unpackWithFrame reads a `"<night> w/ <acts>"` title as its acts only — see
 *   [withFrameActs] for why this is opt-in rather than the default.
 * @param subtitle the event's subtitle, when the caller has one. Used only to recognise a label
 *   showcase — a `"<X> presents"` credit beside a title that opens with `<X>` — via
 *   [isPresenterOwnEventTitle]; omitting it simply skips that check.
 */
@Suppress("ReturnCount") // Three guard clauses (label-led title, presenter credit, w/ frame) read better than nesting
fun headlinersFromTitle(
    title: String,
    splitOnSlash: Boolean = true,
    unpackWithFrame: Boolean = false,
    subtitle: String? = null
): List<ScrapedArtist> {
    // A title led by a label's own name announces that label's event; nothing in it is an act.
    if (isLedByNonArtistLabel(title)) return emptyList()
    // Same conclusion, reached structurally: the subtitle credits the label and the title repeats it.
    if (isPresenterOwnEventTitle(title, subtitle)) return emptyList()
    if (unpackWithFrame) withFrameActs(title)?.let { return it }
    return splitHeadlinerTitle(stripSeriesPrefix(title), splitOnSlash)
        .map { segment ->
            // The role is decided from the *raw* segment, before its label is stripped: a title
            // that bills "… + Support: A.A. Williams" names a support act, not a second headliner.
            val role = if (SUPPORT_ROLE_PREFIX.containsMatchIn(segment.trim())) "SUPPORT" else "HEADLINER"
            stripFramingPrefix(stripArtistPrefix(stripArtistSuffix(segment))) to role
        }.filterNot { (name, _) -> isNonArtistName(name) }
        .map { (name, role) -> ScrapedArtist(name = name, role = role) }
}

/**
 * A `"<night> w/ <acts>"` guest-billing frame, up to and including the marker.
 *
 * The marker is **not** a co-bill separator, which is the tempting reading: across the seed, every
 * title carrying it names a night, a series or a label on the left and the booked acts on the right
 * (`RIPPLES W/ AMINE K`, `House of Rave w/ Maceo Plex, …`). Splitting in place and keeping both
 * halves would mint every one of those night names as a performer.
 *
 * **But it is not universally a frame either, which is why unpacking is opt-in.** Zenner bills
 * `Analogue Foundation presents: David August w/ MFO (live)`, where `w/` joins two collaborating
 * artists and the left side is the headliner — applying the frame there deletes David August.
 * That venue distinguishes the two by anchoring its own frame to a leading duration
 * (`180 min w/ Barker`), a cue no other venue shares. So the shared rule exists, is correct where
 * a venue says it applies, and defaults to off: `w/` means different things at different houses,
 * and no lexical test found here separates them.
 *
 * The tail is split by [splitSupportActs] rather than [splitHeadlinerTitle], because after the marker
 * the text *is* a lineup list, where a comma delimits acts — whereas in a title a comma usually sits
 * inside one name and so suppresses splitting. Every act is billed as a headliner: the frame says
 * who is playing, not in what order.
 *
 * The marker must be preceded by something, so a lineup entry that *opens* with `w/` still goes to
 * [ROLE_LABEL_PREFIX], which strips it as a role label. Returns `null` rather than an empty list
 * when the frame yields nothing usable, so the caller falls back to parsing the whole title.
 */
private val WITH_FRAME_PATTERN = Regex("""^.+?\bw/\s*""", RegexOption.IGNORE_CASE)

/** The acts a [WITH_FRAME_PATTERN] title bills, or `null` when the title carries no such frame. */
private fun withFrameActs(title: String): List<ScrapedArtist>? {
    val frame = WITH_FRAME_PATTERN.find(title) ?: return null
    val acts =
        splitSupportActs(title.substring(frame.range.last + 1))
            .map { stripFramingPrefix(stripArtistPrefix(stripArtistSuffix(it))) }
            .filterNot { isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "HEADLINER") }
    return acts.ifEmpty { null }
}

/**
 * Builds an artist list from a headliner title and support act names.
 *
 * This encapsulates the common "title = headliner + Support:" pattern used by
 * multiple venue scrapers. The presence of [supportNames] confirms the
 * title-as-headliner convention. The title is split into co-billed headliners
 * via [headlinersFromTitle]; placeholder names (e.g. "TBA", "tbc") and bare role
 * labels (e.g. "Special Guest") are filtered out from the output but still serve
 * as the signal that the pattern applies.
 *
 * @param title the event title, assumed to be one or more headliner names.
 * @param supportNames support act names extracted from the listing. If empty, returns
 *   an empty list (cannot confirm the title is an artist name).
 * @param subtitle the event's subtitle, when the caller has one — forwarded to
 *   [headlinersFromTitle] for the label-showcase check only.
 * @return ordered list: headliner(s) first, then support acts by appearance order.
 */
fun buildArtistList(
    title: String,
    supportNames: List<String>,
    subtitle: String? = null
): List<ScrapedArtist> {
    if (supportNames.isEmpty()) return emptyList()

    val supportActs =
        supportNames
            .filterNot { isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "SUPPORT") }

    return headlinersFromTitle(title, subtitle = subtitle) + supportActs
}

/**
 * Builds an artist list using the source's own event-type classification, for venues that expose a
 * clean `kind`/type label (the Kulturhäuser platform — Astra, Lido). Keys off [eventType]:
 * - **Festivals / parties** — the title is the night's name, not an artist; none are extracted.
 * - **Concerts** — the type confirms the title is the headliner, so it is always added, with any
 *   support acts from the subtitle's `"… + Support: A & B"` pattern.
 * - **Unknown / other** — fall back to [buildArtistList], which treats the title as an artist only
 *   when a "Support:" line is present.
 *
 * **The `FESTIVAL`/`PARTY` guard is unconditional on purpose, and narrowing it is the trap.**
 * Running a party title through [headlinersFromTitle] does not recover a missing act, it invents
 * one. Measured across the whole seeded database: of the party and festival titles reaching this
 * function, exactly one hid a recoverable act and about ninety-five would have produced a wrong
 * one. Most would store the night's name verbatim as a 30–60 character "artist"; worse, a tribute
 * night that names the act it covers splits like a co-bill — `Friday I'm in Love – A Tribute to
 * Post-Punk · Dark 80s + Nick Cave` yields `Nick Cave`, an artist row that resolves by slug onto
 * the real Nick Cave, so a visitor browsing him finds a Berlin DJ night in his gig list.
 *
 * The single recoverable case is a classification defect rather than one here: `PARTY_TITLE_KEYWORDS`
 * matches a bare `club` as a substring, so Columbiahalle's `Two Door Cinema Club` is typed `PARTY`.
 * Fixing the keyword fixes the lineup for free. The seam that is genuinely recoverable — the
 * `"<night> curated by / invites / hosted by <act>"` idiom — is #339, and no venue using it routes
 * through this function.
 */
@Suppress("ReturnCount") // Guard clauses for the event-type branches are clearer than nesting
fun buildArtistsForEventType(
    title: String,
    subtitle: String?,
    eventType: String?
): List<ScrapedArtist> {
    if (eventType == EventType.FESTIVAL.name || eventType == EventType.PARTY.name) return emptyList()

    val supportNames = extractSupportFromSubtitle(subtitle)
    if (eventType != EventType.CONCERT.name) return buildArtistList(title, supportNames, subtitle)

    // Concert: the title carries the headliner(s) (co-bills split out), then support acts in listing order.
    val supportActs =
        supportNames
            .filterNot { isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "SUPPORT") }
    // The subtitle goes in as well as being read for support: a `"<X> presents"` credit beside a
    // title that opens with `<X>` means the title is the label's night, not the act.
    return headlinersFromTitle(title, subtitle = subtitle) + supportActs
}
