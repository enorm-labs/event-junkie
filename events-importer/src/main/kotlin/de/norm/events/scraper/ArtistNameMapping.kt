@file:Suppress("TooManyFunctions") // Cohesive collection of small, single-purpose artist-name mapping utilities.

package de.norm.events.scraper

import de.norm.events.event.EventType
import de.norm.events.slug.SlugGenerator
import java.text.Normalizer

// Artist-name resolution for scraped events: performer names from titles and support lines,
// minus non-artist labels (placeholders, role labels, event and segment names). Event-type
// classification lives in EventTypeMapping.kt.

/**
 * Extracts support act names from a subtitle's `"… + <marker>: A & B"` pattern, where
 * `<marker>` is `Support`, `Opener` or `Special Guest(s)` ([SUPPORT_INTRO_PATTERN]). Captures
 * everything after the first marker and hands it to [splitSupportActs]; a subtitle stacking two
 * markers (`"Opener: Warwolf + Special Guest: Motorjesus"`) splits on the `+`, and a leading
 * marker left on a later act is stripped via [ROLE_LABEL_PREFIX]. Empty when no support line.
 * Shared by Privatclub, Astra, Hole 44 and others.
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
 * The first support-billing marker in a subtitle, capturing the acts after it to end of line. A
 * second marker in the tail is stripped per act by [ROLE_LABEL_PREFIX] after [splitSupportActs].
 */
private val SUPPORT_INTRO_PATTERN =
    Regex("""(?:supports?|openers?|special\s+guests?)\s*:\s*(.+)""", RegexOption.IGNORE_CASE)

/**
 * The subtitle line carrying a support-billing marker from already-split [lines], or `null`. A
 * venue that stacks a support line and an "ABGESAGT …" note across lines must isolate the
 * support line first, or `.text()` flattens the note into a support act. Pair with
 * [textLinesAt][de.norm.events.scraper.textLinesAt]. Shared by Astra and Lido.
 */
fun supportSubtitleLine(lines: List<String>): String? = lines.firstOrNull { SUPPORT_INTRO_PATTERN.containsMatchIn(it) }

/**
 * Placeholder names for an unannounced artist ("TBA", "TBD", "TBC", "N.N."), never artist rows.
 */
private val PLACEHOLDER_NAMES =
    setOf("tba", "tbd", "tbc", "tba.", "tbd.", "tbc.", "nn", "n.n.", "nn.")

/**
 * A "more acts to come" continuation: `+ more`, `& more`, `and more`, `+ more tba`, `more tba`,
 * `und mehr`, `many more`. A parser splitting on `+` hands it over as the next act (Kater stored
 * `+ more` and `+ more Tba`). A bare "More" does not match: it is the NWOBHM band. Only a
 * lead-in (`+`/`&`/`and`/`und`), a `many`/`viele` quantifier or a trailing `tba`/`tbc`/`tbd`
 * marks a continuation; the quantified form is what a comma-split lineup ends on once
 * [headlinersFromTitle] has unpacked a `w/` billing.
 */
private val MORE_TO_COME_PATTERN =
    Regex(
        """^(?:[+&]|and|und)\s*(?:many\s+|viele\s+)?(?:more|mehr)(?:\s+(?:tba|tbc|tbd))?\.*$""" +
            """|^(?:more|mehr)\s+(?:tba|tbc|tbd)\.*$""" +
            """|^(?:many|viele)\s+(?:more|mehr)\.*$""",
        RegexOption.IGNORE_CASE
    )

/**
 * Whether [name] is a placeholder ([PLACEHOLDER_NAMES]) or a continuation
 * ([MORE_TO_COME_PATTERN]). Case-insensitive, dots ignored: "T.B.A." and "tba".
 */
fun isPlaceholderName(name: String): Boolean {
    val trimmed = name.trim().lowercase()
    val dotFree = trimmed.replace(".", "")
    return dotFree in PLACEHOLDER_NAMES ||
        trimmed in PLACEHOLDER_NAMES ||
        MORE_TO_COME_PATTERN.containsMatchIn(trimmed)
}

/**
 * A leading role label ("Support:", "Opener:", "Special Guest(s):", "div. Supports", "feat.",
 * "featuring", "w/"), colon optional. Strips the label off an act ("Special Guest: FUCK" to
 * "FUCK") and, when a chunk is nothing but the label, marks it a non-artist via
 * [isNonArtistLabel]. Shared with the SO36 detail scraper and [extractSupportFromSubtitle].
 */
val ROLE_LABEL_PREFIX =
    Regex("""^(?:div\.?\s*supports?|special\s+guests?|supports?|openers?|feat\.?|featuring|w/)\s*:?\s*""", RegexOption.IGNORE_CASE)

/**
 * Whether [name] is a bare [ROLE_LABEL_PREFIX] label: a subtitle `"Support: Special Guest"`
 * captures the label as the act. Matching is exact, so `"Special Guest Foo"` is kept.
 */
fun isNonArtistLabel(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() && trimmed.replaceFirst(ROLE_LABEL_PREFIX, "").isBlank()
}

/**
 * Curated event-segment labels, an aftershow/afterparty/warm-up slot listed in the lineup, with
 * any leading qualifier (`ACID AFTERSHOW`, `TECHNO AFTERPARTY`) and the `aftershow`/`after
 * show`/`after-show` spellings. Matched fully anchored by [isEventSegmentLabel], so the band
 * `"AFTERHOURS"` and the venue's `"Warm Up im Franken"` are kept. Curated because flat lineup
 * text carries no structural signal; add families as they appear.
 */
private val EVENT_SEGMENT_PATTERN =
    Regex("""(?:\S+ )*after[ -]?show(?: party)?|(?:\S+ )*after[ -]?party|warm[ -]?up""", RegexOption.IGNORE_CASE)

/**
 * Whether the whole trimmed, whitespace-collapsed [name] is an [EVENT_SEGMENT_PATTERN] label.
 */
fun isEventSegmentLabel(name: String): Boolean {
    val normalized = name.trim().replace(WHITESPACE, " ")
    return normalized.isNotEmpty() && EVENT_SEGMENT_PATTERN.matches(normalized)
}

