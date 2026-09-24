package de.norm.events.genretag

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

// Genre normalization shared by the admin API and the scraper pipeline: free-text genre strings
// become canonical tag names for the frontend's filter, while the raw text stays on the event.

/**
 * Synonym mapping from a normalized lookup key to canonical tag names. Keys go through
 * [lookupKey], lowercased with every separator stripped, so `"hiphop"` matches "Hip Hop",
 * "hip-hop" and "HIPHOP"; keep new keys normalized. The map encodes only semantic merges string
 * normalization cannot derive ("rap"/"urban" to Hip Hop, "boogaloo" to Funk, "cumbia"/"salsa"
 * to Latin). Canonical names are title case.
 */
internal val GENRE_SYNONYMS: Map<String, String> =
    mapOf(
        // Hip Hop family
        "hiphop" to "Hip Hop",
        "germanhiphop" to "Hip Hop",
        "rap" to "Hip Hop",
        "deutschrap" to "Hip Hop",
        "urban" to "Hip Hop",
        "experimentalhiphop" to "Hip Hop",
        "trap" to "Hip Hop",
        // Rock family
        "rock" to "Rock",
        "alternativerock" to "Rock",
        // Word-level, so one venue's "Dreamy Kuschelrock" lands on Rock rather than on its own tag.
        "kuschelrock" to "Rock",
        "poprock" to "Rock",
        "bluesrock" to "Rock",
        "experimentalrock" to "Rock",
        "rawknroll" to "Rock",
        "garagernr" to "Garage-Rock",
        "kraut" to "Krautrock",
        "krautrock" to "Krautrock",
        "alternative" to "Alternative",
        "alternativeindie" to "Alternative",
        // Indie family
        "indie" to "Indie",
        "indiepop" to "Indie",
        "indierock" to "Indie",
        "shoegaze" to "Shoegaze",
        // Pop family
        "pop" to "Pop",
        // Huxleys emits de-slugified CSS classes, so "Kpop" arrives punctuation-free while another venue
        // writes "K-Pop"; [lookupKey] strips the hyphen, so both share this key.
        "kpop" to "K-Pop",
        "deutschpop" to "Pop",
        "altpop" to "Pop",
        "elektropop" to "Pop",
        "queerpop" to "Pop",
        "poppunk" to "Punk",
        "synthpop" to "Synthpop",
        "synthiepop" to "Synthpop",
        "synth" to "Synthpop",
        // Punk
        "punk" to "Punk",
        "punkrock" to "Punk",
        "emo" to "Emo",
        // Electropunk (folds the "Electropunk"/"Elektro-punk" spellings onto one tag)
        "electropunk" to "Electropunk",
        "elektropunk" to "Electropunk",
        // Metal / heavy
        "metal" to "Metal",
        "metalcore" to "Metalcore",
        "melodichardcore" to "Melodic-Hardcore",
        "hc" to "Hardcore",
        // Delimiter-less compound label some venues write concatenated (Wild at Heart)
        "stonerpsychedelicmetal" to "Metal",
        // Electronic family
        "electronic" to "Electronic",
        "electronica" to "Electronic",
        "elektrofusion" to "Electronic",
        "synthesizerinstrumental&melodicelectronica" to "Electronic",
        "techno" to "Techno",
        "house" to "House",
        "ambient" to "Ambient",
        "afrohouse" to "House",
        "latinhouse" to "House",
        // Drum & Bass (delimiter-embedding name; see DRUM_AND_BASS_REGEX pre-pass)
        "drumnbass" to "Drum & Bass",
        "dnb" to "Drum & Bass",
        // Post-punk / dark wave family
        "postpunk" to "Post-Punk",
        "newwave" to "New Wave",
        "darkwave" to "Darkwave",
        "ebm" to "EBM",
        "gothicrock" to "Gothic Rock",
        "goth" to "Gothic Rock",
        "gothic" to "Gothic Rock",
        // Soul / Funk / R&B family
        "soul" to "Soul",
        "neosoul" to "Soul",
        "indiesoul" to "Soul",
        "funk" to "Funk",
        "boogaloo" to "Funk",
        "r&b" to "R&B",
        "rnb" to "R&B",
        "altrnb" to "R&B",
        // Jazz / Blues
        "jazz" to "Jazz",
        "jazzfusion" to "Jazz",
        "latinjazz" to "Jazz",
        "blues" to "Blues",
        // Folk
        "folk" to "Folk",
        "americana" to "Americana",
        "singersongwriter" to "Singer-Songwriter",
        "singersongwriterin" to "Singer-Songwriter",
        // "Liedermaching" is the German Liedermacher scene. "Acoustic-Guitar-Hasen-Liedermaching" is a
        // single token, so word-level matching never sees the word alone.
        "liedermaching" to "Singer-Songwriter",
        "acousticguitarhasenliedermaching" to "Singer-Songwriter",
        // Reggae
        "reggae" to "Reggae",
        "reggea" to "Reggae",
        // Neue Deutsche Welle / Härte, German new-wave and industrial-metal. Multi-word, so the ≤2-word
        // looksLikeGenre gate would drop them. (lookupKey drops the ä, hence "neuedeutschehrte".)
        "ndw" to "NDW",
        "neuedeutschewelle" to "NDW",
        "neuedeutschehrte" to "Neue Deutsche Härte",
        // Tango
        "tango" to "Tango",
        // Latin family — the Latin-dance styles a club bills per floor (Havanna: "Salsa, Merengue &
        // Bachata", "Reggaeton, Latin-Pop") all fold onto the one Latin tag, following salsa/cumbia.
        "cumbia" to "Latin",
        "salsa" to "Latin",
        "latin" to "Latin",
        "latinroots" to "Latin",
        "latinpop" to "Latin",
        "reggaeton" to "Latin",
        "merengue" to "Latin",
        "bachata" to "Latin",
        "samba" to "Latin",
        "bossanova" to "Latin",
        // Afrobeats
        "afro" to "Afrobeats",
        "afrobeat" to "Afrobeats",
        "afrobeats" to "Afrobeats",
        // Classical — the venues' German label and the English one fold together.
        "klassik" to "Classical",
        "classical" to "Classical",
        // World Music
        "world" to "World Music",
        "worldmusic" to "World Music",
        "global" to "World Music",
        "indian" to "World Music",
        "urdurock" to "World Music",
        // Disco
        "disco" to "Disco",
        "discotunes" to "Disco",
        // Karaoke
        "karaoke" to "Karaoke",
        // Decades / party labels
        "80s" to "80s",
        "90s" to "90s",
        "2000s" to "2000s",
        // Mod
        "mod" to "Mod",
        // Oldschool / Newschool
        "oldschool" to "Old School",
        "newschool" to "New School"
    )

