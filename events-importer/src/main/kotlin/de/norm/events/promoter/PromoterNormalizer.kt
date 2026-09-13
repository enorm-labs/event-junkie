package de.norm.events.promoter

import de.norm.events.common.deshoutWord

// Promoter-name canonicalization for the scraper pipeline.
//
// The same real-world promoter is written many ways across venue websites: an abbreviated label on
// one site ("LOFT"), a fuller trading name on another ("Loft Concerts GmbH"). Resolving promoters by
// `slugify(name)` alone therefore fragments one promoter into several rows. [canonicalPromoterName]
// reduces those variants to a shared canonical form *before* slugging.
//
// The transform is deliberately conservative and deterministic:
//   1. Strip a *trailing* run of legal-form and generic-descriptor words (GmbH, UG, …, "Concerts",
//      "Konzerte", "Music", "Events", …). Only trailing words, so a name whose descriptor is
//      load-bearing and not at the end — "Concert Concept" — is left intact.
//   2. De-shout ALL-CAPS words ("SIMPLY QUIZ" → "Simply Quiz") for an order-independent display
//      name, with the [deshoutWord] the artist normalizer uses, so its acronym list and stylised
//      tokens apply here too ("TV NOIR" → "TV Noir"); intentional mixed casing ("GreyZone") is
//      preserved.
//   3. Fold known source typos and spacing variants onto one canonical spelling via a curated map
//      ("Trinty" → "Trinity", "Allrooms" → "All Rooms"). The lookup key is punctuation- and
//      space-insensitive, so one entry covers "All Rooms", "Allrooms" and "ALLROOMS" alike. Only
//      exact (normalized) matches are corrected — fuzzy matching would risk merging genuinely
//      distinct promoters.
//
// At least one word is always kept, and stripping never leaves a residue nobody would search for:
// "Records" keeps its single word, "36 Concerts" does not collapse to "36", and "HB Music" and
// "MFP Concerts" keep their descriptor and their capitals (#1361). A legal form still comes off
// an initialism ("KKT GmbH" is "KKT"): it is a suffix, not part of the name.
//
// Accepted: a *leading* descriptor is not stripped, so "Konzertbüro Schoneberg" does not merge with
// "Schoneberg Konzerte" without an explicit correction-map entry. Artists are deliberately not
// normalized this way — stripping words from band names is unsafe.

/**
 * Returns the canonical form of a promoter [raw] name (see file header). Falls
 * back to the trimmed input when normalization would leave nothing.
 */
fun canonicalPromoterName(raw: String): String {
    // Drop a trailing parenthetical annotation ("Mind Enterprises GmbH (wf)" → "Mind
    // Enterprises GmbH") before tokenizing, so the descriptor-strip below can reach the
    // real legal-form/descriptor words that the annotation was shielding.
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

    // Drop the trailing run of legal-form / descriptor / connector tokens, but always keep a
    // usable name: stripping "Concerts" off "36 Concerts" would leave the bare number "36", and
    // stripping "Music" off "HB Music" the bare initialism "HB".
    while (tokens.size > 1 && tokens.last().isStrippableTrailingWord() && tokens.dropLast(1).isUsableNameWithout(tokens.last())) {
        tokens.removeAt(tokens.lastIndex)
    }

    // The initialism the guard above kept a descriptor for is an initialism, so it keeps its
    // capitals ("HB Music", not "Hb Music"). Every other token de-shouts as usual.
    val keepFirst = tokens.first().isLoneInitialism() && tokens.drop(1).all { it.isStrippableTrailingWord() }
    val canonical =
        tokens
            .mapIndexed { index, token -> if (index == 0 && keepFirst) token else token.deshoutWord() }
            .joinToString(" ")
            .ifBlank { raw.trim() }
    return NAME_CORRECTIONS[canonical.normalizedKey()] ?: canonical
}

/**
 * Whether these tokens still name something once [stripped] comes off: a letter-bearing word, and
 * not a lone initialism left by a descriptor. A legal form leaves the initialism alone.
 */
private fun List<String>.isUsableNameWithout(stripped: String): Boolean =
    any { word -> word.any(Char::isLetter) } && !(size == 1 && first().isLoneInitialism() && !stripped.isLegalForm())

/** Up to three capitals and nothing else: "HB", "MFP", "ITD" — an initialism a descriptor belongs to. */
private fun String.isLoneInitialism(): Boolean = length <= LONE_INITIALISM_MAX_LEN && all(Char::isLetter) && none(Char::isLowerCase)

private const val LONE_INITIALISM_MAX_LEN = 3

/**
 * Whether [raw] is not a real promoter but something a source dropped into the promoter slot.
 *
 * Three shapes, each seen on staging (#1318): a name that, once a trailing parenthetical is
 * dropped, consists *entirely* of legal-form and descriptor words ([STRIP_WORDS]) or punctuation
 * ("Event.", "Konzerts GmbH"); a lone token of one or two letters ("Ar", "Qu"), which nobody
 * searches for; and a name on [NON_PROMOTER_NAMES]. Runs before [canonicalPromoterName], which
 * always keeps one word and so cannot drop these itself. It reads the raw credit, so a promoter
 * whose descriptor the strip takes ("MFP Concerts") is judged by its whole name (#1361).
 */
