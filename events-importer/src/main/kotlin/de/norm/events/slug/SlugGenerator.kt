package de.norm.events.slug

import com.github.slugify.Slugify

/**
 * Shared singleton for URL-friendly slug generation.
 *
 * [Slugify] is stateless and thread-safe, so a single instance is reused
 * across all services instead of each service creating its own.
 */
object SlugGenerator {
    private val slugify =
        Slugify
            .builder()
            .customReplacements(NON_DECOMPOSING_LATIN)
            .build()

    /** Converts [input] into a URL-friendly slug (e.g. "My Event" → "my-event"). */
    fun slugify(input: String): String = slugify.slugify(input)
}

/**
 * ASCII fallbacks for Latin letters that Unicode canonical decomposition (NFD) cannot split into a
 * base letter plus a combining mark.
 *
 * Slugify strips accents by normalizing to NFD and dropping everything non-ASCII, which handles the
 * *composed* letters — `ö` is `o` plus a combining diaeresis, so it survives as `o`, as do `å`, `é`,
 * `ñ`, `ğ`, `ş`. A letter whose glyph is a single indivisible code point has nothing to strip down
 * to and was **silently deleted**: `Kėkė Søl` slugged to `keke-sl`, `Revaler Straße` to
 * `revaler-strae`. Slugs are the public URL key, so a dropped letter is a permanently wrong — and
 * occasionally colliding — identifier.
 *
 * Each entry maps to the letter's **base form**, not its national expansion, so the result stays
 * consistent with the NFD stripping applied to every other letter in the same slug: `ø` → `o` beside
 * `ö` → `o`, giving `Ørlög` → `orlog`. Slugify's own `no`/`da` locale bundles would instead expand
 * `ø` → `oe` and `å` → `aa` — the correct *Norwegian* romanisation, but it would clash with the
 * surrounding letters and silently rewrite existing `å` slugs, so those bundles are deliberately not
 * used. `æ`, `œ`, `ß` and `þ` have no single base letter and take their two-letter romanisation.
 *
 * Both cases are mapped even though slug output is lower-cased, so the replacements stay correct if
 * the builder is ever configured with `lowerCase(false)`. The list is curated and reactive — the
 * letters that occur in European artist, band and street names — so add further letters as they
 * surface rather than switching on a locale.
 */
private val NON_DECOMPOSING_LATIN: Map<String, String> =
    mapOf(
        // Nordic
        "ø" to "o",
        "Ø" to "O",
        "æ" to "ae",
        "Æ" to "Ae",
        // Icelandic
        "ð" to "d",
        "Ð" to "D",
        "þ" to "th",
        "Þ" to "Th",
        // Polish
        "ł" to "l",
        "Ł" to "L",
        // Croatian / Serbian / Vietnamese
        "đ" to "d",
        "Đ" to "D",
        // Turkish dotless i (the dotted capital `İ` does decompose, so it needs no entry)
        "ı" to "i",
        // German sharp s
        "ß" to "ss",
        "ẞ" to "Ss",
        // French / Latin ligature
        "œ" to "oe",
        "Œ" to "Oe"
    )
