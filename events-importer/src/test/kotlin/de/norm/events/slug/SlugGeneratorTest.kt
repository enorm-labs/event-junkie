package de.norm.events.slug

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SlugGenerator].
 *
 * The transliteration cases are real names taken from the seeded artist and venue data —
 * each one a slug that silently lost a letter before the `NON_DECOMPOSING_LATIN`
 * replacements were added.
 */
class SlugGeneratorTest {
    @Test
    fun `slugifies plain text`() {
        SlugGenerator.slugify("My Event") shouldBe "my-event"
        SlugGenerator.slugify("  Spaced   Out  ") shouldBe "spaced-out"
        SlugGenerator.slugify("AC/DC") shouldBe "ac-dc"
    }

    @Test
    fun `strips accents that decompose to a base letter`() {
        SlugGenerator.slugify("Motörhead") shouldBe "motorhead"
        SlugGenerator.slugify("Sigur Rós") shouldBe "sigur-ros"
        SlugGenerator.slugify("Niño") shouldBe "nino"
        SlugGenerator.slugify("Håkan") shouldBe "hakan"
        SlugGenerator.slugify("Beyoğlu") shouldBe "beyoglu"
    }

    @Test
    fun `keeps Nordic letters that do not decompose`() {
        SlugGenerator.slugify("Kėkė Søl") shouldBe "keke-sol"
        SlugGenerator.slugify("DJ Bjørn") shouldBe "dj-bjorn"
        SlugGenerator.slugify("Kræk") shouldBe "kraek"
        SlugGenerator.slugify("Ævestaden") shouldBe "aevestaden"
        SlugGenerator.slugify("Nils Petter Molvær Trio") shouldBe "nils-petter-molvaer-trio"
    }

    @Test
    fun `keeps a non-decomposing letter beside a decomposing one`() {
        // `ø` maps to its base letter, matching the `ö` next to it — not the
        // Norwegian `oe` expansion, which would read as "oerloeg".
        SlugGenerator.slugify("Ørlög") shouldBe "orlog"
    }

    @Test
    fun `keeps Icelandic, Polish, Croatian and French letters`() {
        SlugGenerator.slugify("Þór") shouldBe "thor"
        SlugGenerator.slugify("Guðrún") shouldBe "gudrun"
        SlugGenerator.slugify("Łukasz") shouldBe "lukasz"
        SlugGenerator.slugify("Đorđe") shouldBe "dorde"
        SlugGenerator.slugify("Cœur") shouldBe "coeur"
    }

    @Test
    fun `keeps the German sharp s`() {
        SlugGenerator.slugify("Revaler Straße") shouldBe "revaler-strasse"
    }

    @Test
    fun `keeps the Turkish dotless i`() {
        SlugGenerator.slugify("Işıl") shouldBe "isil"
        // The dotted capital decomposes on its own and must stay a single `i`.
        SlugGenerator.slugify("İstanbul") shouldBe "istanbul"
    }

    @Test
    fun `distinguishes names that previously collided on a dropped letter`() {
        // Both slugged to "sl" before the fix, so two different acts shared one key.
        SlugGenerator.slugify("Søl") shouldBe "sol"
        SlugGenerator.slugify("Sæl") shouldBe "sael"
    }

    @Test
    fun `transliterates a Cyrillic name as a reader would type it`() {
        SlugGenerator.slugify("ВУИНДЛИЯ") shouldBe "vuindliya"
        SlugGenerator.slugify("Щедрик") shouldBe "shchedrik"
        SlugGenerator.slugify("Ёлка и Йога") shouldBe "yolka-i-yoga"
        SlugGenerator.slugify("Ґуцул Їжак") shouldBe "gutsul-yizhak"
        SlugGenerator.slugify("Объект") shouldBe "obekt"
        SlugGenerator.slugify("Юлия") shouldBe "yuliya"
        SlugGenerator.slugify("Мумий Тролль") shouldBe "mumiy-troll"
        SlugGenerator.slugify("Кино 2") shouldBe "kino-2"
    }

    @Test
    fun `transliterates a Greek name`() {
        SlugGenerator.slugify("Μουσική") shouldBe "mousiki"
        SlugGenerator.slugify("ΨΥΧΗ") shouldBe "psychi"
        SlugGenerator.slugify("Άγγελος Ευαγγέλου") shouldBe "angelos-evangelou"
        SlugGenerator.slugify("Θάλασσα") shouldBe "thalassa"
    }

    @Test
    fun `keeps the plain slug of a name with a Latin letter`() {
        SlugGenerator.slugify("Друга Ріка (Druha Rika)") shouldBe "druha-rika"
        SlugGenerator.slugify("Meduσa") shouldBe "medua"
        SlugGenerator.slugify("ЯETURN: NEW YEAR'S DAY") shouldBe "eturn-new-year-s-day"
    }

    @Test
    fun `leaves a name without letters empty`() {
        SlugGenerator.slugify("–") shouldBe ""
        SlugGenerator.slugify("★ ★") shouldBe ""
        SlugGenerator.slugify("/") shouldBe ""
        SlugGenerator.slugify("+") shouldBe ""
    }

    @Test
    fun `spells a name of only at signs as at`() {
        SlugGenerator.slugify("@") shouldBe "at"
        SlugGenerator.slugify(" @ ") shouldBe "at"
        SlugGenerator.slugify("@@") shouldBe "at-at"
    }

    @Test
    fun `keeps the plain slug of a name with an at sign inside`() {
        SlugGenerator.slugify("Funk@delic") shouldBe "funk-delic"
        SlugGenerator.slugify("Z@p") shouldBe "z-p"
    }

    @Test
    fun `separates words joined by an underscore with a hyphen`() {
        SlugGenerator.slugify("DJ_Dave") shouldBe "dj-dave"
        SlugGenerator.slugify("Ballet Sur_Real") shouldBe "ballet-sur-real"
        SlugGenerator.slugify("REAL_O-_-O_IT") shouldBe "real-o-o-it"
        SlugGenerator.slugify("Кино_2") shouldBe "kino-2"
        SlugGenerator.slugify("_") shouldBe ""
    }
}
