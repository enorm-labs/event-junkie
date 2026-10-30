@file:Suppress("TooManyFunctions") // Cohesive collection of small, single-purpose artist-name mapping utilities.

package de.norm.events.scraper

import de.norm.events.genretag.isGenreLabel
import java.text.Normalizer

// One billing string into its acts: back-to-back, conjunction, comma and cross-bill splits.
// Part of the artist-name rules in ArtistNameMapping.kt.

/**
 * Single acts whose name contains a conjunction [splitHeadlinerTitle] would read as a co-bill,
 * matched case-insensitively against the whole title. `AC/DC` needs no entry, being protected
 * by space-padding; this list is for the `" & "` / `" and "` / `" und "` / `" meets "` cases. Entries are in
 * `&` form and the title's conjunctions are normalized to `&` first.
 *
 * **Name by name, because no source carries the signal.** Klunkerkranich bills a duo and a pair
 * of residents identically — one line in the event page's lineup block, one `&` slot in the
 * title — so nothing on the page separates `Überhaupt & Außerdem` from `Ilo Pan & Elmo Lewis`
 * (#1760). This list is the standing answer, and it does not generalise by design.
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
        "scala & kolacny brothers",
        "chase & status",
        "überhaupt & außerdem",
        "haute & freddy",
        "2mädchen & uwe",
        // Bands SO36 bills as the whole title, beside real co-bills like `MASTER BOOT RECORD & FULCI` (#2414).
        "pöbel & gesocks",
        "tito & tarantula",
        "booze & glory",
        "kai & funky von ton steine scherben",
        // A duo that releases under the ` x ` join, so [splitCrossBilled] must leave it whole (#2365).
        "noah x petter",
        // A duo named for its two members, Tobi Stock and Manfred Pankow (Speakeazy).
        "stock & pankow",
        // The folk-blues duo Issy Ferris and Archie Sylvester, billed by Monarch as its whole title (#2952).
        "ferris & sylvester",
        // A German metal band Neue Zukunft bills as the support after a `+` (#2958).
        "prophet & flesh",
        // Duos billed as their whole title (#3159): Heimathafen, Festsaal Kreuzberg, Privatclub, Frannz Club, Columbia Theater.
        "dicht & ergreifend",
        "dennis & jesko band",
        "anchors & hearts",
        "pauken & planeten",
        "maël & jonas",
        // The Persian pop duo Uber Eats Music Hall co-bills with Andy as `Andy, Kamran & Hooman` (event 11866).
        "kamran & hooman",
        "nie & nimmer",
        // A project whose name holds a padded `meets`, so [splitOnMeets] must leave it whole (#3573).
        "where the river meets the sea",
        // Bands whose name is a comma list, which [commaListActs] would cut into three (#3419).
        "kitty, daisy & lewis",
        "frost, pisse & elend",
        // A Warsaw band Morphine Raum bills as `Mitch & Mitch con il loro Gruppo Etereofonico` (#3565).
        "mitch & mitch",
        // Duos BKA Theater bills as the act before the colon, beside two-act headings like `Tim Becker & Zauberer Umeno` (#3584).
        "simon & jan",
        "jacky & mahoni",
        "evers & eichhorn"
    )

/**
 * Leading words marking the right-hand side of a conjunction as a band-name tail, unioned in
 * [CONJUNCTION_TAIL_MARKERS]: articles/possessives opening a backing band ("X & the Ys", "X
 * and his Ys", "X und die Ys", "X und seine Freunde"), and collectives naming an unnamed cast
 * ("X & Friends", "X & Guests", "X & Gäste", "X & Band", "Lacrimosa mit Orchester"). The same set
 * keeps a [WITH_FRAME_PATTERN] tail from being read as the acts.
 */
private val CONJUNCTION_TAIL_ARTICLES: Set<String> =
    setOf("the", "his", "her", "their", "seine", "seinen", "ihre", "ihren", "los", "las", "die", "der", "das", "el", "la")

private val CONJUNCTION_TAIL_COLLECTIVES: Set<String> =
    setOf("friends", "guests", "gäste", "freunde", "band", "orchester", "orchestra", "ensemble", "chor", "choir")

