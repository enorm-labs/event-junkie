@file:Suppress("TooManyFunctions") // Cohesive collection of small, single-purpose artist-name mapping utilities.

package de.norm.events.scraper

import de.norm.events.slug.SlugGenerator
import java.text.Normalizer

// Names that are not performers: placeholders, role and slot labels, event and series names,
// and the curated denylist. Part of the artist-name rules in ArtistNameMapping.kt.

/**
 * Placeholder names for an unannounced artist ("TBA", "TBD", "TBC", "N.N."), never artist rows.
 */
private val PLACEHOLDER_NAMES =
    setOf("tba", "tbd", "tbc", "tba.", "tbd.", "tbc.", "nn", "n.n.", "nn.")

/**
 * A "more acts to come" continuation: `+ more`, `& more`, `and more`, `+ more tba`, `more tba`,
 * `more to be announced`, `und mehr`, `many more`. A parser splitting on `+` hands it over as the next act (Kater stored
 * `+ more` and `+ more Tba`). A bare "More" does not match: it is the NWOBHM band. Only a
 * lead-in (`+`/`&`/`and`/`und`), a `many`/`viele` quantifier or a trailing `tba`/`tbc`/`tbd`
 * marks a continuation; the quantified form is what a comma-split lineup ends on once
 * [headlinersFromTitle] has unpacked a `w/` billing.
 */
private val MORE_TO_COME_PATTERN =
    Regex(
        """^(?:[+&]|and|und)\s*(?:many\s+|viele\s+)?(?:more|mehr)(?:\s+(?:tba|tbc|tbd))?\.*$""" +
            """|^line[\s-]?up(?:\s+(?:tba|tbc|tbd))?\.*$""" +
            """|^(?:more|mehr)\s+(?:tba|tbc|tbd|to\s+be\s+announced)\.*$""" +
            """|^(?:many|viele)\s+(?:more|mehr)\.*$""",
        RegexOption.IGNORE_CASE
    )

/** A name wrapped whole in one pair of brackets: KitKatClub bills `Line up: (tba)` (#3202). */
private val ENCLOSING_BRACKETS = Regex("""^\((.*)\)$|^\[(.*)]$""")

/**
 * Whether [name] is a placeholder ([PLACEHOLDER_NAMES]) or a continuation
 * ([MORE_TO_COME_PATTERN]). Case-insensitive, dots and one pair of enclosing brackets ignored:
 * "T.B.A.", "(tba)" and "tba".
 */
fun isPlaceholderName(name: String): Boolean {
    val trimmed = unwrapBrackets(name.trim()).lowercase()
    val dotFree = trimmed.replace(".", "")
    return dotFree in PLACEHOLDER_NAMES ||
        trimmed in PLACEHOLDER_NAMES ||
        MORE_TO_COME_PATTERN.containsMatchIn(trimmed)
}

private fun unwrapBrackets(name: String): String =
    ENCLOSING_BRACKETS
        .matchEntire(name)
        ?.groupValues
        ?.drop(1)
        ?.firstOrNull { it.isNotEmpty() }
        ?.trim() ?: name

/**
 * A leading role label in front of the billed act, stripped off it ("Special Guest: FUCK" to
 * "FUCK"). Shared with the SO36 detail scraper and [extractSupportFromSubtitle].
 *
 * **A role word needs its colon**, as in [ARTIST_LABEL_PREFIX] and for the same reason: without it
 * the band `Support Lesbiens` is stored as `Lesbiens` (#1732). The abbreviations are the exception,
 * because no venue writes a colon after them — `feat. Kate NV`, `w/ Gudrun Gut`.
 */
val ROLE_LABEL_PREFIX =
    Regex(
        """^(?:(?:$SUPPORT_LABELS)\s*:|(?:feat\.?|featuring|w/)\s*:?)\s*""",
        RegexOption.IGNORE_CASE
    )

/**
 * Whether [name] is a role label and nothing else: a subtitle `"Support: Special Guest"` captures
 * the label as the act. Matching is exact, so `"Special Guest Foo"` is a name and is kept — and the
 * colon [ROLE_LABEL_PREFIX] requires is optional here, because a line reading `Support` alone names
 * no act either way.
 */
fun isNonArtistLabel(name: String): Boolean = BARE_ROLE_LABEL.matches(name.trim())

/** A whole string that is a role label, with or without its colon. */
private val BARE_ROLE_LABEL =
    Regex("""^(?:$SUPPORT_LABELS|feat\.?|featuring|w/)\s*:?\s*$""", RegexOption.IGNORE_CASE)