/**
 * Event names that are not performers: a festival ("Shred Fest", "Canarias Calling Festival"),
 * a slot or edition ("Grey City Fest Opener", "Sommer Festival Special", "Grobes Fest 2026"),
 * "… Festivalticket", a `Hoffest` (its `hof` prefix defeats the `\bfest\b` boundary), or a
 * leading "<n> Jahre/Years …" anniversary ("36 Jahre Schokoladen - Hoffest"). The
 * `fest`/`festival` markers are word-anchored with any trailing content, so "Infest",
 * "Manifest" and "Sommerfest" stay safe. The anniversary marker is anchored to the title start,
 * so an "… - 30 Jahre" tour tail (already trimmed by [stripArtistSuffix]) never matches.
 */
private val NON_ARTIST_EVENT_PATTERN =
    Regex(
        """.*\bfest\b.*|.*\bfestival\b.*|.*\bfestivalticket\b.*""" +
            """|.*\bhoffest\b.*""" +
            """|\d+\.?\s+(?:jahre|jahr|years?)\b.*""",
        RegexOption.IGNORE_CASE
    )

/**
 * Whether the whole whitespace-collapsed [name] matches [NON_ARTIST_EVENT_PATTERN].
 */
fun isNonArtistEvent(name: String): Boolean {
    val normalized = name.trim().replace(WHITESPACE, " ")
    return normalized.isNotEmpty() && NON_ARTIST_EVENT_PATTERN.matches(normalized)
}

/**
 * Trailing suffixes that decorate a real act name, stripped by [stripArtistSuffix]; every case
 * is asserted in `ArtistNameMappingTest`. Hyphen tails: a tour name, "<n> Years/Jahre", "<n>
 * Sets", an edition ending in a four-digit year, "Releaseshow". Trailing tails: "Live" / "Live
 * in <city>", a format annotation parenthesized or a bare "DJ-Set", "Nachholtermin vom <date>"
 * / "Hochverlegung", "singt <repertoire>", "<Album/EP/…> Release" / "Release Party", and a
 * record title spelled letter by letter after a dash (`KAT FRANKIE - B O D I E S`), which the
 * shouted-tail rule cannot reach because its head is shouted too (#1533).
 *
 * Hyphen tails: a tour name, "<n> Years/Jahre", "<n> Sets", an edition ending in a four-digit
 * year, "Releaseshow". Trailing tails: "Live" / "Live in <city>", a format annotation
 * parenthesized or a bare "DJ-Set", "Nachholtermin vom <date>" / "Hochverlegung", "singt
 * <repertoire>", "<Album/EP/…> Release" / "Release Party", a record title spelled letter by
 * letter after a dash (`KAT FRANKIE - B O D I E S`), which the shouted-tail rule cannot reach
 * because its head is shouted too (#1533), and from #1580 the act's own backing (`Lacrimosa mit
 * Orchester`), which the conjunction split keeps attached, and a dash tail ending in `!`, which
 * is billing prose. A `: <night> 2027` tail is the year-ended tour rule again with a colon
 * (#305), and a `— more TBA` tail is a line-up placeholder glued to the last act (#1564).
 *
 * The boundaries keep real names intact: hyphen tails need `<space>-<space>` and a marker
 * ("BAD COMPANY LEGACY - Dave Colwell" is left alone); the year is anchored at the end ("Blink -
 * 182", "Front 242"); "Live" needs a preceding whitespace boundary (the band Live); the bare
 * "Release" tag needs a format word or a "Party"/"Show" tail (a band named Release); the
 * parenthetical is keyed on the format word (an alias survives); the relocation marker is
 * word-anchored with an optional leading dash.
 */
private val ARTIST_SUFFIX_PATTERN =
    Regex(
        """\s+[-–—]\s+(?:\S.*\btour\b|\d+\s+(?:years?|jahre|sets?)\b).*$""" +
            """|\s+[-–—]\s+\S.*\b(?:19|20)\d{2}\s*$""" +
            """|(?<=\S):\s+\S.*\b(?:19|20)\d{2}\s*$""" +
            """|(?:\s*[-–—]\s*|(?<!many|viele)\s+(?:(?:[&+]|and|und)\s+)?)(?:many\s+|viele\s+)?(?:more|mehr)\b(?:\s+(?:tba|tbc|tbd))?\.*$""" +
            """|\s+[-–—]\s*release\s?show\s*$""" +
            """|\s+live(?:\s+in\s+\S.*)?$""" +
            """|\s*\((?:dj[\s-]?set|live|acoustic|akustik|unplugged|solo|konzert|concert)\)\s*$""" +
            """|\s+dj[\s-]?set$""" +
            """|\s+[-–—(]*\s*(?:nachholtermin|hochverlegung|verschoben)\b.*$""" +
            """|\s+singt\s+\S.*$""" +
            """|\s+(?:album|ep|single|mixtape|record|tape)\s+release(?:\s+(?:party|show|special))?$""" +
            """|\s+release\s+(?:party|show)$""" +
            """|\s+[-–—]\s+(?:\p{L}\s+){2,}\p{L}\s*$""" +
            """|\s+(?:mit|with)\s+(?:orchester|orchestra|band|ensemble|chor|choir|streichern?|strings)\s*$""" +
            """|\s+[-–—]\s+\S.*!\s*$""",
        RegexOption.IGNORE_CASE
    )

/**
 * Strips a trailing tour/live/anniversary suffix or format annotation from an act name.
 * Unchanged when there is none or stripping would leave nothing, so `"Live"` and
 * `"Sickboyrari (Black Kray)"` survive. [ARTIST_SUFFIX_PATTERN] has the boundaries.
 */
fun stripArtistSuffix(name: String): String {
    // One suffix can hide another (`Lacrimosa mit Orchester - … in Europa!`), so strip until stable.
    var stripped = name.trim()
    repeat(MAX_SUFFIX_PASSES) {
        val next = stripped.replace(ARTIST_SUFFIX_PATTERN, "").trim()
        if (next == stripped || next.isBlank()) return@repeat
        stripped = next
    }
    return stripTrailingSeparator(stripWorkTitle(stripShoutedTourTail(stripTrailingParenthetical(stripped))))
}

/**
 * A trailing origin tag: two- or three-letter country codes (`(NL)`, `(PL/USA)`), a genre in front
 * of them (`(Dark Wave US/DE)`), or a spelled-out country with an optional `Live` (`(Thailand-Live)`)
 * (#314). arkaoda's local rule, lifted here and widened to the spelled-out form.
 */