/**
 * Non-genre tokens some venues push into the genre field, event-format labels and series names,
 * which must never become a tag; Cassiopeia reuses the field for arbitrary labels ("Immersive
 * Ausstellung", "… Special"). Stored as [lookupKey] keys and matched per word
 * ([looksLikeGenre]), so `ausstellung` drops "Immersive Ausstellung" and "Ausstellung" alike. A
 * word here only vetoes a token with no recognised genre in it: "Retro Pop" still resolves to
 * Pop via the word-level match, which runs first. Keep tight; extend from the `Dropping
 * non-genre …` logs.
 */
private val NON_GENRE_TOKENS: Set<String> =
    setOf(
        // Event-format / listing labels
        "special",
        "immersive",
        "ausstellung",
        "exhibition",
        "vernissage",
        "lesung",
        "reading",
        "party",
        "festival",
        "show",
        "konzert",
        "concert",
        "presents",
        "present",
        "podcast",
        "markt",
        "support",
        "openair",
        "warmup",
        "aftershow",
        "release",
        "releaseparty",
        // Freeform fragments observed leaking as standalone tags
        "beyond",
        "wave",
        "retro",
        "nontango",
        // Descriptors a venue lists alongside real genres ("Funk, Soul, Groove"; "Dub, Grime, Bass,
        // Dirt"; "Jazz, Progressive, Fusion"). "Groove Metal" / "Progressive Rock" still resolve via the
        // word-level match.
        "dirt",
        "groove",
        "progressive",
        "kickass",
        // Audience / theme / series labels Gretchen pushes into the genre field. [lookupKey] strips
        // non-ASCII letters: "Männerparty" is "mnnerparty", "FLINTA*" is "flinta".
        "flinta",
        "mnnerparty",
        "fetish",
        "berbenautika",
        // Staging formats venues file in a genre field. `show`/`konzert`/`lesung` above veto only the
        // standalone word, so the hyphenated compounds need their own keys ("Musik-Show" is
        // "musikshow"). A mixed label naming a real style still resolves first.
        "musikshow",
        "musikkabarett",
        "kabarett",
        "comedy",
        "tanz",
        "ballett",
        "diskussion",
        "kultur",
        "kunst",
        "tribute",
        // A city is a place, not a style; "Berlin Techno" still resolves to Techno.
        "berlin",
        // A medium whose soundtrack spans every style.
        "anime",
        // Formats that reached genre_tag on staging (#1309). Whole-token keys: "Jam Session" is
        // `jamsession`, and "Metalcore" resolves via the synonym map before `core` could veto it.
        "pingpong",
        "tattoo",
        "market",
        "drag",
        "burlesque",
        "solifest",
        "kinderkonzert",
        "jamsession",
        "spokenword",
        "core",
        "betonarme"
    )

