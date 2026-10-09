package de.norm.events.event

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test

class PartyFeatureRulesTest {
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("positives")
    fun `a clear phrase sets the feature and keeps the phrase`(
        feature: PartyFeature,
        text: String,
        phrase: String
    ) {
        val detection = PartyFeatureRules.detect(text)

        detection.features shouldContainExactly listOf(PartyFeatureMatch(feature, phrase))
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("nearMisses")
    fun `a near-miss sets no feature`(
        feature: PartyFeature,
        text: String
    ) {
        PartyFeatureRules.detect(text).features.map { it.feature } shouldBe emptyList()
        // The feature named is the one the text comes close to; nothing else slips in either.
        PartyFeatureRules
            .detect(text)
            .features
            .firstOrNull { it.feature == feature }
            .shouldBeNull()
    }

    @Test
    fun `a cue without a clear phrase is uncertain, with its surrounding words`() {
        val detection = PartyFeatureRules.detect("Ein Abend mit FLINTA* DJs aus Berlin und Leipzig")

        detection.features.shouldBeEmpty()
        detection.uncertain shouldContainExactly
            listOf(PartyFeatureMatch(PartyFeature.FLINTA_ONLY, "Ein Abend mit FLINTA* DJs aus Berlin und Leipzig"))
    }

    @Test
    fun `a long text cuts the cue's context and marks the cut`() {
        val text = "x".repeat(80) + " queer artists on two floors " + "y".repeat(80)

        val uncertain = PartyFeatureRules.detect(text).uncertain.single()

        uncertain.feature shouldBe PartyFeature.QUEER
        uncertain.phrase shouldContain "queer"
        uncertain.phrase.first() shouldBe '…'
        uncertain.phrase.last() shouldBe '…'
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("performerCues")
    fun `FLINTA tied to who plays or takes part is a cue, not flinta-only`(text: String) {
        val detection = PartyFeatureRules.detect(text)

        detection.features.shouldBeEmpty()
        detection.uncertain.map { it.feature } shouldContainExactly listOf(PartyFeature.FLINTA_ONLY)
    }

    @Test
    fun `Crack Bellmer's open decks keep the decks, not the door, for FLINTA`() {
        val detection =
            PartyFeatureRules.detect(
                "OPEN DECKS FOR FLINTA* - PING PONG FOR ALL",
                null,
                "Yes, you are very welcome to play ping pong, hang out, and be an ally. " +
                    "We kindly ask that you respect the space behind the decks as exclusively for FLINTA*. Free entry."
            )

        detection.features.shouldBeEmpty()
        detection.uncertain.single().phrase shouldContain "behind the decks as exclusively for FLINTA*"
    }

    @Test
    fun `a door restriction next to the decks stays flinta-only`() {
        PartyFeatureRules.detect("FLINTA* only party with open decks").features.map { it.feature } shouldContainExactly
            listOf(PartyFeature.FLINTA_ONLY)
    }

    @Test
    fun `a clear phrase settles its own cue`() {
        PartyFeatureRules.detect("FLINTA* only. All FLINTA* welcome.").uncertain.shouldBeEmpty()
    }

    @Test
    fun `a fetish code wins over the plain dress code and its cue`() {
        val detection = PartyFeatureRules.detect("Dresscode: Fetisch, Latex, Leder. Dress to impress!")

        detection.features.map { it.feature } shouldContainExactly listOf(PartyFeature.FETISH_DRESS_CODE)
        detection.uncertain.shouldBeEmpty()
    }

    @Test
    fun `no dress code with a fetish word after it is a cue, not a code`() {
        val detection = PartyFeatureRules.detect("Kein Dresscode, aber Fetisch ist willkommen")

        detection.features.shouldBeEmpty()
        detection.uncertain.map { it.feature } shouldContainExactly listOf(PartyFeature.FETISH_DRESS_CODE)
    }

    @Test
    fun `a code that bans something is still a code`() {
        PartyFeatureRules.detect("Dresscode: no sneakers").features.map { it.feature } shouldContainExactly listOf(PartyFeature.DRESS_CODE)
    }

    @Test
    fun `the features come in vocabulary order from title, subtitle and description together`() {
        val detection = PartyFeatureRules.detect("Day Rave", null, "Queer party, open end. Bitte keine Fotos auf dem Floor.")

        detection.features.map { it.feature } shouldContainExactly
            listOf(PartyFeature.QUEER, PartyFeature.NO_PHOTO_POLICY, PartyFeature.OPEN_END, PartyFeature.DAY_PARTY)
    }

    @Test
    fun `no text detects nothing`() {
        PartyFeatureRules.detect(null, " ").features.shouldBeEmpty()
    }

    @Test
    fun `a phrase broken over lines is kept on one line`() {
        PartyFeatureRules
            .detect("FLINTA*\n  only")
            .features
            .single()
            .phrase shouldBe "FLINTA* only"
    }

    companion object {
        @JvmStatic
        fun positives(): List<Arguments> =
            listOf(
                Arguments.of(PartyFeature.FLINTA_ONLY, "Diese Party ist nur für FLINTA*.", "nur für FLINTA*"),
                Arguments.of(PartyFeature.FLINTA_ONLY, "Doors 23:00. FLINTA* only!", "FLINTA* only"),
                Arguments.of(PartyFeature.FLINTA_ONLY, "No cis men on this night.", "No cis men"),
                Arguments.of(PartyFeature.FLINTA_ONLY, "FLINTA* only night", "FLINTA* only"),
                Arguments.of(PartyFeature.FLINTA_ONLY, "Einlass nur für FLINTA*, DJs: Ana b2b Mo", "nur für FLINTA*"),
                Arguments.of(PartyFeature.QUEER, "Die queere Party im Wedding", "queere Party"),
                Arguments.of(PartyFeature.QUEER, "A queer rave for everyone", "queer rave"),
                Arguments.of(PartyFeature.QUEER, "Clubnacht für die queere Community", "Clubnacht für die queere Community"),
                Arguments.of(PartyFeature.SEX_POSITIVE, "Eine sexpositive Nacht mit Darkroom", "sexpositive"),
                Arguments.of(PartyFeature.SEX_POSITIVE, "A sex-positive party with a play area", "sex-positive"),
                Arguments.of(PartyFeature.DRESS_CODE, "Dresscode: ganz in Weiß", "Dresscode"),
                Arguments.of(PartyFeature.DRESS_CODE, "Strict dress code: all black", "dress code"),
                Arguments.of(PartyFeature.FETISH_DRESS_CODE, "Fetisch-Dresscode an der Tür", "Fetisch-Dresscode"),
                Arguments.of(PartyFeature.FETISH_DRESS_CODE, "Dress code: rubber, leather or latex", "Dress code: rubber"),
                Arguments.of(PartyFeature.NO_PHOTO_POLICY, "Fotografieren ist streng verboten.", "Fotografieren ist streng verboten"),
                Arguments.of(PartyFeature.NO_PHOTO_POLICY, "We put stickers on your phone cameras at the door.", "stickers on your phone"),
                Arguments.of(PartyFeature.OPEN_END, "Ab 23 Uhr bis Ende offen", "Ende offen"),
                Arguments.of(PartyFeature.OPEN_END, "From 23:00 till open end", "open end"),
                Arguments.of(PartyFeature.DAY_PARTY, "Die Tagesparty im Garten", "Tagesparty"),
                Arguments.of(PartyFeature.DAY_PARTY, "Sunday Day Party on the terrace", "Day Party")
            )

        @JvmStatic
        fun performerCues(): List<String> =
            listOf(
                "Open decks for FLINTA*",
                "Auflegen für FLINTA*",
                "Ein Auflegeworkshop nur für FLINTA*",
                "The decks are exclusively for FLINTA* tonight",
                "No cis men behind the DJ booth"
            )

        @JvmStatic
        fun nearMisses(): List<Arguments> =
            listOf(
                Arguments.of(PartyFeature.FLINTA_ONLY, "Ein FLINTA*-Lineup, die Tür ist offen für alle"),
                Arguments.of(PartyFeature.FLINTA_ONLY, "An all-FLINTA* lineup, everyone welcome"),
                Arguments.of(PartyFeature.QUEER, "Eine Lesung über queere Geschichte"),
                Arguments.of(PartyFeature.QUEER, "Queer artists from Berlin present new work"),
                Arguments.of(PartyFeature.SEX_POSITIVE, "Ein Abend über Sexualität und Positivität"),
                Arguments.of(PartyFeature.SEX_POSITIVE, "Sex Pistols tribute night"),
                Arguments.of(PartyFeature.DRESS_CODE, "Kein Dresscode, komm wie du bist"),
                Arguments.of(PartyFeature.DRESS_CODE, "Dress code: none."),
                Arguments.of(PartyFeature.DRESS_CODE, "DRESSCODE? Be yourself. We care about authenticity."),
                Arguments.of(PartyFeature.FETISH_DRESS_CODE, "Ein Film über Fetisch und Mode"),
                Arguments.of(PartyFeature.FETISH_DRESS_CODE, "Her fetish for analogue synths"),
                Arguments.of(PartyFeature.NO_PHOTO_POLICY, "Fotos: Jane Doe"),
                Arguments.of(PartyFeature.NO_PHOTO_POLICY, "Photos of the last edition are online"),
                Arguments.of(PartyFeature.OPEN_END, "Ein Gespräch mit offenem Ende"),
                Arguments.of(PartyFeature.OPEN_END, "An open-ended discussion about club culture"),
                Arguments.of(PartyFeature.DAY_PARTY, "Wir feiern unsere Geburtstagsparty"),
                Arguments.of(PartyFeature.DAY_PARTY, "A birthday party with a holiday party afterwards")
            )
    }
}