private val ORIGIN_TAG =
    Regex(
        // The codes are upper case by definition — `(An toi)` is an alias, not Antigua — so only the
        // spelled-out alternative is case-insensitive.
        """\s*\((?:[^()]*?\s)?[A-Z]{2,3}(?:\s*[/,+&-]\s*[A-Z]{2,3})*\)\s*$""" +
            """|(?i:\s*\((?:$COUNTRY_NAMES)(?:\s*[-–—/]?\s*live)?\)\s*$)"""
    )

/**
 * A trailing band affiliation, which is never an alias: a comma list (`(WIRE, IMMERSION)`) or an
 * `ex-` opener (`(ex-EINSTÜRZENDE NEUBAUTEN, …)`) (#1561). A single bare name in parentheses
 * (`(PENETRATION)`, `(Black Kray)`) is undecidable between the two and stays.
 */
private val AFFILIATION_TAG = Regex("""\s*\((?:ex-[^()]*|[^(),]+,[^()]*)\)\s*$""", RegexOption.IGNORE_CASE)

/** Drops an [ORIGIN_TAG] or an [AFFILIATION_TAG] from the end of a name, keeping the input when nothing else is left. */
private fun stripTrailingParenthetical(name: String): String {
    val stripped = name.replace(ORIGIN_TAG, "").replace(AFFILIATION_TAG, "").trim()
    return stripped.ifBlank { name }
}

/** Suffixes nest at most a few deep; a bound keeps a pathological title from looping. */
private const val MAX_SUFFIX_PASSES = 4

/** A separator a split left behind at the end of a name — `Stevie Cox -` (#1585). */
private val TRAILING_SEPARATOR = Regex("""\s*[-–—:|]+\s*$""")

/** Drops a trailing bare separator, keeping the input when nothing else is left. */
private fun stripTrailingSeparator(name: String): String = name.replace(TRAILING_SEPARATOR, "").trim().ifBlank { name }

/** Minimum words in a mixed-case tail before it reads as a work title rather than part of the name. */
private const val MIN_WORK_TITLE_WORDS = 3

/** Words a work title leaves lowercase without ceasing to be one (`The Opening of the Cerebral Gate`). */
private val TITLE_CASE_SMALL_WORDS: Set<String> =
    setOf("a", "an", "the", "of", "in", "on", "at", "to", "for", "and", "or", "der", "die", "das", "des", "dem", "von", "und", "im", "am", "für", "zu")

/** A `: ` boundary between an act and the work it presents (`Jon Rose: Hinterland!`). `9:3` has no space and is untouched. */
private val COLON_SEPARATOR = Regex("""\S:\s+""")

/** A head that presents the tail rather than being an act: `<label> presents: <act>` keeps its right side. */
private val PRESENTING_HEAD = Regex("""\b(?:pres|presents|pr(?:ä|ae)sentiert)[.:]?$""", RegexOption.IGNORE_CASE)

/**
 * Strips a work title glued to an act with ` - ` or `: ` — the album played live, the project, the
 * show — so the performer remains (#1585): `Transllusion - The Opening of the Cerebral Gate` →
 * `Transllusion`, `Jon Rose: Hinterland!` → `Jon Rose`.
 *
 * A tail counts as a work title when it ends in `!` or runs to [MIN_WORK_TITLE_WORDS] words in
 * title case (every word capitalised bar the [TITLE_CASE_SMALL_WORDS]). The head must be an act,
 * not a label presenting one ([PRESENTING_HEAD]), and must carry a lowercase letter, so an all-caps
 * name a venue wrote with a dash (`DZ - DEATHRAY`) is left to [stripShoutedTourTail]'s fences. A
 * two-word tail (`BAD COMPANY LEGACY - Dave Colwell`, `Drone Art Show: Harry Potter`) is not
 * enough to call, and stays. Set-length and format suffixes are #301's decision, not this rule's.
 */
private fun stripWorkTitle(name: String): String {
    val dash = DASH_SEPARATOR.findAll(name).lastOrNull()
    val boundary = dash ?: COLON_SEPARATOR.find(name) ?: return name
    val head = name.substring(0, if (dash == null) boundary.range.first + 1 else boundary.range.first).trim()
    val tail = name.substring(boundary.range.last + 1).trim()
    // The lowercase fence guards the dash only: `DZ - DEATHRAY` is a name, `JON ROSE: HINTERLAND!` is not.
    val headIsAct = head.isNotBlank() && !PRESENTING_HEAD.containsMatchIn(head) && (dash == null || head.any { it.isLowerCase() })
    return if (headIsAct && readsAsWorkTitle(tail)) head else name
}

private fun readsAsWorkTitle(tail: String): Boolean {
    val words = tail.split(WHITESPACE).filter { it.isNotBlank() }
    val titleCased =
        words.size >= MIN_WORK_TITLE_WORDS &&
            words.first().first().isUpperCase() &&
            words.all { word -> word.first().isUpperCase() || word.lowercase() in TITLE_CASE_SMALL_WORDS }
    return words.isNotEmpty() && (tail.endsWith('!') || titleCased)
}

/** Countries and cities a venue writes after an act in full, in the two languages the pages use. */
private const val COUNTRY_NAMES =
    "germany|deutschland|austria|österreich|switzerland|schweiz|uk|england|scotland|schottland|wales|ireland|irland" +
        "|usa|canada|kanada|mexico|mexiko|brazil|brasilien|argentina|argentinien|chile|colombia|kolumbien|peru|cuba|kuba" +
        "|france|frankreich|italy|italien|spain|spanien|portugal|netherlands|niederlande|holland|belgium|belgien" +
        "|denmark|dänemark|sweden|schweden|norway|norwegen|finland|finnland|iceland|island|poland|polen|czechia|tschechien" +
        "|hungary|ungarn|greece|griechenland|turkey|türkei|israel|russia|russland|ukraine|georgia|georgien" +
        "|japan|korea|china|india|indien|thailand|indonesia|indonesien|australia|australien|new zealand|neuseeland" +
        "|south africa|südafrika|nigeria|ghana|senegal|mali|berlin|hamburg|köln|münchen|wien|zürich|london|paris"

/** Minimum words in a shouted tail before it reads as a tour/album name rather than an act. */
private const val MIN_SHOUTED_TAIL_WORDS = 2

/**
 * The space-padded dash between an act and its tour name. All three dashes count: LARK writes
 * an en dash (`Greg Mendez – BEAUTY LAND TOUR`), and the ASCII hyphen alone let that tail
 * survive.
 */
private val DASH_SEPARATOR = Regex("""\s[-–—]\s""")

