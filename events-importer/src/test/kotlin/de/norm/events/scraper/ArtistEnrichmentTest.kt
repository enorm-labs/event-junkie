package de.norm.events.scraper

import de.norm.events.artist.ArtistEntity
import de.norm.events.musicbrainz.MusicBrainzArtist
import de.norm.events.musicbrainz.MusicBrainzUrl
import de.norm.events.musicbrainz.MusicBrainzUrlRelation
import de.norm.events.wikimedia.CommonsImage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * The fill rule over three captured entities: a person with 39 relations (Anna von Hausswolff), a
 * DJ with nine (Ben Klock) and a group (Einstürzende Neubauten). What is read, what is skipped, and
 * that a column with a value is never touched.
 */
class ArtistEnrichmentTest {
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    private fun entity(name: String): MusicBrainzArtist =
        mapper.readValue(javaClass.classLoader.getResourceAsStream("musicbrainz/artist-$name.json")!!, MusicBrainzArtist::class.java)

    private fun row(
        name: String = "Anna von Hausswolff",
        websiteUrl: String? = null,
        imageUrl: String? = null,
        artistType: String? = null
    ) = ArtistEntity(
        id = 1L,
        name = name,
        slug = "anna-von-hausswolff",
        websiteUrl = websiteUrl,
        imageUrl = imageUrl,
        imageAttribution = imageUrl?.let { "Someone" },
        imageLicenceId = imageUrl?.let { "CC-BY-4.0" },
        imageSourceUrl = imageUrl?.let { "https://example.test/file" },
        artistType = artistType,
        musicbrainzId = "mbid",
        musicbrainzMatch = "EXACT"
    )

    private fun commons(
        licence: String? = "CC BY 2.0",
        artist: String? = "<a href=\"https://www.flickr.com/people/64654599@N00\">Paul Hudson</a> from United Kingdom",
        mime: String? = "image/jpeg",
        size: Long = 142_401,
        servedOriginal: Boolean = true,
        descriptionUrl: String? = "https://commons.wikimedia.org/wiki/File:Anna_von_Hausswolff,_2022.jpg"
    ) = CommonsImage(
        thumbUrl = "https://upload.wikimedia.org/wikipedia/commons/d/de/Anna_von_Hausswolff%2C_2022.jpg",
        licenceShortName = licence,
        artistHtml = artist,
        descriptionUrl = descriptionUrl,
        size = size,
        mime = mime,
        servedOriginal = servedOriginal
    )

    private val maxBytes = 8L * 1024 * 1024

    @Test
    fun `reads the links a visitor uses, the first live one of each kind, and skips the ended one`() {
        val filled = ArtistEnrichment.fill(row(), entity("hausswolff"), image = null, maxBytes = maxBytes)

        filled.columns shouldContainExactly
            mapOf(
                "website_url" to "https://www.annavonhausswolff.com/",
                "facebook_url" to "https://www.facebook.com/annavonhausswolff",
                "instagram_url" to "https://www.instagram.com/annavonhausswolff/",
                "youtube_url" to "https://www.youtube.com/channel/UCzGfg7QwtVgx5bT7kVQ24UA",
                // The first Bandcamp relation is live; the second, `ended`, is the one skipped.
                "bandcamp_url" to "https://annavonhausswolff.bandcamp.com/",
                "discogs_url" to "https://www.discogs.com/artist/1724854",
                "wikidata_url" to "https://www.wikidata.org/wiki/Q3374548",
                // `free streaming` by host: Spotify, not Deezer. Apple and Tidal are `streaming` and skipped.
                "spotify_url" to "https://open.spotify.com/artist/1eiXrvua27VlWgZ9kiaIn6",
                "artist_type" to "PERSON",
                "country" to "SE"
            )
        filled.fields shouldContainExactly
            listOf("website", "facebook", "instagram", "youtube", "bandcamp", "discogs", "wikidata", "spotify", "type", "country")
        filled.imageRefusal.shouldBeNull()
    }

    @Test
    fun `a person keeps founded and founded_in empty, although MusicBrainz states both`() {
        val hausswolff = entity("hausswolff")
        hausswolff.lifeSpan?.begin shouldBe "1986-09-06"
        hausswolff.beginArea?.name shouldBe "Gothenburg"

        val filled = ArtistEnrichment.fill(row(), hausswolff, image = null, maxBytes = maxBytes)

        filled.columns shouldNotContainKey "founded"
        filled.columns shouldNotContainKey "founded_in"
    }

