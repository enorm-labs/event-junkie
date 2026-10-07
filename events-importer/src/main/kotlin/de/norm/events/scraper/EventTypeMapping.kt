package de.norm.events.scraper

import de.norm.events.event.EventType

// Event-type classification for scraped events, shared by every scraper. Artist-name and
// field-level mapping live in ArtistNameMapping.kt and EventFieldMapping.kt.

/**
 * Base synonym table from German/English category labels to [EventType] names; keys
 * lowercase, matching case-insensitive. Venue-specific labels go through [mapEventType]'s
 * `extraSynonyms`.
 */
private val BASE_EVENT_TYPE_SYNONYMS: Map<String, String> =
    mapOf(
        "konzert" to EventType.CONCERT.name,
        "concert" to EventType.CONCERT.name,
        "festival" to EventType.FESTIVAL.name,
        "party" to EventType.PARTY.name,
        "quiz" to EventType.QUIZ.name,
        "show" to EventType.SHOW.name,
        "comedy" to EventType.COMEDY.name,
        "stand-up" to EventType.COMEDY.name,
        "standup" to EventType.COMEDY.name,
        "stand-up comedy" to EventType.COMEDY.name,
        // Kabarett is comedy at every venue (#2314).
        "kabarett" to EventType.COMEDY.name,
        "musikkabarett" to EventType.COMEDY.name,
        "musik-kabarett" to EventType.COMEDY.name,
        // A football/match screening (Lido labels these "Public Viewing") is a SCREENING, not a concert.
        "public viewing" to EventType.SCREENING.name,
        // A literary reading / spoken-word evening, and a gallery exhibition / opening.
        "lesung" to EventType.READING.name,
        "reading" to EventType.READING.name,
        "ausstellung" to EventType.EXHIBITION.name,
        "exhibition" to EventType.EXHIBITION.name,
        "vernissage" to EventType.EXHIBITION.name,
        "sonstiges" to EventType.OTHER.name,
        "other" to EventType.OTHER.name
    )

/**
 * Maps a raw category label to an [EventType] name, or `null` when missing or unrecognized, so
 * callers can fall back via `?:` and [ScrapedEvent.toEventEntity] applies the `OTHER` default.
 * [extraSynonyms] take precedence over [BASE_EVENT_TYPE_SYNONYMS].
 */
fun mapEventType(
    label: String?,
    extraSynonyms: Map<String, String> = emptyMap()
): String? {
    val key = label?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return extraSynonyms[key] ?: BASE_EVENT_TYPE_SYNONYMS[key]
}

/** Title keywords marking a non-music variety show (mapped to [EventType.SHOW]). */
private val SHOW_TITLE_KEYWORDS = listOf("wrestling", "burlesque", "circus", "roncalli")

/**
 * An ice show ([EventType.SHOW]): `on ice` as a phrase (Holiday on Ice, #2598), or an `Eisrevue`
 * / `Eisshow` word. Anchored at the start, so "Lemon Ice" and a "Preisshow" stay untouched.
 */
private val ICE_SHOW_TITLE_PATTERN = Regex("""\bon\s+ice\b|\beis(?:revue|show)""", RegexOption.IGNORE_CASE)

/**
 * Title phrases marking a comedy night ([EventType.COMEDY]). A bare "comedy" is not one, nor a
 * spaced "stand up" alone: The Divine Comedy is a band, and "Stand Up" a song title.
 */
private val COMEDY_TITLE_PATTERN =
    Regex(
        """\bstand-?up\b|\bstand\s+up\s+comedy\b|\bcomedy[\s-]+(?:show|night|club|special|abend|mixed[\s-]+show)\b|\bopen[\s-]+mic[\s-]+comedy\b""",
        RegexOption.IGNORE_CASE
    )

/**
 * Title keywords marking a screening on their own ([EventType.SCREENING]), long enough for a
 * substring test; the shorter cinema marker is [SCREENING_TITLE_WORD_PATTERN], and the sport
 * words need a context ([isScreeningTitle]).
 */
private val SCREENING_TITLE_KEYWORDS = listOf("public viewing", "live-screening", "screening", "übertragung", "uebertragung", "wm-quartier")

/**
 * A cinema, standalone or as the head of a German compound — `Kino`, `Nomadenkino`,
 * `Freiluftkino` (#310). Anchored at the word's end, so a real act name that merely contains the
 * letters ("Alkinoos Ioannidis") is untouched.
 */
private val SCREENING_TITLE_WORD_PATTERN = Regex("""\b\w*kinos?\b""", RegexOption.IGNORE_CASE)