/**
 * Strips a trailing `" - <SHOUTED TOUR/ALBUM NAME>"`, the spelling for a tour named after a
 * record: `"Tigercub - NETS TO CATCH THE WIND"` to `"Tigercub"`. Casing is the whole signal,
 * fenced three ways: the tail must be fully shouted (`"BAD COMPANY LEGACY - Dave Colwell"`,
 * `"Sinem - Hatun"` keep their second half); the head must contain a lowercase letter (Urban
 * Spree's `"DZ - DEATHRAY"` is DZ Deathrays); the tail must be at least
 * [MIN_SHOUTED_TAIL_WORDS] words. Across a 29-venue seed of 1240 titles it fires on two, both
 * genuine tour tails.
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
 * Curated one-off titles that are not performers and no structural rule catches: a warm-up slot
 * at a room, a package-tour name, a themed night, a venue's own series its structured data
 * lists as the performer (Bi Nuu). Entries are lowercase, accent-free, whitespace-collapsed; a
 * title is normalized the same way ([isDenylistedNonArtist]) with a trailing edition number or
 * `Berlin` ignored, so one entry folds `FEMALE-FRONTED IS NOT A GENRE 5`, `Boheme Sauvage
 * N°141` and `Bohème Sauvage Berlin`.
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
        // The anti-fascist campaign's own concert series: Columbiahalle bills its anniversary
        // festival under the name with the lineup unannounced (#1110).
        "kein bock auf nazis",
        // DLTLLY (Don't Let The Label Label You) is a battle-rap league: Festsaal bills its birthday
        // show under the league's name and names no performer anywhere on the page (#1135).
        "dltlly",
        // Bare event-format words a co-billed title splits off as if they were acts — Säälchen's
        // `10 Jahre "The Big Brassers" – Jubiläumskonzert & Party` yields both of these.
        "party",
        "jubiläumskonzert",
        // Genre words a co-billed format title splits off — Monarch's `POETRY & HIP HOP (KONZERT)` (#1580).
        "poetry",
        "hip hop",
        "hip-hop",
        // Series billed under their own name with the acts on the page body only (#1581).
        "berlin beat invasion",
        "urban spree klubnacht",
        "methods of dance"
    )

/**
 * A trailing edition number on a recurring title, ignored when matching [NON_ARTIST_NAMES]: the
 * plain `… 5`, the `… N°141` and `… No 8` forms (optional `n°`/`nº`/`no.`) and a roman numeral
 * (`Methods of Dance II`).
 */
private val TRAILING_EDITION = Regex("""\s+(?:n[°º]\s*|no\.?\s*)?(?:\d+|[ivx]{1,5})$""", RegexOption.IGNORE_CASE)

/**
 * A trailing `Berlin` on a series title (`GrooveJet Berlin`, `Bohème Sauvage Berlin`), ignored
 * when matching [NON_ARTIST_NAMES]. Matching only: `Isolation Berlin` drops the suffix, is
 * absent from the denylist, and is kept.
 */
private val TRAILING_CITY = Regex("""\s+berlin$""")

/** Combining diacritical marks left by NFD normalization; stripped so accents can't defeat a denylist match. */
private val DIACRITICS = Regex("""\p{Mn}+""")

/**
 * Record labels and promoters that lead a title with their own name for a showcase, where every
 * segment names a part of the programme: `"aufnahme + wiedergabe - Fünfzehn Jahre + Zweiter
 * Akt"` has no performer. Separate from [NON_ARTIST_NAMES], which is compared against a single
 * split act (so `"Karrera Klub + Some Band"` still yields `Some Band`); an entry here
 * suppresses the whole title, so a label that ever bills a real act after its own name
 * (`"<label> presents <act>"`) does not belong. Lowercase, whitespace-collapsed; a title
 * matches when it is the entry or opens with it plus a [TITLE_LEAD_SEPARATOR].
 */
private val NON_ARTIST_TITLE_LEADS: Set<String> = setOf("aufnahme + wiedergabe")

/** The punctuation a leading label uses to introduce the event name that follows it. */
private val TITLE_LEAD_SEPARATOR = Regex("""\s*[-–—:|]\s*""")

/**
 * True when [title] is nothing but a [NON_ARTIST_TITLE_LEADS] label or opens with one plus a
 * separator, so no part of it is a performer.
 */
private fun isLedByNonArtistLabel(title: String): Boolean {
    val normalized = title.trim().replace(WHITESPACE, " ").lowercase()
    return NON_ARTIST_TITLE_LEADS.any { lead ->
        normalized == lead || (normalized.startsWith(lead) && TITLE_LEAD_SEPARATOR.matchesAt(normalized, lead.length))
    }
}

/**
 * A subtitle that is nothing but a `"<X> presents"` / `"<X> präsentiert"` frame, capturing the
 * presenter. The marker must end the line: "Zeppelin Entertainment Presents - Joy of Little
 * Things Tour" is billing a tour, and the title beside it is still the act.
 */
private val PRESENTER_CREDIT_PATTERN =
    Regex("""^(.+?)\s+(?:presents|pr(?:ä|ae)sentiert)\s*[-–—:|.]*\s*$""", RegexOption.IGNORE_CASE)

/**
 * The same marker anywhere in a title, which disqualifies the rule ([isPresenterOwnEventTitle]).
 */
private val PRESENTER_MARKER_IN_TITLE = Regex("""\b(?:presents|pr(?:ä|ae)sentiert)\b""", RegexOption.IGNORE_CASE)

/**
 * Trailing words that say what a presenter is, not what it is called: a label, an agency, a
 * legal form. A venue writes the full business name in the credit and the short name in the
 * title (`Corrupted Blood Records` presents `Corrupted Blood Club Show`).
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
 * True when the [subtitle] credits a presenter whose own name opens the [title], so the title
 * names that presenter's event: the structural counterpart of [isLedByNonArtistLabel]. Huxleys'
 * `Corrupted Blood Club Show`, subtitled `Corrupted Blood Records presents`, would otherwise
 * mint an artist playing nowhere else. Three fences: the credit must be a whole subtitle line
 * ([PRESENTER_CREDIT_PATTERN]); the title must not carry the marker itself, since `<X>
 * presents: <act>` is the opposite and frequent case (Gretchen bills 20 shows that way, and
 * Zenner's `Analogue Foundation presents: David August w/ MFO` would lose David August); the
 * title must continue past the presenter's name, because a title that is the name is as likely
 * an act running its own label. Across the seeded database nine events carry such a subtitle
 * and exactly one satisfies all three, the Huxleys row.
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
 * True when this title opens with [presenter], or [presenter] minus its [descriptor
 * tail][PRESENTER_DESCRIPTOR_TAIL], and continues, ending on a word boundary so `Corrupted
 * Bloodline` does not open with `Corrupted Blood`.
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
 * A bare "DJ set" format label, optionally with a `/ <origin>` tail (`DJ-Set`, `DJ Set`, `DJ-Set
 * / Berlin`), which a Madame Claude detail heading pushes into a performer slot. Anchored, so
 * `DJ Koze` and any `DJ <handle>` are untouched.
 */
