package de.norm.events.promoter

import de.norm.events.common.deshoutWord

// Promoter-name canonicalization. One promoter is written many ways ("LOFT", "Loft Concerts
// GmbH"), so slugging the raw name fragments it; [canonicalPromoterName] reduces variants to one
// form before slugging, conservatively and deterministically:
// 1. Strip a trailing run of legal-form and descriptor words (GmbH, UG, "Concerts", "Konzerte",
// "Music", "Events", …). Trailing only, so "Concert Concept" stays intact.
// 2. De-shout ALL-CAPS words ("SIMPLY QUIZ" to "Simply Quiz") with the artist normalizer's
// [deshoutWord], so its acronym list applies ("TV NOIR" to "TV Noir") and "GreyZone" survives.
// 3. Fold known typos and spacing variants via a curated map ("Trinty" to "Trinity", "Allrooms"
// to "All Rooms"), keyed punctuation- and space-insensitively. Exact matches only; fuzzy
// matching would merge distinct promoters.
//
// At least one word is always kept, and never a residue nobody would search for: "Records"
// keeps its word, "36 Concerts" does not collapse to "36", "HB Music" and "MFP Concerts" keep
// their descriptor and capitals (#1361). A legal form still comes off an initialism ("KKT GmbH"
// is "KKT"). Accepted: a leading descriptor is not stripped, so "Konzertbüro Schoneberg" does
// not merge with "Schoneberg Konzerte" without a map entry. Artists are not normalized this
// way; stripping words from band names is unsafe.

/**
 * The canonical form of a promoter [raw] name (file header); the trimmed input when
 * normalization would leave nothing.
 */
fun canonicalPromoterName(raw: String): String {
    // Drop a trailing parenthetical ("Mind Enterprises GmbH (wf)") first, so the strip can reach
    // the legal-form words it was shielding.
    val withoutAnnotation =
        raw
            .trim()
            .replace(TRAILING_PAREN_REGEX, "")
            .trim()
            .ifBlank { raw.trim() }
    val tokens =
        withoutAnnotation
            .split(WHITESPACE_REGEX)
            .filter { it.isNotBlank() }
            .toMutableList()
    if (tokens.isEmpty()) return raw.trim()

    // Drop the trailing run of strippable tokens, but keep a usable name: "36 Concerts" must not
    // become "36", nor "HB Music" the bare "HB".
    while (tokens.size > 1 && tokens.last().isStrippableTrailingWord() && tokens.dropLast(1).isUsableNameWithout(tokens.last())) {
        tokens.removeAt(tokens.lastIndex)
    }

    // The initialism the guard kept a descriptor for keeps its capitals ("HB Music", not "Hb
    // Music").
    val keepFirst = tokens.first().isLoneInitialism() && tokens.drop(1).all { it.isStrippableTrailingWord() }
    val canonical =
        tokens
            .mapIndexed { index, token -> if (index == 0 && keepFirst) token else token.deshoutWord() }
            .joinToString(" ")
            .ifBlank { raw.trim() }
    return NAME_CORRECTIONS[canonical.normalizedKey()] ?: canonical
}

/**
 * Whether these tokens still name something once [stripped] comes off: a letter-bearing word,
 * and not a lone initialism left by a descriptor. A legal form leaves the initialism alone.
 */
private fun List<String>.isUsableNameWithout(stripped: String): Boolean =
    any { word -> word.any(Char::isLetter) } && !(size == 1 && first().isLoneInitialism() && !stripped.isLegalForm())

/** Up to three capitals and nothing else: "HB", "MFP", "ITD" — an initialism a descriptor belongs to. */
private fun String.isLoneInitialism(): Boolean = length <= LONE_INITIALISM_MAX_LEN && all(Char::isLetter) && none(Char::isLowerCase)

private const val LONE_INITIALISM_MAX_LEN = 3

/**
 * Whether [raw] is not a promoter but something a source dropped into the promoter slot, three
 * shapes seen on staging (#1318): a name consisting entirely of [STRIP_WORDS] or punctuation
 * ("Event.", "Konzerts GmbH"); a lone token of one or two letters ("Ar", "Qu"); a name on
 * [NON_PROMOTER_NAMES]. Runs before [canonicalPromoterName], which always keeps one word, and
 * reads the raw credit, so "MFP Concerts" is judged whole (#1361).
 */
fun isNonPromoterName(raw: String): Boolean {
    val tokens =
        raw
            .trim()
            .replace(TRAILING_PAREN_REGEX, "")
            .split(WHITESPACE_REGEX)
            .filter { it.isNotBlank() }
    // Keyed on the name without its presenter verb, so "… präsentieren:" still matches.
    val named = tokens.dropLastWhile { it.isStrippableTrailingWord() }.ifEmpty { tokens }
    return tokens.isEmpty() ||
        tokens.all { it.isStrippableTrailingWord() } ||
        tokens.singleOrNull()?.isTinyToken() == true ||
        named.joinToString(" ").normalizedKey() in NON_PROMOTER_NAMES
}