/**
 * The sport and its tournaments. None of these types a screening alone: `Der Fussball mein Leben
 * & Ich` is a talk with a football coach (#311). Two of them together (`Fußball
 * Weltmeisterschaft`), or one beside a screening verb or a fixture (`EM Italien - Albanien`), is
 * a public viewing.
 */
private val SPORT_WORD_PATTERN =
    Regex(
        """\b(?:fu(?:ß|ss)ball|football|soccer|11freunde|world\s+cup|weltmeisterschaft|europameisterschaft""" +
            """|bundesliga|champions\s+league|em|wm)\b""",
        RegexOption.IGNORE_CASE
    )

/** The space-padded separator between two sides of a fixture, or `vs`. */
private val FIXTURE_PATTERN = Regex("""\s(?:[-–—:]|vs\.?)\s""")

/** A live-broadcast marker beside a sport word. */
private val LIVE_WORD_PATTERN = Regex("""\blive\b""", RegexOption.IGNORE_CASE)

/** How many sport words type a screening without any other context. */
private const val SPORT_WORDS_ALONE = 2

/**
 * Whether [title] names a screening: a cinema night, or a football public viewing said in so
 * many words, named by its tournament, or naming a fixture; a bare sport word is not enough. A
 * safety net for venues (Madame Claude) that type from a category that may be unknown.
 */
fun isScreeningTitle(title: String): Boolean {
    val haystack = title.lowercase()
    if (SCREENING_TITLE_KEYWORDS.any { it in haystack } || SCREENING_TITLE_WORD_PATTERN.containsMatchIn(haystack)) return true
    val sportWords = SPORT_WORD_PATTERN.findAll(haystack).count()
    return sportWords >= SPORT_WORDS_ALONE ||
        (sportWords == 1 && (FIXTURE_PATTERN.containsMatchIn(title) || LIVE_WORD_PATTERN.containsMatchIn(haystack)))
}

/**
 * Title keywords marking a reading ([EventType.READING]); `lesedüne` is a recurring Berlin
 * series. The shorter `slam` marker is [READING_TITLE_WORD_PATTERN].
 */
private val READING_TITLE_KEYWORDS =
    listOf(
        "lesung",
        "lesedüne"
    )

/**
 * Whole-word reading keyword too short for a substring test: `slam` is a substring of a song
 * slam ("Songslam Kreuzberg"), a musical format. "DAV JURA SLAM" reads, "Songslam" does not.
 */
private val READING_TITLE_WORD_PATTERN = Regex("""\bslam\b""", RegexOption.IGNORE_CASE)

/**
 * Title keywords marking an exhibition or gallery opening ([EventType.EXHIBITION]);
 * `vernissage` is the opening night of an `ausstellung`.
 */
private val EXHIBITION_TITLE_KEYWORDS =
    listOf(
        "ausstellung",
        "exhibition",
        "vernissage"
    )

/**
 * Title keywords marking a non-musical format with no more specific type ([EventType.OTHER]):
 * markets.
 */
private val NON_CONCERT_TITLE_KEYWORDS =
    listOf(
        "markt"
    )

/**
 * Title keywords marking a DJ/club night ([EventType.PARTY]). A bare `club` is absent and must
 * not be restored: as a substring it typed Columbiahalle's `Two Door Cinema Club` as `PARTY`,
 * storing no artist, and word-anchoring does not help. Nothing replaces it, checked: the
 * resident nights ending in the word (`Soda Social Club`, `CLUB TROPICANA`, `MONDAY NITE CLUB`)
 * are at venues whose scraper types every event `PARTY` or reads the venue's category. Huxleys'
 * `Corrupted Blood Club Show` is handled structurally: a `<X> presents` subtitle beside a title
 * opening with `<X>` yields no artists
 * ([isPresenterOwnEventTitle][de.norm.events.scraper.isPresenterOwnEventTitle]).
 */
private val PARTY_TITLE_KEYWORDS =
    listOf("aftershow", "afterparty", "after-party", "after party", "party", "club night", "clubnight", "karaoke")

/**
 * Whole-word party keyword too short for a substring test: `rave` is inside "GRAVE DIGGER" and
 * "The Brave".
 */
private val PARTY_TITLE_WORD_PATTERN = Regex("""\brave\b""", RegexOption.IGNORE_CASE)

