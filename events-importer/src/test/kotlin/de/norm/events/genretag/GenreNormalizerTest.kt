package de.norm.events.genretag

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test

/**
 * Tests for [normalizeGenre] — the genre string normalization pipeline.
 *
 * Verifies synonym mapping, delimiter splitting, noise stripping,
 * deduplication, and fallback behavior for unknown genres.
 */
class GenreNormalizerTest {
    @Test
    fun `null input returns empty list`() {
        normalizeGenre(null).shouldBeEmpty()
    }

    @Test
    fun `blank input returns empty list`() {
        normalizeGenre("").shouldBeEmpty()
        normalizeGenre("   ").shouldBeEmpty()
    }

    @Test
    fun `single known genre maps to canonical name`() {
        normalizeGenre("Hip Hop").shouldContainExactly("Hip Hop")
        normalizeGenre("hip-hop").shouldContainExactly("Hip Hop")
        normalizeGenre("Rap").shouldContainExactly("Hip Hop")
    }

    @Test
    fun `comma-separated genres are split and normalized`() {
        normalizeGenre("Pop Punk, Indie, Karaoke")
            .shouldContainExactlyInAnyOrder("Punk", "Indie", "Karaoke")
    }

    @Test
    fun `double-slash delimiter is handled`() {
        normalizeGenre("80s, 90s & 2000s Floor // Pop, Rock & Disco // Hip Hop & Urban Disco Floor // Karaoke Disco Floor")
            .shouldContainExactlyInAnyOrder("80s", "90s", "2000s", "Pop", "Rock", "Disco", "Hip Hop", "Karaoke")
    }

    @Test
    fun `ampersand delimiter splits correctly`() {
        normalizeGenre("80s, Disco & Hip Hop")
            .shouldContainExactlyInAnyOrder("80s", "Disco", "Hip Hop")
    }

    @Test
    fun `post-punk and gothic variants are normalized`() {
        normalizeGenre("Postpunk, Gothicrock, Darkwave, EBM und Synthpop etc.")
            .shouldContainExactlyInAnyOrder("Post-Punk", "Gothic Rock", "Darkwave", "EBM", "Synthpop")
    }

    @Test
    fun `alternative slash indie is normalized`() {
        normalizeGenre("Alternative / Indie")
            .shouldContainExactlyInAnyOrder("Alternative", "Indie")
    }

    @Test
    fun `indie variants map to Indie`() {
        normalizeGenre("Indie Pop").shouldContainExactly("Indie")
        normalizeGenre("Indie-Pop").shouldContainExactly("Indie")
        normalizeGenre("Indie Rock").shouldContainExactly("Indie")
    }

    @Test
    fun `jazz-fusion maps to Jazz`() {
        normalizeGenre("Jazz-Fusion").shouldContainExactly("Jazz")
    }

    @Test
    fun `reggae phrase is handled`() {
        val result = normalizeGenre("All kinds of Reggae - from then till now")
        result.shouldContainExactly("Reggae")
    }

    @Test
    fun `latin genres are normalized`() {
        normalizeGenre("Cumbia, Salsa, Latin Roots")
            .shouldContainExactly("Latin")
    }

    @Test
    fun `electronic variants are normalized`() {
        normalizeGenre("Elektro-Fusion, House,Techno")
            .shouldContainExactlyInAnyOrder("Electronic", "House", "Techno")
    }

    @Test
    fun `duplicates are removed`() {
        normalizeGenre("Indie, Indie Rock, Indie Pop")
            .shouldContainExactly("Indie")
    }

    @Test
    fun `unknown genre is kept with title case`() {
        normalizeGenre("Noise")
            .shouldContainExactly("Noise")
    }

    @Test
    fun `descriptor labels are dropped while surrounding genres survive`() {
        // "Groove"/"Dirt"/"Progressive" are section labels a venue lists next to real
        // genres, not genres themselves — they must not leak as standalone tags.
        normalizeGenre("Funk, Soul, Groove").shouldContainExactlyInAnyOrder("Funk", "Soul")
        normalizeGenre("Dub, Grime, Bass, Dirt").shouldContainExactlyInAnyOrder("Dub", "Grime", "Bass")
        normalizeGenre("Jazz, Progressive, Fusion").shouldContainExactlyInAnyOrder("Jazz", "Fusion")
        // A compound built on the descriptor still resolves via the word-level synonym match.
        normalizeGenre("Progressive Rock").shouldContainExactly("Rock")
    }

