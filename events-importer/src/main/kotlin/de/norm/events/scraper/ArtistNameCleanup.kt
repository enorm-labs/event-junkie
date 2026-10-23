@file:Suppress("TooManyFunctions") // Cohesive collection of small, single-purpose artist-name mapping utilities.

package de.norm.events.scraper

import java.util.Locale

// Tails and frames stripped from a real act name: tour, format, origin and alias tags. Part of
// the artist-name rules in ArtistNameMapping.kt.

/**
 * A show name of two words or more in straight quotes after a dash, closing the title: `SPIELHAGEN - "HARMONY + ECSTASY"` (#3622).
 * One quoted word can finish the act's own name, so `DZ - "DEATHRAY"` stays.
 */
internal const val DASHED_QUOTED_SHOW = """\s+[-–—]\s+"[^"\s][^"]*\s[^"]*[^"\s]"\s*$"""

/**
 * Trailing suffixes that decorate a real act name, stripped by [stripArtistSuffix]; every case
 * is asserted in `ArtistNameMappingTest`.
 *
 * Hyphen tails: a tour name, "<n> Years/Jahre", "<n> Sets", "<n>th Anniversary …", an edition ending in a four-digit
 * year, "Releaseshow". Trailing tails: "Live" / "Live in <city>" / "in Berlin" (not "Made in Berlin") / "live <year>", a format annotation
 * parenthesized or a bare "DJ-Set", "Nachholtermin vom <date>" / "Hochverlegung", "singt
 * <repertoire>" or "sings <repertoire>", "<Album/EP/…> Release" / "Release Party" / "Releaseshow", a record title spelled letter by
 * letter after a dash (`KAT FRANKIE - B O D I E S`), which the shouted-tail rule cannot reach
 * because its head is shouted too (#1533), and from #1580 the act's own backing (`Lacrimosa mit
 * Orchester`), which the conjunction split keeps attached, and a dash tail ending in `!`, which
 * is billing prose. A `: <night> 2027` tail is the year-ended tour rule again with a colon
 * (#305), and a `— more TBA` or bare `tba` tail is a placeholder glued to the act (#1564, #1843). A show
 * note in brackets (`(Zusatzshow)`, `(Vinyl)`) is a format too (#1761), and so are `(Album Set)` (#2960) and `(all night long)`.
 *
 * From #1841: a compound tour word after a dash (`Die Abschiedstour`), an anniversary without a
 * dash when it reads `<n> Years Of …` or `<n> Jahre Jubiläum` (`Mutabor 35 Jahre Jubiläum`), and
 * a `– Zusatzshow` dash tail, and a work title in German quotes (`KARAT „45 Jahre Der blaue
 * Planet“`); straight quotes stay, because `Voodoo Jürgens und die "Ansa Panier"` names the band.
 *
 * A tribute act's `– A tribute to <X>` or `Tribute Band` tail goes (#2882). A bare `<X> Tribute` is the name acts tour under.
 *
 * The boundaries keep real names intact: hyphen tails need `<space>-<space>` and a marker
 * ("BAD COMPANY LEGACY - Dave Colwell" is left alone); the year is anchored at the end ("Blink -
 * 182", "Front 242"); "Live" needs a preceding whitespace boundary (the band Live); the bare
 * "Release" tag needs a format word or a "Party"/"Show" tail (a band named Release); the
 * parenthetical is keyed on the format word (an alias survives); the relocation marker is
 * word-anchored with an optional leading dash.
 */
