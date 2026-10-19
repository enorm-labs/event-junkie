package de.norm.events.artist

import de.norm.events.common.deshoutWord
import de.norm.events.common.foldTypedApostrophes
import de.norm.events.common.isCapitals
import de.norm.events.common.isMixedCase
import de.norm.events.common.isShortInitialism
import de.norm.events.slug.SlugGenerator

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
// normalizer and which documents the tokens it leaves alone. Two rules read the whole name rather
// than one token:
//   - a name that is a *single* short all-caps token ("JJ", "MØ") is an initialism far more often
//     than a shouted word, and [isShortInitialism] keeps it verbatim. A short word inside a longer
//     shouted name still de-shouts ("WARS OF ATTRITION" -> "Wars of Attrition");
//   - a name with a mixed-case word ([isMixedCase]) is not shouted, so a lone all-caps word in it
//     is an initialism and stays as written ("HB&P Soundsystem", "ABF & Friends", #3507). Two
//     all-caps words side by side are still a shouted name: "Kai & Funky von TON STEINE SCHERBEN"
//     stores "Ton Steine Scherben". A token without letters ("&") does not separate them.

/**
 * Returns the de-shouted display form of an artist [raw] name (see file header),
 * then applies any curated [NAME_CORRECTIONS] entry. Algorithmically casing-only:
 * no words are added or removed except by an explicit correction. Falls back to the
 * trimmed input when normalization would leave nothing.
 */
fun canonicalArtistName(raw: String): String {
    val trimmed = raw.trim().foldTypedApostrophes()
    val tokens = trimmed.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
    val canonical =
        if (tokens.size == 1 && tokens[0].isShortInitialism()) {
            tokens[0]
        } else {
            tokens.deshoutBilling().joinToString(" ").ifBlank { trimmed }
        }
    return NAME_CORRECTIONS[canonical.normalizedKey()] ?: canonical
}

/** The tokens of a billing, de-shouted except for a lone all-caps word in a mixed-case billing (file header). */
private fun List<String>.deshoutBilling(): List<String> {
    if (none { it.isMixedCase() }) return map { it.deshoutWord() }
    val worded = indices.filter { index -> this[index].any(Char::isLetter) }
    return mapIndexed { index, token ->
        val position = worded.indexOf(index)
        val neighbours = listOfNotNull(worded.getOrNull(position - 1), worded.getOrNull(position + 1))
        if (position >= 0 && neighbours.any { this[it].isCapitals() }) token.deshoutWord() else token
    }
}

/**
 * The slug an artist [name] resolves to: an [ARTIST_SLUG_OVERRIDES] entry, or the slug of its canonical name.
 * Every place that turns an artist name into a slug goes through here, or the import and the admin API disagree.
 */
fun artistSlugFor(name: String): String {
    val canonical = canonicalArtistName(name)
    return ARTIST_SLUG_OVERRIDES[canonical.lowercase()] ?: SlugGenerator.slugify(canonical)
}

/**
 * A second act whose name folds to a slug another act holds, mapped to a slug of its own (#2942).
 *
 * The key is the canonical name in lower case, accents kept. That is what separates `Göre` from `Gore`, which
 * [SlugGenerator] folds to one slug. Names that are exactly identical cannot be keyed here (#2954).
 *
 * The act already stored keeps the plain slug, so no published URL moves. An entry names both acts' sources in its
 * comment, and ships with a data migration that moves the stored links. Artists only: no promoter clash is known.
 */
private val ARTIST_SLUG_OVERRIDES: Map<String, String> =
    mapOf(
        // The German band Göre, billed by Monarch (kottimonarch.de). The US band Gore, billed by UFO im Velodrom
        // beside I Prevail, keeps `gore`.
        "göre" to "goere"
    )

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
        "formatb" to "Format:B",
        // The act writes its name in capitals, and ESNS, festivalinfo.nl and Säälchen bill it so (#3623).
        "micha" to "MICHA",
        // One DJ billed with and without the prefix: the same club, or the same scene at two (/artist-cleanup).
        "djbenwashington" to "Ben Washington",
        "djbuttchin" to "Buttchin",
        "djdannyd" to "Danny D",
        "djsterni" to "Sterni",
        "sterni" to "Sterni",
        "djtroymytea" to "Troy Mytea",
        "djallynx" to "Allynx",
        "djeggsdelicious" to "Eggs Delicious",
        "djlobotomy" to "Lobotomy",
        "djdonrogall" to "Don Rogall",
        // One act, spaced two ways by two venues.
        "bluesky" to "Blue Sky",
        "traumamia" to "Trauma Mia",
        "limboboys" to "Limbo Boys",
        "uppercuts" to "The Uppercuts",
        "darktenor" to "The Dark Tenor"
    )

/** Lowercased, punctuation- and space-free lookup key for a name. */
private fun String.normalizedKey(): String = lowercase().replace(NON_WORD_REGEX, "")

/** Everything except letters (incl. German umlauts) and digits — used to normalize a name for lookup. */
private val NON_WORD_REGEX = Regex("""[^a-z0-9äöüßø]""")

private val WHITESPACE_REGEX = Regex("""\s+""")
