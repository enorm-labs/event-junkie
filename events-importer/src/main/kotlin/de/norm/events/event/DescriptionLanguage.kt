package de.norm.events.event

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The language a description is written in, as far as a stop-word count can tell.
 *
 * [detect] answers German or English only, and an honest `null` for everything else: a line-up list,
 * a two-line note, a Spanish biography, or one field that carries both languages (ADR-026). [classify]
 * adds [UNDETERMINED] for a text that is neither of our two for certain: one the Spanish, French,
 * Italian and Turkish stop words lead (#4685), or one written mostly in a non-Latin script (#4665).
 * The rule is deliberately not a library. Lingua ships every language model in one jar for a question with two
 * answers, and a stop-word count is measured on the real corpus in [DescriptionLanguageTest].
 */
enum class DescriptionLanguage(
    val code: String
) {
    GERMAN("de"),
    ENGLISH("en"),

    /** BCP 47 `und`: neither German nor English, and no guess at which language. Cyrillic alone does not tell Russian from Ukrainian. */
    UNDETERMINED("und")
    ;

    /** A detection, with the share of stop-word hits the winning language took. */
    data class Detection(
        val language: DescriptionLanguage,
        val confidence: BigDecimal
    )

    companion object {
        /** Words that belong to one language and not the other. `in`, `an`, `was` and `her` are both, so neither. */
        private val GERMAN_STOP_WORDS =
            words(
                "und der die das nicht mit für von den dem des ein eine einer einem einen auf ist sind wir ihr euch " +
                    "uhr im am zum zur bei aus nach über auch oder wird werden sich es sie er dass wie noch nur mehr " +
                    "wenn aber hier alle als zu um"
            )

        /** The English half of the same list. */
        private val ENGLISH_STOP_WORDS =
            words(
                "and the with for from this that you your are our of to on at is be as by it its more new his their " +
                    "has have were but not they we can which who about"
            )

        /** Spanish, French, Italian and Turkish markers. A text they lead is neither of our two. */
        private val OTHER_STOP_WORDS =
            words(
                "el la los las es un una para por con del que y le les des et est dans une il di che per non è da " +
                    "nel della ve bir bu için ile"
            )

        private val WORD = Regex("[a-zäöüßàáâãçéèêíóôõúñ]+")

        /** Under this many words a text has no grammar to count, whatever language it is in. */
        private const val MIN_WORDS = 8
        private const val MIN_HITS = 3

        /** Stop words as a share of all words. A line-up of names clears the word floor and fails this. */
        private const val MIN_HIT_DENSITY = 0.10

        /** The winner's share of the hits. Below it the field holds both languages rather than one. */
        private const val MIN_SHARE = 0.8
        private const val CONFIDENCE_SCALE = 3

        private fun words(list: String): Set<String> = list.split(" ").toSet()

        /** The language of [text], or null when the count cannot say. */
        fun detect(text: String?): Detection? =
            text
                ?.let { Counts.of(it) }
                ?.takeIf { it.isDecisive() }
                ?.let {
                    Detection(
                        language = if (it.german > it.english) GERMAN else ENGLISH,
                        confidence = BigDecimal(it.share()).setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP)
                    )
                }

        /** Under this many letters a text is a note, not prose to translate. */
        private const val MIN_SCRIPT_LETTERS = 40

        /** The share of letters outside the Latin script above which a text is written in another script. */
        private const val MIN_FOREIGN_SHARE = 0.5

        /**
         * [detect], else [UNDETERMINED] for a text a third language leads or a non-Latin script fills.
         * The language stored for an event.
         */
        fun classify(text: String?): Detection? = detect(text) ?: text?.let { thirdLanguage(it) ?: otherScript(it) }

        private fun thirdLanguage(text: String): Detection? =
            Counts.of(text).thirdLanguageShare()?.let {
                Detection(UNDETERMINED, BigDecimal(it).setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP))
            }

        private fun otherScript(text: String): Detection? {
            val letters = text.filter { it.isLetter() }
            if (letters.length < MIN_SCRIPT_LETTERS) return null
            val share = letters.count { Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }.toDouble() / letters.length
            return Detection(UNDETERMINED, BigDecimal(share).setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP)).takeIf { share > MIN_FOREIGN_SHARE }
        }

        /**
         * The language with more stop words in [text], however few: a weaker answer than [detect], for
         * a line too short to call on its own. Null on a tie.
         */
        fun leaning(text: String): DescriptionLanguage? {
            val counts = Counts.of(text)
            return when {
                counts.german > counts.english -> GERMAN
                counts.english > counts.german -> ENGLISH
                else -> null
            }
        }

        /** How many words of each kind one text holds. */
        private data class Counts(
            val total: Int,
            val german: Int,
            val english: Int,
            val other: Int
        ) {
            private val hits = german + english
            private val leader = maxOf(german, english)

            fun share(): Double = leader.toDouble() / hits

            /** Whether the counts name one of the two languages rather than merely leaning at one. */
            fun isDecisive(): Boolean = hasEnoughToJudge() && !isLedByAThirdLanguage() && share() >= MIN_SHARE

            private fun hasEnoughToJudge(): Boolean = total >= MIN_WORDS && hits >= MIN_HITS && hits >= total * MIN_HIT_DENSITY

            private fun isLedByAThirdLanguage(): Boolean = other * 2 > leader

            /**
             * The third-language markers' share of all hits, when they carry the text on their own. The
             * share floor is [MIN_SHARE], not [isLedByAThirdLanguage]'s: a field that holds German and
             * Spanish is bilingual, and an English translation would hide its German half.
             */
            fun thirdLanguageShare(): Double? {
                if (total < MIN_WORDS || other < MIN_HITS || other < total * MIN_HIT_DENSITY) return null
                return (other.toDouble() / (other + hits)).takeIf { it >= MIN_SHARE }
            }

            companion object {
                fun of(text: String): Counts {
                    val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
                    return Counts(
                        total = words.size,
                        german = words.count { it in GERMAN_STOP_WORDS },
                        english = words.count { it in ENGLISH_STOP_WORDS },
                        other = words.count { it in OTHER_STOP_WORDS }
                    )
                }
            }
        }

        /** SHA-256 of [text], hex. Stored beside a derived text so a changed original invalidates it. */
        fun hash(text: String): String =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            )
    }
}