internal val ARTIST_SUFFIX_PATTERN =
    Regex(
        """\s+[-–—]\s+(?:\S.*tour\b|\d+\s+(?:years?|jahre|sets?)\b|\d+(?:st|nd|rd|th)\s+anniversary\b).*$""" +
            """|(?<=\S)\s+\d+\s+(?:years\s+of|jahre\s+jubil(?:ä|ae)um)\b.*$""" +
            """|\s+[-–—]\s*zusatz(?:show|konzert|termin)\s*$""" +
            """|(?<=\S)\s+„[^„“]+“\s*$""" +
            "|$DASHED_QUOTED_SHOW" +
            """|\s+[-–—]\s+\S.*\b(?:19|20)\d{2}\s*$""" +
            """|(?<=\S):\s+\S.*\b(?:19|20)\d{2}\s*$""" +
            """|(?:\s*[-–—]\s*|\s+(?:[&+]|and|und)\s+)(?:many\s+|viele\s+)?(?:more|mehr)\b(?:\s+(?:tba|tbc|tbd))?\.*$""" +
            """|\s+(?:many|viele)\s+(?:more|mehr)\b(?:\s+(?:tba|tbc|tbd))?\.*$""" +
            """|\s+(?:more|mehr)\s+(?:tba|tbc|tbd)\.*$""" +
            """|(?<=\S)(?<!\bmore)(?<!\bmehr)\s+(?:(?:w/|with|feat\.?|[+&])\s*)?(?:tba|tbc|tbd)\.*$""" +
            """|\s+[-–—]\s*release\s?show\s*$""" +
            """|\s+(?:hybrid\s+)?live(?:\s+(?:set|band|on\s+stage))?(?:\s*&\s*dj[\s-]?set)?""" +
            """(?:\s+in\s+\S.*|\s+(?:19|20)\d{2})?$""" +
            """|\s*\((?:dj[\s-]?set|(?:hybrid\s+)?live(?:\s+(?:set|band))?|hybrid|acoustic|akustik|unplugged|solo|drag""" +
            """|(?:duo|trio)[\s-]?set|konzert|concert|zusatz(?:show|konzert|termin)|(?:extra|additional)\s+show""" +
            """|(?:all\s+)?vinyl(?:\s+(?:set|only))?|album[\s-]?set|all\s+night\s+long|open\s+to\s+close)\)\s*$""" +
            """|\s+(?:dj[\s-]?set|hybrid|(?:acoustic|akustik)[\s-]?set)$""" +
            """|\s+[-–—(]*\s*(?:nachholtermin|hochverlegung|verschoben)\b.*$""" +
            """|\s+(?:singt|sings)\s+\S.*$""" +
            """|\s+(?:album|ep|single|mixtape|record|tape)\s+release(?:\s+(?:party|show|special))?$""" +
            """|\s+release(?:\s+party|\s?show)$""" +
            """|(?<=\S)(?<!\bmade)\s+in\s+berlin$""" +
            """|\s+[-–—]\s+(?:\p{L}\s+){2,}\p{L}\s*$""" +
            """|\s+(?:mit|with)\s+(?:orchester|orchestra|band|ensemble|chor|choir|streichern?|strings)\s*$""" +
            """|(?<=\S)\s*\((?!\s*live\s*\))[^()]+\)\s+show\s*$""" +
            """|\s*\(\s*(?:&|und|and|mit|with)\s+[^()]*\b(?:band|friends|freunde|orchester|orchestra|ensemble)\)\s*$""" +
            """|^(?:nachholtermin|hochverlegung|verschoben|verlegt)\b\s*[-–—:]*\s*(?=\S)""" +
            """|\s+[-–—]\s+\S.*!\s*$""" +
            """|\s+[-–—]\s+(?:a\s+)?tribute\s+to\s+\S.*$""" +
            """|\s+[-–—]\s+tribute\s?band\s*$""" +
            """|(?<=\S)\s+(?:$TOUR_REGION[\s-]+tour|(?:$TOUR_REGION[\s-]+)?\p{L}*tournee)\s*$""" +
            """|(?<=\S)\s+tribute\s+band\s*$""",
        RegexOption.IGNORE_CASE
    )

/**
 * The region before a dashless tour word: `Europa Tournee`, `UK Tour` (#3827). Any `Tournee` goes, but a bare `Tour` needs
 * the region, as a band can end in `Tour`. No city, so `Isolation Berlin Tour` keeps its act.
 */
private const val TOUR_REGION = """(?:europa|europe|european|welt|world|deutschland|germany|uk|usa|us)"""

/**
 * Splits a guest out of a trailing bracket: `Dosenstolz (feat. Tancred)` and `Warhammer (+ Corrode)`
 * are two acts each (#1761). A singleton when there is no such bracket.
 */