/**
 * Classifies an event by unambiguous title keywords, or `null`: quiz to [QUIZ][EventType.QUIZ],
 * a stand-up or comedy-night phrase to [COMEDY][EventType.COMEDY], wrestling/burlesque/circus/ice show to
 * [SHOW][EventType.SHOW], football screening or cinema to
 * [SCREENING][EventType.SCREENING], reading/slam to [READING][EventType.READING],
 * exhibition/vernissage to [EXHIBITION][EventType.EXHIBITION], market to
 * [OTHER][EventType.OTHER], party/club-night keyword to [PARTY][EventType.PARTY].
 */
private fun classifyByTitleKeyword(title: String): String? {
    val haystack = title.lowercase()
    return when {
        "quiz" in haystack -> EventType.QUIZ.name

        COMEDY_TITLE_PATTERN.containsMatchIn(title) -> EventType.COMEDY.name

        SHOW_TITLE_KEYWORDS.any { it in haystack } ||
            ICE_SHOW_TITLE_PATTERN.containsMatchIn(title) -> EventType.SHOW.name

        isScreeningTitle(title) -> EventType.SCREENING.name

        READING_TITLE_KEYWORDS.any { it in haystack } ||
            READING_TITLE_WORD_PATTERN.containsMatchIn(haystack) -> EventType.READING.name

        EXHIBITION_TITLE_KEYWORDS.any { it in haystack } -> EventType.EXHIBITION.name

        NON_CONCERT_TITLE_KEYWORDS.any { it in haystack } -> EventType.OTHER.name

        PARTY_TITLE_KEYWORDS.any { it in haystack } ||
            PARTY_TITLE_WORD_PATTERN.containsMatchIn(haystack) -> EventType.PARTY.name

        else -> null
    }
}

/**
 * Title-based inference for a concert-leaning venue that left an event entirely unclassified
 * (Roadrunner has no category field; Astra/Lido/So36 omit it for some events). The default is
 * [CONCERT][EventType.CONCERT], so the title is minted as the headliner
 * ([buildArtistsForEventType]); only an unambiguous [classifyByTitleKeyword] match flips it.
 */
fun inferConcertVenueType(title: String): String = classifyByTitleKeyword(title) ?: EventType.CONCERT.name

/**
 * [inferConcertVenueType] for a venue with its own title vocabulary or a subtitle line. The
 * [venueKeywords] map a keyword to its type and are checked after the shared cues, so a
 * "Quiz Night" stays a quiz where `night` marks a party. Each is matched as a whole word
 * ([containsVenueKeyword]).
 *
 * The [subtitle] is a format note beside the name, so only a party or quiz cue in it types the
 * night: a market or show word there describes a real act's evening. A subtitle naming a tour
 * ("Indie Rave Tour 2026") is the act's tour title and types nothing, and an after-show there
 * ("+ Aftershow: FISH'N'CANDY") follows the concert without replacing it.
 */
fun inferConcertVenueType(
    title: String,
    subtitle: String?,
    venueKeywords: Map<String, String> = emptyMap()
): String {
    fun classify(text: String): String? =
        classifyByTitleKeyword(text) ?: venueKeywords.entries.firstOrNull { (keyword, _) -> containsVenueKeyword(text, keyword) }?.value
    return classify(title)
        ?: subtitle
            ?.takeUnless { TOUR_NAME.containsMatchIn(it) }
            ?.let { classify(AFTER_PARTY.replace(it, "")) }
            ?.takeIf { it in SUBTITLE_CUE_TYPES }
        ?: EventType.CONCERT.name
}

/** Title keywords of a venue that bills DJ sets under the act's name as club nights. */
val DJ_SET_PARTY_KEYWORDS: Map<String, String> = mapOf("dj set" to EventType.PARTY.name, "dj-set" to EventType.PARTY.name)

/** The types a subtitle cue may set on its own ([inferConcertVenueType]). */
private val SUBTITLE_CUE_TYPES = setOf(EventType.PARTY.name, EventType.QUIZ.name)

/** A tour named in a subtitle: "Indie Rave Tour 2026", "World Tour". */
private val TOUR_NAME = Regex("""\btour\b""", RegexOption.IGNORE_CASE)

/** An after-show or after-party named in a subtitle. */
private val AFTER_PARTY = Regex("""after[\s-]?(?:show|party)""", RegexOption.IGNORE_CASE)

/**
 * Whether [text] holds [keyword] as a whole word. The boundary applies only on a side where the
 * keyword has a letter: `night` misses "Nightwish" and "Midnight", while `90s` still finds
 * "TOP90s".
 */
private fun containsVenueKeyword(
    text: String,
    keyword: String
): Boolean {
    val before = if (keyword.first().isLetter()) """(?<!\p{L})""" else ""
    val after = if (keyword.last().isLetter()) """(?!\p{L})""" else ""
    return Regex(before + Regex.escape(keyword) + after, RegexOption.IGNORE_CASE).containsMatchIn(text)
}