/** Right-hand-side opener words that keep a conjunction boundary joined — see the two source sets. */
internal val CONJUNCTION_TAIL_MARKERS: Set<String> = CONJUNCTION_TAIL_ARTICLES + CONJUNCTION_TAIL_COLLECTIVES

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
internal val CONJUNCTION_SEPARATOR = Regex("""\s+(?:&|and|und)\s+|(?<=\S{2})\s+e\s+(?=\S{2})""", RegexOption.IGNORE_CASE)

/**
 * A segment that ends like a sentence and carries a function word is billing prose (`Einzige und
 * Exklusive Orchester-Show in Europa!`); no conjunction inside it delimits acts. Both halves are
 * needed: `ALL ABOUT BIRDS & JON ROSE: HINTERLAND!` ends in `!` and is still a co-bill.
 */
private val PROSE_END = Regex("""\s(?:in|im|mit|für|von|of|the|for|to|at|on|aus|bei)\s.*[!?]\s*$""", RegexOption.IGNORE_CASE)

/**
 * [KNOWN_SINGLE_ACTS] without accents: venues spell `Maël & Jonas` as `Maēl`, `Mael` or `Maël`
 * (event 2386). Lazy because [COMBINING_MARKS] is declared further down the file.
 */
private val KNOWN_SINGLE_ACTS_FOLDED: Set<String> by lazy { KNOWN_SINGLE_ACTS.mapTo(HashSet(), ::withoutAccents) }

/**
 * True when [name] is a [KNOWN_SINGLE_ACTS] entry, conjunctions normalized to `&` and accents
 * stripped first. Checked at segment level, so `"BLOOD & SUN + SOCIETY OF THE SILVER CROSS"`
 * splits at the `+` into two acts, and "Support: Simon & Garfunkel" is protected. Public because a
 * venue parser with its own act separator asks the same question before splitting (Klunkerkranich, #1760).
 */
fun isKnownSingleAct(name: String): Boolean = withoutAccents(name.trim().replace(CONJUNCTION_SEPARATOR, " & ").lowercase()) in KNOWN_SINGLE_ACTS_FOLDED

