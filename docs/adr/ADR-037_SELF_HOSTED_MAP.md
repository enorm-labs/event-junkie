# ADR-037: The map is self-hosted, from an image pinned by digest

## Status

**Accepted (2026-10-01) — the site draws its maps with MapLibre GL JS from Berlin vector tiles that this site serves itself. The tiles, the label fonts and
the map icons come from a `FROM scratch` image in GHCR. The frontend Dockerfile copies them from that image, pinned by digest. No request for the map leaves
this site.**

**Implemented in [#357](https://github.com/enorm-labs/event-junkie/issues/357).**

## Context

#357 adds two maps: tonight's events on `/map`, and every venue on the venues page. A map needs a base map: streets, water, parks and labels.

### The constraints a candidate had to satisfy

- **No new third-party request in a visitor's browser.** A tile server receives the visitor's IP address with each tile. That makes the tile provider a
  processor. The privacy notice then must name it, and LEGAL.md §7 must record the transfer. The notice now says that no third party is contacted.
- **The CSP stays as it is.** `connect-src 'self'` and `img-src 'self'` refuse a tile server. `worker-src` falls back to `script-src 'self'`, so a worker
  must be a same-origin file and not a `blob:` URL.
- **A reproducible build.** The same commit must produce the same image.
- **Attribution.** The base map is OpenStreetMap data under the ODbL, which requires credit where the data is shown.

## Candidate options

- **A hosted tile service** (OpenStreetMap's tile server, OpenFreeMap, MapTiler and others). This adds a processor and widens the CSP. OpenStreetMap's tile
  usage policy does not permit a product to use its tile server.
- **No base map.** Pins on the outlines of the Berlin districts. This is the smallest option. A visitor then cannot see which street a venue is on.
- **Self-hosted vector tiles, downloaded in each build.** Protomaps publishes a daily planet build. `pmtiles extract` cuts Berlin out of it with HTTP range
  requests. Protomaps keeps each daily build for one week only. A build pinned to a date therefore fails after a week. A build that takes the newest date is
  not reproducible, and it fails when Protomaps is not available.
- **Self-hosted vector tiles, from our own image.** `scripts/map-assets.sh build` cuts the tiles once. `publish-map-assets.yml` pushes them to GHCR. The
  frontend Dockerfile copies them from the image, pinned by digest.

## Decision

**Self-hosted vector tiles, from our own image.** It is the only option that keeps every map request on this site and also gives a reproducible build.

The parts:

- **Tiles**: a Berlin extract of a Protomaps build, about 35 MB. The bounding box is the importer's coordinate range in `VenueRequest.kt`, so the map covers
  every venue that the importer can store. The highest zoom level is 14. MapLibre over-zooms vector tiles above that level. Zoom 15 costs 82 MB and shows no
  more detail at venue scale.
- **Fonts and icons**: Noto Sans glyphs and the Protomaps sprites, from `protomaps/basemaps-assets` at a pinned commit, about 13 MB.
- **Library**: MapLibre GL JS with the `pmtiles` protocol and the `@protomaps/basemaps` style. The map code loads only on the two pages that show a map.
- **Worker**: Vite bundles MapLibre's worker into one same-origin file (`?worker&url`), because the CSP refuses a `blob:` worker.
- **Zoom buttons**: our own. MapLibre's buttons draw their icons from `data:` URLs, which `img-src 'self'` refuses.

## Consequences

### What this obliges

- **A map refresh is two steps.** Run `publish-map-assets.yml`. Then copy the `COPY --from=` line from its summary into `events-frontend/Dockerfile` in a pull
  request. Nothing refreshes the map automatically.
- **The frontend image is about 48 MB larger.** The map files are data. They add nothing for the image scan to find.
- **The map shows the credit** `© OpenStreetMap · Protomaps`. The font licence (`fonts/OFL.txt`) and the icon licence (`sprites/LICENSE.md`) ship beside the
  files. LEGAL.md §9.3 lists all three.
- **Local development needs the files.** `scripts/map-assets.sh dev` copies them from the pinned image into `events-frontend/public/map/`, which git ignores.
  Without them the map shows the pins on an empty background.

### What it does not do

- It does not geocode anything. The pins come from the venue coordinates in the database.
- It does not add a third-party request, so the privacy notice does not change.

## When to revisit

- When the site covers a city outside Berlin. The bounding box then changes, and the tile file grows.
- When Protomaps keeps its builds for longer. A download in the build would then be reproducible, and the image would not be necessary.

## References

- [#357](https://github.com/enorm-labs/event-junkie/issues/357), and [#358](https://github.com/enorm-labs/event-junkie/issues/358) for the "near me" filter
  that uses these maps
- [Protomaps basemap downloads](https://docs.protomaps.com/basemaps/downloads)
- [MapLibre GL JS CSP directives](https://maplibre.org/maplibre-gl-js/docs/)
- `scripts/map-assets.sh`, `.github/workflows/publish-map-assets.yml`, `events-frontend/src/components/VenueMap.vue`