/**
 * True when [label] is a genre the vocabulary knows, as a whole (`R&B`) or by one of its words
 * (`Psychedelic Rock`). For a source whose tags mix genres with formats and rooms (Heimathafen's
 * `events_tag`, #313): a tag that resolves is a genre, one that does not is a format, so the
 * fall-through that keeps an unknown short token is not offered here.
 */
fun isGenreLabel(label: String): Boolean = lookupKey(label) in GENRE_SYNONYMS || label.split(WHITESPACE_RUN).any { lookupKey(it) in GENRE_SYNONYMS }

private val WHITESPACE_RUN = Regex("""\s+""")

/**
 * Normalizes a raw token to a [GENRE_SYNONYMS] key: lowercased, everything but `a-z`, `0-9` and
 * `&` stripped ("Hip Hop", "hip-hop", "HIPHOP" to "hiphop"). `&` stays so "R&B"/"rnb" differ.
 */
private fun lookupKey(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9&]"), "")

/**
 * Delimiters splitting a raw genre string: `, ` ("Pop, Rock, Indie"); `/`, spaced or not
 * ("Alternative / Indie", "Hip-Hop/Rap", "80s Floor // Hip Hop Floor"); `&` ("80s, Disco & Hip
 * Hop"); ` or ` / ` oder ` / ` vs ` ("Tango or NonTango"). `/` always splits, since no genre
 * embeds a bare slash; `&` and the word separators can ("R&B"), so they split only when
 * surrounded by spaces. Case-insensitive.
 */
private val GENRE_DELIMITERS = Regex("""[,/]|\s(?:&|or|oder|vs)\s""", RegexOption.IGNORE_CASE)

/**
 * Genre names whose canonical spelling embeds a [GENRE_DELIMITERS] character (" & " in "Drum &
 * Bass", "'n'" in "Drum'n'Bass"), collapsed to one delimiter-free token before the split so they
 * resolve via [GENRE_SYNONYMS] ("drumnbass").
 */
private val DRUM_AND_BASS_REGEX =
    Regex(
        """\bdrum\s*['’]?\s*n\s*['’]?\s*bass\b|\bdrum\s*&\s*bass\b|\bdrum\s+and\s+bass\b|\bd\s*&\s*b\b|\bdnb\b""",
        RegexOption.IGNORE_CASE
    )

/**
 * "Singer-Songwriter" is often written "Singer-/Songwriter" or "Singer/Songwriter", and `/` is
 * a hard delimiter, so it would become "Singer-" + "Songwriter". Collapsed to the hyphen-only
 * spelling before the split ("singersongwriter").
 */
private val SINGER_SONGWRITER_REGEX = Regex("""\bsinger[\s/-]*songwriter(in)?\b""", RegexOption.IGNORE_CASE)

/** Collapses delimiter-embedding genre names to a split-safe spelling before tokenization. */
private fun preNormalize(rawGenre: String): String =
    rawGenre
        .replace(DRUM_AND_BASS_REGEX, "Drum'n'Bass")
        .replace(SINGER_SONGWRITER_REGEX, "Singer-Songwriter")

/**
 * Suffixes appended to genre names in listings ("Hip Hop Floor", "Pop Disco Floor"), stripped
 * before normalization. Longer suffixes first, so "Pop Disco Floor" strips to "Pop".
 */
private val NOISE_SUFFIXES = listOf("disco floor", "floor")