/**
 * Curated event-segment labels, an aftershow/afterparty/warm-up/event-start slot listed in the lineup, with
 * any leading qualifier (`ACID AFTERSHOW`, `TECHNO AFTERPARTY`) and the `aftershow`/`after
 * show`/`after-show` spellings. Matched fully anchored by [isEventSegmentLabel], so the band
 * `"AFTERHOURS"` and the venue's `"Warm Up im Franken"` are kept. Curated because flat lineup
 * text carries no structural signal; add families as they appear.
 */
private val EVENT_SEGMENT_PATTERN =
    Regex("""(?:\S+ )*after[ -]?show(?: party)?|(?:\S+ )*after[ -]?party|warm[ -]?up|event[ -]?start""", RegexOption.IGNORE_CASE)

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
 * so an "… - 30 Jahre" tour tail (already trimmed by [stripArtistSuffix]) never matches. An
 * organiser credit is no act either: `Eine Veranstaltung des …`, `In Kooperation mit …` (#1843).
 * A sing-along names its format, not who leads it: `SingAlong – Das große Mitsing-Event` (#1902),
 * and so does a `…singen` compound (`Weihnachtssingen im Kiez`).
 *
 * A programme names its edition or its occasion, not its acts (#2851): a leading ordinal
 * (`41. Berliner Jazztreff`), a `Vol. <n>` anywhere (`Anime Rap Revolution Vol. I`), and a
 * `Tribute concert:` prefix. The ordinal needs its dot and a space, so `65daysofstatic` and
 * `808 State` stay acts.
 *
 * A gala named for its night is no act either (#3160): `Die Nacht der Musicals`, `Lange Nacht der
 * …` and `Die Schlagernacht des Jahres`. Both forms are anchored to the title start.
 *
 * So is a night that ends in a year, `Edition` or `Special`, or carries `Label Night` (#3212): `Autumn
 * Bash 2026`, `Herbst Edition`, `Xmas Special`. A real act's tour or show year is stripped before this
 * check, and `The Specials` stays an act.
 *
 * A night that ends on `invites` or `invite` names its hosts, not an act (#3214): `Not Stereo
 * Invites`. The verb must end the name: a mid-name `invites` splits host and guest ([splitOnInvites]).
 */
private val NON_ARTIST_EVENT_PATTERN =
    Regex(
        """.*\bfest\b.*|.*\bfestival\b.*|.*\bfestivalticket\b.*""" +
            """|.*\bhoffest\b.*""" +
            """|.*\bsing[\s-]?along\b.*|.*\bmitsing.*|.*\p{L}singen(?!\p{L}).*""" +
            """|\d+\.\s+\S.*|.*\bvol\.\s*(?:\d+|[ivx]+)\b.*|tribute[\s-]?(?:concert|konzert)\s*:.*""" +
            """|\d+\.?\s+(?:jahre|jahr|years?)\b.*""" +
            """|(?:eine\s+veranstaltung|in\s+kooperation\s+mit|in\s+zusammenarbeit\s+mit)\b.*""" +
            """|(?:die\s+)?(?:lange\s+)?nacht\s+der\s+\S.*|(?:die\s+)?\S*nacht\s+des\s+jahres\b.*""" +
            """|\S.*\s(?:20\d{2}|edition|special)|.*\blabel\s+night\b.*""" +
            """|.*\binvites?""",
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
 * Film and game score concerts: `in Concert`, `Live to Film`, or `Symphony` / `Symphonie` as the
 * format word. A named orchestra and the band `Symphony X` are acts, so both are excluded.
 */
private val SCORE_CONCERT_PATTERN =
    Regex(
        """\bin concert\b|\blive to film\b|\bsymphon(?:y|ie)\b(?![ -]*orche)(?! x\b)""",
        RegexOption.IGNORE_CASE
    )

/**
 * Whether [title] bills a score concert, a production named after its work (#1829). The name
 * in front is the brand, not the performer, and that includes a composer's name.
 */
fun isScoreConcertTitle(title: String): Boolean = SCORE_CONCERT_PATTERN.containsMatchIn(title.replace(WHITESPACE, " "))

/**
 * Series billed under their own name with the acts on the page body only (#1581), and the venue
 * name a partner co-brands its night with (`aufnahme + wiedergabe X Urban Spree`). Part of
 * [NON_ARTIST_NAMES]; [isCoBrandedSeriesTitle] also reads them after a partner's ` x ` (#2350).
 * Declared first, because [NON_ARTIST_NAMES] reads it while the file initialises.
 */
private val BILLED_SERIES_NAMES: Set<String> =
    setOf(
        "berlin beat invasion",
        "urban spree klubnacht",
        "urban spree",
        "methods of dance"
    )

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
        // Bi Nuu's salsa party with a live house band; the night's acts are only in its prose (#2316).
        "fuego36",
        // Bi Nuu's party named for the old Abraxas club; it names no DJ (#2416).
        "abraxas party",
        // Heimathafen's classical concert format by klassix; only the prose names the performer (#2416).
        "auf takt! das podcast-konzert",
        // Heimathafen's crowd karaoke format; no act is billed (#2788).
        "sing dela sing",
        // A Wühlmäuse revue, a ufaFabrik songwriter showcase and a Mikropol hip-hop series, each billed as the act (#2709).
        "der blaue montag",
        "berlin songwood sessions",
        "große hip hop offensive",
        "jazz after dark",
        "future bash reloaded",
        "a dead moon night",
        // The anti-fascist campaign's own concert series: Columbiahalle bills its anniversary
        // festival under the name with the lineup unannounced (#1110).
        "kein bock auf nazis",
        // DLTLLY (Don't Let The Label Label You) is a battle-rap league: Festsaal bills its birthday
        // show under the league's name and names no performer anywhere on the page (#1135).
        "dltlly",
        // Bare event-format words a co-billed title splits off as if they were acts: `… – Jubiläumskonzert & Party`.
        "party",
        "jubiläumskonzert",
        // Genre words a co-billed format title splits off — Monarch's `POETRY & HIP HOP (KONZERT)` (#1580).
        "poetry",
        "hip hop",
        "hip-hop",
        // What a split leaves of a note, a format word or a guest tail (#301): `verschoben – Black
        // River Delta`, `PANEL: …`, the `Friends` of `Emorine, Bandidas & Friends`. A whole `Feo &
        // Friends` billing stays one act (Supamolly); only the split-off word is dropped.
        "verschoben",
        "panel",
        "friends",
        // Series and festivals minted as their own act (#1952): Cassiopeia's two-stage metal day,
        // Festsaal's queer art festival, a Badehaus party the listing typed as a concert.
        "thrash talk",
        "in the mountains",
        "rockstar girlfriends",
        // Night and series names minted as acts (#3160): Quasimodo's `Jazz Night ft. <guests>`, the
        // `Jam` of Badehaus's `Uats Live + Jam` and Mikropol's numbered `Im Kreise der Familia 3`.
        "jazz night",
        "jam",
        "im kreise der familia",
        // Productions and series billed as the act; only the page text names the cast (#3326):
        // three Wühlmäuse shows, a Kesselhaus sampler concert and comedy brand, a Downstairs show.
        "pater brown",
        "die weihnachtsengel",
        "generation xy",
        "unter meinem bett",
        "nightwash",
        "smash comedy deluxe",
        // A touring band contest billed as the act at Maschinenhaus; its acts are not named.
        "sph music masters"
    ) + BILLED_SERIES_NAMES

/**
 * A trailing edition number on a recurring title, ignored when matching [NON_ARTIST_NAMES]: the
 * plain `… 5`, the `… N°141`, `… No 8` and `… Vol 5` forms (optional `n°`/`nº`/`no.`/`vol.`) and a
 * roman numeral (`Methods of Dance II`).
 */
private val TRAILING_EDITION = Regex("""\s+(?:n[°º]\s*|no\.?\s*|vol\.?\s*)?(?:\d+|[ivx]{1,5})$""", RegexOption.IGNORE_CASE)

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
internal fun isLedByNonArtistLabel(title: String): Boolean {
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
    Regex("""^(.+?)\s+(?:$PRESENTS_VERBS)\s*[-–—:|.]*\s*$""", RegexOption.IGNORE_CASE)

/**
 * The same marker anywhere in a title, which disqualifies the rule ([isPresenterOwnEventTitle]).
 */
private val PRESENTER_MARKER_IN_TITLE = Regex("""\b(?:$PRESENTS_VERBS)\b""", RegexOption.IGNORE_CASE)

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
internal val SUBTITLE_LINE_SEPARATOR = Regex("""\s*[\n|]\s*""")

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
internal fun isPresenterOwnEventTitle(
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

internal fun isDenylistedNonArtist(name: String): Boolean = denylistKey(name) in NON_ARTIST_NAMES

/** [name] as the denylists spell their entries, with a trailing edition number or `Berlin` dropped. */
private fun denylistKey(name: String): String =
    Normalizer
        .normalize(name.trim().replace(WHITESPACE, " ").lowercase(), Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .replace(TRAILING_EDITION, "")
        .replace(TRAILING_CITY, "")
        .trim()

/** The ` x ` a partner joins its name to a series with: `Human Tree x Urban Spree Klubnacht`. */
private val CO_BRAND_SEPARATOR = Regex("""\s+x\s+""", RegexOption.IGNORE_CASE)

/**
 * True when [title] ends in a [BILLED_SERIES_NAMES] entry after ` x ` (#2350). The partner on
 * the left is a promoter or a label that co-hosts the series night, so neither side is an act.
 * A co-bill such as `Pyrexia x Relics of Humanity` does not end in a series and is not matched.
 */
internal fun isCoBrandedSeriesTitle(title: String): Boolean {
    val separator = CO_BRAND_SEPARATOR.findAll(title).lastOrNull() ?: return false
    return denylistKey(title.substring(separator.range.last + 1)) in BILLED_SERIES_NAMES
}

/**
 * A bare "DJ set" format label, optionally with a `/ <origin>` tail (`DJ-Set`, `DJ Set`, `DJ-Set
 * / Berlin`), which a Madame Claude detail heading pushes into a performer slot. Anchored, so
 * `DJ Koze` and any `DJ <handle>` are untouched. A set-length note is the same kind of label:
 * Tresor bills `All night long` in a performer slot (#1843).
 */
private val DJ_SET_LABEL_PATTERN = Regex("""dj[\s-]?set(?:\s*/.*)?|all\s+night\s+long|open\s+to\s+close""", RegexOption.IGNORE_CASE)

/**
 * Whether [name] is a bare `DJ set` label ([DJ_SET_LABEL_PATTERN]); anchored, so `DJ Koze` /
 * `DJ Set Sail` are kept.
 */
fun isDjSetFormatLabel(name: String): Boolean = DJ_SET_LABEL_PATTERN.matches(name.trim().replace(WHITESPACE, " "))

/**
 * An unannounced-guest slot: a bare "Guest(s)"/"Gäste", optionally with a leading "+", a
 * `secret`/`surprise` (Aeden, #1843) and a format ("Guest DJs", Club der Visionäre's spelling);
 * Wild at Heart lists "+ Guest". A
 * placeholder, mirroring the [CONJUNCTION_TAIL_COLLECTIVES] that keep "X & Guests" one act.
 * Anchored, so "Special Guest DJ Foo" is untouched; kept to the guest forms, since a standalone
 * "Friends"/"Band" is a plausible act name. OHM bills "+ Secret Lineup" and "+ Secret Guest tab",
 * its spelling of "tba" (#2439); a lineup slot needs the `secret` word, since "Line Up" alone is
 * [MORE_TO_COME_PATTERN]'s. One word may sit between: KitKatClub bills "Secret Music Line up" (#2901).
 * One place from [COUNTRY_NAMES] may follow the guest word: Urban Spree bills "+ GÄSTE BERLIN" (#3214).
 */
private val GUEST_SLOT_PATTERN =
    Regex(
        """\+?\s*(?:(?:(?:secret|surprise|mystery)\s+)?(?:guests?|gäste|gaeste)(?:\s+djs?)?(?:\s+(?:$COUNTRY_NAMES))?""" +
            """|(?:secret|surprise|mystery)\s+(?:\p{L}+\s+)?line[\s-]?up)(?:\s+(?:tba|tbc|tbd|tab)\.*)?""",
        RegexOption.IGNORE_CASE
    )

/**
 * Whether [name] is a bare guest slot ("+ Guest", "Guests", "Gäste", "Guest DJs");
 * [GUEST_SLOT_PATTERN], anchored.
 */
fun isGuestSlotLabel(name: String): Boolean = GUEST_SLOT_PATTERN.matches(name.trim().replace(WHITESPACE, " "))

/**
 * A `+` slot that adds a programme item to the night, not an act: Klunkerkranich bills `+ DJ
 * Workshop (Workshop Sold Out)` and Kater `+ Darkroom` (#3202). The `+` must lead, so a `+` that
 * joins two acts is untouched, and one word may stand before the item.
 */
private val PROGRAMME_ITEM_SLOT = Regex("""\+\s*(?:\p{L}+\s+)?(?:workshops?|darkroom)(?:\s*\(.*\))?""", RegexOption.IGNORE_CASE)

/** Whether [name] is a slot that bills nobody: a [guest slot][isGuestSlotLabel] or a [PROGRAMME_ITEM_SLOT]. */
private fun isUnbilledSlot(name: String): Boolean = isGuestSlotLabel(name) || PROGRAMME_ITEM_SLOT.matches(name.trim().replace(WHITESPACE, " "))

/**
 * True when [name] must never be stored as an artist: a placeholder ("TBA"), a bare role label
 * ("Special Guest"), a segment ("Acid Aftershow"), an event ("Shred Fest"), a "DJ set" label, a
 * guest slot ("+ Guest"), a programme item ("+ Darkroom"), a curated title ("The Revival Tour"), a
 * head-count ("20 DJ's"), a night name ("Autumn Bash 2026"), or a name that slugs to nothing ("-"). The single predicate applied
 * wherever names are resolved.
 */
fun isNonArtistName(name: String): Boolean =
    isPlaceholderName(name) || isNonArtistLabel(name) || isEventSegmentLabel(name) ||
        isNonArtistEvent(name) || isScoreConcertTitle(name) || isDjSetFormatLabel(name) || isUnbilledSlot(name) || isDenylistedNonArtist(name) ||
        isTitleFragment(name) || isSlugless(name) || isBareNumber(name) || isTimeRange(name) || isNumberedVolume(name) ||
        isHeadCount(name)

/**
 * A number of performers rather than a performer: Gretchen bills `Afro Haus Soundsystem / + 20 DJ's`
 * (#2270). The number comes first and the noun directly after, so `2 Many DJs` is still an act.
 */
private val HEAD_COUNT = Regex("""\d+\s*(?:dj'?s?|djanes?|acts?|bands?|artists?|künstler(?:innen)?|musiker(?:innen)?)""", RegexOption.IGNORE_CASE)

/** Whether [name] is only a [HEAD_COUNT]. */
private fun isHeadCount(name: String): Boolean = HEAD_COUNT.matches(name.trim())

/** A `Vol 5` / `Vol. 12` / `Volume 3` ending: a series edition, never a performer (#1952). */
private val NUMBERED_VOLUME = Regex("""\bvol(?:\.|ume)?\s*\d+$""", RegexOption.IGNORE_CASE)

/** Whether [name] ends in a [NUMBERED_VOLUME], so it names an edition of a series. */
private fun isNumberedVolume(name: String): Boolean = NUMBERED_VOLUME.containsMatchIn(name.trim())

/**
 * A name with nothing a slug can keep is a separator the split left behind (#1553).
 * `artist.slug` is UNIQUE, so the second such name from any source would fail its whole import.
 */
fun isSlugless(name: String): Boolean = SlugGenerator.slugify(name).isBlank()

private val BARE_NUMBER = Regex("""\d+""")

/**
 * Acts whose whole name is a number, kept against [isBareNumber]. Name by name, like
 * [KNOWN_SINGLE_ACTS]: Columbiahalle bills `Support: 255 & BLUMENGARTEN` (#1953), and Huxleys
 * bills `+ Support: 234` (#2207).
 */
private val KNOWN_NUMERIC_ACTS: Set<String> = setOf("234", "255")

/** A digits-only name is the tail of a `<show> 1 & 2` billing, never an act (#1556), unless it is a [KNOWN_NUMERIC_ACTS] name. */
internal fun isBareNumber(name: String): Boolean = BARE_NUMBER.matches(name.trim()) && name.trim() !in KNOWN_NUMERIC_ACTS

/** A clock range that a title can be cut down to: Arcanoa's `19-21Uhr` (#1841). */
private val TIME_RANGE = Regex("""\d{1,2}(?:[:.]\d{2})?\s*[-–]\s*\d{1,2}(?:[:.]\d{2})?\s*uhr""", RegexOption.IGNORE_CASE)

/** Whether [name] is only a [TIME_RANGE]. */
private fun isTimeRange(name: String): Boolean = TIME_RANGE.matches(name.trim())

/**
 * A candidate still carrying a title's `|` separator ("SKETCHY SESSIONS | jazz") is a slice of
 * the title (#1494). No performer writes a pipe; length says nothing, since "…And You Will Know
 * Us by the Trail of Dead" is one band.
 */
private fun isTitleFragment(name: String): Boolean = name.contains('|')