fun splitBracketedGuest(name: String): List<String> {
    val match = BRACKETED_GUEST.find(name.trim()) ?: return listOf(name)
    val (head, guest) = match.destructured
    return listOf(head.trim(), guest.trim()).filter { it.isNotBlank() }.ifEmpty { listOf(name) }
}

/** A trailing `(feat. X)`, `(ft. X)`, `(featuring X)`, `(with X)`, `(w/ X)` or `(+ X)`. */
private val BRACKETED_GUEST =
    Regex("""^(.+?)\s*\((?:feat\.?|ft\.?|featuring|with|w/|\+)\s*([^()]+)\)$""", RegexOption.IGNORE_CASE)

/**
 * Strips a trailing tour/live/anniversary suffix or format annotation from an act name.
 * Unchanged when there is none or stripping would leave nothing, so `"Live"` and
 * `"Sickboyrari (Black Kray)"` survive. [ARTIST_SUFFIX_PATTERN] has the boundaries.
 * With [fromTitle], the title's closing `!` or `?` comes off too ([stripTitleMark]).
 */
fun stripArtistSuffix(
    name: String,
    fromTitle: Boolean = false
): String {
    // One suffix can hide another (`Lacrimosa mit Orchester - … in Europa!`), so strip until stable.
    // The separator comes off first as well as last: `Tweaken – live –` hides its format word
    // behind a dash (#301).
    // A footnote star (`Sinkane live*`) comes off first, or it hides the `live` in front of it (#1845).
    // A soft hyphen is invisible on the page but not in a stored name: PAS bills `Jean-\u00ADLuc` (#3752).
    var stripped =
        stripTrailingSeparator(
            name
                .replace(SOFT_HYPHEN, "")
                .trim()
                .replace(AFFILIATION_OPENER_INSIDE, "")
                .replace(FOOTNOTE_STAR, "")
        )
    repeat(MAX_SUFFIX_PASSES) {
        val suffixless = stripped.replace(ARTIST_SUFFIX_PATTERN, "").replace(BACKING_BAND_TAIL, "").trim()
        val next = if (fromTitle) stripTitleMark(suffixless) else suffixless
        if (next == stripped || next.isBlank()) return@repeat
        stripped = next
    }
    val untailed = stripPlaysTail(stripWorkTitle(stripPlaceTail(stripShoutedTourTail(stripTrailingParenthetical(stripped)))))
    return stripCasedTail(stripGenitiveShow(stripTrailingSeparator(untailed)))
}

private const val SOFT_HYPHEN = "\u00AD"

/** A `!` or `?` glued to the last word, which a title closes on: `SING WITH FAUXPAS & MC UNCUT GEM!` (#3642). */
private val TITLE_MARK = Regex("""(?<=[\p{L}\p{N}])[!?]+$""")

/** Acts whose own name ends in `!`, each a MusicBrainz `EXACT` row, compared in lower case. */
private val BANG_NAMED_ACTS = setOf("agatha is dead!", "cc:disco!", "ludique!", "nein danke!", "wham!")

/**
 * [name] without the title's closing `!` or `?`. A name in [BANG_NAMED_ACTS] keeps it, and so does a Spanish
 * `¡…!` pair. A line-up names the act itself, so only a title-derived name comes here.
 */
fun stripTitleMark(name: String): String = if (name.lowercase() in BANG_NAMED_ACTS || '¡' in name || '¿' in name) name else name.replace(TITLE_MARK, "")

/**
 * An unnamed backing band after the act: `Matze Rossi & Band`, `KLAUS IGNATZEK und BAND`, `JACK KAYS AND HIS BAND` (#3325).
 * A `Solo` in front of it goes too. `the` is no possessive, so `Ann & the Band` names a band and stays.
 */
private val BACKING_BAND_TAIL =
    Regex("""(?<=\S)(?:\s+solo)?\s+(?:&|und|and)\s+(?:(?:his|her|seine|seiner|ihre|ihrer)\s+)?band\s*$""", RegexOption.IGNORE_CASE)

