package de.norm.events.common

import java.text.Normalizer
import java.util.Locale

/** Checks a passage a model says it quoted against the text it was given. A model's quote is never trusted as is. */
object QuotedText {
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val APOSTROPHES = Regex("[‘’‛ʼ´`]")
    private val QUOTES = Regex("[“”„‟«»]")
    private val DASHES = Regex("[‐-―]")

    /** Emoji and other pictographs, with their joiners and skin tones. They carry no fact, and a model often drops them from a quote. */
    private val SYMBOLS = Regex("[\\p{So}\\p{Sk}\\u200D]+")
    private val WHITESPACE = Regex("\\s+")

    /** Case, accents, quote and dash shapes, emoji and line breaks: what a faithful quote can still change. */
    fun normalise(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .replace(APOSTROPHES, "'")
            .replace(QUOTES, "\"")
            .replace(DASHES, "-")
            .replace(SYMBOLS, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase(Locale.ROOT)

    fun occursIn(
        quote: String,
        text: String
    ): Boolean = normalise(quote) in normalise(text)

    /**
     * Each line of [quote] occurs in [text], though not next to each other. A model joins the line with
     * the date to the line with the time and leaves out the lines between them.
     */
    fun eachLineOccursIn(
        quote: String,
        text: String
    ): Boolean = quote.lines().filter { it.isNotBlank() }.let { lines -> lines.isNotEmpty() && lines.all { occursIn(it, text) } }
}
