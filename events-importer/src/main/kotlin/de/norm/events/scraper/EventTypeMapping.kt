package de.norm.events.scraper

import de.norm.events.event.EventType

// Event-type classification for scraped events: maps raw venue category/genre/title
// text into EventType constants. Shared across all venue-specific scrapers so
// classification stays consistent. Artist-name and field-level mapping live in
// ArtistNameMapping.kt and EventFieldMapping.kt.

/**
 * Base synonym table mapping common German/English event category labels to
 * [EventType] names. Keys are lowercase; matching is case-insensitive.
 *
 * Venue-specific labels (e.g. Madame Claude's WordPress CSS class names) are
 * passed as `extraSynonyms` to [mapEventType] rather than polluting this table.
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
 * Maps a raw venue category/kind label to an [EventType] name, or `null` when
 * the label is missing or unrecognized.
 *
 * Returning `null` (rather than defaulting to `"OTHER"`) lets callers fall back
 * to another data source via `?:`, and lets the persistence boundary
 * ([ScrapedEvent.toEventEntity]) apply the `OTHER` default. This is the single
 * shared mapper used by every venue scraper — venue-specific labels are supplied
 * via [extraSynonyms], which take precedence over [BASE_EVENT_TYPE_SYNONYMS].
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
 * Title keywords marking a film/match screening (mapped to [EventType.SCREENING]):
 * football public-viewings / live screenings — the audience watches a screen rather
 * than a live act. These are long/distinctive enough for a safe substring test; the
 * shorter cinema marker lives in [SCREENING_TITLE_WORD_PATTERN].
 */
private val SCREENING_TITLE_KEYWORDS =
    listOf(
        "public viewing",
        "live-screening",
        "screening",
        "world cup",
        "weltmeisterschaft",
        "fußball",
        "fussball",
        "wm-quartier",
        "11freunde"
    )

/**
 * Whole-word screening keyword too short for a safe substring test: `kino` (cinema)
 * is a substring of real act names like "Alkinoos Ioannidis", so it is matched only
 * as a standalone word.
 */
private val SCREENING_TITLE_WORD_PATTERN = Regex("""\bkino\b""", RegexOption.IGNORE_CASE)

/**
 * Whether [title] names a film/match screening — a football public-viewing / live
 * screening or a cinema night. Exposed for venues (e.g. Madame Claude) that type
 * from a category but want a title-based screening safety net when the category is
 * unknown.
 */
fun isScreeningTitle(title: String): Boolean {
    val haystack = title.lowercase()
    return SCREENING_TITLE_KEYWORDS.any { it in haystack } || SCREENING_TITLE_WORD_PATTERN.containsMatchIn(haystack)
}

/**
 * Title keywords marking a literary reading / spoken-word evening (mapped to
 * [EventType.READING]) — the audience listens to a text being read, not a musical
 * act. `lesedüne` is a recurring Berlin reading series. These are distinctive enough
 * for a safe substring test; the shorter `slam` marker lives in
 * [READING_TITLE_WORD_PATTERN].
 */
private val READING_TITLE_KEYWORDS =
    listOf(
        "lesung",
        "lesedüne"
    )

/**
 * Whole-word reading keyword too short for a safe substring test: `slam` (poetry
 * slam) is a substring of a *song* slam ("Songslam Kreuzberg") — a musical format,
 * not a reading — so it is matched only as a standalone word: "DAV JURA SLAM" reads,
 * "Songslam" does not.
 */
private val READING_TITLE_WORD_PATTERN = Regex("""\bslam\b""", RegexOption.IGNORE_CASE)

/**
 * Title keywords marking a visual-art exhibition or gallery opening (mapped to
 * [EventType.EXHIBITION]): `vernissage` is the opening night of an `ausstellung`.
 */
private val EXHIBITION_TITLE_KEYWORDS =
    listOf(
        "ausstellung",
        "exhibition",
        "vernissage"
    )

/**
 * Title keywords marking a clearly non-musical format with no more specific type
 * (mapped to [EventType.OTHER]): markets.
 */
private val NON_CONCERT_TITLE_KEYWORDS =
    listOf(
        "markt"
    )

/**
 * Title keywords marking a DJ/club night (mapped to [EventType.PARTY]).
 *
 * **A bare `club` is deliberately absent, and must not be restored.** A booked band and a resident
 * night share the shape: as a substring it typed Columbiahalle's `Two Door Cinema Club` as `PARTY`,
 * which stored no artist at all. Word-anchoring does not help — `Club` is already a whole word
 * there.
 *
 * Nothing replaces it, checked rather than assumed: the resident nights ending in the word
 * (`Soda Social Club`, `CLUB TROPICANA`, `MONDAY NITE CLUB`) are all at venues whose scraper types
 * every event `PARTY` outright or reads the venue's own category, so their titles never reach this
 * list. The one title the keyword still caught, Huxleys' `Corrupted Blood Club Show`, is handled
 * structurally instead: a `<X> presents` subtitle beside a title opening with `<X>` yields no
 * artists ([isPresenterOwnEventTitle][de.norm.events.scraper.isPresenterOwnEventTitle]), so the row
 * keeps `CONCERT` and mints nobody.
 */
private val PARTY_TITLE_KEYWORDS =
    listOf("aftershow", "afterparty", "after-party", "after party", "party", "club night", "clubnight", "karaoke")

/**
 * Whole-word party keyword too short for a safe substring test: `rave` is a substring
 * of real band names like "GRAVE DIGGER" and "The Brave", so it is matched only as a
 * standalone word — mirrors the `\bkino\b` / `\bslam\b` guards above.
 */