/** One or two letters and nothing else: a fragment, not a name ("Ar", "Qu"). */
private fun String.isTinyToken(): Boolean = length <= 2 && all(Char::isLetter)

/** Lowercased, punctuation-free lookup key for a name (matches [String.isStrippableTrailingWord]'s scheme). */
private fun String.normalizedKey(): String = lowercase().replace(NON_WORD_REGEX, "")

/** A token is strippable if, stripped of punctuation, it is empty (a connector like "&") or a known word. */
private fun String.isStrippableTrailingWord(): Boolean {
    val key = lowercase().replace(NON_WORD_REGEX, "")
    return key.isEmpty() || key in STRIP_WORDS
}

private fun String.isLegalForm(): Boolean = lowercase().replace(NON_WORD_REGEX, "") in LEGAL_FORMS

private val WHITESPACE_REGEX = Regex("""\s+""")

/** A trailing parenthetical annotation (" (wf)", " (GSA)") appended to a promoter name. */
private val TRAILING_PAREN_REGEX = Regex("""\s*\([^)]*\)\s*$""")

/** Everything except letters (incl. German umlauts) and digits — used to normalize a token for lookup. */
private val NON_WORD_REGEX = Regex("""[^a-z0-9äöüß]""")

/**
 * Trailing words removed during canonicalization: German/English legal forms plus generic
 * descriptors. Kept tight to limit accidental merges.
 */
private val LEGAL_FORMS: Set<String> =
    setOf(
        "gmbh",
        "mbh",
        "ug",
        "gbr",
        "kg",
        "ohg",
        "ag",
        "ev",
        "ou",
        "oü",
        "ltd",
        "llc",
        "inc",
        "co"
    )

private val STRIP_WORDS: Set<String> =
    LEGAL_FORMS +
        setOf(
            // Generic promoter descriptors
            "concert",
            "concerts",
            "konzert",
            "konzerte",
            "music",
            "musik",
            "events",
            "event",
            "booking",
            "agency",
            "agentur",
            "promotion",
            "promotions",
            "entertainment",
            "live",
            "records",
            "production",
            "productions",
            // Longer German forms that split one promoter into two rows on staging (#328). "Radio France
            // International" is why "international" is not here.
            "veranstaltungs",
            "veranstaltungsgmbh",
            "konzertproduktionen",
            "kulturproduktionen",
            "konzertagentur",
            "konzertdirektion",
            "einzelunternehmer",
            // Presenter verbs a promoter appends to its own name ("porcupine records & little league shows
            // prsnt:"); the colon is stripped before lookup. The English "presents" is absent: "AEG
            // Presents" is the company's name, and stripping it would leave "Aeg".
            "prsnt",
            "prsnts",
            "presenting",
            "präsentiert",
            "präsentieren"
        )

/**
 * Credits in the promoter slot that name no promoter (#1318): a bar night, a series label, a
 * show title, a curator's role, a sponsor's URL. Keyed like [NAME_CORRECTIONS]. A three-letter
 * fragment goes here by name, since length cannot separate it from "KKT", and only once the
 * whole credit was read: "Mfp" was "MFP Concerts" with its descriptor stripped (#1361).
 */
private val NON_PROMOTER_NAMES: Set<String> =
    setOf(
        "bum",
        "mup",
        "niemals",
        "nova",
        "slam",
        "kneipenabend",
        "sundaymatinee",
        "tagderklubkultur",
        "teamfeelfreeenergy",
        "kuratorinanikameier",
        "dasforgottenfemalecomposers",
        "thehilariousdeepamazingcomedy",
        "thehilariousdeepamazingberlincomedy",
        "peteredelwwwstummfilmkonzertede"
    )

/**
 * Known corrections, keyed on the [String.normalizedKey] of the canonicalized name, so one entry
 * folds "All Rooms" / "Allrooms" / "ALLROOMS". Only unambiguously the same promoter: a wrong
 * entry silently merges two. Because the correction runs after stripping, an entry can pin a
 * fuller form: "LOFT", "Loft Concerts" and "Loft Concerts GmbH" reduce to "Loft", and the
 * "loft" entry restores "Loft Concerts".
 */
