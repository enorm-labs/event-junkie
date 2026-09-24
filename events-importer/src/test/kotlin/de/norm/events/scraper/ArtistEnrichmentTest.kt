package de.norm.events.scraper

import de.norm.events.artist.ArtistEntity
import de.norm.events.musicbrainz.MusicBrainzArtist
import de.norm.events.musicbrainz.MusicBrainzUrl
import de.norm.events.musicbrainz.MusicBrainzUrlRelation
import de.norm.events.wikimedia.CommonsImage
import de.norm.events.wikimedia.WikipediaExtract
import io.kotest.matchers.collections.shouldBeEmpty
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
        artistType: String? = null,
        description: String? = null
    ) = ArtistEntity(
        id = 1L,
        name = name,
        slug = "anna-von-hausswolff",
        description = description,
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

    private val neubautenLead =
        "Einstürzende Neubauten ist eine deutsche Band aus Berlin, die 1980 gegründet wurde. " +
            "Sie gilt als eine der einflussreichsten Gruppen der Industrial-Musik."

    private fun wikipedia(
        text: String = neubautenLead,
        language: String = "de"
    ) = WikipediaExtract(language = language, text = text, pageUrl = "https://$language.wikipedia.org/wiki/Einst%C3%BCrzende_Neubauten")

    @Test
    fun `an ensemble's lead lands with its language and its credit`() {
        val filled =
            ArtistEnrichment.fill(
                row(name = "Einstürzende Neubauten"),
                entity("neubauten"),
                image = null,
                maxBytes = maxBytes,
                extracts = listOf(wikipedia())
            )

        filled.columns["description"] shouldBe neubautenLead
        filled.columns["description_language"] shouldBe "de"
        filled.columns["description_attribution"] shouldBe "Wikipedia"
        filled.columns["description_licence_id"] shouldBe "CC-BY-SA-4.0"
        filled.columns["description_source_url"] shouldBe "https://de.wikipedia.org/wiki/Einst%C3%BCrzende_Neubauten"
        filled.fields.last() shouldBe "description"
        filled.descriptionRefusals.shouldBeEmpty()
    }

    @Test
    fun `a person is never offered a lead, whatever the extract says`() {
        ArtistEnrichment.wantsDescription(row(), entity("hausswolff")) shouldBe false
        val filled = ArtistEnrichment.fill(row(), entity("hausswolff"), image = null, maxBytes = maxBytes, extracts = listOf(wikipedia()))

        filled.columns shouldNotContainKey "description"
        filled.descriptionRefusals.shouldBeEmpty()
    }

    @Test
    fun `a stored description is never replaced, the venue's or a person's`() {
        val own = row(name = "Einstürzende Neubauten", description = "Die Band spielt heute ihr neues Album.")

        ArtistEnrichment.wantsDescription(own, entity("neubauten")) shouldBe false
        ArtistEnrichment.fill(own, entity("neubauten"), image = null, maxBytes = maxBytes, extracts = listOf(wikipedia())).columns shouldNotContainKey
            "description"
    }

    @Test
    fun `birth data refuses the lead on an ensemble too, because MusicBrainz types some solo acts as groups`() {
        listOf(
            "Deine Cousine (* 12. März 1990 in Hamburg) ist eine deutsche Rockmusikerin, die seit 2016 unter diesem Namen auftritt.",
            "Kid Francescoli is the project of Mathieu Hocine, born in Marseille, who has released five albums of electropop since 2002.",
            "Die Band wurde von Max Muster (geb. 1970) gegründet und spielt seitdem in wechselnder Besetzung deutschsprachigen Rock.",
            "Anna Beispiel (1986–2024) war eine schwedische Sängerin und Organistin, deren Alben vor allem in Skandinavien erschienen.",
            "Die Gruppe um den Sänger Otto Beispiel († 2019) war eine der ersten deutschen Punkbands und spielte bis 2019 in Berlin."
        ).forEach { lead ->
            val filled = ArtistEnrichment.fill(row(name = "Einstürzende Neubauten"), entity("neubauten"), null, maxBytes, listOf(wikipedia(text = lead)))
            filled.descriptionRefusals shouldContainExactly listOf("birth-data")
            filled.columns shouldNotContainKey "description"
        }
    }

    @Test
    fun `a capital Geboren is a title, not birth data, so both of Unheilig's leads land`() {
        val de =
            "Unheilig ist eine deutsche Musikgruppe aus Aachen, die 1999 um den Sänger und Songschreiber Der Graf entstand. " +
                "Der größte Erfolg der Band ist das Album Große Freiheit aus dem Jahr 2010 mit der Singleauskopplung Geboren um zu leben."
        val en =
            "Unheilig is a German band that combines a number of musical styles, including pop and electronic as well as hard rock. " +
                "It was founded in Aachen in 1999 and principally consists of vocalist Bernd \"Der Graf\" Heinrich, along with various musicians."
        val filled =
            ArtistEnrichment.fill(
                row(name = "Unheilig"),
                entity("neubauten"),
                null,
                maxBytes,
                listOf(wikipedia(text = de), wikipedia(text = en, language = "en"))
            )

        filled.descriptionRefusals.shouldBeEmpty()
        filled.columns["description"] shouldBe de
        filled.columns["description_alt"] shouldBe en
    }

    @Test
    fun `a member's birthplace or a German birth clause is still birth data, wherever the date stands`() {
        listOf(
            "Los Bitchos is a pan-continental band based in London, England. The band consists of Western Australian-born Serra Petale " +
                "(guitar), Swede Josefine Jonsson, and South London-born Nic Crawshaw.",
            "The Temperance Movement are a British blues rock supergroup formed in 2011 by Glasgow-born vocalist Phil Campbell " +
                "and guitarists Luke Potashnick and Paul Sayer.",
            "Die Band gründete der Sänger Max Muster, der 1970 in Hamburg geboren wurde, gemeinsam mit zwei Schulfreunden aus Altona.",
            "Die Band spielt Rock aus Köln. Geboren am 3. Mai 1980 in Köln, gründete ihr Sänger sie nach dem Studium mit zwei Freunden.",
            "Die Band gründete die Sängerin Anna Schmidt geb. Müller gemeinsam mit ihrem Bruder, und sie spielt seitdem Folk in Leipzig."
        ).forEach { lead ->
            val filled = ArtistEnrichment.fill(row(name = "Einstürzende Neubauten"), entity("neubauten"), null, maxBytes, listOf(wikipedia(text = lead)))
            filled.descriptionRefusals shouldContainExactly listOf("birth-data")
        }
    }

    @Test
    fun `a lead shorter than a sentence of substance is refused as short`() {
        val lead = "Einstürzende Neubauten ist eine deutsche Band aus Berlin."
        val filled = ArtistEnrichment.fill(row(name = "Einstürzende Neubauten"), entity("neubauten"), null, maxBytes, listOf(wikipedia(text = lead)))

        filled.descriptionRefusals shouldContainExactly listOf("short")
        filled.columns shouldNotContainKey "description"
    }

    private val neubautenLeadEn =
        "Einstürzende Neubauten is a German band from West Berlin, formed in 1980. " +
            "The group is known for building its own instruments from scrap metal."

    private val storedLead =
        row(name = "Einstürzende Neubauten", description = neubautenLead).copy(
            descriptionLanguage = "de",
            descriptionAttribution = "Wikipedia",
            descriptionLicenceId = "CC-BY-SA-4.0",
            descriptionSourceUrl = "https://de.wikipedia.org/wiki/Einst%C3%BCrzende_Neubauten"
        )

    @Test
    fun `both wikis' leads land, each with its own language and credit`() {
        val leads = listOf(wikipedia(), wikipedia(text = neubautenLeadEn, language = "en"))
        val filled = ArtistEnrichment.fill(row(name = "Einstürzende Neubauten"), entity("neubauten"), null, maxBytes, leads)

        filled.columns["description"] shouldBe neubautenLead
        filled.columns["description_language"] shouldBe "de"
        filled.columns["description_alt"] shouldBe neubautenLeadEn
        filled.columns["description_alt_language"] shouldBe "en"
        filled.columns["description_alt_attribution"] shouldBe "Wikipedia"
        filled.columns["description_alt_licence_id"] shouldBe "CC-BY-SA-4.0"
        filled.columns["description_alt_source_url"] shouldBe "https://en.wikipedia.org/wiki/Einst%C3%BCrzende_Neubauten"
        filled.fields.takeLast(2) shouldContainExactly listOf("description", "description_alt")
        filled.descriptionRefusals.shouldBeEmpty()
    }

    @Test
    fun `a short second lead is refused alone, and the first still lands`() {
        val leads = listOf(wikipedia(), wikipedia(text = "Einstürzende Neubauten is a German band.", language = "en"))
        val filled = ArtistEnrichment.fill(row(name = "Einstürzende Neubauten"), entity("neubauten"), null, maxBytes, leads)

        filled.columns["description"] shouldBe neubautenLead
        filled.columns shouldNotContainKey "description_alt"
        filled.descriptionRefusals shouldContainExactly listOf("short")
    }

    @Test
    fun `a short preferred lead gives way to the other wiki's, which then has no alt`() {
        val leads = listOf(wikipedia(text = "Einstürzende Neubauten ist eine Band."), wikipedia(text = neubautenLeadEn, language = "en"))
        val filled = ArtistEnrichment.fill(row(name = "Einstürzende Neubauten"), entity("neubauten"), null, maxBytes, leads)

        filled.columns["description"] shouldBe neubautenLeadEn
        filled.columns["description_language"] shouldBe "en"
        filled.columns shouldNotContainKey "description_alt"
        filled.descriptionRefusals shouldContainExactly listOf("short")
    }

    @Test
    fun `birth data in either lead refuses both, because the item is a person`() {
        val person = "Kid Francescoli is the project of Mathieu Hocine, born in Marseille, who has released five albums of electropop since 2002."
        val leads = listOf(wikipedia(), wikipedia(text = person, language = "en"))
        val filled = ArtistEnrichment.fill(row(name = "Einstürzende Neubauten"), entity("neubauten"), null, maxBytes, leads)

        filled.columns shouldNotContainKey "description"
        filled.columns shouldNotContainKey "description_alt"
        filled.descriptionRefusals shouldContainExactly listOf("birth-data", "birth-data")
    }

    @Test
    fun `a stored Wikipedia lead takes the other wiki's beside it, and is not rewritten`() {
        ArtistEnrichment.wantsDescription(storedLead, entity("neubauten")) shouldBe false
        ArtistEnrichment.wantsDescriptionAlt(storedLead, entity("neubauten")) shouldBe true
        val filled = ArtistEnrichment.fill(storedLead, entity("neubauten"), null, maxBytes, listOf(wikipedia(text = neubautenLeadEn, language = "en")))

        filled.columns shouldNotContainKey "description"
        filled.columns["description_alt"] shouldBe neubautenLeadEn
        filled.columns["description_alt_language"] shouldBe "en"
    }

    @Test
    fun `a stored lead is never paired with a lead in its own language`() {
        val filled = ArtistEnrichment.fill(storedLead, entity("neubauten"), null, maxBytes, listOf(wikipedia()))

        filled.columns shouldNotContainKey "description_alt"
    }

    @Test
    fun `a venue's or a person's text takes no Wikipedia lead beside it`() {
        val own = row(name = "Einstürzende Neubauten", description = "Die Band spielt heute ihr neues Album.")

        ArtistEnrichment.wantsDescriptionAlt(own, entity("neubauten")) shouldBe false
        val filled = ArtistEnrichment.fill(own, entity("neubauten"), null, maxBytes, listOf(wikipedia(text = neubautenLeadEn, language = "en")))
        filled.columns shouldNotContainKey "description_alt"
    }

    @Test
    fun `a row with both leads is offered neither`() {
        val both = storedLead.copy(descriptionAlt = neubautenLeadEn, descriptionAltLanguage = "en")

        ArtistEnrichment.wantsDescriptionAlt(both, entity("neubauten")) shouldBe false
    }

    @Test
    fun `a German-speaking act reads dewiki first, any other enwiki first`() {
        WikipediaLead.languagesFor("DE") shouldContainExactly listOf("de", "en")
        WikipediaLead.languagesFor("at") shouldContainExactly listOf("de", "en")
        WikipediaLead.languagesFor("SE") shouldContainExactly listOf("en", "de")
        WikipediaLead.languagesFor(null) shouldContainExactly listOf("en", "de")
    }
}
