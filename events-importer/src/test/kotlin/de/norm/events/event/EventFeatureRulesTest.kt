package de.norm.events.event

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test

class EventFeatureRulesTest {
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("crowdAndDressPositives", "policyAndTimePositives")
    fun `a clear phrase sets the feature and keeps the phrase`(
        feature: EventFeature,
        text: String,
        phrase: String
    ) {
        val detection = EventFeatureRules.detect(text)

        detection.features shouldContainExactly listOf(EventFeatureMatch(feature, phrase))
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("nearMisses")
    fun `a near-miss sets no feature`(
        feature: EventFeature,
        text: String
    ) {
        EventFeatureRules.detect(text).features.map { it.feature } shouldBe emptyList()
        // The feature named is the one the text comes close to; nothing else slips in either.
        EventFeatureRules
            .detect(text)
            .features
            .firstOrNull { it.feature == feature }
            .shouldBeNull()
    }

    @Test
    fun `a cue without a clear phrase is uncertain, with its surrounding words`() {
        val detection = EventFeatureRules.detect("Ein Abend mit FLINTA* DJs aus Berlin und Leipzig")

        detection.features.shouldBeEmpty()
        detection.uncertain shouldContainExactly
            listOf(EventFeatureMatch(EventFeature.FLINTA_ONLY, "Ein Abend mit FLINTA* DJs aus Berlin und Leipzig"))
    }

    @Test
    fun `a long text cuts the cue's context and marks the cut`() {
        val text = "x".repeat(80) + " queer artists on two floors " + "y".repeat(80)

        val uncertain = EventFeatureRules.detect(text).uncertain.single()

        uncertain.feature shouldBe EventFeature.QUEER
        uncertain.phrase shouldContain "queer"
        uncertain.phrase.first() shouldBe '…'
        uncertain.phrase.last() shouldBe '…'
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("performerCues")
    fun `FLINTA tied to who plays or takes part is a cue, not flinta-only`(text: String) {
        val detection = EventFeatureRules.detect(text)

        detection.features.map { it.feature } shouldNotContain EventFeature.FLINTA_ONLY
        detection.uncertain.map { it.feature } shouldContainExactly listOf(EventFeature.FLINTA_ONLY)
    }

    @Test
    fun `Crack Bellmer's open decks keep the decks, not the door, for FLINTA`() {
        val detection =
            EventFeatureRules.detect(
                "OPEN DECKS FOR FLINTA* - PING PONG FOR ALL",
                null,
                "Yes, you are very welcome to play ping pong, hang out, and be an ally. " +
                    "We kindly ask that you respect the space behind the decks as exclusively for FLINTA*. Free entry."
            )

        detection.features.map { it.feature } shouldContainExactly listOf(EventFeature.OPEN_STAGE)
        detection.uncertain.single().phrase shouldContain "behind the decks as exclusively for FLINTA*"
    }

    @Test
    fun `a door restriction next to the decks stays flinta-only`() {
        EventFeatureRules.detect("FLINTA* only party with open decks").features.map { it.feature } shouldContainExactly
            listOf(EventFeature.FLINTA_ONLY, EventFeature.OPEN_STAGE)
    }

    @Test
    fun `a clear phrase settles its own cue`() {
        EventFeatureRules.detect("FLINTA* only. All FLINTA* welcome.").uncertain.shouldBeEmpty()
    }

    @Test
    fun `a fetish code wins over the plain dress code and its cue`() {
        val detection = EventFeatureRules.detect("Dresscode: Fetisch, Latex, Leder. Dress to impress!")

        detection.features.map { it.feature } shouldContainExactly listOf(EventFeature.FETISH_DRESS_CODE)
        detection.uncertain.shouldBeEmpty()
    }

    @Test
    fun `no dress code with a fetish word after it is a cue, not a code`() {
        val detection = EventFeatureRules.detect("Kein Dresscode, aber Fetisch ist willkommen")

        detection.features.shouldBeEmpty()
        detection.uncertain.map { it.feature } shouldContainExactly listOf(EventFeature.FETISH_DRESS_CODE)
    }

    @Test
    fun `a code that bans something is still a code`() {
        EventFeatureRules.detect("Dresscode: no sneakers").features.map { it.feature } shouldContainExactly listOf(EventFeature.DRESS_CODE)
    }

    @Test
    fun `the features come in vocabulary order from title, subtitle and description together`() {
        val detection = EventFeatureRules.detect("Day Rave", null, "Queer party, open end. Bitte keine Fotos auf dem Floor.")

        detection.features.map { it.feature } shouldContainExactly
            listOf(EventFeature.QUEER, EventFeature.NO_PHOTO_POLICY, EventFeature.OPEN_END, EventFeature.DAY_PARTY)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("prose")
    fun `a duration, a film title or a figure of speech in prose is no cue`(text: String) {
        EventFeatureRules.detect(text).uncertain.shouldBeEmpty()
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("partyCues")
    fun `a cue next to the night itself stays a cue`(
        feature: EventFeature,
        text: String,
        phrase: String
    ) {
        val uncertain = EventFeatureRules.detect(text).uncertain.single()

        uncertain.feature shouldBe feature
        uncertain.phrase shouldContain phrase
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("jamNames")
    fun `a bare jam is a name, not an open-stage cue`(text: String) {
        EventFeatureRules.detect(text).uncertain.shouldBeEmpty()
    }

    @Test
    fun `no text detects nothing`() {
        EventFeatureRules.detect(null, " ").features.shouldBeEmpty()
    }

    @Test
    fun `a phrase broken over lines is kept on one line`() {
        EventFeatureRules
            .detect("FLINTA*\n  only")
            .features
            .single()
            .phrase shouldBe "FLINTA* only"
    }

    companion object {
        @JvmStatic
        fun crowdAndDressPositives(): List<Arguments> =
            listOf(
                Arguments.of(EventFeature.FLINTA_ONLY, "Diese Party ist nur für FLINTA*.", "nur für FLINTA*"),
                Arguments.of(EventFeature.FLINTA_ONLY, "Doors 23:00. FLINTA* only!", "FLINTA* only"),
                Arguments.of(EventFeature.FLINTA_ONLY, "No cis men on this night.", "No cis men"),
                Arguments.of(EventFeature.FLINTA_ONLY, "FLINTA* only night", "FLINTA* only"),
                Arguments.of(EventFeature.FLINTA_ONLY, "Einlass nur für FLINTA*, DJs: Ana b2b Mo", "nur für FLINTA*"),
                Arguments.of(EventFeature.QUEER, "Die queere Party im Wedding", "queere Party"),
                Arguments.of(EventFeature.QUEER, "A queer rave for everyone", "queer rave"),
                Arguments.of(EventFeature.QUEER, "Clubnacht für die queere Community", "Clubnacht für die queere Community"),
                Arguments.of(EventFeature.QUEER, "GAYHANE - QueerOriental Dancefloor", "QueerOriental Dancefloor"),
                Arguments.of(EventFeature.QUEER, "Eine queere Tanzfläche im Keller", "queere Tanzfläche"),
                Arguments.of(
                    EventFeature.QUEER,
                    "Queer & Friends Party #5: 3 Dancefloors für Queers, Friends und alle, die mitfeiern wollen",
                    "Dancefloors für Queers"
                ),
                Arguments.of(EventFeature.QUEER, "Two dancefloors for queer folks and allies", "dancefloors for queer folks"),
                Arguments.of(EventFeature.QUEER, "Queerer Tresen", "Queerer Tresen"),
                Arguments.of(EventFeature.QUEER, "Mittwochs: Queer-Tresen mit DJ", "Queer-Tresen"),
                Arguments.of(EventFeature.QUEER, "Ein Tresen für Queers und Freund*innen", "Tresen für Queers"),
                Arguments.of(EventFeature.QUEER, "A queer, high-energy party for Berlin", "queer, high-energy party"),
                Arguments.of(EventFeature.QUEER, "Eine queere, laute Nacht im Keller", "queere, laute Nacht"),
                Arguments.of(EventFeature.QUEER, "The queer friendly dance party", "queer friendly dance party"),
                Arguments.of(EventFeature.QUEER, "GAY CHRISTMAS: A QUEER FLINTA* HALLOWEEN PARTY", "QUEER FLINTA* HALLOWEEN PARTY"),
                Arguments.of(EventFeature.QUEER, "This is a queer FLINTA* costume party.", "queer FLINTA* costume party"),
                Arguments.of(EventFeature.QUEER, "This is only the beginning - more to follow!Queer event, safe space. Playroom.", "Queer event"),
                Arguments.of(EventFeature.QUEER, "Eine queere Veranstaltung im Keller", "queere Veranstaltung"),
                Arguments.of(EventFeature.SEX_POSITIVE, "Eine sexpositive Nacht mit Darkroom", "sexpositive"),
                Arguments.of(EventFeature.SEX_POSITIVE, "A sex-positive party with a play area", "sex-positive"),
                Arguments.of(EventFeature.DRESS_CODE, "Dresscode: ganz in Weiß", "Dresscode"),
                Arguments.of(EventFeature.DRESS_CODE, "Strict dress code: all black", "dress code"),
                Arguments.of(EventFeature.FETISH_DRESS_CODE, "Fetisch-Dresscode an der Tür", "Fetisch-Dresscode"),
                Arguments.of(EventFeature.FETISH_DRESS_CODE, "Dress code: rubber, leather or latex", "Dress code: rubber")
            )

        @JvmStatic
        fun policyAndTimePositives(): List<Arguments> =
            listOf(
                Arguments.of(EventFeature.NO_PHOTO_POLICY, "Fotografieren ist streng verboten.", "Fotografieren ist streng verboten"),
                Arguments.of(EventFeature.NO_PHOTO_POLICY, "We put stickers on your phone cameras at the door.", "stickers on your phone"),
                Arguments.of(
                    EventFeature.NO_PHOTO_POLICY,
                    "Fester Bestandteil der Shows ist auch diesmal die No Phones Policy. Bei This Never Happened-Events",
                    "No Phones Policy"
                ),
                Arguments.of(EventFeature.NO_PHOTO_POLICY, "Strictly NO phones in the club allowed.", "Strictly NO phones"),
                Arguments.of(
                    EventFeature.NO_PHOTO_POLICY,
                    "Wie immer setzen wir auf ein geheimes Lineup und verbotene Handykameras.",
                    "verbotene Handykameras"
                ),
                Arguments.of(EventFeature.NO_PHOTO_POLICY, "Auf dem Floor gilt: keine Handy-Kameras.", "keine Handy-Kameras"),
                Arguments.of(EventFeature.NO_PHOTO_POLICY, "Kameras sind verboten.", "Kameras sind verboten"),
                Arguments.of(EventFeature.NO_PHOTO_POLICY, "Handykameras untersagt", "Handykameras untersagt"),
                Arguments.of(EventFeature.OPEN_END, "Ab 23 Uhr bis Ende offen", "Ende offen"),
                Arguments.of(EventFeature.OPEN_END, "From 23:00 till open end", "open end"),
                Arguments.of(EventFeature.DAY_PARTY, "Die Tagesparty im Garten", "Tagesparty"),
                Arguments.of(EventFeature.DAY_PARTY, "Sunday Day Party on the terrace", "Day Party"),
                Arguments.of(EventFeature.DAY_PARTY, "Let's Swing - Tanztee", "Tanztee"),
                Arguments.of(EventFeature.DAY_PARTY, "Kommt ins Ballhaus zu unserem Sonntags-Tanztee", "Tanztee"),
                Arguments.of(EventFeature.DAY_PARTY, "Tanztee im PETER EDEL", "Tanztee"),
                Arguments.of(EventFeature.DAY_PARTY, "A queer tea dance on Sunday afternoon", "tea dance"),
                Arguments.of(EventFeature.OPEN_STAGE, "Open Stage Varieté präsentiert von Stefan Linne", "Open Stage"),
                Arguments.of(EventFeature.OPEN_STAGE, "ARCANOA - Open Stage", "Open Stage"),
                Arguments.of(EventFeature.OPEN_STAGE, "JamSession", "JamSession"),
                Arguments.of(EventFeature.OPEN_STAGE, "Grünes Licht (Open Mic Show)", "Open Mic"),
                Arguments.of(EventFeature.OPEN_STAGE, "Comedy, Pizza and Shots – OPEN-MIC THURSDAY", "OPEN-MIC"),
                Arguments.of(EventFeature.OPEN_STAGE, "THE ZIG ZAG JAZZED UP JAM SESSION", "JAM SESSION"),
                Arguments.of(EventFeature.OPEN_STAGE, "OPEN DECKS & TISCHTENNIS", "OPEN DECKS"),
                Arguments.of(EventFeature.OPEN_STAGE, "Offene Bühne", "Offene Bühne"),
                Arguments.of(EventFeature.OPEN_STAGE, "Jeden Dienstag: offene Jam für alle", "offene Jam"),
                Arguments.of(
                    EventFeature.OPEN_STAGE,
                    "The CCB Improv Jam is an open stage for improvisers looking to practice",
                    "open stage"
                ),
                Arguments.of(EventFeature.OPEN_STAGE, "Berlin’s finest live hip-hop jam session. Open Stage, Open Mic.", "Open Stage")
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
        fun jamNames(): List<String> =
            listOf(
                "Doppelkonzert: Glam Jam + Mission BlueZ",
                "Modern Jam",
                "MOCKY - LA meets BERLIN JAM with MEGIAPA",
                "Produced by Brendan O’Brien (Pearl Jam, Soundgarden), on air at JAM FM",
                "Expect chill house and sultry slow jams",
                "Mambo: DJ JAM, Bachata: DJ Reza"
            )

        @JvmStatic
        fun prose(): List<String> =
            listOf(
                "Er schläft im Laden ein, obwohl sein Späti bereits 24 Stunden geöffnet ist.",
                "Fat Mike once spent 24 hours in Disneyland jail.",
                "Er half, an „From Dusk Till Dawn“ (1995) mitzuarbeiten.",
                "Wir feiern Songs, die lange kein Tageslicht mehr gesehen haben."
            )

        @JvmStatic
        fun partyCues(): List<Arguments> =
            listOf(
                Arguments.of(EventFeature.OPEN_END, "Der 24h Rave im Hof", "24h Rave"),
                Arguments.of(EventFeature.OPEN_END, "Die 36 Stunden Party zum Jubiläum", "36 Stunden Party"),
                Arguments.of(EventFeature.OPEN_END, "Join us for 48 hours of techno", "48 hours of techno"),
                Arguments.of(EventFeature.OPEN_END, "We dance till dawn", "till dawn"),
                Arguments.of(EventFeature.DAY_PARTY, "Tanzen im Tageslicht auf der Terrasse", "Tageslicht"),
                Arguments.of(EventFeature.OPEN_STAGE, "The CCB Improv Jam", "Improv Jam"),
                Arguments.of(EventFeature.OPEN_STAGE, "Jam Night im Keller", "Jam Night"),
                Arguments.of(
                    EventFeature.NO_PHOTO_POLICY,
                    "Ihr benutzt bitte keine Handys oder ähnliche Hilfsmittel, sonst macht das Quiz keinen Spaß.",
                    "keine Handys"
                )
            )

        @JvmStatic
        fun nearMisses(): List<Arguments> =
            listOf(
                Arguments.of(EventFeature.FLINTA_ONLY, "Ein FLINTA*-Lineup, die Tür ist offen für alle"),
                Arguments.of(EventFeature.FLINTA_ONLY, "An all-FLINTA* lineup, everyone welcome"),
                Arguments.of(EventFeature.QUEER, "Eine Lesung über queere Geschichte"),
                Arguments.of(EventFeature.QUEER, "Queer artists from Berlin present new work"),
                Arguments.of(EventFeature.QUEER, "Ein Gespräch zu queeren Aspekten der Clubkultur"),
                Arguments.of(EventFeature.QUEER, "FLINTA* LOVE AUF DER TANZFLÄCHE"),
                Arguments.of(EventFeature.QUEER, "Zwei Dancefloors für alle"),
                Arguments.of(EventFeature.QUEER, "Tresen"),
                Arguments.of(EventFeature.QUEER, "Stammtisch am Tresen"),
                Arguments.of(EventFeature.QUEER, "queer artists play a party"),
                Arguments.of(EventFeature.QUEER, "a queer event series of talks about queer artists"),
                Arguments.of(EventFeature.QUEER, "Queer DJs night"),
                Arguments.of(EventFeature.QUEER, "Queer FLINTA* DJs night"),
                Arguments.of(EventFeature.QUEER, "Ein Vortrag zu queeren Aspekten der Clubnacht"),
                Arguments.of(EventFeature.QUEER, "Queer Cinema Talk\nClub night afterwards"),
                Arguments.of(EventFeature.QUEER, "A queer reading. Party later"),
                Arguments.of(EventFeature.SEX_POSITIVE, "Ein Abend über Sexualität und Positivität"),
                Arguments.of(EventFeature.SEX_POSITIVE, "Sex Pistols tribute night"),
                Arguments.of(EventFeature.DRESS_CODE, "Kein Dresscode, komm wie du bist"),
                Arguments.of(EventFeature.DRESS_CODE, "Dress code: none."),
                Arguments.of(EventFeature.DRESS_CODE, "DRESSCODE? Be yourself. We care about authenticity."),
                Arguments.of(EventFeature.DRESS_CODE, "Kostüme erwünscht"),
                Arguments.of(EventFeature.FETISH_DRESS_CODE, "Ein Film über Fetisch und Mode"),
                Arguments.of(EventFeature.FETISH_DRESS_CODE, "Her fetish for analogue synths"),
                Arguments.of(EventFeature.NO_PHOTO_POLICY, "Fotos: Jane Doe"),
                Arguments.of(EventFeature.NO_PHOTO_POLICY, "Photos of the last edition are online"),
                Arguments.of(EventFeature.NO_PHOTO_POLICY, "Ihr benutzt bitte keine Handys"),
                Arguments.of(EventFeature.NO_PHOTO_POLICY, "Kamera: Jane Doe, Schnitt: John Doe"),
                Arguments.of(EventFeature.NO_PHOTO_POLICY, "Ein Film über verbotene Liebe, gedreht mit drei Kameras"),
                Arguments.of(EventFeature.OPEN_END, "Ein Gespräch mit offenem Ende"),
                Arguments.of(EventFeature.OPEN_END, "An open-ended discussion about club culture"),
                Arguments.of(EventFeature.DAY_PARTY, "Wir feiern unsere Geburtstagsparty"),
                Arguments.of(EventFeature.DAY_PARTY, "A birthday party with a holiday party afterwards"),
                Arguments.of(EventFeature.DAY_PARTY, "Tea Party mit Kuchen und Lesung"),
                Arguments.of(EventFeature.DAY_PARTY, "Tanzteekessel"),
                Arguments.of(EventFeature.OPEN_STAGE, "Song Slam: acht Songwriter*innen, ein Sieg"),
                Arguments.of(EventFeature.OPEN_STAGE, "Karaoke Night mit DJ Hasi"),
                Arguments.of(EventFeature.OPEN_STAGE, "Schülervorspiel der Musikschule Fanny Hensel"),
                Arguments.of(EventFeature.OPEN_STAGE, "Songwriting workshop mit Dave Benjoya | anschließend Karaoke open stage"),
                Arguments.of(EventFeature.OPEN_STAGE, "Jam-Session-Workshop für Bläser*innen"),
                Arguments.of(EventFeature.OPEN_STAGE, "JAMES BLAKE | „Trying Times Tour“ + Support: MONICA MARTIN"),
                Arguments.of(EventFeature.OPEN_STAGE, "Declan Forde Trio | Plays Ahmad Jamal"),
                Arguments.of(EventFeature.OPEN_STAGE, "Doppelkonzert: Glam Jam + Mission BlueZ"),
                Arguments.of(EventFeature.OPEN_STAGE, "Produced by Brendan O’Brien (Pearl Jam, Soundgarden), on air at JAM FM"),
                Arguments.of(EventFeature.OPEN_STAGE, "Expect chill house and sultry slow jams")
            )
    }
}