private val NAME_CORRECTIONS: Map<String, String> =
    mapOf(
        // "Music" is stripped, so "Trinity Music" and "Trinity" both reduce here; restores the trading
        // name (#1139).
        "trinity" to "Trinity Music",
        "trinty" to "Trinity Music",
        // Huxleys' taxonomy slug drops the first word of "Konzertbüro Schoneberg".
        "schoneberg" to "Konzertbüro Schoneberg",
        "konzertbüroschoneberg" to "Konzertbüro Schoneberg",
        "radioactve" to "Radioactive",
        "allrooms" to "All Rooms",
        "loft" to "Loft Concerts",
        // "FluxFM" (Columbia Theater, Frannz), "fluxfm" (Heimathafen) and "Flux FM" (Zitadelle, which
        // de-shouts to "Flux Fm") share this key.
        "fluxfm" to "FluxFM",
        // The station spells itself in one lowercase word; one venue writes "Radio Eins".
        "radioeins" to "radioeins",
        // "tipBerlin", "tip Berlin" and Zitadelle's bare "Tip" are the city magazine; no other promoter
        // is called "Tip" (#304).
        "tipberlin" to "tipBerlin",
        "tip" to "tipBerlin",
        // Abbreviated and full trading name. The acronym list keeps "KKT" in capitals; the first entry
        // catches a "Kkt" stored before it did (#304).
        "kkt" to "KKT",
        "kktgmbhkikiskleinertourneeservice" to "KKT",
        // Pairs that split one promoter on staging (#328), the rest after the strip; each pins the
        // promoter's own spelling.
        "allroom" to "All Rooms",
        "atok" to "ATOK Berlin",
        "atokberlin" to "ATOK Berlin",
        "audiolithinternational" to "Audiolith",
        "streetlife" to "Streetlife International",
        // The agency renamed itself, and one venue still credits the old name.
        "listenagency" to "Friendly Reminder",
        "fkpscorpio" to "FKP Scorpio",
        "greyzone" to "Greyzone Concerts",
        "greyzoneconcertspromotiongreyvonbronikowski" to "Greyzone Concerts",
        // The person is not the company: "Konzertdirektion" is stripped above and restored here.
        "karstenjahnke" to "Karsten Jahnke Konzertdirektion",
        "känguruh" to "Känguruh Production",
        "kaenguruh" to "Känguruh Production",
        "messedupmagazine" to "Messed!Up Magazine",
        "musikblog" to "MusikBlog",
        "musikblogde" to "MusikBlog",
        "prkdreamhaus" to "PRK DreamHaus",
        "prkdreamhouse" to "PRK DreamHaus",
        "rausgeganger" to "Rausgegangen",
        "punkfilmfestivalberlin" to "punkfilmfest berlin",
        // The spelling each promoter uses on its own site (#328, docs/promoters/REVIEWED.tsv), with a
        // stripped descriptor restored where it is part of the brand.
        "11freunde" to "11FREUNDE",
        "aokdiegesundheitskasse" to "AOK",
        "atocsoundlab" to "ATOC Soundlab",
        "aufdiegutetour" to "Auf die gute Tour",
        "aufnahmewiedergabe" to "aufnahme + wiedergabe",
        "berlinkonzerte" to "New Berlin Konzerte",
        "newberlin" to "New Berlin Konzerte",
        "boese" to "Boese Live",
        "bricks" to "BRICKS",
        "chnsw" to "CHNSW!",
        "diffus" to "DIFFUS",
        "dlf" to "Deutschlandfunk",
        "doomstar" to "Doomstar Bookings",
        "doomstarbookings" to "Doomstar Bookings",
        "gotobeat" to "Gotobeat",
        "headline" to "Headline Concerts",
        "ibb" to "IBB Booking",
        "kingstar" to "Kingstar Music",
        "kinkyhub" to "KinkyHub Berlin",
        "kulturalarm" to "kulturALARM",
        "kulturnews" to "kulturnews",
        "landstreicher" to "Landstreicher Konzerte",
        "larsberndt" to "Lars Berndt Events",
        "mawi" to "MAWI Concert",
        "mbkonzerte" to "MB Konzerte",
        "mct" to "MCT Agentur",
        "metalde" to "metal.de",
        "oxfancine" to "Ox-Fanzine",
        "powerline" to "Powerline Agency",
        "radiobob" to "RADIO BOB!",
        "radiofranceinternational" to "Radio France Internationale",
        "rockitsessions" to "Rockitsessions",
        "rudelsingendasoriginalausmünster" to "Rudelsingen",
        "semmel" to "Semmel Concerts",
        "stiftungwissensart" to "Stiftung Wissensart",
        "touringtunesspzoo" to "TouringTunes",
        "unreleased" to "Unreleased Berlin",
        "unreleasedberlin" to "Unreleased Berlin",
        "zart" to "Z|ART Agency",
        // Huxleys credits "JB Freie Musik presents": the strip took the descriptor and the de-shout read
        // the initials as a word, so the row was "Jb Freie" (#307).
        "jbfreie" to "JB Freie Musik",
        "jmaudio" to "JM Audio Entertainment",
        // Lido credits "Atoc Live"; the company is ATOC Soundlab (#1343).
        "atoc" to "ATOC Soundlab",
        "atoclive" to "ATOC Soundlab",
        "atocsoundlab" to "ATOC Soundlab",
        // Credits whose descriptor the strip took and #1318 read as fragments (#1361); the initialisms
        // keep their descriptor now, and these restore rows stored before.
        "act" to "ACT Agency",
        "actagency" to "ACT Agency",
        "channel" to "Channel Music",
        "itd" to "ITD Events",
        "mfp" to "MFP Concerts",
        "spirit" to "Spirit Events",
        "leasingrent" to "LEASING&RENT OÜ"
    )
