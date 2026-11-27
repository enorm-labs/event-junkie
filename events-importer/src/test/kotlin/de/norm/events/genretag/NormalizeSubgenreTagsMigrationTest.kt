package de.norm.events.genretag

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.connect
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection

/**
 * Runs V287 against a respelt tag, a hand-corrected one, a survivor that exists (Breakbeat) and one that does not
 * (Dream-Pop), and an event that holds two tags of one fold.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NormalizeSubgenreTagsMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        flyway(postgres, "286").migrate()
        connection = connect(postgres)
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute(
                "INSERT INTO genre_tag (name, slug, family) VALUES ('Post-rock', 'post-rock', 'rock'), ('Arab Pop', 'arab-pop', 'pop'), " +
                    "('Dreampop', 'dreampop', 'pop'), ('Noise-dream-pop', 'noise-dream-pop', 'pop'), ('Breaks', 'breaks', 'electronic'), " +
                    "('Breakbeats', 'breakbeats', 'electronic'), ('Breakbeat', 'breakbeat', 'electronic')"
            )
            statement.execute(
                "INSERT INTO event (venue_id, title, slug, event_date, source_id) SELECT id, t, t, DATE '2026-11-20', 'x:' || t FROM venue, " +
                    "(VALUES ('lorne'), ('slowdive'), ('rave')) AS e (t)"
            )
            statement.execute(
                "INSERT INTO event_genre_tag (event_id, genre_tag_id, origin) SELECT e.id, g.id, o FROM event e, genre_tag g, " +
                    "(VALUES ('lorne', 'post-rock', 'SOURCE'), ('slowdive', 'dreampop', 'WIKIDATA'), ('slowdive', 'noise-dream-pop', 'WIKIDATA'), " +
                    "('rave', 'breaks', 'SOURCE'), ('rave', 'breakbeat', 'SOURCE'), ('rave', 'arab-pop', 'DESCRIPTION')) AS l (ev, tag, o) " +
                    "WHERE e.slug = l.ev AND g.slug = l.tag"
            )
        }
        flyway(postgres, "287").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `each event keeps one link to the survivor, with the origin it had`() {
        query(
            "SELECT e.slug || '|' || g.slug || '|' || l.origin FROM events.event_genre_tag l JOIN events.event e ON e.id = l.event_id " +
                "JOIN events.genre_tag g ON g.id = l.genre_tag_id ORDER BY 1"
        ) shouldContainExactly
            listOf("lorne|post-rock|SOURCE", "rave|arab-pop|DESCRIPTION", "rave|breakbeat|SOURCE", "slowdive|dream-pop|WIKIDATA")
    }

    @Test
    fun `a respelt tag keeps its row, a hand-corrected name stays, and a missing survivor is created`() {
        query("SELECT slug || '|' || name || '|' || family FROM events.genre_tag ORDER BY slug") shouldContainExactly
            listOf("arab-pop|Arab Pop|pop", "breakbeat|Breakbeat|electronic", "dream-pop|Dream-Pop|pop", "post-rock|Post-Rock|rock")
    }

    @Test
    fun `every survivor is what the normalizer gives for the stored tag`() {
        assertSoftly {
            SURVIVORS.forEach { (stored, survivor) -> withClue(stored) { normalizeGenre(stored) shouldBe listOf(survivor) } }
        }
    }

    private fun query(sql: String): List<String> = rows(connection, sql) { it.getString(1) }
}

/** Each stored tag V287 respells or folds, and its survivor (#5592). */
private val SURVIVORS =
    mapOf(
        "Post-rock" to "Post-Rock",
        "Post Hardcore" to "Post-Hardcore",
        "Post Grunge" to "Post-Grunge",
        "Dreampop" to "Dream-Pop",
        "Hardrock" to "Hard-Rock",
        "Jazzrock" to "Jazz-Rock",
        "Glam-rock" to "Glam-Rock",
        "Math-rock" to "Math-Rock",
        "Noise-rock" to "Noise-Rock",
        "Desert-rock" to "Desert-Rock",
        "Arab-pop" to "Arab-Pop",
        "Experimental-pop" to "Experimental-Pop",
        "Folk-pop" to "Folk-Pop",
        "Noise-pop" to "Noise-Pop",
        "Garage-punk" to "Garage-Punk",
        "Egg-punk" to "Egg-Punk",
        "No-wave" to "No-Wave",
        "Uk Bass" to "UK Bass",
        "ElectroClash" to "Electroclash",
        "Psych.Soul" to "Psych-Soul",
        "AntiFolk" to "Anti-Folk",
        "PsychFolk" to "Psych-Folk",
        "NeoPsychedelia" to "Neo-Psychedelia",
        "DeathDoom" to "Death-Doom",
        "Afrocuban" to "Afro-Cuban",
        "Japanese Citypop" to "City-Pop",
        "Punk+Ska" to "Ska-Punk",
        "Cinematic Dream-pop" to "Dream-Pop",
        "Noise-dream-pop" to "Dream-Pop",
        "Exp-indie-rock" to "Indie",
        "Exp-indie" to "Indie",
        "Lofi-indie-pop" to "Indie",
        "Indie-Musik" to "Indie",
        "CollegeRock" to "Indie",
        "Exp Prog-rock" to "Prog-Rock",
        "70s Prog-Rock" to "Prog-Rock",
        "Fuzz-noise-rock" to "Noise-Rock",
        "Grunge-gaze" to "Shoegaze",
        "Grunge-Rock" to "Grunge",
        "Glam" to "Glam-Rock",
        "Math" to "Math-Rock",
        "Psych" to "Psychedelic",
        "Psychedelia" to "Psychedelic",
        "Psychedelic Sounds" to "Psychedelic",
        "PsychRock" to "Psychedelic",
        "Queer-psych-rock" to "Psychedelic",
        "Psych-pop" to "Psychedelic",
        "Surf-Beat" to "Surf",
        "60s Beat" to "Beat",
        "Groovy Beat" to "Beat",
        "Funkrockpop" to "Pop",
        "Alternative-Pop" to "Pop",
        "Ghost-pop" to "Pop",
        "Poetic Country-Rock" to "Country-Rock",
        "Country-Blues-Rock" to "Country-Rock",
        "Folk-Country-Indie" to "Folk",
        "Exp-folk" to "Folk",
        "Country-Bluegrass" to "Bluegrass",
        "Songwriter" to "Singer-Songwriter",
        "Blues-Trash" to "Blues",
        "Funk-Jazz" to "Jazz",
        "Art-Funk" to "Funk",
        "Neo-Funk-Soul" to "Soul",
        "Rockin´Soul" to "Soul",
        "Indie-Schlager" to "Schlager",
        "Pop-schlager" to "Schlager",
        "Schlagerpop" to "Schlager",
        "Partyschlager" to "Schlager",
        "Synthie" to "Synthpop",
        "Bewegungs-Punk" to "Punk",
        "Solo-Punk" to "Punk",
        "Solar-punk" to "Punk",
        "Astro-punk" to "Punk",
        "Cold-punk" to "Punk",
        "PowerPunk" to "Punk",
        "Punk-Pop" to "Punk",
        "Akustikpunk" to "Folk-Punk",
        "Noisy Jazz-Punk" to "Punk",
        "RnR-Metal-Punk" to "Punk",
        "Metal-HC-Punk" to "Punk",
        "Post-digital-punk" to "Post-Punk",
        "Garage-post-punk" to "Post-Punk",
        "Shoegaze-post-punk" to "Post-Punk",
        "Indie-Punk-Noise" to "Indie-Punk",
        "Garage-Punk-Psych" to "Garage-Punk",
        "Jamaican Ska" to "Ska",
        "Sludge Hardcore" to "Hardcore",
        "Stench Crust" to "Crust",
        "Beatdown" to "Beatdown Hardcore",
        "BlackDeathDoom" to "Death-Doom",
        "Rock-Metal" to "Metal",
        "Heavy Noise" to "Noise",
        "Nndw" to "NDW",
        "Goth" to "Gothic Rock",
        "Breaks" to "Breakbeat",
        "Breakbeats" to "Breakbeat",
        "Bass Music" to "Bass",
        "Electronics" to "Electronic",
        "Electro Minimal" to "Minimal",
        "Minimalistic" to "Minimal",
        "Deep Electro" to "Electro",
        "Dark Electronics" to "Dark Electro",
        "Jazzy-Breaks-House" to "House",
        "Afro&House" to "House",
        "Leftfield Grooves" to "Leftfield",
        "Dada-synth-lofi" to "Lofi",
        "Indie-Electronic" to "Indietronica",
        "Club-Rap" to "Hip Hop",
        "Live Beatbox" to "Beatbox",
        "Balkan Beats" to "Balkan",
        "Bossa" to "Latin",
        "Neo-Mexican" to "Latin",
        "Rock‘n’Roll Cabaret" to "Rock'n'Roll"
    )