    @Test
    fun `staging formats and framings are dropped rather than tagged as genres`() {
        // Audit T-4: these leaked into the genre-tag vocabulary from venues that file a staging
        // format in their genre field. `show`/`konzert` only veto the standalone word, so the
        // hyphenated compounds needed their own keys.
        normalizeGenre("Musik-Show").shouldBeEmpty()
        normalizeGenre("Musik-Kabarett").shouldBeEmpty()
        normalizeGenre("Comedy").shouldBeEmpty()
        normalizeGenre("Ballett").shouldBeEmpty()
        normalizeGenre("Tanz").shouldBeEmpty()
        normalizeGenre("Diskussion").shouldBeEmpty()
        normalizeGenre("Kultur").shouldBeEmpty()
        normalizeGenre("Kunst").shouldBeEmpty()
        normalizeGenre("Tribute").shouldBeEmpty()
        normalizeGenre("Anime").shouldBeEmpty()
        normalizeGenre("Berlin").shouldBeEmpty()
    }

    // Audit T-10: Huxleys emits genres as de-slugified CSS classes, so the stylised spelling
    // arrives punctuation-free as "Kpop" while another venue writes "K-Pop" — two tag rows.
    @Test
    fun `folds the punctuation-stripped K-Pop spelling onto one tag`() {
        normalizeGenre("Kpop").shouldContainExactly("K-Pop")
        normalizeGenre("K-Pop").shouldContainExactly("K-Pop")
        normalizeGenre("k pop").shouldContainExactly("K-Pop")
    }

    @Test
    fun `nonGenreTokens names the dropped labels as written, and not the noise`() {
        nonGenreTokens("Immersive Ausstellung, Techno").shouldContainExactly("Immersive Ausstellung")
        nonGenreTokens("Comedy, Soul, Comedy").shouldContainExactly("Comedy")
        nonGenreTokens("Berlin Techno").shouldBeEmpty()
        nonGenreTokens("from 22:00, x").shouldBeEmpty()
        nonGenreTokens(null).shouldBeEmpty()
    }

    @Test
    fun `a real genre beside a dropped format still survives`() {
        // The stop-list must not swallow a label that also names a style.
        normalizeGenre("Berlin Techno").shouldContainExactly("Techno")
        normalizeGenre("Comedy, Soul").shouldContainExactly("Soul")
        normalizeGenre("Tribute, Rock").shouldContainExactly("Rock")
    }

    @Test
    fun `hip hop soul rnb oldschool newschool`() {
        normalizeGenre("Hip Hop, Soul, RnB, Oldschool, Newschool")
            .shouldContainExactlyInAnyOrder("Hip Hop", "Soul", "R&B", "Old School", "New School")
    }

    @Test
    fun `world music variants`() {
        normalizeGenre("World Music, Indian, Urdu Rock")
            .shouldContainExactly("World Music")
    }

    @Test
    fun `single pop returns Pop`() {
        normalizeGenre("Pop").shouldContainExactly("Pop")
    }

    @Test
    fun `single rock returns Rock`() {
        normalizeGenre("Rock").shouldContainExactly("Rock")
    }

    @Test
    fun `funk and boogaloo compound label extracts matching genres`() {
        normalizeGenre("Superheavy Funky Soul & Boogaloo")
            .shouldContainExactlyInAnyOrder("Soul", "Funk")
    }

    @Test
    fun `new wave post punk are distinct`() {
        normalizeGenre("New Wave, Post Punk")
            .shouldContainExactlyInAnyOrder("New Wave", "Post-Punk")
    }

    @Test
    fun `metal is preserved`() {
        normalizeGenre("Metal").shouldContainExactly("Metal")
    }

    @Test
    fun `folk indie comma separated`() {
        normalizeGenre("Folk, Indie")
            .shouldContainExactlyInAnyOrder("Folk", "Indie")
    }