/**
 * Title-based inference for a venue that categorises only some events: Monarch flags concerts
 * with a "(KONZERT)" suffix and emits nothing for its DJ nights. An unmarked event goes by
 * [classifyByTitleKeyword], falling back to [OTHER][EventType.OTHER], deliberately not CONCERT:
 * the venue's own concert marker is authoritative, and defaulting would mint the unmarked
 * parties' names as headliners ([buildArtistsForEventType]). Mirrors the OTHER branch of
 * [refineConcertVenueType].
 */
fun inferUnmarkedTitleType(title: String): String = classifyByTitleKeyword(title) ?: EventType.OTHER.name

/**
 * Title-based inference for a house that states no category but names its formats in its own
 * words: Peter Edel a silent-film night as "Stummfilm", a tea dance as "Tanztee".
 * [venueFormats] maps such a keyword to its type and is checked first, so the house's own
 * vocabulary beats the shared cues; use a [linkedMapOf] where an earlier entry has to win. Then
 * [inferUnmarkedTitleType], which falls back to [OTHER][EventType.OTHER] rather than CONCERT.
 *
 * [title] and [subtitle] are searched as one lowercased string, because either may carry the cue.
 */
fun inferVenueFormatType(
    title: String,
    subtitle: String?,
    venueFormats: Map<String, String>
): String {
    val haystack = listOfNotNull(title, subtitle).joinToString(" ").lowercase()
    return venueFormats.entries.firstOrNull { (keyword, _) -> keyword in haystack }?.value
        ?: inferUnmarkedTitleType(haystack)
}

/**
 * Classifies an event by a non-musical format cue in its raw [genre] text, or `null`: Festsaal
 * tags a reading `genre = "Lesung"`, Cassiopeia an immersive show `genre = "Immersive
 * Ausstellung"`, leaving the title cue-less. Narrower than the title classifier: only a
 * screening, a reading or an exhibition, formats a music venue never lists as a genre, so
 * "Techno" or "Spoken Word, Jazz" never reclassifies a concert. Same whole-word guards
 * (`\bslam\b`, `\bkino\b`).
 */
fun classifyByGenreKeyword(genre: String): String? {
    val haystack = genre.lowercase()
    return when {
        isScreeningTitle(genre) -> EventType.SCREENING.name

        READING_TITLE_KEYWORDS.any { it in haystack } ||
            READING_TITLE_WORD_PATTERN.containsMatchIn(haystack) -> EventType.READING.name

        EXHIBITION_TITLE_KEYWORDS.any { it in haystack } -> EventType.EXHIBITION.name

        else -> null
    }
}

/** The generic "unclassified" category labels a venue may emit (as opposed to a specific kind). */
private val GENERIC_OTHER_TYPES = setOf(EventType.OTHER.name)

/**
 * Refines the [mappedType] a concert-leaning venue produced. The venue's category is trusted
 * unless unclassified: a `null` mapping goes to [inferConcertVenueType], default CONCERT; a
 * generic [OTHER][EventType.OTHER] mapping (Astra's "Other" kind) is reclassified by
 * [classifyByTitleKeyword] only, falling back to OTHER, since the venue said "not a normal
 * concert" and a signal-less catch-all ("GWF Summer Smash") stays OTHER; any specific type is
 * trusted.
 */
fun refineConcertVenueType(
    mappedType: String?,
    title: String
): String =
    when (mappedType) {
        null -> inferConcertVenueType(title)
        in GENERIC_OTHER_TYPES -> classifyByTitleKeyword(title) ?: EventType.OTHER.name
        else -> mappedType
    }

/**
 * A title that unambiguously names a festival: a word-anchored `festival` / `festivalticket`
 * anywhere ("Canarias Calling Festival", "Grossstadtwahnsinn 2026 - Festivalticket"). Tighter
 * than [NON_ARTIST_EVENT_PATTERN]: a bare `fest` is not matched.
 */
private val FESTIVAL_TITLE_PATTERN =
    Regex("""\bfestivaltickets?\b|\bfestivals?\b""", RegexOption.IGNORE_CASE)

/**
 * Whether [title] unambiguously names a festival ([FESTIVAL_TITLE_PATTERN]), used at
 * [ScrapedEvent.toEventEntity] to promote an under-classified `CONCERT`/`OTHER` to `FESTIVAL`.
 * A source that typed the event itself is trusted.
 */
fun isFestivalTitle(title: String): Boolean = FESTIVAL_TITLE_PATTERN.containsMatchIn(title)