private val PARTY_TITLE_WORD_PATTERN = Regex("""\brave\b""", RegexOption.IGNORE_CASE)

/**
 * Classifies an event by unambiguous keywords in its [title], or `null` when none
 * match. Curated and reactive: a quiz keyword → [QUIZ][EventType.QUIZ]; a
 * wrestling/burlesque/circus show → [SHOW][EventType.SHOW]; a football screening or
 * cinema night → [SCREENING][EventType.SCREENING]; a reading/poetry slam →
 * [READING][EventType.READING]; an art exhibition/vernissage →
 * [EXHIBITION][EventType.EXHIBITION]; another non-music format (market) →
 * [OTHER][EventType.OTHER]; a party/club-night keyword → [PARTY][EventType.PARTY].
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
 * Title-based event-type inference for a concert-leaning venue that left an event
 * *entirely unclassified* (no category at all — e.g. Roadrunner has no category
 * field; Astra/Lido/So36 omit the label for some events).
 *
 * These are dedicated live-music venues, so the default is [CONCERT][EventType.CONCERT] —
 * a category-less event is almost always an ordinary gig whose title names the act,
 * and defaulting to CONCERT lets that title be minted as the headliner
 * ([buildArtistsForEventType]). Only an unambiguous [classifyByTitleKeyword] match
 * flips it (keeping quiz/screening/party event-names out of the lineup).
 */
fun inferConcertVenueType(title: String): String = classifyByTitleKeyword(title) ?: EventType.CONCERT.name

/**
 * Title-based type inference for a venue that categorises only *some* of its events
 * and leaves the rest unlabelled — e.g. Monarch flags concerts with a "(KONZERT)"
 * suffix and emits no category at all for its DJ nights, parties and other formats.
 *
 * Classifies an unmarked event by an unambiguous [classifyByTitleKeyword] cue (a
 * party/quiz/show/screening/reading/exhibition keyword → that type), falling back to
 * [OTHER][EventType.OTHER]. Unlike [inferConcertVenueType] it deliberately does **not**
 * default to CONCERT: the venue's *own* concert marker is authoritative, so its absence
 * signals "not a standard concert", and defaulting to CONCERT would both mislabel the
 * unmarked parties and mint their event names as headliners
 * ([buildArtistsForEventType]). Mirrors the OTHER branch of [refineConcertVenueType].
 */
fun inferUnmarkedTitleType(title: String): String = classifyByTitleKeyword(title) ?: EventType.OTHER.name

/**
 * Classifies an event by an unambiguous non-musical **format** cue in its raw
 * [genre] text, or `null` when none match. A venue occasionally files the format in
 * the genre/category field rather than the title: Festsaal tags a book reading
 * `genre = "Lesung"` and Cassiopeia tags an immersive show `genre = "Immersive
 * Ausstellung"`, leaving the title — just an author or event name — with no cue for
 * [classifyByTitleKeyword].
 *
 * Deliberately narrower than the title classifier: only the three formats a music
 * venue would never list as a *music* genre are recognized — a screening, a reading,
 * or an exhibition — so a genuine genre string ("Techno", "Spoken Word, Jazz") never
 * reclassifies a concert. Reuses the title classifier's keyword lists and the same
 * whole-word guards (`\bslam\b`, `\bkino\b`), so a `Songslam`/`Alkinoos` substring in
 * a genre can't false-match either.
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
 * Refines the [mappedType] a concert-leaning venue produced for an event, using
 * title keywords to recover types the venue's own category field got wrong.
 *
 * The venue's category is trusted unless it is *unclassified*:
 *  - a `null` mapping (no category) → [inferConcertVenueType]: default CONCERT for
 *    this live-music venue, since a category-less event is almost always a gig.
 *  - a generic [OTHER][EventType.OTHER] mapping (the venue's own catch-all bucket,
 *    e.g. Astra's "Other" kind) → reclassified by [classifyByTitleKeyword] only,
 *    falling back to OTHER. Here the venue *explicitly* said "not a normal concert",
 *    so — unlike the `null` case — we do **not** default to CONCERT: only a positive
 *    keyword (a wrestling show → SHOW, a party → PARTY) overrides it. A signal-less
 *    catch-all event (e.g. "GWF Summer Smash") stays OTHER.
 *  - any specific type (CONCERT/PARTY/QUIZ/FESTIVAL/SHOW) → trusted as-is.
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
 * A title that unambiguously names a festival: a word-anchored `festival` /
 * `festivalticket` marker *anywhere* in the title ("Canarias Calling Festival",
 * "Grossstadtwahnsinn 2026 - Festivalticket"). Deliberately tighter than
 * [NON_ARTIST_EVENT_PATTERN] — it does **not** match a bare `fest`, so an event
 * merely titled "… Fest" is not promoted on this weak signal.
 */
private val FESTIVAL_TITLE_PATTERN =
    Regex("""\bfestivaltickets?\b|\bfestivals?\b""", RegexOption.IGNORE_CASE)

/**
 * Whether [title] unambiguously names a festival (see [FESTIVAL_TITLE_PATTERN]).
 *
 * Used at the persistence boundary ([ScrapedEvent.toEventEntity]) to promote an
 * event whose source under-classified it as `CONCERT`/`OTHER` — a festival day the
 * venue labelled "Konzert", or a title with no source category at all — to
 * `FESTIVAL`. A source that already typed the event `PARTY`/`QUIZ`/`FESTIVAL` is
 * trusted and left unchanged.
 */
fun isFestivalTitle(title: String): Boolean = FESTIVAL_TITLE_PATTERN.containsMatchIn(title)
