package de.norm.events.genretag

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DescriptionGenresTest {
    @Test
    fun `known genre words are read in order of mention`() {
        descriptionGenres("Eine Nacht voller Techno und Drum & Bass, dazu etwas Jazz.") shouldBe listOf("Techno", "Drum & Bass", "Jazz")
    }

    @Test
    fun `a two-word genre is one genre`() {
        descriptionGenres("Classic hip hop and punk rock all night") shouldBe listOf("Hip Hop", "Punk")
    }

    @Test
    fun `a genre in a support or guest clause is skipped`() {
        descriptionGenres("Indie pop from Leipzig. Support for a metal band.").shouldBe(listOf("Indie"))
        descriptionGenres("Jazz quartet, Support von einer Punk-Band") shouldBe listOf("Jazz")
        descriptionGenres("Folk duo plus a reggae DJ") shouldBe listOf("Folk")
        descriptionGenres("Soul night with special guest from the techno scene") shouldBe listOf("Soul")
        descriptionGenres("Blues trio + metal openers") shouldBe listOf("Blues")
    }

    @Test
    fun `a vocabulary word that prose uses in another sense is skipped`() {
        descriptionGenres("Bands from around the world in the Opera House, the party is in full swing").shouldBeEmpty()
        descriptionGenres("An experimental film about the Berlin techno scene") shouldBe listOf("Techno")
        descriptionGenres("Experimental theatre with live music").shouldBeEmpty()
        descriptionGenres("A downbeat comedy about a band on tour").shouldBeEmpty()
        descriptionGenres("Ambient and down tempo sets by the pool") shouldBe listOf("Ambient", "Downtempo")
    }

    @Test
    fun `a genre word inside a quoted title is skipped`() {
        descriptionGenres("Kingstar präsentiert: \"Whiskey Tango Foxtrot”, zurück zum Punk") shouldBe listOf("Punk")
        descriptionGenres("Ihr Album „Soul Kitchen“ ist Rock") shouldBe listOf("Rock")
        descriptionGenres("Their single ‘Disco Inferno’ and «Jazz Club» are rock songs") shouldBe listOf("Rock")
    }

    @Test
    fun `a band in a sounds-like list gives no genre, a genre item in it does`() {
        // #4359: event 2235 was tagged Disco by "Panic! At The Disco", event 3970 Soul by "De La Soul".
        descriptionGenres("The Home of Core\nAlternative // Nu Metal // PopPunk\nSounds like: Korn • Panic! At The Disco • Papa Roach") shouldBe
            listOf("Metal", "Punk")
        descriptionGenres("Sounds like: A tribe called quest, Nas, De La Soul, Gang Starr").shouldBeEmpty()
        // Event 2247: the list's own genre items count, its bands do not.
        descriptionGenres("Sounds like: 80s Party + Pop Hits, 70s Disco / Abba / Iggy Pop / Joy Division") shouldBe listOf("Pop", "Disco")
        descriptionGenres("We love: Iggy Pop").shouldBeEmpty()
    }

    @Test
    fun `two words that join into a key by accident are not a genre`() {
        // Events 13495 and MOJELA: "in die" was read as Indie.
        descriptionGenres("Am 7. Januar führt diese musikalische Reise in die Tausend.").shouldBeEmpty()
        descriptionGenres("Let's go a little further tonight").shouldBeEmpty()
        descriptionGenres("Indie Pop und Reisen in die Ferne") shouldBe listOf("Indie")
    }

    @Test
    fun `a decade the title bills as a party is a genre, a bare decade is not`() {
        // Events 13541 and 13511.
        titleDecadeGenres("80er/90er Party im Spiegelsaal", null) shouldBe listOf("80s", "90s")
        titleDecadeGenres("DANCING WITH TEARS IN YOUR EYES", "Die 80er Party - Tanzen wie früher") shouldBe listOf("80s")
        titleDecadeGenres("80'S & 90'S PARTY VOL. 2") shouldBe listOf("80s", "90s")
        titleDecadeGenres("Y2K 2000er-Party") shouldBe listOf("2000s")
        titleDecadeGenres("Hits of the 80s", "since the 90s").shouldBeEmpty()
        titleDecadeGenres("Silvester 2026 Party").shouldBeEmpty()
        titleDecadeGenres("70er Party").shouldBeEmpty()
    }

    @Test
    fun `German electronic-music phrases are genres, the single words are not`() {
        // Event 13495, Hidéo at Bar Tausend.
        descriptionGenres("Hidéo versteht elektronische Musik als Erzählung.") shouldBe listOf("Electronic")
        descriptionGenres("Melodischer House, organische Rhythmen und atmosphärische Klanglandschaften") shouldBe listOf("House")
        descriptionGenres("Ein Abend mit Organic House und Deep Grooves") shouldBe listOf("House")
        descriptionGenres("Er spielt elektronische Gitarre im Trio.").shouldBeEmpty()
        descriptionGenres("Live in the Opera House").shouldBeEmpty()
    }

    @Test
    fun `free text that names no known genre gives nothing`() {
        descriptionGenres("Die Band spielt Lieder aus ihrem neuen Album.").shouldBeEmpty()
        descriptionGenres(null).shouldBeEmpty()
        descriptionGenres("   ").shouldBeEmpty()
    }

    @Test
    fun `at most three genres are kept, and a long description is read whole`() {
        val long = "x ".repeat(5000) + "Rock, Pop, Jazz, Blues and Funk"
        descriptionGenres(long) shouldBe listOf("Rock", "Pop", "Jazz")
    }
}
