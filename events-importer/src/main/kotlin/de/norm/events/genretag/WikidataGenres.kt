package de.norm.events.genretag

/**
 * Wikidata genre items (P136) to the tag an untagged event takes from its act (ADR-060). Keyed by item id, not label:
 * Q3071 (punk rock) has no English label. A subgenre folds onto the family a venue's own word would give, as
 * [normalizeGenre] folds "Progressive Rock" onto Rock. An item not listed gives no tag.
 *
 * Built from the items the acts of the untagged music events carried when #5222 measured them. Each value is a name
 * [normalizeGenre] returns unchanged, so the map never creates a genre the vocabulary lacks.
 */
val WIKIDATA_GENRES: Map<String, String> =
    mapOf(
        // Pop
        "Q37073" to "Pop", // pop music
        "Q211756" to "Pop", // dance-pop
        "Q739138" to "Pop", // teen pop
        "Q1196752" to "Pop", // traditional pop
        "Q2336819" to "Pop", // operatic pop
        "Q46046" to "Pop", // baroque pop
        "Q264289" to "Pop", // sophisti-pop
        "Q188450" to "Electropop", // electropop
        "Q1298934" to "Synthpop", // synth-pop
        "Q213665" to "K-Pop", // K-pop
        "Q592330" to "Dreampop", // dream pop
        "Q383982" to "Psychedelic", // psychedelic pop
        "Q104695865" to "Hyperpop", // hyperpop
        "Q959583" to "Schlager", // schlager music
        "Q1062400" to "Chanson", // chanson
        // Rock
        "Q11399" to "Rock", // rock music
        "Q484641" to "Rock", // pop rock
        "Q11366" to "Rock", // alternative rock
        "Q83270" to "Rock", // hard rock
        "Q49451" to "Rock", // progressive rock
        "Q217467" to "Rock", // art rock
        "Q325504" to "Rock", // electronic rock
        "Q217191" to "Rock", // soft rock
        "Q193355" to "Rock", // blues rock
        "Q76092" to "Rock", // glam rock
        "Q379033" to "Rock", // arena rock
        "Q852767" to "Rock", // Southern rock
        "Q617240" to "Rock", // stoner rock
        "Q2281409" to "Rock", // dance-rock
        "Q1650296" to "Rock", // instrumental rock
        "Q1641839" to "Rock", // experimental rock
        "Q236913" to "Rock", // space rock
        "Q1132152" to "Rock", // math rock
        "Q209137" to "Rock", // post-rock
        "Q379671" to "Rock", // post-grunge
        "Q1055871" to "Rock", // Japanese rock
        "Q206159" to "Psychedelic", // psychedelic rock
        "Q485395" to "Gothic Rock", // gothic rock
        "Q211573" to "Garage-Rock", // garage rock
        "Q1695023" to "Alternative", // alternative music
        // Indie
        "Q183504" to "Indie", // indie rock
        "Q842324" to "Indie", // indie pop
        "Q1317816" to "Indie", // indie folk
        "Q42201" to "Indie", // independent music
        "Q28746819" to "Indie", // bedroom pop
        "Q272167" to "Shoegaze", // shoegaze
        // Punk and hardcore
        "Q3071" to "Punk", // punk rock
        "Q487914" to "Punk", // pop-punk
        "Q2118402" to "Punk", // German punk
        "Q1990122" to "Punk", // Celtic punk
        "Q460674" to "Ska", // ska punk
        "Q383615" to "Streetpunk", // street punk
        "Q274401" to "Streetpunk", // oi!
        "Q598929" to "Post-Punk", // post-punk
        "Q10922" to "Hardcore", // hardcore punk
        "Q1983116" to "Hardcore", // New York hardcore
        "Q542703" to "Melodic-Hardcore", // melodic hardcore
        "Q377910" to "Post Hardcore", // post-hardcore
        "Q10926" to "Emo", // emo
        "Q3724735" to "Emo", // emo pop
        "Q3724778" to "Emo", // emotional hardcore
        // Metal
        "Q38848" to "Metal", // heavy metal music
        "Q1133657" to "Metal", // traditional heavy metal
        "Q57143" to "Metal", // power metal
        "Q31930766" to "Metal", // symphonic power metal
        "Q20378" to "Metal", // alternative metal
        "Q484344" to "Metal", // progressive metal
        "Q41343" to "Metal", // post-metal
        "Q484179" to "Metal", // folk metal
        "Q15629158" to "Metal", // medieval metal
        "Q483251" to "Metal", // death metal
        "Q253918" to "Metal", // melodic death metal
        "Q132438" to "Metal", // black metal
        "Q263734" to "Metal", // nu metal
        "Q483352" to "Metal", // thrash metal
        "Q241662" to "Metal", // groove metal
        "Q178145" to "Metal", // gothic metal
        "Q188539" to "Metal", // glam metal
        "Q186170" to "Metal", // doom metal
        "Q641357" to "Metal", // avant-garde metal
        "Q211723" to "Metal", // speed metal
        "Q720959" to "Metal", // sludge metal
        "Q465978" to "Metal", // extreme metal
        "Q208494" to "Metal", // industrial metal
        "Q183862" to "Metalcore", // metalcore
        "Q4490718" to "Metalcore", // melodic metalcore
        "Q213572" to "Neue Deutsche Härte", // Neue Deutsche Härte
        // Hip hop, R&B, soul, funk
        "Q11401" to "Hip Hop", // hip-hop
        "Q6010" to "Hip Hop", // rapping
        "Q2913982" to "Hip Hop", // trap music
        "Q454071" to "Hip Hop", // German hip-hop
        "Q2572585" to "Hip Hop", // pop rap
        "Q16829781" to "Hip Hop", // cloud rap
        "Q753679" to "Hip Hop", // gangsta rap
        "Q141459" to "Hip Hop", // conscious hip-hop
        "Q438503" to "Hip Hop", // alternative hip-hop
        "Q255406" to "Hip Hop", // jazz rap
        "Q45981" to "R&B", // rhythm and blues
        "Q850412" to "R&B", // contemporary R&B
        "Q131272" to "Soul", // soul
        "Q164444" to "Funk", // funk
        // Electronic
        "Q9778" to "Electronic", // electronic music
        "Q817138" to "Electronic", // electronica
        "Q851213" to "Electronic", // electronic dance music
        "Q105763581" to "Electronic", // experimental electronic music
        "Q65937906" to "Electronic", // leftfield electronic
        "Q217597" to "Electro", // electro
        "Q170611" to "Techno", // techno
        "Q1430587" to "Techno", // minimal techno
        "Q1262781" to "Techno", // dub techno
        "Q9149801" to "Techno", // ambient techno
        "Q29480448" to "Techno", // industrial techno
        "Q20502" to "House", // house music
        "Q874038" to "House", // deep house
        "Q627906" to "House", // electro house
        "Q915152" to "Progressive House", // progressive house
        "Q170435" to "Trance", // trance
        "Q1027999" to "Trance", // vocal trance
        "Q1136765" to "Trance", // uplifting trance
        "Q2260696" to "Trance", // progressive trance
        "Q2302695" to "Trance", // tech trance
        "Q890887" to "Trance", // Eurotrance
        "Q622871" to "Psytrance", // psychedelic trance
        "Q193207" to "Ambient", // ambient music
        "Q251192" to "Downtempo", // downtempo
        "Q205560" to "Trip-Hop", // trip hop
        "Q213121" to "New Age", // new age music
        "Q58339" to "Disco", // disco
        "Q1374731" to "Disco", // Eurodisco
        "Q606393" to "Disco", // Hi-NRG
        "Q187760" to "New Wave", // new wave
        "Q3840596" to "Darkwave", // dark wave
        "Q284543" to "Darkwave", // ethereal wave
        "Q968730" to "Industrial", // industrial rock
        "Q1640319" to "Experimental", // experimental music
        // Folk, country, blues, jazz and the rest
        "Q43343" to "Folk", // folk music
        "Q235858" to "Folk", // traditional folk music
        "Q186472" to "Folk", // folk rock
        "Q11700058" to "Folk", // folk-pop
        "Q1195253" to "Folk", // sea shanty
        "Q854451" to "Folk", // Celtic music
        "Q83440" to "Country", // country music
        "Q438476" to "Country", // alternative country
        "Q613408" to "Country", // country rock
        "Q2407862" to "Americana", // Americana
        "Q213714" to "Bluegrass", // bluegrass music
        "Q9759" to "Blues", // blues
        "Q8341" to "Jazz", // jazz
        "Q846833" to "Jazz", // crossover jazz
        "Q211781" to "Jazz", // free jazz
        "Q38656" to "Jazz", // jazz-funk
        "Q203775" to "Swing", // swing
        "Q9794" to "Reggae", // reggae
        "Q474027" to "Reggae", // dancehall
        "Q202930" to "Latin", // reggaeton
        "Q205049" to "World Music", // world music
        "Q1344" to "Classical", // opera
        "Q106576145" to "Classical" // neoclassical music
    )