/**
 * Tails no separator marks, where the casing change alone shows where the act ends (#1841). Case-sensitive
 * on purpose, and each shape needs a closing word as well: a debut at a venue, a tour word, or a year.
 * Each matched one production title of 3303.
 */
private val CASED_TAILS: List<Regex> =
    listOf(
        // `Zascha HOT MESS Debut at Lark`: the shouted words are the show, the debut its venue.
        Regex("""^(.*?\p{Ll}\S*)(?:\s+[\p{Lu}\d]{2,})*\s+[Dd]ebut\s+at\s+\S.*$"""),
        // `WISBORG Phantomschmerz Tour`: a shouted act before a tour name in mixed case.
        Regex("""^([\p{Lu}\d]{2,}(?:\s+[\p{Lu}\d]{2,})*)\s+\p{Lu}\p{Ll}.*[Tt]our$"""),
        // `YASUHARU TAKANASHI TOUR`: a shouted act and a bare tour word (#2709).
        Regex("""^(\P{Ll}*\p{Lu}\P{Ll}*?)\s+TOUR$"""),
        // `schluma Heulen am Wasser 2026`: a lowercase act before a capitalised work title and its year.
        Regex("""^(\p{Ll}[\p{Ll}\d]+)\s+\p{Lu}\p{Ll}.*\s(?:19|20)\d{2}$"""),
        // `Exofa - Altern.RockPop`: a genre tag abbreviated with a dot inside one word.
        Regex("""^(.+?)\s+[-–—]\s+\p{L}+\p{Ll}\.\p{Lu}\p{L}+$""")
    )

/** A denylisted whole title (`THE REVIVAL TOUR`, a package tour) is left whole, so the denylist still drops it. */
private fun stripCasedTail(name: String): String =
    if (isDenylistedNonArtist(name)) name else CASED_TAILS.firstNotNullOfOrNull { it.matchEntire(name)?.groupValues?.get(1) } ?: name

/** A footnote star after the last word: `Sweely live*`. A star inside a name (`*n8`) stays. */
private val FOOTNOTE_STAR = Regex("""(?<=\S)\*+\s*$""")

/**
 * A show named in the German genitive after its act, without the apostrophe: `SIDOS
 * WEIHNACHTSSHOW 2026` is Sido's Christmas show (#1841). The compound `…show` word and its year
 * go, and so does the genitive `s`. The year is required: without it `Ship Happens Aftershow`
 * reads the same way. A plain `Show` word is not enough either (`Harry Styles Show 2026`).
 */
private val GENITIVE_SHOW = Regex("""^(.*\p{L}{2})s\s+\p{L}{3,}show\s+(?:19|20)\d{2}$""", RegexOption.IGNORE_CASE)

private fun stripGenitiveShow(name: String): String = GENITIVE_SHOW.matchEntire(name)?.groupValues?.get(1) ?: name

/**
 * Every ISO 3166 alpha-2 and alpha-3 country code, plus `UK`, for an origin tag the venue did not
 * write in upper case (`(Est)`, `(jp)`, #1761). A list, not a letter-count rule, so a short alias
 * (`(Chi)`) stays.
 */
private val ISO_COUNTRY_CODES: String =
    (Locale.getISOCountries() + Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA3) + "UK")
        .joinToString("|") { it.lowercase() }

/**
 * A trailing origin tag: two- or three-letter country codes (`(NL)`, `(PL/USA)`), a genre in front
 * of them (`(Dark Wave US/DE)`), or a spelled-out country with an optional `Live` (`(Thailand-Live)`)
 * (#314), or a note naming where the act is from (`(Hip Hop aus Australien)`, #3827). Lifted from a venue's local rule
 * and widened to the spelled-out form.
 */