private fun withoutAccents(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")

/**
 * The back-to-back marker joining two DJs into one slot. Padding keeps it off a name that merely
 * contains the letters.
 */
val B2B_SEPARATOR = Regex("""\s+b2b\s+""", RegexOption.IGNORE_CASE)

/** A `b2b` token anywhere in a line, a leading or trailing one included. Jsoup turns `&nbsp;` into U+00A0, so that pads it too. */
private val B2B_TOKEN = Regex("""(?<![^\s ])b2b(?![^\s ])""", RegexOption.IGNORE_CASE)

/** A bracket that ends the line and lists labels or an origin: `(GreyNote, hng.picnics /D)`, `(Recycle/D)`. */
private val TRAILING_LABEL_BRACKET = Regex("""\s*\([^()]*[/,][^()]*\)\s*$""")

/**
 * Splits a lineup line at each `b2b` outside a bracket, because back-to-back is never one act
 * (#1759, #1844). A `b2b` inside a bracket names a duo's members and keeps the line whole
 * (`Double Penetration (FLOWWW b2b Joe Cleen)`). A label bracket that ends a split line describes
 * the pair, not its last DJ, so it comes off. A stray marker at either end (`B2B Alina Matini`) leaves one act.
 */
fun splitBackToBack(line: String): List<String> {
    val outsideBrackets = maskBracketed(line)
    val markers = B2B_TOKEN.findAll(outsideBrackets).map { it.range }.toList()
    if (markers.isEmpty()) return listOf(line)
    val bounds = listOf(-1) + markers.flatMap { listOf(it.first, it.last) } + line.length
    return bounds
        .chunked(2)
        .map { (end, start) -> line.substring(end + 1, start) }
        .mapIndexed { index, part -> if (index == markers.size) part.replace(TRAILING_LABEL_BRACKET, "") else part }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .ifEmpty { listOf(line) }
}

/** [splitBackToBack] on one billing: each DJ of a `b2b` slot keeps the slot's role, stage and set times. */
fun splitBackToBack(artist: ScrapedArtist): List<ScrapedArtist> = splitBackToBack(artist.name).map { artist.copy(name = it) }

/** A run of pictographic emoji, with the variation selector and zero-width joiner that compose one. */
private val EMOJI_RUN = Regex("""[\p{IsExtended_Pictographic}\x{FE0F}\x{200D}]+""")

/**
 * Splits a line at every run of emoji, because a venue that writes its roster as
 * `🔥 Kopf & Hörer 🔥 🎤 Dr. Sheppat ⚡ EVOX ⚡` bills one act between each pair (#3038). An emoji
 * that only wraps one name leaves it whole. A line that is nothing but emoji stays as it is.
 */
fun splitOnEmoji(line: String): List<String> =
    line
        .split(EMOJI_RUN)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .ifEmpty { listOf(line) }

/** [splitOnEmoji] on one billing: each act keeps the slot's role, stage and set times. */
fun splitOnEmoji(artist: ScrapedArtist): List<ScrapedArtist> = splitOnEmoji(artist.name).map { artist.copy(name = it) }

/** [line] with every character inside a round bracket replaced, so a match in it is outside any. */
private fun maskBracketed(line: String): String {
    var depth = 0
    return buildString {
        line.forEach { char ->
            if (char == ')' && depth > 0) depth--
            append(if (depth > 0) '_' else char)
            if (char == '(') depth++
        }
    }
}

/**
 * Splits a title segment into acts at a ` x ` join ([splitCrossBilled]) and then at its
 * conjunctions, per boundary, so a real co-bill still splits beside a band-name tail: a one-comma list `A, B & C` is three acts
 * ([commaListActs]), and any other comma keeps the segment whole,
 * and a boundary whose right-hand side opens with a [tail marker][CONJUNCTION_TAIL_MARKERS]
 * stays joined, so `CARL CARLTON & MELANIE WIEGMANN AND THE GREAT BAND` cuts only at the `&`.
 * Never on `/` or `+`, so a venue can pre-split its co-bills and hand each segment here;
 * [splitSupportActs] and [splitHeadlinerTitle] apply it after their hard-separator split.
 */
fun splitSegmentOnConjunctions(segment: String): List<String> = splitCrossBilled(segment).flatMap(::splitOnConjunctionsOnly)

/**
 * The ` x ` two acts are co-billed with: `Hatebreed x Life Of Agony`, `Fhionn x Cathal` (#2365).
 * Both sides need two characters, and the right side must open with a letter or a digit, so
 * `Sadat X & Grand Agent` and `Symphony X + …` keep the `X` that ends a name.
 */
private val CROSS_BILL_SEPARATOR = Regex("""(?<=\S{2})\s+x\s+(?=[\p{L}\p{N}]{2})""", RegexOption.IGNORE_CASE)

/** Words that cannot open the second act of a ` x ` join, because they continue the first: `Sadat X and Grand Agent`. */
private val CROSS_BILL_CONTINUATIONS: Set<String> = setOf("and", "und", "feat.", "feat", "ft.", "featuring", "with", "mit")

/**
 * Splits [segment] at each [CROSS_BILL_SEPARATOR] outside brackets, leaving a [KNOWN_SINGLE_ACTS]
 * duo that releases under the join whole (`Noah X Petter`). A co-brand of a series is not split
 * here: [headlinersFromTitle] drops it first ([isCoBrandedSeriesTitle]). Public because a venue
 * parser that reads its line-up line by line asks the same question (Gretchen).
 */
fun splitCrossBilled(segment: String): List<String> {
    if (isKnownSingleAct(segment)) return listOf(segment)
    val cuts =
        CROSS_BILL_SEPARATOR
            .findAll(segment)
            .filter { !isInsideBrackets(segment, it.range.first) }
            .filter { match ->
                segment
                    .substring(match.range.last + 1)
                    .substringBefore(' ')
                    .lowercase() !in CROSS_BILL_CONTINUATIONS
            }.map { it.range }
            .toList()
    if (cuts.isEmpty()) return listOf(segment)
    return cutAt(segment, cuts).map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf(segment) }
}