    @Test
    fun `electronica jazz trip-hop`() {
        normalizeGenre("Electronica, Jazz, Trip-Hop")
            .shouldContainExactlyInAnyOrder("Electronic", "Jazz", "Trip-Hop")
    }

    @Test
    fun `arbitrary casing and spacing variants resolve via normalized lookup key`() {
        // These exact spellings are not listed individually — they collapse to
        // the same normalized key (e.g. "hiphop", "postpunk") as the canonical entry.
        normalizeGenre("HIP HOP").shouldContainExactly("Hip Hop")
        normalizeGenre("Hip   Hop").shouldContainExactly("Hip Hop")
        normalizeGenre("POSTPUNK").shouldContainExactly("Post-Punk")
        normalizeGenre("Gothic-Rock").shouldContainExactly("Gothic Rock")
        normalizeGenre("Singer Songwriter").shouldContainExactly("Singer-Songwriter")
    }

    @Test
    fun `distinct new genres map to their own canonical tags`() {
        normalizeGenre("Blues").shouldContainExactly("Blues")
        normalizeGenre("Shoegaze").shouldContainExactly("Shoegaze")
        normalizeGenre("Emo").shouldContainExactly("Emo")
        normalizeGenre("Metalcore").shouldContainExactly("Metalcore")
        normalizeGenre("Melodic-Hardcore").shouldContainExactly("Melodic-Hardcore")
    }

    @Test
    fun `near-duplicate genres merge into existing canonical tags`() {
        normalizeGenre("Deutschpop").shouldContainExactly("Pop")
        normalizeGenre("Deutschrock").shouldContainExactly("Rock")
        normalizeGenre("Pop-Rock").shouldContainExactly("Rock")
        normalizeGenre("Trap").shouldContainExactly("Hip Hop")
    }

    @Test
    fun `non-genre event labels are dropped`() {
        // Cassiopeia reuses the genre field for event-format labels.
        normalizeGenre("Immersive Ausstellung").shouldBeEmpty()
        normalizeGenre("Release Party").shouldBeEmpty()
    }

    @Test
    fun `Festsaal event-format labels in the genre field are dropped`() {
        // Festsaal pushes event-kind / venue-type labels into its genre.title field.
        // "Open Air" and "Release Party" survive the per-word check (no single word is
        // stop-listed) but their normalized whole-token form is, so they still drop.
        normalizeGenre("LIVE PODCAST").shouldBeEmpty()
        normalizeGenre("Markt").shouldBeEmpty()
        normalizeGenre("Open Air").shouldBeEmpty()
    }

    @Test
    fun `unspaced slash alternatives are split so they don't fragment`() {
        // "Hip-Hop/Rap" (no spaces around the slash) must split to fold onto Hip Hop
        // rather than mint a distinct "Hip-Hop/Rap" tag.
        normalizeGenre("Hip-Hop/Rap").shouldContainExactly("Hip Hop")
        normalizeGenre("Techno/House")
            .shouldContainExactlyInAnyOrder("Techno", "House")
    }

    @Test
    fun `long freeform descriptors are dropped`() {
        // Too many words to plausibly be a genre name — leaks as a series label.
        normalizeGenre("Twenty One Pilots Special").shouldBeEmpty()
    }

    @Test
    fun `non-genre label mixed with a real genre keeps only the genre`() {
        normalizeGenre("Rock, Immersive Ausstellung")
            .shouldContainExactly("Rock")
        normalizeGenre("Twenty One Pilots Special, Pop")
            .shouldContainExactly("Pop")
    }

    @Test
    fun `standalone freeform fragments are dropped`() {
        normalizeGenre("Beyond, Wave, Retro").shouldBeEmpty()
    }

    @Test
    fun `Kraut folds onto the Krautrock canonical tag`() {
        // The bare "Kraut" fragment must not fragment against its full "Krautrock" twin.
        normalizeGenre("Kraut").shouldContainExactly("Krautrock")
        normalizeGenre("Krautrock").shouldContainExactly("Krautrock")
        normalizeGenre("Kraut, ExperimentalRock, Electronica")
            .shouldContainExactlyInAnyOrder("Krautrock", "Rock", "Electronic")
    }

