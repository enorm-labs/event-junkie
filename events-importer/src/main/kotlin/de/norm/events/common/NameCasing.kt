package de.norm.events.common

// De-shouting shared by the artist and promoter normalizers (#304) and the relocation note reader (#2213).
//
// A venue website writes the same name "GREEN LUNG" or "Green Lung", "TV NOIR" or "TV Noir".
// Slugs are case-insensitive, so both spellings resolve to one row — but whichever import creates
// the row first fixes its display name, so a name can be stored SHOUTING forever. [deshoutWord]
// title-cases a shouted word and leaves four kinds of token alone:
//   - tokens already carrying a lowercase letter, so intentional styling survives ("DJ Koze",
//     "will.i.am", "GoGo");
//   - tokens with a digit or a "." / "/" / "$" — stylised names and dotted initialisms rather
//     than plain words ("MC5", "AC/DC", "R.E.M.");
//   - recognised acronyms in [ACRONYMS], so "DJ KOZE" becomes "DJ Koze" and not "Dj Koze", and
//     "TV NOIR" becomes "TV Noir" and not "Tv Noir";
//   - nothing else: a short all-caps token ("JJ", "HB") is a word here, and the caller decides
//     when a whole name is an initialism instead, via [isShortInitialism].
//
// A genuine all-caps name of three or more letters that is not in [ACRONYMS] ("NVST", "MGMT") is
// title-cased here like any shouted word, since nothing here tells the two apart. MusicBrainz is the
// lookup table: an artist with an EXACT match takes MusicBrainz's spelling when it differs from the
// stored name in letter case only, and the slug stays (ArtistEnrichment, #2317). A promoter has no
// such lookup. Display-only either way: slugs are case-insensitive, so the resolved row is unaffected.

/**
 * Title-cases a shouted word (see [isShoutedWord]); returns any other token unchanged. A hyphen
 * joins words, so each part is de-shouted on its own: "K-POP" to "K-Pop", "DJ-SLOT" to "DJ-Slot"
 * (#1846). A token with a digit or a stylising character stays whole, as before ("BLINK-182").
 */
fun String.deshoutWord(): String =
    when {
        any { it.isDigit() || it in STYLISED_CHARS } -> this
        '-' in this -> split('-').joinToString("-") { it.deshoutPart() }
        else -> deshoutPart()
    }

private fun String.deshoutPart(): String = if (isShoutedWord()) titleCaseKeepingPunctuation() else this

/**
 * Whether the token is a short initialism to keep verbatim: only letters, no lowercase, and at
 * most [SHORT_INITIALISM_MAX_LEN] characters long. Far more often an initialism than a shouted
 * word ("JJ", "MØ", "HB"), and it reads as a typo title-cased. Callers apply it to a whole name
 * rather than to every token, so a short word inside a longer shouted name still de-shouts
 * ("WARS OF ATTRITION" -> "Wars of Attrition").
 */
fun String.isShortInitialism(): Boolean = length <= SHORT_INITIALISM_MAX_LEN && any { it.isLetter() } && none { it.isLowerCase() || it.isDigit() }

/** Max length of an all-caps token kept as an initialism rather than de-shouted. */
private const val SHORT_INITIALISM_MAX_LEN = 2

/**
 * A token is a shouted word — safe to title-case — when it has letters, no lowercase, no
 * digit or "." / "/" / "$" (which mark stylised names and dotted initialisms), and is not
 * a recognised acronym. Punctuation like apostrophes, parentheses, "!" or "," does not exempt
 * it, so possessives and bracketed words de-shout too ("MURPHY'S" -> "Murphy's").
 */
private fun String.isShoutedWord(): Boolean =
    any { it.isLetter() } &&
        none { it.isLowerCase() } &&
        none { it.isDigit() || it in STYLISED_CHARS } &&
        uppercase() !in ACRONYMS

/**
 * Uppercases the first letter, lowercases the rest, leaving every non-letter character in place.
 * The letter after an Irish `O'` prefix stays capital too ("O'NEILL" -> "O'Neill", #2914). Any other
 * apostrophe lowers what follows: "MURPHY'S" -> "Murphy's", and "D'NICE" -> "D'nice", since stored
 * `D'` names use both styles.
 */
private fun String.titleCaseKeepingPunctuation(): String {
    val letters = StringBuilder()
    var capitalizeNext = true
    return buildString {
        for (ch in this@titleCaseKeepingPunctuation) {
            when {
                ch in APOSTROPHES -> append(ch).also { if (letters.toString() == "O") capitalizeNext = true }
                !ch.isLetter() -> append(ch)
                capitalizeNext -> append(ch.uppercaseChar()).also { capitalizeNext = false }
                else -> append(ch.lowercaseChar())
            }
            if (ch.isLetter()) letters.append(ch.uppercaseChar())
        }
    }
}

private val APOSTROPHES = setOf('\'', '\u2019')

/** Characters that mark a token as a stylised name or dotted initialism, not a plain word ("$ONO$"). */
private val STYLISED_CHARS = setOf('.', '/', '$')

/**
 * Acronyms/initialisms kept in their capitals when they appear as a standalone word,
 * so "DJ KOZE" de-shouts to "DJ Koze" rather than "Dj Koze". Deliberately tight —
 * a broader set risks freezing genuine words in caps — and extended as new music
 * acronyms surface. Compared case-insensitively (see [isShoutedWord]).
 */
private val ACRONYMS: Set<String> =
    setOf(
        "DJ",
        "MC",
        "VJ",
        "UK",
        "US",
        "USA",
        "FM",
        "AM",
        "TV",
        "EP",
        "LP",
        "NYC",
        "LA",
        "EDM",
        "DIY",
        "RIP",
        "FX",
        // Act names that are themselves initialisms — kept in caps so they aren't flattened.
        "FKJ",
        "AZ",
        "DBG",
        "LSD",
        "SDP",
        "UFO",
        // A DJ handle that is the act's own initials; without an entry the two-token
        // "DJ JC" reads as a shouted word and de-shouts to "DJ Jc" (the standalone
        // short-initialism rule only covers a single-token name).
        "JC",
        // Promoters that are themselves initialisms: the tour agency "KKT" and the concert arm
        // of the Anschutz group, "AEG Presents".
        "KKT",
        "AEG",
        "FKP",
        // Act names that are initialisms and lose their capitals as a shouted word (#2082): `UVB`
        // (Berghain), `Joe BRT` (Club der Visionäre), `DJ TC` (Matrix).
        "UVB",
        "BRT",
        "TC"
    )

/**
 * An apostrophe typed as a backtick or an acute accent, folded to `'`: `Dingo`s Dream`,
 * `pris´n break`, `Rock`n`Roll` (#2174). Only between two letters, so a lone accent or a leading
 * `´s` stays. `'` is the form every stored name used before this. The slug is unchanged, because
 * the slugger turns all three characters into `-`.
 */
fun String.foldTypedApostrophes(): String = replace(TYPED_APOSTROPHE, "'")

private val TYPED_APOSTROPHE = Regex("""(?<=\p{L})[`\u00B4](?=\p{L})""")
