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
        "Q517415" to "Pop", // Europop
        "Q378988" to "Pop", // adult contemporary music
        "Q23301960" to "Pop", // dark pop
        "Q3397882" to "Pop", // progressive pop
        "Q3535998" to "Pop", // Indian pop
        "Q95469495" to "Alternative-Pop", // alternative pop
        "Q25094849" to "Art-Pop", // art pop
        "Q2545135" to "Noise-pop", // noise pop
        "Q25094591" to "Experimental-pop", // experimental pop
        "Q837837" to "Power-Pop", // power pop
        "Q116985035" to "Electropop", // electronic pop
        "Q11319210" to "Synthpop", // techno-pop
        "Q828243" to "Synthpop", // futurepop
        "Q185298" to "A Cappella", // a cappella
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
        "Q56351111" to "Rock", // classic rock
        "Q24383" to "Rock", // German rock
        "Q719927" to "Rock", // symphonic rock
        "Q2090023" to "Rock", // piano rock
        "Q1795903" to "Rock", // Kölschrock
        "Q2994332" to "Rock", // rock en español
        "Q646026" to "Rock", // Argentine rock
        "Q747717" to "Rock", // heartland rock
        "Q1929352" to "Rock", // roots rock
        "Q55296418" to "Rock", // occult rock
        "Q1166218" to "Rock", // dark rock
        "Q1785028" to "Hardrock", // sleaze rock
        "Q181861" to "Noise-rock", // noise rock
        "Q11365" to "Grunge", // grunge
        "Q7749" to "Rock'n'Roll", // rock and roll
        "Q641318" to "Mittelalterrock", // medieval folk rock
        "Q944465" to "Jazzrock", // jazz rock
        "Q2662148" to "Folk-Rock", // Celtic rock
        "Q98528291" to "Psychedelic", // heavy psych
        "Q703778" to "Psychedelic", // neo-psychedelia
        "Q846119" to "Psychedelic", // psychedelic music
        "Q106581470" to "Surf", // surf rock
        "Q220830" to "Surf", // surf music
        "Q320592" to "Krautrock", // Krautrock
        "Q503685" to "Mod", // mod
        "Q646679" to "NDW", // Neue Deutsche Welle
        "Q5110298" to "Alternative", // Christian alternative rock
        // Indie
        "Q183504" to "Indie", // indie rock
        "Q842324" to "Indie", // indie pop
        "Q1317816" to "Indie", // indie folk
        "Q42201" to "Indie", // independent music
        "Q28746819" to "Indie", // bedroom pop
        "Q272167" to "Shoegaze", // shoegaze
        "Q189045" to "Indie", // Britpop
        "Q896513" to "Indie", // Hamburger Schule
        "Q783097" to "Indie", // college rock
        "Q1190565" to "Indie", // jangle pop
        "Q108123046" to "Indie", // slacker rock
        "Q1490170" to "Indie", // slowcore
        "Q7397641" to "Indie", // sadcore
        "Q1982973" to "Indie", // new wave of new wave
        "Q2187392" to "Indie", // alternative dance
        "Q1507298" to "Lofi", // lo-fi music
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
        "Q677542" to "Punk", // riot grrrl
        "Q2101610" to "Punk", // political punk
        "Q486365" to "Punk", // anarcho-punk
        "Q1307555" to "Punk", // punk'n'roll
        "Q930995" to "Punk", // queercore
        "Q2296495" to "Garage-punk", // garage punk
        "Q830325" to "Horrorpunk", // horror punk
        "Q476983" to "Electropunk", // synth-punk
        "Q209498" to "Ska", // 2 tone
        "Q54365" to "Ska", // ska
        "Q13627677" to "Psychobilly", // psychobilly
        "Q1538765" to "Psychobilly", // gothabilly
        "Q1164252" to "Gothic Rock", // deathrock
        "Q1643549" to "Post-Punk", // dance-punk
        "Q1899347" to "Post-Punk", // post-punk revival
        "Q11363" to "Screamo", // screamo
        "Q48770364" to "Emo", // emo revival
        "Q5724040" to "Beatdown Hardcore", // beatdown hardcore
        "Q616679" to "Hardcore", // mathcore
        "Q18126969" to "Hardcore", // Krishnacore
        "Q2084353" to "Metalcore", // electronicore
        "Q30587784" to "Metalcore", // progressive metalcore
        "Q475221" to "Deathcore", // deathcore
        "Q188534" to "Grindcore", // grindcore
        "Q1147692" to "Grindcore", // goregrind
        "Q584752" to "Grindcore", // pornogrind
        "Q888612" to "Grindcore", // deathgrind
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
        "Q486415" to "Metal", // symphonic metal
        "Q1772422" to "Metal", // stoner metal
        "Q1076092" to "Metal", // technical death metal
        "Q2265866" to "Metal", // brutal death metal
        "Q56353998" to "Metal", // Southern metal
        "Q1166197" to "Metal", // dark metal
        "Q2456210" to "Metal", // true metal
        "Q2525544" to "Metal", // Celtic metal
        "Q1621467" to "Metal", // symphonic black metal
        "Q1878514" to "Metal", // post-black metal
        "Q2089511" to "Metal", // death-doom
        "Q1433387" to "Metal", // drone metal
        "Q2738113" to "Metal", // funeral doom metal
        "Q5834708" to "Metal", // epic doom metal
        "Q2997058" to "Metal", // melodic black metal
        "Q2599472" to "Metal", // blackened death metal
        "Q12882577" to "Metal", // pagan black metal
        "Q65937017" to "Metal", // atmospheric black metal
        "Q3908370" to "Metal", // pop metal
        "Q667659" to "Metal", // funk metal
        "Q856872" to "Metal", // rap metal
        "Q2532560" to "Djent", // djent
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
        "Q876171" to "Hip Hop", // East Coast hip-hop
        "Q966564" to "Hip Hop", // hardcore hip-hop
        "Q5421158" to "Hip Hop", // experimental hip-hop
        "Q469343" to "Hip Hop", // UK rap
        "Q1253172" to "Hip Hop", // Southern hip-hop
        "Q965635" to "Hip Hop", // underground hip-hop
        "Q969663" to "Hip Hop", // political hip-hop
        "Q12302979" to "Hip Hop", // battle rap
        "Q65089257" to "Hip Hop", // punk rap
        "Q12362064" to "Hip Hop", // emo rap
        "Q54329757" to "Hip Hop", // Latin trap
        "Q429264" to "Hip Hop", // West Coast hip-hop
        "Q783243" to "Hip Hop", // Australian hip-hop
        "Q6431989" to "Hip Hop", // Korean hip-hop
        "Q131535017" to "Hip Hop", // Argentine hip-hop
        "Q6010117" to "Hip Hop", // Memphis rap
        "Q8560269" to "Hip Hop", // dirty south
        "Q2043175" to "Hip Hop", // hyphy
        "Q107572242" to "Hip Hop", // progressive rap
        "Q121667432" to "Hip Hop", // psychedelic rap
        "Q101552821" to "Hip Hop", // gothic rap
        "Q65335148" to "Hip Hop", // Zeckenrap
        "Q55641646" to "Hip Hop", // Russ music
        "Q1132127" to "Hip Hop", // hip hop culture
        "Q111133884" to "Hip Hop", // rage
        "Q123707590" to "Hip Hop", // pop urbaine
        "Q877693" to "Hip Hop", // rap rock
        "Q1139136" to "Horrorcore", // horrorcore
        "Q1166726" to "Grime", // grime
        "Q2256407" to "R&B", // hip-hop soul
        "Q7118426" to "R&B", // alternative R&B
        "Q2529400" to "R&B", // urban contemporary
        "Q268253" to "Soul", // neo soul
        "Q885561" to "Soul", // blue-eyed soul
        "Q180268" to "Gospel", // gospel music
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
        "Q4736507" to "Electronic", // alternative electronic
        "Q2406633" to "Electronic", // broken beat
        "Q1136045" to "Electronic", // wonky
        "Q2626684" to "Electronic", // chillwave
        "Q101109587" to "Electronic", // future rave
        "Q898709" to "Electronic", // rave music
        "Q2549052" to "Electronic", // ethnic electronica
        "Q1100989" to "Electronic", // glitch
        "Q1435509" to "Electronic", // folktronica
        "Q325397" to "Electronic", // indietronica
        "Q20474" to "Dubstep", // dubstep
        "Q106130091" to "Dubstep", // post-dubstep
        "Q188994" to "Drum & Bass", // drum and bass
        "Q1166321" to "Drum & Bass", // darkstep
        "Q967654" to "Neurofunk", // neurofunk
        "Q1102161" to "Jungle", // jungle
        "Q2710523" to "Jungle", // ragga jungle
        "Q858579" to "Breakbeat", // big beat
        "Q1756101" to "Breakbeat", // nu skool breaks
        "Q1585075" to "Breakbeat", // breakbeat hardcore
        "Q903735" to "Breakbeat", // breakcore
        "Q5466607" to "Footwork", // footwork
        "Q660984" to "IDM", // intelligent dance music
        "Q598593" to "House", // tech house
        "Q341364" to "House", // acid house
        "Q105637110" to "House", // jazz house
        "Q388100" to "House", // Afro house
        "Q20021059" to "House", // future house
        "Q1931555" to "House", // microhouse
        "Q928825" to "House", // garage house
        "Q1165777" to "House", // UK garage
        "Q1751409" to "House", // 2-step garage
        "Q4846346" to "House", // bassline
        "Q601235" to "Hip-House", // hip house
        "Q526463" to "Techno", // Detroit techno
        "Q5801257" to "Trance", // deep trance
        "Q388906" to "Gabber", // hardcore
        "Q372103" to "EBM", // electronic body music
        "Q487965" to "Industrial", // industrial music
        "Q1403744" to "Industrial", // electro-industrial
        "Q56580" to "Industrial", // digital hardcore
        "Q2343562" to "Coldwave", // coldwave
        "Q19938910" to "Retrowave", // synthwave
        "Q861823" to "ElectroClash", // electroclash
        "Q784310" to "Electro", // electro hop
        "Q930592" to "Italo", // Italo dance
        "Q216961" to "Euro Dance", // Eurodance
        "Q756956" to "Ambient", // dark ambient
        "Q650316" to "Ambient", // drone music
        "Q757143" to "Downtempo", // chill-out music
        "Q726239" to "Noise", // noise music
        "Q106388388" to "Hardwave", // hardwave
        "Q106369996" to "Hardwave", // wave
        "Q90568328" to "Phonk", // phonk
        "Q107515261" to "Digicore", // digicore
        "Q85741139" to "Amapiano", // Amapiano
        "Q183308" to "New Age", // New Age
        "Q1190485" to "Disco", // post-disco
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
        "Q106576145" to "Classical", // neoclassical music
        "Q105527" to "Fusion", // jazz fusion
        "Q105513" to "Jazz", // bebop
        "Q1092837" to "Jazz", // post-bop
        "Q1136331" to "Jazz", // nu jazz
        "Q790188" to "Jazz", // avant-garde jazz
        "Q3163340" to "Jazz", // Afro-Cuban jazz
        "Q13634037" to "Jazz", // samba-jazz
        "Q12372810" to "Jazz", // progressive jazz
        "Q105637106" to "Jazz", // clubjazz
        "Q742341" to "Jazz", // modern creative
        "Q2104067" to "Jazz", // jazz pop
        "Q844245" to "Gypsy-Jazz", // Gypsy jazz
        "Q602561" to "Swing", // electro swing
        "Q2635682" to "Brass", // Brass
        "Q640097" to "Blues", // electric blues
        "Q918542" to "Blues", // British blues
        "Q775614" to "Folk", // neofolk
        "Q7482056" to "Folk", // Irish folk music
        "Q252833" to "Folk", // Alpine folk music
        "Q1524296" to "Folk", // Neue Volksmusik
        "Q2404568" to "Folk", // freak folk
        "Q1414130" to "Folk", // filk
        "Q153071" to "Folk", // polka
        "Q574651" to "AntiFolk", // anti-folk
        "Q361164" to "Chanson", // Wienerlied
        "Q2532274" to "Schlager", // Volkstümliche Musik
        "Q1277532" to "Reggae", // early reggae
        "Q10364485" to "Reggae", // Roots Reggae
        "Q1606591" to "Reggae", // reggae fusion
        "Q85755605" to "Reggae", // dancehall pop
        "Q212688" to "Dub", // dub music
        "Q1046768" to "MPB", // música popular brasileira
        "Q11403" to "Latin", // samba
        "Q547137" to "Latin", // Latin pop
        "Q723418" to "Latin", // cumbia
        "Q7171184" to "Latin", // Peruvian cumbia
        "Q208239" to "Latin", // salsa
        "Q333002" to "Latin", // mambo
        "Q371745" to "Latin", // timba
        "Q4428744" to "Latin", // son cubano
        "Q20715191" to "Latin", // Afro-Cuban music
        "Q15830404" to "Latin", // bolero
        "Q2307786" to "Latin", // huayno
        "Q14390274" to "Tango", // tango
        "Q25099840" to "Afrobeats", // Afrobeats
        "Q388296" to "World Music", // Afrobeat
        "Q9764" to "World Music", // flamenco
        "Q3321920" to "World Music", // rumba flamenca
        "Q3453150" to "World Music", // Congolese rumba
        "Q7972788" to "World Music", // Wassoulou music
        "Q8030395" to "World Music", // Wolof music
        "Q972634" to "World Music", // African popular music
        "Q7800608" to "World Music", // desert blues
        "Q281111" to "World Music", // ghazal
        "Q956883" to "World Music", // Carnatic music
        "Q1770695" to "World Music", // Hindustani classical music
        "Q544440" to "World Music", // Indian film music
        "Q3570024" to "World Music", // ethnic music
        "Q786815" to "Balkan", // turbo-folk
        "Q9730" to "Classical", // classical music
        "Q9734" to "Classical", // symphony
        "Q1583807" to "Classical", // art music
        "Q572901" to "Classical", // minimalist music
        "Q179465" to "Classical", // polyphony
        "Q1326777" to "Experimental", // electroacoustic music
        "Q1045756" to "Experimental", // microtonal music
        "Q2332751" to "Experimental" // avant-garde music
    )