private val DJ_SET_LABEL_PATTERN = Regex("""dj[\s-]?set(?:\s*/.*)?""", RegexOption.IGNORE_CASE)

/**
 * Whether [name] is a bare `DJ set` label ([DJ_SET_LABEL_PATTERN]); anchored, so `DJ Koze` /
 * `DJ Set Sail` are kept.
 */
fun isDjSetFormatLabel(name: String): Boolean = DJ_SET_LABEL_PATTERN.matches(name.trim().replace(WHITESPACE, " "))

/**
 * An unannounced-guest slot: a bare "Guest(s)"/"Gäste", optionally with a leading "+" and a
 * format ("Guest DJs", Club der Visionäre's spelling); Wild at Heart lists "+ Guest". A
 * placeholder, mirroring the [CONJUNCTION_TAIL_COLLECTIVES] that keep "X & Guests" one act.
 * Anchored, so "Special Guest DJ Foo" is untouched; kept to the guest forms, since a standalone
 * "Friends"/"Band" is a plausible act name.
 */
private val GUEST_SLOT_PATTERN = Regex("""\+?\s*(?:guests?|gäste|gaeste)(?:\s+djs?)?""", RegexOption.IGNORE_CASE)

/**
 * Whether [name] is a bare guest slot ("+ Guest", "Guests", "Gäste", "Guest DJs");
 * [GUEST_SLOT_PATTERN], anchored.
 */
fun isGuestSlotLabel(name: String): Boolean = GUEST_SLOT_PATTERN.matches(name.trim().replace(WHITESPACE, " "))

/**
 * True when [name] must never be stored as an artist: a placeholder ("TBA"), a bare role label
 * ("Special Guest"), a segment ("Acid Aftershow"), an event ("Shred Fest"), a "DJ set" label, a
 * guest slot ("+ Guest"), a curated title ("The Revival Tour"), or a name that slugs to nothing
 * ("-"). The single predicate applied wherever names are resolved.
 */
fun isNonArtistName(name: String): Boolean =
    isPlaceholderName(name) || isNonArtistLabel(name) || isEventSegmentLabel(name) ||
        isNonArtistEvent(name) || isDjSetFormatLabel(name) || isGuestSlotLabel(name) || isDenylistedNonArtist(name) ||
        isTitleFragment(name) || isSlugless(name) || isBareNumber(name)

/**
 * A name with nothing a slug can keep is a separator the split left behind (#1553).
 * `artist.slug` is UNIQUE, so the second such name from any source would fail its whole import.
 */
fun isSlugless(name: String): Boolean = SlugGenerator.slugify(name).isBlank()

private val BARE_NUMBER = Regex("""\d+""")

/** A digits-only name is the tail of a `<show> 1 & 2` billing, never an act (#1556). */
fun isBareNumber(name: String): Boolean = BARE_NUMBER.matches(name.trim())

/**
 * A candidate still carrying a title's `|` separator ("SKETCHY SESSIONS | jazz") is a slice of
 * the title (#1494). No performer writes a pipe; length says nothing, since "…And You Will Know
 * Us by the Trail of Dead" is one band.
 */
private fun isTitleFragment(name: String): Boolean = name.contains('|')

/**
 * Single acts whose name contains a conjunction [splitHeadlinerTitle] would read as a co-bill,
 * matched case-insensitively against the whole title. `AC/DC` needs no entry, being protected
 * by space-padding; this list is for the `" & "` / `" and "` / `" und "` cases. Entries are in
 * `&` form and the title's conjunctions are normalized to `&` first.
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
 * Leading words marking the right-hand side of a conjunction as a band-name tail, unioned in
 * [CONJUNCTION_TAIL_MARKERS]: articles/possessives opening a backing band ("X & the Ys", "X
 * and his Ys", "X und die Ys"), and collectives naming an unnamed cast ("X & Friends", "X &
 * Guests", "X & Gäste", "X & Band", "Lacrimosa mit Orchester"). The same set keeps a
 * [WITH_FRAME_PATTERN] tail from being read as the acts.
 */
private val CONJUNCTION_TAIL_ARTICLES: Set<String> =
    setOf("the", "his", "her", "their", "los", "las", "die", "der", "das", "el", "la")

private val CONJUNCTION_TAIL_COLLECTIVES: Set<String> =
    setOf("friends", "guests", "gäste", "freunde", "band", "orchester", "orchestra", "ensemble", "chor", "choir")

/** Right-hand-side opener words that keep a conjunction boundary joined — see the two source sets. */
private val CONJUNCTION_TAIL_MARKERS: Set<String> = CONJUNCTION_TAIL_ARTICLES + CONJUNCTION_TAIL_COLLECTIVES

/** Space-padded `/` or `+` — unambiguous co-bill separators once whitespace is required on both sides. */
private val SAFE_TITLE_SEPARATOR = Regex("""\s+[/+]\s+""")

/**
 * Space-padded `+` only, for venues (Madame Claude) that use `/` inside one act name (`Morimoto
 * / Wong duo`); selected via `splitOnSlash = false`.
 */
private val PLUS_ONLY_TITLE_SEPARATOR = Regex("""\s+\+\s+""")

/**
 * Space-padded conjunction (`&`, `and`, `und`, the Portuguese and Italian `e`), split only at
 * boundaries passing the [splitSegmentOnConjunctions] guardrails. Space-padded so "Portland"
 * never matches; the one-letter `e` also needs two non-space characters each side, so `KAT
 * FRANKIE - B O D I E S` is not cut at its E (#1533).
 */
private val CONJUNCTION_SEPARATOR = Regex("""\s+(?:&|and|und)\s+|(?<=\S{2})\s+e\s+(?=\S{2})""", RegexOption.IGNORE_CASE)