    @Test
    fun `a group gets when and where it formed`() {
        val filled = ArtistEnrichment.fill(row(name = "Einstürzende Neubauten"), entity("neubauten"), image = null, maxBytes = maxBytes)

        filled.columns["artist_type"] shouldBe "GROUP"
        filled.columns["founded"] shouldBe "1980-04-01"
        filled.columns["founded_in"] shouldBe "Berlin"
        filled.columns["country"] shouldBe "DE"
        filled.columns["soundcloud_url"] shouldBe "https://soundcloud.com/einsturzende-neubauten"
        // Twitter and the ended Google+ page are `social network` too, and neither has a column.
        filled.columns.values.none { "twitter" in it || "plus.google" in it } shouldBe true
    }

    @Test
    fun `the DJ layer - SoundCloud and Discogs, and the Last fm image relation is not a picture`() {
        val filled = ArtistEnrichment.fill(row(name = "Ben Klock"), entity("klock"), image = null, maxBytes = maxBytes)

        filled.columns["soundcloud_url"] shouldBe "https://soundcloud.com/ben-klock"
        filled.columns["discogs_url"] shouldBe "https://www.discogs.com/artist/77025"
        filled.columns shouldNotContainKey "website_url"
        filled.columns shouldNotContainKey "image_url"
        ArtistEnrichment.wikidataIdOf(entity("klock")) shouldBe "Q816535"
    }

    @Test
    fun `resident advisor is read by host from other databases`() {
        val entity =
            entity("klock").let { klock ->
                klock.copy(
                    relations =
                        klock.relations +
                            MusicBrainzUrlRelation(type = "other databases", url = MusicBrainzUrl("https://ra.co/dj/benklock"))
                )
            }

        val filled = ArtistEnrichment.fill(row(name = "Ben Klock"), entity, image = null, maxBytes = maxBytes)

        filled.columns["resident_advisor_url"] shouldBe "https://ra.co/dj/benklock"
    }

    @Test
    fun `a column that holds a value is never touched`() {
        val filled = ArtistEnrichment.fill(row(websiteUrl = "https://a-person-set-this.test/"), entity("hausswolff"), image = null, maxBytes = maxBytes)

        filled.columns shouldNotContainKey "website_url"
        filled.fields.contains("website") shouldBe false
    }

    @Test
    fun `the picture lands with its four fields, the credit as plain text`() {
        val filled = ArtistEnrichment.fill(row(), entity("hausswolff"), commons(), maxBytes)

        filled.columns["image_url"] shouldBe "https://upload.wikimedia.org/wikipedia/commons/d/de/Anna_von_Hausswolff%2C_2022.jpg"
        filled.columns["image_attribution"] shouldBe "Paul Hudson from United Kingdom, via Wikimedia Commons"
        filled.columns["image_licence_id"] shouldBe "CC-BY-2.0"
        filled.columns["image_source_url"] shouldBe "https://commons.wikimedia.org/wiki/File:Anna_von_Hausswolff,_2022.jpg"
        filled.fields.last() shouldBe "image"
        filled.imageRefusal.shouldBeNull()
    }

    @Test
    fun `a licence the map does not know refuses the whole picture, credit and all`() {
        val filled = ArtistEnrichment.fill(row(), entity("hausswolff"), commons(licence = "GFDL"), maxBytes)

        filled.imageRefusal shouldBe "licence"
        filled.columns.keys.none { it.startsWith("image_") } shouldBe true
        filled.fields.contains("image") shouldBe false
    }

    @Test
    fun `Commons naming no author, a non-image file and an original over the cap are refused too`() {
        val boilerplate = commons(artist = "No machine-readable author provided. Foo assumed.")
        ArtistEnrichment.fill(row(), entity("hausswolff"), boilerplate, maxBytes).imageRefusal shouldBe "author"
        ArtistEnrichment.fill(row(), entity("hausswolff"), commons(mime = "application/pdf"), maxBytes).imageRefusal shouldBe "mime"
        ArtistEnrichment.fill(row(), entity("hausswolff"), commons(size = maxBytes + 1, servedOriginal = true), maxBytes).imageRefusal shouldBe "size"
        // A rendering is never the original, so its size is Commons' to choose.
        ArtistEnrichment.fill(row(), entity("hausswolff"), commons(size = maxBytes + 1, servedOriginal = false), maxBytes).imageRefusal.shouldBeNull()
        ArtistEnrichment.fill(row(), entity("hausswolff"), commons(descriptionUrl = null), maxBytes).imageRefusal shouldBe "source"
    }

    @Test
    fun `a row with a picture is not offered another, and nothing is refused`() {
        val filled = ArtistEnrichment.fill(row(imageUrl = "https://venue.test/press.jpg"), entity("hausswolff"), commons(licence = "GFDL"), maxBytes)

        filled.columns shouldNotContainKey "image_url"
        filled.imageRefusal.shouldBeNull()
    }
}
