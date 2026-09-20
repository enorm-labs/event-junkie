package de.norm.events.genretag

import de.norm.events.genretag.GenreFamily.CHARTS
import de.norm.events.genretag.GenreFamily.CLASSICAL
import de.norm.events.genretag.GenreFamily.ELECTRONIC
import de.norm.events.genretag.GenreFamily.FOLK
import de.norm.events.genretag.GenreFamily.HIP_HOP
import de.norm.events.genretag.GenreFamily.JAZZ_BLUES
import de.norm.events.genretag.GenreFamily.LATIN_WORLD
import de.norm.events.genretag.GenreFamily.METAL
import de.norm.events.genretag.GenreFamily.POP
import de.norm.events.genretag.GenreFamily.PUNK
import de.norm.events.genretag.GenreFamily.ROCK
import de.norm.events.genretag.GenreFamily.SOUL_FUNK
import de.norm.events.genretag.GenreFamily.WAVE

/**
 * Which [GenreFamily] each genre tag belongs to, keyed by the tag's slug (#363).
 *
 * Keyed by slug rather than name so the casing a venue shouts in ("HARDTECHNO", "Hardtechno")
 * does not matter: both spellings are one tag with one slug. The map is knowledge, like
 * `GENRE_SYNONYMS` in the same module, and moves with it if #323 decides on tables.
 *
 * Every canonical name `GENRE_SYNONYMS` produces has an entry — `GenreFamiliesTest` asserts it.
 * A tag that reaches the database by the `looksLikeGenre` fall-through and is absent here has no
 * family and shows in neither filter select; `GenreFamilyReconciler` logs those on every start,
 * which is the list to extend this map — or `NON_GENRE_TOKENS` — from.
 */