/**
 * A segment that ends like a sentence and carries a function word is billing prose (`Einzige und
 * Exklusive Orchester-Show in Europa!`); no conjunction inside it delimits acts. Both halves are
 * needed: `ALL ABOUT BIRDS & JON ROSE: HINTERLAND!` ends in `!` and is still a co-bill.
 */
private val PROSE_END = Regex("""\s(?:in|im|mit|für|von|of|the|for|to|at|on|aus|bei)\s.*[!?]\s*$""", RegexOption.IGNORE_CASE)

/**
 * True when [name] is a [KNOWN_SINGLE_ACTS] entry, conjunctions normalized to `&` first. Checked
 * at segment level, so `"BLOOD & SUN + SOCIETY OF THE SILVER CROSS"` splits at the `+` into two
 * acts, and "Support: Simon & Garfunkel" is protected.
 */
private fun isKnownSingleAct(name: String): Boolean = name.trim().replace(CONJUNCTION_SEPARATOR, " & ").lowercase() in KNOWN_SINGLE_ACTS

/**
 * Splits a title segment into acts at its conjunctions, per boundary, so a real co-bill still
 * splits beside a band-name tail: a comma anywhere suppresses splitting ("Earth, Wind & Fire"),
 * and a boundary whose right-hand side opens with a [tail marker][CONJUNCTION_TAIL_MARKERS]
 * stays joined, so `CARL CARLTON & MELANIE WIEGMANN AND THE GREAT BAND` cuts only at the `&`.
 * Never on `/` or `+`, so a venue can pre-split its co-bills and hand each segment here;
 * [splitSupportActs] and [splitHeadlinerTitle] apply it after their hard-separator split.
 */
@Suppress("ReturnCount") // Guard clauses for the comma and no-cut cases are clearer than nesting
fun splitSegmentOnConjunctions(segment: String): List<String> {
    if (isKnownSingleAct(segment)) return listOf(segment)
    if (segment.contains(',')) return listOf(segment)
    // `Einzige und Exklusive Orchester-Show in Europa!` is billing prose, not two acts (#1580).
    if (PROSE_END.containsMatchIn(segment)) return listOf(segment)

    val cuts =
        CONJUNCTION_SEPARATOR
            .findAll(segment)
            .filter { !isInsideBrackets(segment, it.range.first) }
            .filter { match ->
                val nextWord = segment.substring(match.range.last + 1).trimStart().substringBefore(' ')
                // `Eiskönigin 1 & 2` is one billing: a number after the conjunction is a
                // sequel or a volume, not a second act (#1556).
                nextWord.lowercase() !in CONJUNCTION_TAIL_MARKERS && !isBareNumber(nextWord)
            }.map { it.range }
            .toList()
    return cutAt(segment, cuts)
}

/**
 * True when [index] sits inside a `(…)` or `[…]` group. A separator inside brackets belongs to a
 * band affiliation (`David J (Bauhaus / Love & Rockets)`), a member list (`Los Refrescos (Dandy
 * Jack & Argenis Brito)`) or a format note, and splitting there leaves `Rockets)`. An unclosed
 * bracket only makes the guard more conservative.
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
 * Splits a support line into act names: comma, `+` and `/` always delimit; `&` / `and` / `und`
 * per boundary via [splitSegmentOnConjunctions], so `Scott Hepple & The Sun Band` is one act and
 * `High On Fire & Gnome` two. Role-label stripping and placeholder filtering are the caller's.
 */
fun splitSupportActs(text: String): List<String> =
    text
        .split(SUPPORT_HARD_SEPARATOR)
        .flatMap { splitSegmentOnConjunctions(it) }
        .map { it.trim() }
        .filter { it.isNotBlank() }

/**
 * Splits a headliner title into co-billed acts (`TOTAL CHAOS + RUMKICKS + THE DOLLHEADS`,
 * `LAGWAGON / THE VIRGINMARYS`, `BLACK STAR RIDERS & TYKETTO`) on space-padded separators only,
 * which protects `AC/DC` and `dance/electronic`; `" & "` / `" and "` / `" und "` per boundary
 * via [splitSegmentOnConjunctions], never for a [KNOWN_SINGLE_ACTS] title. No separator returns
 * the trimmed title alone. Every case is asserted in `ArtistNameMappingTest`.
 *
 * @param splitOnSlash when false, `/` is not a separator, for venues that use it inside one act
 * name (Madame Claude's `Morimoto / Wong duo`).
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
 * A leading series label ending in "#<n>:" ("OFF THE RAILS #5: …"); the acts follow the colon.
 * Non-greedy, and a non-blank series name is required, so "9:3" or "H2:O" is untouched.
 */
private val SERIES_PREFIX_PATTERN = Regex("""^.+?#\s*\d+\s*:\s*""")

/**
 * Strips a leading "<series> #<n>:" label: `"OFF THE RAILS #5: Blake Harley & Superior Motive"`
 * to `"Blake Harley & Superior Motive"`. Unchanged when none or stripping would leave nothing.
 */
fun stripSeriesPrefix(title: String): String {
    val stripped = title.trim().replaceFirst(SERIES_PREFIX_PATTERN, "").trim()
    return stripped.ifBlank { title.trim() }
}

/**
 * A leading "A night with" / "An evening with" / "Ein Abend mit" frame, stripped from a
 * title-derived headliner ("A night with GULVØSS II" to "GULVØSS II"). Title-scoped
 * ([headlinersFromTitle]); the stored title is untouched.
 */
private val ARTIST_FRAMING_PREFIX =
    Regex("""^(?:a\s+night\s+with|an\s+evening\s+with|ein\s+abend\s+mit)\s+""", RegexOption.IGNORE_CASE)

/** Strips a leading [ARTIST_FRAMING_PREFIX], keeping the input when stripping would leave nothing. */
private fun stripFramingPrefix(name: String): String {
    val stripped = name.replaceFirst(ARTIST_FRAMING_PREFIX, "").trim()
    return stripped.ifBlank { name.trim() }
}

/**
 * A leading role label that specifically marks a support billing, narrower than
 * [ROLE_LABEL_PREFIX], to decide a title segment's role before its label is stripped.
 */
private val SUPPORT_ROLE_PREFIX =
    Regex("""^(?:div\.?\s*supports?|special\s+guests?|supports?|openers?)\s*:""", RegexOption.IGNORE_CASE)