    @Test
    fun `Afrobeat folds onto the Afrobeats canonical tag`() {
        // Both spellings resolve to one tag, so "Afrobeat" and "Afrobeats" don't fragment.
        normalizeGenre("Afrobeat").shouldContainExactly("Afrobeats")
        normalizeGenre("HipHop, Afrobeat").shouldContainExactly("Hip Hop", "Afrobeats")
    }

    @Test
    fun `Drum and Bass spelling variants fold onto one canonical tag`() {
        // The name embeds a delimiter (" & ", "'n'"), so it must survive the split
        // and collapse to a single tag rather than fragmenting into "Drum" + "Bass".
        normalizeGenre("Drum'n'Bass").shouldContainExactly("Drum & Bass")
        normalizeGenre("Drum & Bass").shouldContainExactly("Drum & Bass")
        normalizeGenre("DnB").shouldContainExactly("Drum & Bass")
        normalizeGenre("D&B").shouldContainExactly("Drum & Bass")
        normalizeGenre("Techno, Drum & Bass, House")
            .shouldContainExactlyInAnyOrder("Techno", "Drum & Bass", "House")
    }

    @Test
    fun `Singer-Songwriter slash spelling folds onto one canonical tag`() {
        // "Singer-/Songwriter" embeds a "/" delimiter, so without the pre-pass it fragments
        // into the junk tags "Singer-" + "Songwriter"; it must resolve to one clean tag.
        normalizeGenre("Singer-/Songwriter").shouldContainExactly("Singer-Songwriter")
        normalizeGenre("Singer/Songwriter").shouldContainExactly("Singer-Songwriter")
        normalizeGenre("Singer-Songwriterin").shouldContainExactly("Singer-Songwriter")
        normalizeGenre("Singer-/Songwriter, Latin, Folk")
            .shouldContainExactlyInAnyOrder("Singer-Songwriter", "Latin", "Folk")
    }

    @Test
    fun `German Hip Hop maps to Hip Hop rather than being dropped`() {
        // A three-word label would fail the looksLikeGenre gate and be discarded; the
        // synonym entry recovers it as the canonical Hip Hop tag.
        normalizeGenre("German Hip Hop").shouldContainExactly("Hip Hop")
    }

    @Test
    fun `compound sub-genres fold into their parent tag`() {
        // camelCase / hyphenated sub-genres collapse to the head genre so they don't
        // fragment against their spaced twins from other venues.
        normalizeGenre("AfroHouse").shouldContainExactly("House")
        normalizeGenre("LatinHouse").shouldContainExactly("House")
        normalizeGenre("ElektroPop").shouldContainExactly("Pop")
        normalizeGenre("AltPop").shouldContainExactly("Pop")
        normalizeGenre("Queer-Pop").shouldContainExactly("Pop")
        normalizeGenre("NeoSoul").shouldContainExactly("Soul")
        normalizeGenre("IndieSoul").shouldContainExactly("Soul")
        normalizeGenre("AltRnB").shouldContainExactly("R&B")
        normalizeGenre("BluesRock").shouldContainExactly("Rock")
        normalizeGenre("ExperimentalRock").shouldContainExactly("Rock")
        normalizeGenre("Latin-Jazz").shouldContainExactly("Jazz")
        normalizeGenre("Global").shouldContainExactly("World Music")
    }

    @Test
    fun `genuinely distinct genres are still kept as their own tag`() {
        // Moderate folding must not swallow standalone genres that have no parent tag.
        normalizeGenre("Amapiano").shouldContainExactly("Amapiano")
        normalizeGenre("Grime").shouldContainExactly("Grime")
        normalizeGenre("Dub").shouldContainExactly("Dub")
    }

    @Test
    fun `Gretchen audience and theme labels are dropped`() {
        // Audience/theme/series labels Gretchen drops into the genre field, split on
        // "//" and "," — the real genres survive, the labels do not.
        normalizeGenre("FLINTA*// Pop, HipHop, House")
            .shouldContainExactly("Pop", "Hip Hop", "House")
        normalizeGenre("Männerparty // Fetish").shouldBeEmpty()
        normalizeGenre("Berbenautika, Cumbia, Breaks, HipHop")
            .shouldContainExactly("Latin", "Breaks", "Hip Hop")
    }

