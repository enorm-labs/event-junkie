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
private val SHOW_TITLE_KEYWORDS = listOf("wrestling", "burlesque", "circus")

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
 * wrestling/burlesque/circus to [SHOW][EventType.SHOW], football screening or cinema to
 * [SCREENING][EventType.SCREENING], reading/slam to [READING][EventType.READING],
 * exhibition/vernissage to [EXHIBITION][EventType.EXHIBITION], market to
 * [OTHER][EventType.OTHER], party/club-night keyword to [PARTY][EventType.PARTY].
 */
private fun classifyByTitleKeyword(title: String): String? {
    val haystack = title.lowercase()
    return when {
        "quiz" in haystack -> EventType.QUIZ.name

        SHOW_TITLE_KEYWORDS.any { it in haystack } -> EventType.SHOW.name

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
 * Title-based inference for a venue that categorises only some events: Monarch flags concerts
 * with a "(KONZERT)" suffix and emits nothing for its DJ nights. An unmarked event goes by
 * [classifyByTitleKeyword], falling back to [OTHER][EventType.OTHER], deliberately not CONCERT:
 * the venue's own concert marker is authoritative, and defaulting would mint the unmarked
 * parties' names as headliners ([buildArtistsForEventType]). Mirrors the OTHER branch of
 * [refineConcertVenueType].
 */
fun inferUnmarkedTitleType(title: String): String = classifyByTitleKeyword(title) ?: EventType.OTHER.name

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
