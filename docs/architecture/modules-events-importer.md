# Modules — events-importer

The application modules and the dependencies between them, as Spring Modulith reads them.

**This diagram is generated.** Do not edit it by hand. Rewrite it with `./gradlew updateModuleDiagrams`.

<!-- generated: module diagram -->

```mermaid
flowchart TD
    artist["artist"]
    common["common «open»"]
    dataquality["dataquality"]
    discogs["discogs"]
    enrichment["enrichment"]
    event["event"]
    genretag["genretag"]
    image["image"]
    importing["importing"]
    licence["licence"]
    musicbrainz["musicbrainz"]
    promoter["promoter"]
    scraper["scraper"]
    slug["slug"]
    translation["translation"]
    venue["venue"]
    wikimedia["wikimedia"]

    artist --> common
    artist --> slug
    dataquality --> event
    dataquality --> importing
    dataquality --> scraper
    discogs --> artist
    discogs --> common
    discogs --> musicbrainz
    enrichment --> artist
    enrichment --> discogs
    enrichment --> musicbrainz
    enrichment --> wikimedia
    event --> artist
    event --> common
    event --> genretag
    event --> promoter
    event --> slug
    event --> venue
    genretag --> common
    importing --> artist
    importing --> common
    importing --> enrichment
    importing --> event
    importing --> genretag
    importing --> licence
    importing --> musicbrainz
    importing --> promoter
    importing --> scraper
    importing --> slug
    importing --> translation
    importing --> venue
    importing --> wikimedia
    musicbrainz --> artist
    musicbrainz --> common
    promoter --> common
    promoter --> slug
    scraper --> common
    scraper --> event
    scraper --> genretag
    scraper --> licence
    scraper --> slug
    translation --> event
    venue --> common
    venue --> slug
    wikimedia --> common
```

<!-- /generated: module diagram -->
