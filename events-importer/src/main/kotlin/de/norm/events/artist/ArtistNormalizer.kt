package de.norm.events.artist

import de.norm.events.common.deshoutWord
import de.norm.events.common.foldTypedApostrophes
import de.norm.events.common.isShortInitialism

// Artist-name canonicalization for the scraper pipeline.
//
// The same act is written many ways across venue websites ("GREEN LUNG", "Green Lung"). Slugs are
// case-insensitive, so these already resolve to one artist row — but whichever import creates the
// row first also fixes its *display name*, so an act can be stored SHOUTING forever.
// [canonicalArtistName] de-shouts the name to a stable display form before it is persisted.
//
// Unlike promoters, the transform is casing-only, beside one apostrophe fold
// ([foldTypedApostrophes]): no word is ever stripped, because every word in a
// band name can be load-bearing ("The The", "Wolf Alice", "Arcade Fire Concerts"). Each shouted
// ALL-CAPS word is title-cased by [deshoutWord], which `common` shares with the promoter
// normalizer and which documents the tokens it leaves alone. One rule is applied here rather than
// there: a name that is a *single* short all-caps token ("JJ", "MØ") is an initialism far more
// often than a shouted word, and [isShortInitialism] keeps it verbatim. Scoped to the whole name,
// so a short word inside a longer one still de-shouts ("WARS OF ATTRITION" -> "Wars of Attrition").

/**
 * Returns the de-shouted display form of an artist [raw] name (see file header),
 * then applies any curated [NAME_CORRECTIONS] entry. Algorithmically casing-only:
 * no words are added or removed except by an explicit correction. Falls back to the
 * trimmed input when normalization would leave nothing.
 */
fun canonicalArtistName(raw: String): String {
    val trimmed = raw.trim().foldTypedApostrophes()
    val tokens = trimmed.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
    // A whole name that is a single short all-caps token is an initialism/stylisation
    // ("JJ", "MØ"), not a shouted word — keep it verbatim rather than minting "Jj"/"Mø".
    val canonical =
        if (tokens.size == 1 && tokens[0].isShortInitialism()) {
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
        "oxo86" to "Oxo 86",
        // Stylisations the de-shouter cannot tell from a shouted word (#2082). A colon is not a
        // rule of its own: `KOS:MO` and `RE:NI` would come out no better.
        "ccosmo" to "CCOSMO",
        "formatb" to "Format:B"
    )

/** Lowercased, punctuation- and space-free lookup key for a name. */
private fun String.normalizedKey(): String = lowercase().replace(NON_WORD_REGEX, "")

/** Everything except letters (incl. German umlauts) and digits — used to normalize a name for lookup. */
private val NON_WORD_REGEX = Regex("""[^a-z0-9äöüßø]""")

private val WHITESPACE_REGEX = Regex("""\s+""")
