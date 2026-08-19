package de.norm.events.artist

// Artist-name canonicalization for the scraper pipeline.
//
// The same act is written many ways across venue websites ("GREEN LUNG", "Green Lung"). Slugs are
// case-insensitive, so these already resolve to one artist row — but whichever import creates the
// row first also fixes its *display name*, so an act can be stored SHOUTING forever.
// [canonicalArtistName] de-shouts the name to a stable display form before it is persisted.
//
// Unlike promoters, the transform is casing-only: no word is ever stripped, because every word in a
// band name can be load-bearing ("The The", "Wolf Alice", "Arcade Fire Concerts"). It de-shouts each
// shouted ALL-CAPS word to title case, keeping attached punctuation in place ("MURPHY'S LAW" ->
// "Murphy's Law"), and leaves four kinds of token alone:
//   - tokens already carrying a lowercase letter, so intentional styling survives ("DJ Koze",
//     "will.i.am", "GoGo");
//   - tokens with a digit or an interior "." / "/" — stylised names and dotted initialisms rather
//     than plain words ("MC5", "AC/DC", "R.E.M.");
//   - recognised acronyms in [ACRONYMS], so "DJ KOZE" becomes "DJ Koze" and not "Dj Koze";
//   - a name that is a *single* short all-caps token (≤ [SHORT_INITIALISM_MAX_LEN] letters: "JJ",
//     "MØ") — an initialism far more often than a shouted word, and it reads as a typo title-cased.
//     Scoped to the whole name, so a short word inside a longer one still de-shouts ("WARS OF
//     ATTRITION" -> "Wars of Attrition").
//
// Accepted: a genuine all-caps name of three or more letters that is not in [ACRONYMS] ("ABBA",
// "MGMT") is title-cased like any shouted word, since nothing distinguishes the two without a lookup
// table — extend [ACRONYMS] when a real act needs its capitals kept. Display-only either way: slugs
// are case-insensitive, so the resolved artist row is unaffected.

/**
 * Returns the de-shouted display form of an artist [raw] name (see file header),
 * then applies any curated [NAME_CORRECTIONS] entry. Algorithmically casing-only:
 * no words are added or removed except by an explicit correction. Falls back to the
 * trimmed input when normalization would leave nothing.
 */
fun canonicalArtistName(raw: String): String {
    val trimmed = raw.trim()
    val tokens = trimmed.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
    // A whole name that is a single short all-caps token is an initialism/stylisation
    // ("JJ", "MØ"), not a shouted word — keep it verbatim rather than minting "Jj"/"Mø".
    val canonical =
        if (tokens.size == 1 && tokens[0].isShortStandaloneInitialism()) {
            tokens[0]
        } else {
            tokens.joinToString(" ") { it.deshoutWord() }.ifBlank { trimmed }
        }
    return NAME_CORRECTIONS[canonical.normalizedKey()] ?: canonical
}

/**
 * Known spelling/spacing variants of one act, keyed on the [normalizedKey] of the
 * de-shouted name and mapped to the spelling to display.
 *
 * This is the *only* way a word is ever changed here: the casing rules above cannot fold
 * a variant that differs by a space or a hyphen, because doing so algorithmically is
 * unsafe for band names. The key is punctuation- and space-insensitive, so one entry
 * covers every spacing variant with the same letters.
 *
 * Only add an entry when the two spellings are unambiguously the **same act** — this
 * merges artist entities, so a wrong entry silently collapses two real acts into one.
 * A same-letters coincidence is not enough: "Paul K" (a DJ billed at Ritter Butzke) and
 * "Paulk" (a live act at Badehaus) share a key and are deliberately *not* folded.
 */
private val NAME_CORRECTIONS: Map<String, String> =
    mapOf(
        // The Berlin punk band, written "OXO86" by one venue and "Oxo 86" by another. Its digit
        // keeps it out of the de-shouter (a stylised token), so the two spellings never converge.
        "oxo86" to "Oxo 86"
    )

/** Lowercased, punctuation- and space-free lookup key for a name. */
private fun String.normalizedKey(): String = lowercase().replace(NON_WORD_REGEX, "")

/** Everything except letters (incl. German umlauts) and digits — used to normalize a name for lookup. */
private val NON_WORD_REGEX = Regex("""[^a-z0-9äöüßø]""")

/**
 * Whether the token is a standalone short initialism to keep verbatim: only letters,
 * no lowercase, and at most [SHORT_INITIALISM_MAX_LEN] characters long. Only applied
 * to a single-token name (see [canonicalArtistName]) so it never freezes a short word
 * ("OF", "MY") inside a longer shouted name.
 */
private fun String.isShortStandaloneInitialism(): Boolean =
    length <= SHORT_INITIALISM_MAX_LEN && any { it.isLetter() } && none { it.isLowerCase() || it.isDigit() }

/** Max length of a single-token all-caps name kept as an initialism rather than de-shouted. */
private const val SHORT_INITIALISM_MAX_LEN = 2

/** Title-cases a shouted word (see [isShoutedWord]); returns any other token unchanged. */
private fun String.deshoutWord(): String = if (isShoutedWord()) titleCaseKeepingPunctuation() else this

/**
 * A token is a shouted word — safe to title-case — when it has letters, no lowercase, no
 * digit or interior "." / "/" (which mark stylised names and dotted initialisms), and is not
 * a recognised acronym. Punctuation like apostrophes, parentheses, "!" or "," does not exempt
 * it, so possessives and bracketed words de-shout too ("MURPHY'S" -> "Murphy's").
 */
private fun String.isShoutedWord(): Boolean =
    any { it.isLetter() } &&
        none { it.isLowerCase() } &&
        none { it.isDigit() || it in STYLISED_CHARS } &&
        uppercase() !in ACRONYMS

/** Uppercases the first letter, lowercases the rest, leaving every non-letter character in place. */
private fun String.titleCaseKeepingPunctuation(): String {
    var seenLetter = false
    return buildString {
        for (ch in this@titleCaseKeepingPunctuation) {
            when {
                !ch.isLetter() -> append(ch)
                !seenLetter -> append(ch.uppercaseChar()).also { seenLetter = true }
                else -> append(ch.lowercaseChar())
            }
        }
    }
}

/** Interior characters that mark a token as a stylised name or dotted initialism, not a plain word. */
private val STYLISED_CHARS = setOf('.', '/')

private val WHITESPACE_REGEX = Regex("""\s+""")

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
        // Act names that are themselves initialisms — kept in caps so they aren't flattened.
        "FKJ",
        "AZ",
        "DBG",
        // A DJ handle that is the act's own initials; without an entry the two-token
        // "DJ JC" reads as a shouted word and de-shouts to "DJ Jc" (the standalone
        // short-initialism rule only covers a single-token name).
        "JC"
    )