private fun splitOnConjunctionsOnly(segment: String): List<String> {
    if (isKnownSingleAct(segment)) return listOf(segment)
    // `Einzige und Exklusive Orchester-Show in Europa!` is billing prose, not two acts (#1580).
    if (PROSE_END.containsMatchIn(segment)) return listOf(segment)
    if (segment.contains(',')) {
        val oneCommaList = if (topLevelCommas(segment).size == 1) commaListActs(segment) else null
        return oneCommaList ?: listOf(segment)
    }
    return cutAtConjunctions(segment)
}

/**
 * The acts of a comma list whose last part holds a conjunction, `A, B & C`, or `null` when [line] is not one (#3419).
 * Each part is cut at its conjunctions by the per-boundary rules, so `A, B & The Cs` cuts at the comma only.
 * A [KNOWN_SINGLE_ACTS] entry such as `Kitty, Daisy & Lewis` stays whole.
 */
fun commaListActs(line: String): List<String>? {
    if (isKnownSingleAct(line)) return listOf(line)
    val commas = topLevelCommas(line)
    val parts = cutAt(line, commas.map { it..it }).map { it.trim() }.filter { it.isNotEmpty() }
    val last = parts.lastOrNull() ?: return null
    val endsInConjunction = CONJUNCTION_SEPARATOR.findAll(last).any { !isInsideBrackets(last, it.range.first) }
    if (commas.isEmpty() || !endsInConjunction) return null
    return parts.flatMap { if (isKnownSingleAct(it)) listOf(it) else cutAtConjunctions(it) }.map { it.trim() }
}

