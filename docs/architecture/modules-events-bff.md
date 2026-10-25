# Modules — events-bff

The application modules and the dependencies between them, as Spring Modulith reads them.

**This diagram is generated.** Do not edit it by hand. Rewrite it with `./gradlew updateModuleDiagrams`.

<!-- generated: module diagram -->

```mermaid
flowchart TD
    account["account"]
    artist["artist"]
    common["common «open»"]
    event["event"]
    feed["feed"]
    festival["festival"]
    genretag["genretag"]
    image["image"]
    licence["licence"]
    mail["mail"]
    meta["meta"]
    promoter["promoter"]
    search["search"]
    sitemap["sitemap"]
    sourcelicence["sourcelicence"]
    venue["venue"]

    account --> common
    account --> event
    account --> mail
    artist --> common
    artist --> image
    event --> artist
    event --> common
    event --> festival
    event --> genretag
    event --> image
    event --> licence
    event --> promoter
    event --> sourcelicence
    event --> venue
    feed --> common
    feed --> event
    feed --> venue
    festival --> common
    genretag --> common
    promoter --> common
    promoter --> image
    search --> artist
    search --> common
    search --> event
    search --> promoter
    search --> venue
    sitemap --> common
    sourcelicence --> licence
    venue --> common
    venue --> image
```

<!-- /generated: module diagram -->