/**
 * A leading role or event-format label in front of the billed act (`Support:`, `Opener:`,
 * `Record Release:`, `Listening Session:`), which must not become part of the name
 * (Admiralspalast stored `Support: A.A. Williams`, Loge `Record Release: Pair`, Tresor
 * `Listening Session: Drexciya - Neptune's Lair`). The colon is required, unlike
 * [ROLE_LABEL_PREFIX]: against an arbitrary title, `Support Lesbiens` and `Session Victim`
 * would be maimed.
 */
private val ARTIST_LABEL_PREFIX =
    Regex(
        """^(?:div\.?\s*supports?|special\s+guests?|supports?|openers?""" +
            """|listening\s+session|record\s+release|record\s+launch|album\s+release|release\s+show)\s*:\s*""",
        RegexOption.IGNORE_CASE
    )

/**
 * Strips a leading [ARTIST_LABEL_PREFIX] from an act name, the title-level counterpart of
 * [stripArtistSuffix]. Unchanged when none or stripping would leave nothing. The colon is what
 * makes it safe: without it Support Lesbiens becomes "Lesbiens".
 */
fun stripArtistPrefix(name: String): String {
    val stripped = name.trim().replaceFirst(ARTIST_LABEL_PREFIX, "").trim()
    return stripped.ifBlank { name.trim() }
}

/**
 * Turns an event title into its headliner entries: [stripSeriesPrefix], [splitHeadlinerTitle],
 * strip an "A night with …" frame and any suffix ([stripArtistSuffix]), drop
 * [isNonArtistName]. Billing order is title order; the caller appends support acts.
 *
 * @param splitOnSlash forwarded to [splitHeadlinerTitle] (false for Madame Claude).
 * @param unpackWithFrame reads a `"<night> w/ <acts>"` or `"<night> mit <acts>"` title as its
 * acts only; [withFrameActs] has why this is opt-in.
 * @param subtitle used only for the label-showcase check ([isPresenterOwnEventTitle]).
 */
@Suppress("ReturnCount") // Three guard clauses (label-led title, presenter credit, w/ frame) read better than nesting
fun headlinersFromTitle(
    rawTitle: String,
    splitOnSlash: Boolean = true,
    unpackWithFrame: Boolean = false,
    subtitle: String? = null
): List<ScrapedArtist> {
    // The persistence boundary strips a cancellation from the title after the acts are built, so
    // `Absage: The Act` must lose the marker here or bill it (#1560).
    val title = stripTitleStatusMarker(rawTitle)
    // A title led by a label's own name announces that label's event; nothing in it is an act.
    if (isLedByNonArtistLabel(title)) return emptyList()
    // Same conclusion, reached structurally: the subtitle credits the label and the title repeats it.
    if (isPresenterOwnEventTitle(title, subtitle)) return emptyList()
    if (unpackWithFrame) withFrameActs(title)?.let { return it }
    // `<act> feat. <guest>` mid-title: the guest is billed as support, the act goes on (#305).
    val (billing, guests) = splitFeaturedGuests(title)
    return splitHeadlinerTitle(stripSeriesPrefix(billedSideOfPres(billing, splitOnSlash)), splitOnSlash)
        .map { segment ->
            // The role is decided from the *raw* segment, before its label is stripped: a title
            // that bills "… + Support: A.A. Williams" names a support act, not a second headliner.
            val role = if (SUPPORT_ROLE_PREFIX.containsMatchIn(segment.trim())) "SUPPORT" else "HEADLINER"
            stripFramingPrefix(stripArtistPrefix(stripArtistSuffix(segment))) to role
        }.filterNot { (name, _) -> isNonArtistName(name) }
        .map { (name, role) -> ScrapedArtist(name = name, role = role, titleDerived = true) } + guests
}

/**
 * A night named for the DJ who runs it: `<night> curated by <acts>`, `<night> hosted by <acts>`,
 * `<night> by <Person Name>` (#339). The acts after the marker are the booking.
 */
private val HOSTED_ACTS_MARKER = Regex("""^.+?\s+(?:curated\s+by|hosted\s+by)\s+(.+)$""", RegexOption.IGNORE_CASE)

/** The bare `by` form, accepted only when what follows is shaped like a person's name. */
private val BY_PERSON_MARKER = Regex("""^.+?\s+by\s+(\p{Lu}[\p{L}'.-]*(?:\s+\p{Lu}[\p{L}'.-]*){1,3})$""")

/**
 * The acts a `curated by` / `hosted by` / `by <Person>` title names, for a venue that publishes no
 * line-up for that night (#339). The marker words are a closed set and the acts follow them, which
 * is what separates this from the `PARTY` guard in [buildArtistsForEventType]: a party title is not
 * an act, but the DJ a party names as its curator is. A venue that does publish a line-up keeps it
 * — Tresor's floor host (`hosted by HARD WAX`) is a collective, not a booking, and its scraper drops
 * it — so callers use this as the fallback for an empty line-up only.
 */
fun hostedActsFromTitle(
    title: String,
    role: String = "HEADLINER"
): List<ScrapedArtist> {
    val acts = HOSTED_ACTS_MARKER.find(title.trim())?.groupValues?.get(1) ?: BY_PERSON_MARKER.find(title.trim())?.groupValues?.get(1)
    return acts
        ?.let(::splitSupportActs)
        .orEmpty()
        .map { stripArtistSuffix(it) }
        .filterNot(::isNonArtistName)
        .map { ScrapedArtist(name = it, role = role, titleDerived = true) }
}

/** The `feat.` / `featuring` / `ft.` marker between an act and its guest, mid-title. */
private val FEATURED_GUEST_MARKER = Regex("""\s+(?:feat\.?|featuring|ft\.)\s+""", RegexOption.IGNORE_CASE)

/** The title's billing before a [FEATURED_GUEST_MARKER], and the guests after it as support acts. */
private fun splitFeaturedGuests(title: String): Pair<String, List<ScrapedArtist>> {
    val marker = FEATURED_GUEST_MARKER.find(title) ?: return title to emptyList()
    val guests =
        splitSupportActs(title.substring(marker.range.last + 1))
            .map { stripArtistSuffix(it) }
            .filterNot(::isNonArtistName)
            .map { ScrapedArtist(name = it, role = "SUPPORT", titleDerived = true) }
    return title.substring(0, marker.range.first) to guests
}

/** `<X> pres. <Y>` / `<X> pres: <Y>` — the abbreviated presenter marker, with what stands on either side. */
private val PRES_MARKER = Regex("""^(.+?)\s+pres[.:]\s+(.+)$""", RegexOption.IGNORE_CASE)