private val ORIGIN_TAG =
    Regex(
        // Any two or three capitals count, and only then: `(An toi)` is an alias, not Antigua. The
        // spelled-out names and the ISO list match in any case.
        """\s*\((?:[^()]*?\s)?[A-Z]{2,3}(?:\s*[/,+&-]\s*[A-Z]{2,3})*\)\s*$""" +
            """|(?i:\s*\((?:$COUNTRY_NAMES)(?:\s*[-–—/]?\s*live)?\)\s*$)""" +
            """|(?i:\s*\([^()]*?\b(?:aus|from)\s+(?:$COUNTRY_NAMES)\)\s*$)""" +
            """|(?i:\s*\((?:$ISO_COUNTRY_CODES)(?:\s*[/,+&-]\s*(?:$ISO_COUNTRY_CODES))*\)\s*$)"""
    )

/**
 * A trailing tag in square brackets: a country (`Endica [Esp]`) or a format (`Ohnmacht [Live]`)
 * (#2078). Keyed on the content, because a bracket can be the name itself (`Ø [Phase]`).
 */
private val SQUARE_TAG =
    Regex(
        """(?i:\s*\[\s*(?:$ISO_COUNTRY_CODES|$COUNTRY_NAMES|(?:hybrid\s+)?live(?:\s+(?:set|band))?|dj[\s-]?set)\s*]\s*$)"""
    )

/**
 * A trailing band affiliation, which is never an alias: a comma or slash list (`(WIRE, IMMERSION)`,
 * `(Bauhaus / Love & Rockets)`) or an opener (`(ex-EINSTÜRZENDE NEUBAUTEN, …)`, `(von Spandau
 * Ballet)`) (#1561, #1761). A single bare name in parentheses (`(PENETRATION)`, `(Black Kray)`) is
 * undecidable between the two and stays.
 */
private val AFFILIATION_TAG =
    Regex("""\s*\((?:$AFFILIATION_OPENER[^()]*|[^(),]+,[^()]*|[^()/]+/[^()]*)\)\s*$""", RegexOption.IGNORE_CASE)

/** A trailing alias, which a venue that bills the alias too lists twice: `CFL (AKA KRUMELUR)`, `Foo (a.k.a. Bar)`. */
private val ALIAS_TAG = Regex("""\s*\((?:aka|a\.k\.a\.?)(?:\s|(?<=\.))[^()]*\)\s*$""", RegexOption.IGNORE_CASE)

/**
 * A Discogs or Resident Advisor number that tells same-named acts apart: `Emma (8)`, `SiSi (2)`. One page has no
 * room for it, so same-named acts share a row. Four-digit years and a name that is only the bracket stay.
 */
private val NUMBER_DISAMBIGUATOR = Regex("""(?<=\S)\s*\(\d{1,2}\)\s*$""")

/** The words that open an affiliation: `ex-`, `von`, `of`, `formerly`. */
private const val AFFILIATION_OPENER = """(?:ex-|(?:ex|von|of|formerly)\s)"""

/** An opener affiliation before the rest of a billing: `Steve Norman (von Spandau Ballet) & The Sleevz`. */
private val AFFILIATION_OPENER_INSIDE = Regex("""\s*\($AFFILIATION_OPENER[^()]*\)(?=\s)""", RegexOption.IGNORE_CASE)

/**
 * A bracket the page opened and never closed, at the end of a name: `Sean Steinfeger (OHSHITF*CKYES`.
 * A `(` right after a `)` is part of a stylised name, so `Vibrant as Fu)(K` stays whole (#3748).
 */
private val UNCLOSED_TRAILING_BRACKET = Regex("""(?<!\))\s*\([^()]*$""")

/**
 * Drops an [ORIGIN_TAG], a [SQUARE_TAG], an [AFFILIATION_TAG], an [ALIAS_TAG], a [NUMBER_DISAMBIGUATOR] or an
 * [UNCLOSED_TRAILING_BRACKET] from the end of a name, keeping the input when nothing else is left. Repeated, because one can hide the other
 * (`Sylk (DE) (Malör Records, Surge)`).
 */