/** [segment] cut at each conjunction outside brackets whose right-hand side is no tail marker and no bare number. */
private fun cutAtConjunctions(segment: String): List<String> {
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

/**
 * Two commas make a list; one does not (#1789). `Hey, Nothing`, `Wracaj, bociemno` and
 * `Yes, I’m Very Tired Now` are all real acts with one comma, and `D-Block Europe, French Montana`
 * is a bill with one, so a single comma decides nothing. A conjunction after it does: see
 * [commaListActs]. No act in the corpus carries two.
 */
private const val MIN_COMMAS_FOR_A_LIST = 2

/**
 * The positions of the commas that separate acts: those outside any bracket. A comma inside one
 * belongs to that act's own affiliation, as in `New Candys (It, Fuzz Club)`, where both commas are
 * parenthetical and the bill is delimited by something else entirely.
 */
private fun topLevelCommas(text: String): List<Int> = text.indices.filter { text[it] == ',' && !isInsideBrackets(text, it) }

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

/**
 * A space-padded bullet, `•` or `·`. Almost never a separator: eight of the nine top-level
 * occurrences on production are a night's name and its strapline (`THE EARLY DAYS • THROWBACK INDIE
 * PARTY`, `Call Me Maybe! • 2000s & 2010s – Pop Party`), and splitting on the character alone would
 * mint the strapline as an act. [bulletCoBill] has the one shape that is a bill.
 */
private val BULLET_SEPARATOR = Regex("""\s+[•·]\s+""")

/** A closing annotation ending a segment: the origin or affiliation tag [stripArtistSuffix] removes. */
private val TRAILING_ANNOTATION = Regex("""\([^()]*\)\s*$""")

/**
 * The acts of a bullet-separated bill, or `null` when [title] is not one (#1818).
 *
 * A bill is recognised by every segment carrying its own trailing annotation, which is the venue
 * writing `<act> (<country>, <label>)` once per act: Urban Spree's `New Candys (IT, Fuzz Club) •
 * BLKE (DE, Tonzonen)` was stored as a single 46-character artist. A night's strapline never
 * carries one, so `June Cocó • Berlin (Kulturhaus Insel) • EP Release Show` stays whole on its last
 * segment — the near miss, and the reason the annotation is required on *every* segment rather than
 * on any. One of the nine top-level bullet titles on production matches.
 *
 * Bracket-aware like the comma and the hard separators: Migas writes a record's running time as
 * `(Ostgut Ton, 2019 • 43 min • vinyl)`, where the bullets are the label's own punctuation.
 */
private fun bulletCoBill(title: String): List<String>? {
    val cuts =
        BULLET_SEPARATOR
            .findAll(title)
            .filter { !isInsideBrackets(title, it.range.first) }
            .map { it.range }
            .toList()
    if (cuts.isEmpty()) return null
    val segments = cutAt(title, cuts).map { it.trim() }.filter { it.isNotBlank() }
    return segments.takeIf { it.size > 1 && it.all { segment -> TRAILING_ANNOTATION.containsMatchIn(segment) } }
}

/**
 * The typographic characters a venue's title and its own blurb spell differently: curly quotes and
 * the dashes. `Yes, I’m Very Tired Now` is billed with `\u2019` and described with `'`.
 */
private val TYPOGRAPHIC_FORMS =
    mapOf('\u2019' to '\'', '\u2018' to '\'', '\u02bc' to '\'', '\u201c' to '"', '\u201d' to '"', '\u2013' to '-', '\u2014' to '-', '\u2212' to '-')

private val COMBINING_MARKS = Regex("""\p{M}+""")

/** Everything a phrase drops: punctuation, but never a space and never the `&` that joins a name. */
private val NOT_PHRASE_CHARACTER = Regex("""[^\p{L}\p{N}&\s]""")

/**
 * The comparison form of a line of prose or a title: accents stripped, typographic forms folded,
 * lower case, punctuation dropped **without joining the words it sat between**, whitespace
 * collapsed.
 *
 * **Deliberately not [MusicBrainzMatcher.fold][de.norm.events.musicbrainz.MusicBrainzMatcher.fold]**,
 * which maps `and`/`und`/`&` onto one token. Under that fold a description reading `D-Block Europe
 * und French Montana` is the same string as the title `D-Block Europe, French Montana`, so
 * [corroboratedActs] reads the bill as one act's own name and keeps it fused — measured, and the
 * reason this fold exists separately (#1832). Keeping the conjunction keeps `A, B` and `A und B`
 * apart, while dropping the comma and the apostrophe makes `Yes, I’m Very Tired Now` and a blurb's
 * `Yes I'm Very Tired Now` one string, which is what protects the band.
 */
private fun phraseForm(text: String): String =
    Normalizer
        .normalize(text.map { TYPOGRAPHIC_FORMS[it] ?: it }.joinToString(""), Normalizer.Form.NFKD)
        .replace(COMBINING_MARKS, "")
        .lowercase()
        .replace(NOT_PHRASE_CHARACTER, "")
        .replace(WHITESPACE, " ")
        .trim()

/** Whether [term] stands in [descriptionPhrase] as a whole word, both already in [phraseForm]. */
private fun namedIn(
    descriptionPhrase: String,
    term: String
): Boolean {
    val wanted = phraseForm(term)
    return wanted.isNotBlank() &&
        Regex("""(?<![\p{L}\p{N}])${Regex.escape(wanted)}(?![\p{L}\p{N}])""").containsMatchIn(descriptionPhrase)
}

/** How many capitalised words an act's name may gain from the description. */
private const val MAX_EXTENSION_WORDS = 3

/**
 * Spaces within one line. A name never runs across a line break, and a blurb that puts the night's
 * instrumentation on the line under its bill would otherwise hand a greedy run an act that is not
 * one: Madame Claude's reads `Elshan Ghasimi, Carla Boregas & Joss Turnbull\nSoundart, classical
 * iranien, …`.
 */
private const val SAME_LINE_GAP = """[ \t]+"""

/** A capitalised word, as the tail of a name the description spells out more fully. */
private const val CAPITALISED_WORD = """\p{Lu}[\p{L}\p{N}'\u2019.-]*"""

/**
 * [segment] as the description spells it: the longest run of up to [MAX_EXTENSION_WORDS]
 * capitalised words that begins with it, or [segment] unchanged when the description adds nothing.
 *
 * This is what makes the description worth reading rather than the title worth splitting. Hole 44
 * bills `Myki, Darlene & Nini` and describes `Myki Meeks, Darlene Mitchell, and Nini Coco`, so
 * every act in that title is a short form and no rule over the string can reach the real name.
 */
private fun extendFromDescription(
    description: String,
    segment: String
): String =
    Regex("""${Regex.escape(segment)}(?:$SAME_LINE_GAP$CAPITALISED_WORD){1,$MAX_EXTENSION_WORDS}""")
        .findAll(description)
        .map { it.value.trim() }
        .maxByOrNull { it.length }
        ?: segment

/**
 * The acts a single-comma title bills, when the event's own [description] corroborates them, or
 * `null` to leave the title whole (#1832).
 *
 * A single top-level comma decides nothing by itself ([MIN_COMMAS_FOR_A_LIST]) — `Hey, Nothing` is
 * a band and `D-Block Europe, French Montana` is a bill. The description is the evidence the title
 * lacks, and it answers in two steps, both required:
 *
 * 1. **The whole title standing in the description as a phrase is a name**, not a bill, and is kept
 *    whole. This is what `Kitty, Daisy & Lewis` and `Yes, I’m Very Tired Now` rest on.
 * 2. **Otherwise every candidate act must be named in it** as a whole word. One segment the blurb
 *    never mentions makes the split a guess, and the title is kept.
 *
 * Whatever the description does not decide is left alone: no description, or a title whose segments
 * it does not all name, keeps today's answer exactly.
 */
private fun corroboratedActs(
    title: String,
    candidates: List<String>,
    description: String?
): List<String>? {
    val descriptionPhrase = description?.takeIf { it.isNotBlank() }?.let(::phraseForm) ?: return null
    val titlePhrase = phraseForm(title)
    val corroborated =
        candidates.size >= MIN_CORROBORATED_ACTS &&
            !descriptionPhrase.contains(titlePhrase) &&
            candidates.all { namedIn(descriptionPhrase, it) } &&
            !conjoinedAlike(titlePhrase, descriptionPhrase, candidates)
    return candidates
        .takeIf { corroborated }
        ?.map { extendFromDescription(description, it) }
}

/** A bill is at least two acts; one segment is the title itself. */
private const val MIN_CORROBORATED_ACTS = 2

/** A conjunction as it stands between two names once both sides are in [phraseForm]. */
private const val JOINED_BY_CONJUNCTION = """\s+(?:&|and|und)\s+"""

/**
 * Whether two neighbouring [candidates] are joined by a conjunction in the title **and** in the
 * description alike, which makes them one act rather than two.
 *
 * Kantine am Berghain bills `Parlour Magic, Fee Aviv und Ghosts & Errors` and writes `GHOSTS &
 * ERRORS` throughout its blurb: the conjunction split inside that comma segment is wrong, and
 * nothing in the title says so. Both sides are required, because a description joining two acts
 * on its own proves the opposite — Uber Eats writes `D-Block Europe und French Montana` for a
 * bill its title separates with a comma. A pair joined in both places leaves the whole segment
 * to the ordinary rules rather than guessing which of the two readings to keep.
 */
private fun conjoinedAlike(
    titlePhrase: String,
    descriptionPhrase: String,
    candidates: List<String>
): Boolean =
    candidates.zipWithNext().any { (first, second) ->
        val joined =
            Regex(
                """(?<![\p{L}\p{N}])${Regex.escape(phraseForm(first))}$JOINED_BY_CONJUNCTION${Regex.escape(phraseForm(second))}(?![\p{L}\p{N}])"""
            )
        joined.containsMatchIn(titlePhrase) && joined.containsMatchIn(descriptionPhrase)
    }

/** Hard separators that always delimit acts in a support/lineup line: comma, plus, slash. */
private val SUPPORT_HARD_SEPARATOR = Regex("""\s*[,+/]\s*""")

/**
 * Splits a support line into act names: comma, `+` and `/` always delimit; `&` / `and` / `und`
 * per boundary via [splitSegmentOnConjunctions], so `Scott Hepple & The Sun Band` is one act and
 * `High On Fire & Gnome` two.
 *
 * A mid-name `feat.` / `featuring` / `ft.` bills a guest, as [FEATURED_GUEST_MARKER] reads it in a
 * title: `Bad Sound System feat. Fermin Muguruza` is two acts. `feat` needs its full stop here, so
 * `Little Feat & Friends` stays whole, and a marker inside a bracket is [splitBracketedGuest]'s.
 *
 * Each act loses a leading [ARTIST_LABEL_PREFIX]: a venue that prints its support line as
 * `Support: THE BAXBYS` bills the band, and thirteen Metropol rows were stored under the label
 * (#1678). The colon is what makes that safe — the band `Support Lesbiens` keeps its name.
 * Placeholder filtering stays the caller's.
 */
fun splitSupportActs(text: String): List<String> =
    text
        .split(SUPPORT_HARD_SEPARATOR)
        .flatMap { splitOnFeaturedGuest(it) }
        .flatMap { splitSegmentOnConjunctions(it) }
        .map { stripArtistPrefix(it.trim()) }
        .filter { it.isNotBlank() }

/** A lineup segment cut at each [LINEUP_FEATURED_GUEST] outside a bracket; the segment alone when there is none. */
private fun splitOnFeaturedGuest(segment: String): List<String> {
    val marker = LINEUP_FEATURED_GUEST.find(segment) ?: return listOf(segment)
    val head = segment.substring(0, marker.range.first)
    val insideBracket = head.count { it == '(' } != head.count { it == ')' }
    return if (insideBracket) listOf(segment) else listOf(head) + splitOnFeaturedGuest(segment.substring(marker.range.last + 1))
}

/** [FEATURED_GUEST_MARKER] with the full stop `feat.` needs in a lineup segment. */
private val LINEUP_FEATURED_GUEST = Regex("""\s+(?:feat\.|featuring|ft\.)\s+""", RegexOption.IGNORE_CASE)

/**
 * Splits a headliner title into co-billed acts (`TOTAL CHAOS + RUMKICKS + THE DOLLHEADS`,
 * `LAGWAGON / THE VIRGINMARYS`, `BLACK STAR RIDERS & TYKETTO`) on space-padded separators only,
 * which protects `AC/DC` and `dance/electronic`; `" & "` / `" and "` / `" und "` per boundary
 * via [splitSegmentOnConjunctions], never for a [KNOWN_SINGLE_ACTS] title; a `meets` co-bill via
 * [splitOnMeets]. A bullet delimits only
 * in the one shape [bulletCoBill] recognises. No separator returns the trimmed title alone. Every
 * case is asserted in `ArtistNameMappingTest`.
 *
 * @param splitOnSlash when false, `/` is not a separator, for venues that use it inside one act
 * name (Madame Claude's `Morimoto / Wong duo`).
 * @param description the event's own blurb, which alone can decide a single comma — see
 * [commaBillOf]. Null keeps the title's answer.
 */
fun splitHeadlinerTitle(
    title: String,
    splitOnSlash: Boolean = true,
    description: String? = null
): List<String> {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return listOf(title)
    // A whole title that is one denylisted act is kept intact before any separator split;
    // co-billed occurrences are protected per segment inside splitSegmentOnConjunctions.
    if (isKnownSingleAct(trimmed)) return listOf(trimmed)

    val commas = topLevelCommas(trimmed)
    if (commas.size >= MIN_COMMAS_FOR_A_LIST) {
        return cutAt(trimmed, commas.map { it..it })
            .flatMap { splitHeadlinerTitle(it.trim(), splitOnSlash) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(trimmed) }
    }

    bulletCoBill(trimmed)?.let { segments ->
        return segments
            .flatMap { splitHeadlinerTitle(it, splitOnSlash) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(trimmed) }
    }

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
            .flatMap(::splitOnMeets)
            .flatMap(::splitOnInvites)
            .flatMap { segment -> commaBillOf(segment, description) ?: splitSegmentOnConjunctions(segment) }
            .map { it.trim() }
            .filter { it.isNotBlank() }

    return acts.ifEmpty { listOf(trimmed) }
}

/** A space-padded `invites` between a host act and its guest: `Tony Hurdle Invites TC The 3RD`. */
private val INVITES_SEPARATOR = Regex("""\s+invites?\s+""", RegexOption.IGNORE_CASE)

/**
 * Splits [segment] at an [INVITES_SEPARATOR] outside brackets into host and guest. A one-word host is a venue or a
 * series (`Tresor invites Surgeon`), as with [splitOnMeets], and a name that ends in `invites` has no guest: both
 * stay whole.
 */
private fun splitOnInvites(segment: String): List<String> {
    val cuts =
        INVITES_SEPARATOR
            .findAll(segment)
            .filter { !isInsideBrackets(segment, it.range.first) }
            .map { it.range }
            .toList()
    val sides = cutAt(segment, cuts).map { it.trim() }
    val hostIsAct =
        sides
            .firstOrNull()
            .orEmpty()
            .split(WHITESPACE)
            .size >= 2
    return if (sides.size >= 2 && sides.none { it.isEmpty() } && hostIsAct) sides else listOf(segment)
}

/** A space-padded `meets` joining two co-billed acts: `Roots Daughters meets La Comadre Soundsystem` (#3159). */
private val MEETS_SEPARATOR = Regex("""\s+meets\s+""", RegexOption.IGNORE_CASE)

/**
 * Splits [segment] at a [MEETS_SEPARATOR] when both sides look like acts, or returns it whole.
 * A genre side is a style pairing (`Klassik meets Jazz`). A one-word left side is a venue, a series or a show
 * (`Tresor meets ENIGMA`, `FALCO MEETS AMADEUS`), and a spaced dash marks a tagline (`… - Piano meets Percussion`).
 */
private fun splitOnMeets(segment: String): List<String> {
    if (isKnownSingleAct(segment)) return listOf(segment)
    val cuts =
        MEETS_SEPARATOR
            .findAll(segment)
            .filter { !isInsideBrackets(segment, it.range.first) }
            .map { it.range }
            .toList()
    val sides = cutAt(segment, cuts).map { it.trim() }
    val coBill =
        sides.size == 2 &&
            sides.none { it.isEmpty() || isGenreLabel(it) || DASH_SEPARATOR.containsMatchIn(it) } &&
            sides.first().split(WHITESPACE).size >= 2
    return if (coBill) sides else listOf(segment)
}

/**
 * The acts of one hard-separated [segment] whose single comma the [description] corroborates, or
 * `null` to leave the segment to the ordinary rules (#1832).
 *
 * **Asked per segment, after the hard separators**, because a comma binds no tighter than a `+`.
 * Madame Claude bills `Elshan Ghasimi, Carla Boregas & Joss Turnbull + Orca Eroticae + …`, and only
 * the part before the first `+` is the comma's business; asking the description about the whole
 * title finds none of it and splits a name the blurb states outright. Aeden's `Pikante / rhythm,
 * spice and everything heiß` is the same shape behind a slash.
 */
private fun commaBillOf(
    segment: String,
    description: String?
): List<String>? {
    val trimmed = segment.trim()
    val commas = topLevelCommas(trimmed)
    if (description.isNullOrBlank() || commas.size != 1) return null
    val candidates =
        cutAt(trimmed, commas.map { it..it })
            .flatMap { splitSegmentOnConjunctions(it) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
    return corroboratedActs(trimmed, candidates, description) ?: listOf(trimmed).takeIf { readsAsOneName(trimmed, candidates, description) }
}

/**
 * Whether [description] reads the one-comma [title] as one name, which keeps it whole ahead of [commaListActs]: the blurb
 * states the whole title, or joins two of its acts with the title's own conjunction (`Ghosts & Errors`).
 */
private fun readsAsOneName(
    title: String,
    candidates: List<String>,
    description: String
): Boolean {
    val descriptionPhrase = phraseForm(description)
    val titlePhrase = phraseForm(title)
    return descriptionPhrase.contains(titlePhrase) || conjoinedAlike(titlePhrase, descriptionPhrase, candidates)
}