private val GENRE_FAMILIES: Map<String, GenreFamily> =
    mapOf(
        // Electronic — the club floor, from house to the hardcore-techno end of the spectrum
        "electronic" to ELECTRONIC,
        "techno" to ELECTRONIC,
        "house" to ELECTRONIC,
        "electro" to ELECTRONIC,
        "electroclash" to ELECTRONIC,
        "drum-bass" to ELECTRONIC,
        "jungle" to ELECTRONIC,
        "trance" to ELECTRONIC,
        "psytrance" to ELECTRONIC,
        "hardtrance" to ELECTRONIC,
        "hardtechno" to ELECTRONIC,
        "hardgroove" to ELECTRONIC,
        "hard-dance" to ELECTRONIC,
        "hardbounce" to ELECTRONIC,
        "bounce" to ELECTRONIC,
        "gabber" to ELECTRONIC,
        "speedcore" to ELECTRONIC,
        "terror" to ELECTRONIC,
        "early-hardcore" to ELECTRONIC,
        "digi-core" to ELECTRONIC,
        "hardwave" to ELECTRONIC,
        "bass" to ELECTRONIC,
        "basshall" to ELECTRONIC,
        "dubstep" to ELECTRONIC,
        "breaks" to ELECTRONIC,
        "footwork" to ELECTRONIC,
        "uk-funky" to ELECTRONIC,
        "edm" to ELECTRONIC,
        "acid" to ELECTRONIC,
        "ambient" to ELECTRONIC,
        "deep" to ELECTRONIC,
        "hypnotic" to ELECTRONIC,
        "tribal" to ELECTRONIC,
        "club" to ELECTRONIC,
        "lofi" to ELECTRONIC,
        "phonk" to ELECTRONIC,
        "amapiano" to ELECTRONIC,
        "noise" to ELECTRONIC,
        "experimental" to ELECTRONIC,
        "guitartronica" to ELECTRONIC,
        "tech-house" to ELECTRONIC,
        "uk-garage" to ELECTRONIC,
        "neurofunk" to ELECTRONIC,
        "trip-hop" to ELECTRONIC,
        "italo" to ELECTRONIC,
        "rave" to ELECTRONIC,
        "riddim" to ELECTRONIC,
        "speedhouse" to ELECTRONIC,
        "breakz" to ELECTRONIC,
        "tearout" to ELECTRONIC,
        "steppers" to ELECTRONIC,
        "dark" to ELECTRONIC,
        "cinematic" to ELECTRONIC,
        // Hip Hop & R&B
        "hip-hop" to HIP_HOP,
        "r-b" to HIP_HOP,
        "rb" to HIP_HOP,
        "grime" to HIP_HOP,
        "horrorcore" to HIP_HOP,
        "old-school" to HIP_HOP,
        "new-school" to HIP_HOP,
        "beatbox" to HIP_HOP,
        // Pop
        "pop" to POP,
        "synthpop" to POP,
        "synthie-pop" to POP,
        "electropop" to POP,
        "k-pop" to POP,
        "hyperpop" to POP,
        "art-pop" to POP,
        "dreampop" to POP,
        "power-pop" to POP,
        "schlagerpop" to POP,
        "kammerpop" to POP,
        "ethno-pop" to POP,
        "a-cappella" to POP,
        // Rock & Indie
        "rock" to ROCK,
        "indie" to ROCK,
        "indie-musik" to ROCK,
        "alternative" to ROCK,
        "shoegaze" to ROCK,
        "garage-rock" to ROCK,
        "krautrock" to ROCK,
        "deutschrock" to ROCK,
        "mittelalterrock" to ROCK,
        "grunge-rock" to ROCK,
        "post-grunge" to ROCK,
        "psychedelic" to ROCK,
        "psychedelia" to ROCK,
        "neopsychedelia" to ROCK,
        "70s-prog-rock" to ROCK,
        "60s-beat" to ROCK,
        "beat" to ROCK,
        "mod" to ROCK,
        "surf" to ROCK,
        "rockabilly" to ROCK,
        "rock-n-roll" to ROCK,
        "motorbilly" to ROCK,
        "crossover" to ROCK,
        "prog" to ROCK,
        // Punk & Hardcore
        "punk" to PUNK,
        "hardcore" to PUNK,
        "post-hardcore" to PUNK,
        "melodic-hardcore" to PUNK,
        "emo" to PUNK,
        "street-punk" to PUNK,
        "horrorpunk" to PUNK,
        "surf-punk" to PUNK,
        "cowpunk" to PUNK,
        "garage-punk-psych" to PUNK,
        "electropunk" to PUNK,
        "psychobilly" to PUNK,
        "ska" to PUNK,
        "oi-punk" to PUNK,
        // Metal
        "metal" to METAL,
        "metalcore" to METAL,
        "metal-hc-punk" to METAL,
        "deathcore" to METAL,
        "djent" to METAL,
        "nu-metal" to METAL,
        "power-metal" to METAL,
        "neue-deutsche-harte" to METAL,
        "post-metal" to METAL,
        // Post-Punk & Wave
        "post-punk" to WAVE,
        "new-wave" to WAVE,
        "darkwave" to WAVE,
        "ebm" to WAVE,
        "industrial" to WAVE,
        "gothic-rock" to WAVE,
        "gothic" to WAVE,
        "goth" to WAVE,
        "ndw" to WAVE,
        // Soul, Funk & Disco
        "soul" to SOUL_FUNK,
        "funk" to SOUL_FUNK,
        "neo-funk-soul" to SOUL_FUNK,
        "psych-soul" to SOUL_FUNK,
        "disco" to SOUL_FUNK,
        // Jazz & Blues
        "jazz" to JAZZ_BLUES,
        "blues" to JAZZ_BLUES,
        "fusion" to JAZZ_BLUES,
        "swing" to JAZZ_BLUES,
        "brass" to JAZZ_BLUES,
        // Folk & Songwriter
        "folk" to FOLK,
        "indie-folk" to FOLK,
        "chamber-folk" to FOLK,
        "psychfolk" to FOLK,
        "folk-country-indie" to FOLK,
        "singer-songwriter" to FOLK,
        "americana" to FOLK,
        "country" to FOLK,
        "country-blues" to FOLK,
        "country-bluegrass" to FOLK,
        "poetic-country-rock" to FOLK,
        "chanson" to FOLK,
        "country-trash" to FOLK,
        // Latin, Reggae & World
        "latin" to LATIN_WORLD,
        "afrobeats" to LATIN_WORLD,
        "reggae" to LATIN_WORLD,
        "dancehall" to LATIN_WORLD,
        "dub" to LATIN_WORLD,
        "dembow" to LATIN_WORLD,
        "soca" to LATIN_WORLD,
        "mpb" to LATIN_WORLD,
        "tango" to LATIN_WORLD,
        "balkan" to LATIN_WORLD,
        "tuareg" to LATIN_WORLD,
        "world-music" to LATIN_WORLD,
        "north-african" to LATIN_WORLD,
        "swana" to LATIN_WORLD,
        "poncha" to LATIN_WORLD,
        // Classical & Chamber
        "classical" to CLASSICAL,
        "kammermusik" to CLASSICAL,
        // Charts & Decades — chart hits and decade floors, the labels a party night bills itself with
        "top40" to CHARTS,
        "charts" to CHARTS,
        "hits" to CHARTS,
        "classics" to CHARTS,
        "dance-classics" to CHARTS,
        "alltime-favorites" to CHARTS,
        "movie-anthems" to CHARTS,
        "cartoon-hits" to CHARTS,
        "dance" to CHARTS,
        "euro-dance" to CHARTS,
        "high-energy" to CHARTS,
        "karaoke" to CHARTS,
        "80s" to CHARTS,
        "90s" to CHARTS,
        "00s" to CHARTS,
        "2000s" to CHARTS
    )

/** The family of the genre tag with this [slug], or `null` when [GENRE_FAMILIES] does not name it. */
fun genreFamily(slug: String): GenreFamily? = GENRE_FAMILIES[slug]