/**
 * Parses a raw genre string into a deduplicated list of canonical tag names: split on the
 * delimiters, noise suffixes stripped, looked up case-insensitively. A token matching nothing is
 * kept in title case, so an unseen genre is captured without a map change.
 *
 * @return canonical genre tag names, empty when the input is null or blank.
 *
 * ```
 * normalizeGenre("Pop Punk, Indie, Karaoke")                     → ["Punk", "Indie", "Karaoke"]
 * normalizeGenre("Postpunk, Gothicrock, EBM und Synthpop etc.")  → ["Post-Punk", "Gothic Rock", "EBM", "Synthpop"]
 * normalizeGenre(null)                                           → []
 * ```
 */
fun normalizeGenre(rawGenre: String?): List<String> {
    if (rawGenre.isNullOrBlank()) return emptyList()

    return preNormalize(rawGenre)
        .split(GENRE_DELIMITERS)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { stripNoise(it) }
        .filter { it.isNotBlank() }
        // Re-split after noise stripping — stripNoise may introduce new commas
        // (e.g. "EBM und Synthpop" → "EBM, Synthpop")
        .flatMap { it.split(",") }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .flatMap { token -> resolveGenre(token) }
        .distinct()
}

/**
 * Strips noise suffixes and filler words: "Hip Hop & Urban Disco Floor" to "Hip Hop & Urban",
 * trailing "etc.".
 */
private fun stripNoise(token: String): String {
    var cleaned =
        token
            .replace(Regex("""\s+etc\.?$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+und\s+""", RegexOption.IGNORE_CASE), ", ")
            .trim()

    // Strip "Floor" / "Disco Floor" suffixes (case-insensitive)
    for (suffix in NOISE_SUFFIXES) {
        if (cleaned.endsWith(suffix, ignoreCase = true)) {
            cleaned = cleaned.dropLast(suffix.length).trim()
        }
    }
    return cleaned
}

/**
 * Resolves one cleaned token to canonical names, in order: direct synonym lookup; word-level
 * matching returning all matched genres ("Superheavy Funky Soul & Boogaloo" to ["Funk",
 * "Soul"]); else kept as a new genre only if it [looksLikeGenre]. Tokens that are clearly not
 * genres return an empty list.
 */
@Suppress("ReturnCount") // Multiple early returns improve readability for this cascading lookup
private fun resolveGenre(token: String): List<String> {
    val lower = token.lowercase().trim()

    // Skip tokens that are clearly not genre names
    if (lower.length < 2) return emptyList()
    if (lower.startsWith("all kinds of")) {
        val genre =
            GENRE_SYNONYMS[
                lookupKey(
                    lower
                        .substringAfter("all kinds of")
                        .trim()
                        .split(" ")
                        .first()
                )
            ]
        return listOfNotNull(genre)
    }
    if (lower.startsWith("from ")) return emptyList()

    // Direct synonym match
    GENRE_SYNONYMS[lookupKey(lower)]?.let { return listOf(it) }

    // Try matching individual words if the full token didn't match
    // (handles compound freeform labels like "Superheavy Funky Soul & Boogaloo")
    val words = lower.split(Regex("""\s+"""))
    val matched = words.mapNotNull { GENRE_SYNONYMS[lookupKey(it)] }.distinct()
    if (matched.isNotEmpty()) return matched

    // No synonym match: keep as a new genre only if it plausibly names one, so format labels never
    // leak into genre_tag.
    if (!looksLikeGenre(token)) {
        logger.info { "Dropping non-genre token '$token'" }
        return emptyList()
    }
    logger.info { "No synonym match for genre token '$token', using as-is" }
    val titleCased =
        token.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercaseChar() }
        }
    return listOf(titleCased)
}

/**
 * Heuristic gate for the [resolveGenre] fall-through. A token qualifies when it contains a
 * letter; is at most [MAX_GENRE_WORDS] words ("Noise", "Trip-Hop", "New Wave", where "Twenty
 * One Pilots Special" runs long); contains no [NON_GENRE_TOKENS] word; and does not itself
 * normalize to a [NON_GENRE_TOKENS] entry ("Open Air" to `openair`, "Release Party" to
 * `releaseparty`), which the per-word check misses.
 */
private fun looksLikeGenre(token: String): Boolean {
    val words = token.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
    return words.size <= MAX_GENRE_WORDS &&
        words.none { lookupKey(it) in NON_GENRE_TOKENS } &&
        lookupKey(token) !in NON_GENRE_TOKENS &&
        token.any { it.isLetter() }
}

/** Maximum word count for a fall-through token to still be treated as a genre name. */
private const val MAX_GENRE_WORDS = 2