/**
 * The side of a `pres.` / `pres:` title that bills the acts (#1581).
 *
 * The marker reads two ways. `hub pres. Doorman + Franco Franco` and `Unguarded pres. Jungstötter +
 * Blurrydog` name a host and its programme, so the acts are on the right; `Burnt Friedman pres:
 * Secret Rhythms` names an act and its project, so the act is on the left. What separates them is
 * the right side: a co-bill is a programme, a single name is a work. The spelled-out `presents` is
 * not handled here — a venue that bills that way splits it itself (Gretchen), and the shared rule
 * that reads a `presents` credit is [isPresenterOwnEventTitle].
 */
private fun billedSideOfPres(
    title: String,
    splitOnSlash: Boolean
): String {
    val match = PRES_MARKER.find(title.trim()) ?: return title
    val (host, programme) = match.destructured
    return if (splitHeadlinerTitle(programme, splitOnSlash).size > 1) programme else host
}

/**
 * A `"<night> w/ <acts>"` or `"<night> mit <acts>"` frame, up to and including the marker.
 * `mit` is the German spelling: SO36 bills `SADTEMBER mit TAHA, JOHNBOY M.IKARUS` (#1132), and
 * needs whitespace both sides so Mitski and Submit are untouched.
 *
 * Not a co-bill separator: across the seed every title carrying it names a night, series or
 * label on the left (`RIPPLES W/ AMINE K`, `House of Rave w/ Maceo Plex, …`), and keeping both
 * halves would mint every night name. Not universally a frame either, so unpacking is opt-in:
 * Zenner's `Analogue Foundation presents: David August w/ MFO (live)` joins two collaborating
 * artists, and Zenner anchors its own frame to a leading duration (`180 min w/ Barker`), a cue
 * no other venue shares.
 *
 * The tail is split by [splitSupportActs], since after the marker a comma delimits acts; every
 * act is a headliner. The marker must be preceded by something, so an entry opening with `w/`
 * goes to [ROLE_LABEL_PREFIX]; a tail opening with a [CONJUNCTION_TAIL_MARKERS] word is the
 * act's own backing (`Lacrimosa mit Orchester`). Both return `null`.
 */
private val WITH_FRAME_PATTERN = Regex("""^.+?(?:\bw/\s*|\smit\s+)""", RegexOption.IGNORE_CASE)

/** The acts a [WITH_FRAME_PATTERN] title bills, or `null` when the title carries no such frame. */
@Suppress("ReturnCount") // Two guard clauses (no frame, backing-band tail) read better than nesting
private fun withFrameActs(title: String): List<ScrapedArtist>? {
    val frame = WITH_FRAME_PATTERN.find(title) ?: return null
    val tail = title.substring(frame.range.last + 1)
    if (tail.trimStart().substringBefore(' ').lowercase() in CONJUNCTION_TAIL_MARKERS) return null
    val acts =
        splitSupportActs(tail)
            .map { stripFramingPrefix(stripArtistPrefix(stripArtistSuffix(it))) }
            .filterNot { isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "HEADLINER", titleDerived = true) }
    return acts.ifEmpty { null }
}

/**
 * Builds an artist list from a headliner title and support names, the "title = headliner +
 * Support:" pattern. [supportNames] confirms the convention; placeholders ("TBA") and bare role
 * labels ("Special Guest") are filtered from the output but still serve as the signal.
 *
 * @param title the event title, one or more headliner names.
 * @param supportNames support acts; empty returns an empty list.
 * @param subtitle forwarded to [headlinersFromTitle] for the label-showcase check.
 * @return headliner(s) first, then support acts in order.
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
 * The `"<Performer> – <Show>"` idiom every variety and comedy house bills a solo act with (#315):
 * a person-shaped head — two to four capitalised words — before a dash or colon, and a show after
 * it. Cosmic Comedy derives its `Comedy Special` acts the same way; a production title
 * (`DIE KLIMA-MONOLOGE`) has no such head and yields nothing.
 */
private val SOLO_BILL = Regex("""^(\p{Lu}[\p{L}'.-]*(?:\s+\p{Lu}[\p{L}'.-]*){1,3})\s*(?:[-–—]|:)\s+\S.*$""")

/** The solo act a show title bills, or none: a shouted head (`FOTZENSCHLEIMPOWER GEGEN RAUBTIER – …`) is a production's title, not a name. */
private fun soloBillOf(title: String): List<ScrapedArtist> =
    SOLO_BILL
        .find(stripTitleStatusMarker(title).trim())
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf { performer -> performer.any { it.isLowerCase() } && !isNonArtistName(performer) }
        ?.let { listOf(ScrapedArtist(name = it, role = "HEADLINER", titleDerived = true)) }
        .orEmpty()

/**
 * Builds an artist list from the source's own event type, for venues with a clean `kind` label
 * (Astra, Lido): festivals and parties extract none; a show with no support line bills its solo
 * act via [SOLO_BILL] (#315); concerts always add the title plus the subtitle's support acts;
 * unknown falls back to [buildArtistList].
 *
 * The `FESTIVAL`/`PARTY` guard is unconditional, and narrowing it is the trap: measured across
 * the seeded database, of the party and festival titles reaching here exactly one hid a
 * recoverable act and about ninety-five would have produced a wrong one, most storing the
 * night's name verbatim as a 30–60 character "artist", and a tribute night splitting like a
 * co-bill: `Friday I'm in Love – A Tribute to Post-Punk · Dark 80s + Nick Cave` yields `Nick
 * Cave`, whose row resolves by slug onto the real one. The single recoverable case was a
 * classification defect: `PARTY_TITLE_KEYWORDS` matched a bare `club`, typing Columbiahalle's
 * `Two Door Cinema Club` as `PARTY`. The `"<night> curated by / invites / hosted by <act>"`
 * idiom is #339.
 */

@Suppress("ReturnCount") // Guard clauses for the event-type branches are clearer than nesting
fun buildArtistsForEventType(
    title: String,
    subtitle: String?,
    eventType: String?
): List<ScrapedArtist> {
    if (eventType == EventType.FESTIVAL.name || eventType == EventType.PARTY.name) return emptyList()

    val supportNames = extractSupportFromSubtitle(subtitle)
    if (eventType == EventType.SHOW.name && supportNames.isEmpty()) return soloBillOf(title)
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