fun isNonPromoterName(raw: String): Boolean {
    val tokens =
        raw
            .trim()
            .replace(TRAILING_PAREN_REGEX, "")
            .split(WHITESPACE_REGEX)
            .filter { it.isNotBlank() }
    // The list is keyed on the name without its presenter verb, so a credit that adds one
    // ("… präsentieren:") still matches.
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
 * Trailing words removed during canonicalization: German/English legal forms plus
 * generic promoter descriptors. Kept intentionally tight to limit accidental merges;
 * extend it as new venues surface new descriptor conventions.
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
            // Longer German forms that staging showed splitting one promoter into two rows (#328).
            // "Radio France International" is why "international" is not here: it would become
            // "Radio France", which is a different broadcaster.
            "veranstaltungs",
            "veranstaltungsgmbh",
            "konzertproduktionen",
            "kulturproduktionen",
            "konzertagentur",
            "konzertdirektion",
            "einzelunternehmer",
            // Presenter *verbs* a promoter appends to its own name when it heads a billing
            // ("porcupine records & little league shows prsnt:"). Punctuation is stripped before the
            // lookup, so the trailing colon is already handled.
            //
            // The plain English "presents" is deliberately **absent**: it is a brand word as often as a
            // verb — "AEG Presents" is the company's actual name, and stripping it would leave "Aeg".
            "prsnt",
            "prsnts",
            "presenting",
            "präsentiert",
            "präsentieren"
        )

/**
 * Credits a source prints in the promoter slot that name no promoter (#1318): a bar night, a
 * series label, a show title, a curator's role, a sponsor's URL, or a word with nothing
 * behind it. Keyed like [NAME_CORRECTIONS], so casing and punctuation do not matter. A three-letter
 * fragment goes here by name because the length alone cannot separate it from "KKT" or "IBB" —
 * and only once the whole credit was read: "Mfp" was "MFP Concerts" with its descriptor
 * stripped (#1361).
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
 * Known name corrections — source typos and spelling/spacing variants — keyed on the
 * [String.normalizedKey] of the canonicalized name and mapped to the correct display
 * spelling. The key is punctuation- and space-insensitive, so a single entry folds every
 * spacing/casing variant with the same letters (e.g. "All Rooms" / "Allrooms" / "ALLROOMS").
 * Only add entries that are unambiguously the same real promoter — this map merges promoter
 * entities, so a wrong entry silently collapses two distinct promoters into one.
 *
 * Because the correction runs *after* descriptor-stripping, an entry can also pin a fuller
 * display form that includes a stripped descriptor: "LOFT", "Loft Concerts" and
 * "Loft Concerts GmbH" all reduce to "Loft" first, so the single "loft" entry restores the
 * preferred brand name "Loft Concerts" for every variant.
 */
private val NAME_CORRECTIONS: Map<String, String> =
    mapOf(
        // "Music" is a stripped descriptor, so "Trinity Music" and "Trinity" both reduce to this
        // key; the entry restores the agency's trading name, as "loft" does below (#1139).
        "trinity" to "Trinity Music",
        "trinty" to "Trinity Music",
        // Huxleys' taxonomy slug drops the first word of "Konzertbüro Schoneberg"; folded here so
        // rows minted from the slug resolve to the same promoter as the visible credit.
        "schoneberg" to "Konzertbüro Schoneberg",
        "konzertbüroschoneberg" to "Konzertbüro Schoneberg",
        "radioactve" to "Radioactive",
        "allrooms" to "All Rooms",
        "loft" to "Loft Concerts",
        // Three sources, three spellings — "FluxFM" (Columbia Theater, Frannz), "fluxfm"
        // (Heimathafen) and "Flux FM" (Zitadelle), the last of which de-shouts to "Flux Fm".
        // All four share this key, so one entry folds them onto the station's own casing.
        "fluxfm" to "FluxFM",
        // The station spells itself in one lowercase word; one venue writes it "Radio Eins".
        // Both share this key, so the entry folds them onto the broadcaster's own branding.
        "radioeins" to "radioeins",
        // "tipBerlin" (one venue), "tip Berlin" (another) and the bare "Tip" Zitadelle prints are
        // the city magazine, which writes itself "tipBerlin"; no other promoter is called "Tip" (#304).
        "tipberlin" to "tipBerlin",
        "tip" to "tipBerlin",
        // The tour agency appears both abbreviated and under its full trading name. The shared
        // acronym list keeps "KKT" in its capitals; the first entry still catches a "Kkt" that
        // an earlier import stored before it did (#304).
        "kkt" to "KKT",
        "kktgmbhkikiskleinertourneeservice" to "KKT",
        // Pairs that split one promoter into two rows on staging (#328). Where a variant is a
        // legal form or a descriptor, it is stripped above; these are the rest, and each one
        // pins the spelling the promoter uses itself.
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
        // The spelling each promoter uses on its own site, where the venues' credit differs from it
        // (#328, docs/promoters/REVIEWED.tsv). A descriptor the strip removes is restored where it
        // is part of the brand, the way "Loft Concerts" is above.
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
        // Huxleys credits "JB Freie Musik presents" and "JM Audio Entertainment presents": the strip
        // takes the descriptor and the de-shout then reads the two-letter initials as a word, so
        // the row was "Jb Freie" (#307). The entry restores the trading name, as "Loft Concerts" does.
        "jbfreie" to "JB Freie Musik",
        "jmaudio" to "JM Audio Entertainment",
        // Lido credits "Atoc Live"; the company is ATOC Soundlab (#1343).
        "atoc" to "ATOC Soundlab",
        "atoclive" to "ATOC Soundlab",
        "atocsoundlab" to "ATOC Soundlab",
        // Credits whose descriptor the strip took, and #1318 then read as fragments (#1361). The
        // initialisms keep their descriptor now; these entries restore the rows stored before.
        "act" to "ACT Agency",
        "actagency" to "ACT Agency",
        "channel" to "Channel Music",
        "itd" to "ITD Events",
        "mfp" to "MFP Concerts",
        "spirit" to "Spirit Events",
        "leasingrent" to "LEASING&RENT OÜ"
    )