/**
 * Wikidata genre items judged to give no tag: a region, an occasion or a medium rather than a style (#5252). `/genre-cleanup`
 * adds an item here when its verdict is `NO_TAG`, so the next run does not list it again, and the importer does not log it as unmapped.
 */
val WIKIDATA_NO_TAG: Set<String> =
    setOf(
        "Q846784", // music of Sweden
        "Q1075799", // music of Albania
        "Q2389651", // children's music
        "Q1062702", // video game music
        "Q1428637", // spoken word
        "Q2743", // musical play
        "Q170539", // parody
        "Q40831", // comedy
        "Q145806", // stand-up comedy
        "Q53094", // black comedy
        "Q128758", // satire
        "Q4209421", // observational comedy
        "Q17112404", // insult comedy
        "Q1092460", // surreal humour
        "Q1113999", // German television comedy
        "Q131183", // cabaret
        "Q1166158", // dark cabaret
        "Q1746926", // kleinkunst
        "Q58854", // literary criticism
        "Q4482", // film criticism
        "Q35760", // essay
        "Q112983", // memoir
        "Q16575965", // horror fiction
        "Q7366", // song
        "Q685884", // vocal music
        "Q24887304", // instrumental composition
        "Q286080", // acoustic music
        "Q107736421", // soundtrack
        "Q24189851", // epic music
        "Q8701407", // waltz
        "Q329385", // schottische
        "Q1051575", // humppa
        "Q1952679", // mundart music
        "Q727781", // music of Latin America
        "Q899215", // music of Brazil
        "Q1378776", // music of Ireland
        "Q316930", // dance music
        "Q11974316", // hardcore
        "Q504635", // crossover
        "Q655514", // freestyle music
        "Q533914" // National Socialist black metal
    )
