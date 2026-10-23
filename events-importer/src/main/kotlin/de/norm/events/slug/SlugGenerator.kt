package de.norm.events.slug

import com.github.slugify.Slugify
import java.text.Normalizer
import java.util.Locale

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

    private val transliterating =
        Slugify
            .builder()
            .customReplacements(NON_DECOMPOSING_LATIN + CYRILLIC + GREEK_ELOT)
            .build()

    /**
     * Converts [input] into a URL-friendly slug (e.g. "My Event" → "my-event").
     *
     * A name in Cyrillic or Greek with no Latin letter is transliterated, so `Кино 2` is `kino-2`, not `2` (#3475). A name
     * with a Latin letter keeps its plain slug, so `Друга Ріка (Druha Rika)` stays `druha-rika`.
     *
     * A name that slugs to nothing is slugged again with `@` spelled `at`, so the band `@` is `at` (#3793). Only then:
     * `Funk@delic` keeps `funk-delic`, and `–` stays empty.
     */
    fun slugify(input: String): String = slugOnce(input).ifBlank { if ('@' in input) slugOnce(input.replace("@", " at ")) else "" }

    private fun slugOnce(input: String): String =
        if (TRANSLITERATED_SCRIPT.containsMatchIn(input) && !LATIN_LETTER.containsMatchIn(input)) {
            transliterating.slugify(withoutMarks(input))
        } else {
            slugify.slugify(input)
        }

    /** Lower-cases [input] and strips its marks, so the tables need no capital or accented letter; `й ё ї` keep theirs as `y`. */
    private fun withoutMarks(input: String): String {
        val lower = Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFC)
        val marked = MARKED_CYRILLIC.entries.fold(lower) { text, (letter, latin) -> text.replace(letter, latin) }
        return Normalizer.normalize(marked, Normalizer.Form.NFKD).replace(MARKS, "")
    }
}

private val TRANSLITERATED_SCRIPT = Regex("[\\p{IsCyrillic}\\p{IsGreek}]")
private val LATIN_LETTER = Regex("\\p{IsLatin}")
private val MARKS = Regex("\\p{M}+")

/** The Cyrillic letters whose mark changes the sound, replaced before the marks are stripped. */
private val MARKED_CYRILLIC: Map<String, String> =
    mapOf(
        "й" to "y",
        "ё" to "yo",
        "ї" to "yi"
    )

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

/**
 * Russian, Ukrainian and Belarusian letters in a BGN/PCGN-style romanisation without diacritics: `Юлия` → `yuliya`, `Объект` → `obekt`.
 *
 * ICAO 9303, the passport scheme, writes `iuliia` and `obieekt`, which nobody types. `г` is `g` in every language: the script
 * does not say whether a name is Ukrainian, where `h` is usual.
 */
private val CYRILLIC: Map<String, String> =
    mapOf(
        "а" to "a",
        "б" to "b",
        "в" to "v",
        "г" to "g",
        "ґ" to "g",
        "д" to "d",
        "е" to "e",
        "є" to "ye",
        "ж" to "zh",
        "з" to "z",
        "и" to "i",
        "і" to "i",
        "к" to "k",
        "л" to "l",
        "м" to "m",
        "н" to "n",
        "о" to "o",
        "п" to "p",
        "р" to "r",
        "с" to "s",
        "т" to "t",
        "у" to "u",
        "ф" to "f",
        "х" to "kh",
        "ц" to "ts",
        "ч" to "ch",
        "ш" to "sh",
        "щ" to "shch",
        "ъ" to "",
        "ы" to "y",
        "ь" to "",
        "э" to "e",
        "ю" to "yu",
        "я" to "ya"
    )

/**
 * Greek letters in ELOT 743 transliteration: `Μουσική` → `mousiki`.
 *
 * The digraphs come first, because the replacements run in order. ELOT 743 writes `αυ` and `ευ` as `af` and `ef` before a
 * voiceless consonant. This table always writes `av` and `ev`, and keeps `μπ` and `γκ` letter by letter.
 */
private val GREEK_ELOT: Map<String, String> =
    mapOf(
        "ου" to "ou",
        "αυ" to "av",
        "ευ" to "ev",
        "ηυ" to "iv",
        "γγ" to "ng",
        "γξ" to "nx",
        "γχ" to "nch",
        "α" to "a",
        "β" to "v",
        "γ" to "g",
        "δ" to "d",
        "ε" to "e",
        "ζ" to "z",
        "η" to "i",
        "θ" to "th",
        "ι" to "i",
        "κ" to "k",
        "λ" to "l",
        "μ" to "m",
        "ν" to "n",
        "ξ" to "x",
        "ο" to "o",
        "π" to "p",
        "ρ" to "r",
        "σ" to "s",
        "ς" to "s",
        "τ" to "t",
        "υ" to "y",
        "φ" to "f",
        "χ" to "ch",
        "ψ" to "ps",
        "ω" to "o"
    )