    @Test
    fun `or delimiter splits and drops the non-genre half`() {
        // "Tango or NonTango" previously fell through whole as one noisy tag.
        normalizeGenre("Tango or NonTango").shouldContainExactly("Tango")
    }

    @Test
    fun `punctuation and spacing variants fold into existing canonical tags`() {
        // Variants that previously fell through to as-is, creating duplicate tags
        // (e.g. "World" vs "World Music", "Singer Songwriter" vs "Singer-Songwriter").
        normalizeGenre("Electronic").shouldContainExactly("Electronic")
        normalizeGenre("Post-Punk").shouldContainExactly("Post-Punk")
        normalizeGenre("Singer Songwriter").shouldContainExactly("Singer-Songwriter")
        normalizeGenre("World").shouldContainExactly("World Music")
    }

    @Test
    fun `punk-rock spelling variants fold onto the Punk tag`() {
        // "PunkRock" and "Punk-Rock" previously fell through as two distinct as-is tags.
        normalizeGenre("PunkRock").shouldContainExactly("Punk")
        normalizeGenre("Punk-Rock").shouldContainExactly("Punk")
    }

    @Test
    fun `electropunk spelling variants fold onto one tag`() {
        // "Electropunk" (Festsaal) and "Elektro-punk" (Hole 44) previously fragmented.
        normalizeGenre("Electropunk").shouldContainExactly("Electropunk")
        normalizeGenre("EDM, elektro-punk").shouldContainExactly("EDM", "Electropunk")
    }

    @Test
    fun `Wild at Heart freeform style labels are normalized or dropped`() {
        // Idiosyncratic style labels from the retro concerts.php page.
        normalizeGenre("Ska/Reggea/Beat").shouldContainExactly("Ska", "Reggae", "Beat") // typo folded
        normalizeGenre("Kick Ass / Rock").shouldContainExactly("Rock") // non-genre dropped
        normalizeGenre("StonerPsychedelicMetal").shouldContainExactly("Metal")
        normalizeGenre("Rawk`n`Roll").shouldContainExactly("Rock")
        normalizeGenre("HC").shouldContainExactly("Hardcore")
    }

    @Test
    fun `multi-word German genres are kept rather than dropped by the word-count gate`() {
        // Hole 44 lists these; they are ≥2 words so the looksLikeGenre gate would drop them
        // without an explicit synonym.
        normalizeGenre("Neue Deutsche Welle").shouldContainExactly("NDW")
        normalizeGenre("Neue Deutsche Härte").shouldContainExactly("Neue Deutsche Härte")
    }

    @Test
    fun `formats found in genre_tag on staging are dropped`() {
        // The nine formats and two fragments #1309 found sitting in genre_tag as genres.
        listOf(
            "Ping Pong",
            "Tattoo",
            "Market",
            "Drag",
            "Burlesque",
            "Soli-Fest",
            "Kinderkonzert",
            "Jam Session",
            "Spoken Word",
            "Core",
            "BETON ARME"
        ).forEach { normalizeGenre(it).shouldBeEmpty() }
    }

    @Test
    fun `core vetoes the bare fragment only, never a compound genre`() {
        normalizeGenre("Metalcore").shouldContainExactly("Metalcore")
        normalizeGenre("Deathcore").shouldContainExactly("Deathcore")
        normalizeGenre("Hardcore").shouldContainExactly("Hardcore")
        normalizeGenre("Melodic-Hardcore").shouldContainExactly("Melodic-Hardcore")
    }

    @Test
    fun `second spellings fold onto the tag that exists`() {
        normalizeGenre("Synthie-Pop").shouldContainExactly("Synthpop")
        normalizeGenre("Goth").shouldContainExactly("Gothic Rock")
        normalizeGenre("Gothic").shouldContainExactly("Gothic Rock")
        normalizeGenre("Dreamy Kuschelrock").shouldContainExactly("Rock")
        normalizeGenre("Acoustic-Guitar-Hasen-Liedermaching").shouldContainExactly("Singer-Songwriter")
        normalizeGenre("Liedermaching").shouldContainExactly("Singer-Songwriter")
    }
}