private fun stripTrailingParenthetical(name: String): String {
    var stripped = name
    repeat(MAX_SUFFIX_PASSES) {
        val next =
            stripped
                .replace(ORIGIN_TAG, "")
                .replace(SQUARE_TAG, "")
                .replace(AFFILIATION_TAG, "")
                .replace(ALIAS_TAG, "")
                .replace(NUMBER_DISAMBIGUATOR, "")
                .replace(UNCLOSED_TRAILING_BRACKET, "")
                .trim()
        if (next == stripped || next.isBlank()) return@repeat
        stripped = next
    }
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
private val PRESENTING_HEAD = Regex("""\b(?:$PRESENTS_VERBS|pres)[.:]?$""", RegexOption.IGNORE_CASE)

/**
 * Strips a work title glued to an act with ` - ` or `: ` — the album played live, the project, the
 * show — so the performer remains (#1585): `Transllusion - The Opening of the Cerebral Gate` →
 * `Transllusion`, `Jon Rose: Hinterland!` → `Jon Rose`.
 *
 * A tail counts as a work title when it ends in `!`, is one [QUOTED_SPAN] (`Adam Goodwin: “Grumsin”`,
 * #3211), or runs to [MIN_WORK_TITLE_WORDS] words in title case (every word capitalised bar the
 * [TITLE_CASE_SMALL_WORDS]). The head must be an act,
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
    return if (headIsAct && (readsAsWorkTitle(tail) || echoesHead(tail, head))) head else name
}

/** Whether [tail] repeats a word of four letters or more from [head]: `Potsa Lotsa XL - Pizza Lotsa` names its show. */
private fun echoesHead(
    tail: String,
    head: String
): Boolean {
    val headWords = head.lowercase().split(WHITESPACE).toSet()
    return tail.lowercase().split(WHITESPACE).any { it.length >= MIN_ECHO_LETTERS && it in headWords }
}

/** The shortest word [echoesHead] counts; a shorter one (`the`, `DJ`) repeats by chance. */
private const val MIN_ECHO_LETTERS = 4

private fun readsAsWorkTitle(tail: String): Boolean {
    val words = tail.split(WHITESPACE).filter { it.isNotBlank() }
    val titleCased =
        words.size >= MIN_WORK_TITLE_WORDS &&
            words.first().first().isUpperCase() &&
            words.all { word -> word.first().isUpperCase() || word.lowercase() in TITLE_CASE_SMALL_WORDS }
    return words.isNotEmpty() && (tail.endsWith('!') || titleCased || QUOTED_SPAN.matches(tail))
}

/** A whole tail in one pair of straight, German or curly quotes: `"Zebrastreifen"`, `„Hurra“`, `“Grumsin”`. */
private val QUOTED_SPAN = Regex("""["„“”][^"„“”]+["“”]""")

/** A spaced dash before exactly one place from [COUNTRY_NAMES], in any case: `Dead Phoenix - BERLIN` (#3211). */
private val PLACE_TAIL = Regex("""^(.*\S)\s+[-–—]\s+(?i:$COUNTRY_NAMES)$""")

/** Drops a [PLACE_TAIL]. A place without the dash belongs to the name (`Isolation Berlin`). */
private fun stripPlaceTail(name: String): String = PLACE_TAIL.matchEntire(name)?.groupValues?.get(1) ?: name

/**
 * `<act> plays <Name>` or `spielt`: the ensemble and the music it plays (#3211). A lowercase descriptor after a
 * dash goes with it (`YOK - pocketpunk spielt FUCK 10 CHECK`). Case-sensitive, and a capital must follow, so a
 * lowercase `plays` phrase inside a name stays.
 */
private val PLAYS_TAIL = Regex("""^(.*?\S)(?:\s+[-–—]\s+\p{Ll}[\p{L}-]*)?\s+(?:plays|spielt)\s+\p{Lu}.*$""")

/** Drops a [PLAYS_TAIL]: `Lésions Graves Sextet plays Julius Eastman` → `Lésions Graves Sextet`. */
private fun stripPlaysTail(name: String): String = PLAYS_TAIL.matchEntire(name)?.groupValues?.get(1) ?: name

/** Countries and cities a venue writes after an act in full, in the two languages the pages use. */
internal const val COUNTRY_NAMES =
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
internal val DASH_SEPARATOR = Regex("""\s[-–—]\s""")

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
