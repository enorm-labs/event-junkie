# ADR-020: Image processing

## Status

**Accepted (2026-08-28) — imgproxy generates the derivatives. The importer calls it over HTTP during an import, and it
never serves a visitor.**

**Not implemented.** [ADR-019](ADR-019_VENUE_IMAGE_DELIVERY.md) decided to cache venue images and nothing is built yet.
[#283](https://github.com/enorm-labs/event-junkie/issues/283) blocks that work, so it blocks this too.

**It supersedes nothing, and it depends on one thing.** ADR-019 decided _that_ we store images. This decides _how_ we
produce the sizes we store. The two reverse independently, which is why they are two documents.

Two other ADRs set the constraints rather than the answer:

- [ADR-012](ADR-012_CLOUD_PLATFORM.md) removed the CDN, and it counted the deployables.
- [ADR-017](ADR-017_JRE_BASE_IMAGE.md) chose an Alpine base image, and it is the record of what an unfixable finding in a
  base layer costs.

## Context

### What forced it

ADR-019 stops the site hotlinking. A stored image must therefore be resized, because the source is whatever the venue
published. The three render sites ask for 80 px and 96 px, and a venue flyer can arrive at 4 MB.

Nothing in this repository decodes an image today. So this is a new dependency, whichever way it goes.

### The constraints any candidate had to satisfy

| Constraint                                                                                                                                      | Where it comes from                         | What it rules out                                                                                                                     |
| ----------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| The runtime is `bellsoft/liberica-openjre-alpine:25`                                                                                            | ADR-017                                     | Anything needing a glibc binary                                                                                                       |
| The importer Dockerfile has no builder stage and no `RUN` that does build work (as amended 2026-09-05 in ADR-017; at the time, no `RUN` at all) | `events-importer/Dockerfile`                | Building a native library in the image; an `apk add` of a prebuilt one was ruled out when this was decided and is not re-weighed here |
| The chart sets `readOnlyRootFilesystem: true`                                                                                                   | `deploy/charts/*/templates/_helpers.tpl`    | Any decoder that writes temporary files by default                                                                                    |
| No CDN and no caching proxy                                                                                                                     | ADR-012, and §5 of both privacy notices     | Anything that resizes for each request                                                                                                |
| Stored objects are content addressed and immutable                                                                                              | ADR-019                                     | A processor that must run at request time                                                                                             |
| Politeness belongs to our own fetcher                                                                                                           | [ADR-007](ADR-007_WEB_SCRAPING_STRATEGY.md) | Letting the processor pull from a venue                                                                                               |

**Throughput is not a constraint, and it is worth saying so.** About 80 sources produce roughly 65 images a day. A
processor chosen for speed would win a race that nobody is running.

## Candidate options

### A — imgproxy, called during the import

A separate program in its own container. The importer fetches the file, stores it, then asks imgproxy for each width
over HTTP. Results are stored as immutable objects.

**This is not how imgproxy documents itself.** Its README expects a serving proxy. It says imgproxy _"will live behind a
CDN, a load balancer, or a reverse proxy in a production environment anyway"_, because **imgproxy does not cache**. That
shape is option D below, and the constraints table rules it out.

### B — Thumbnailator, inside the importer

One jar, no transitive dependencies, no native library. Version 0.4.21 was released on 2025-10-01. It writes JPEG and PNG
through Java's own ImageIO.

It needs care that the container makes non-obvious. `ImageIO.setUseCache(false)` is required, because ImageIO otherwise
writes to `java.io.tmpdir` and a read-only root filesystem refuses it.

### C — a native encoder inside the importer

Scrimage with its WebP module, NightMonkeys, `webp-imageio`, or libvips through a foreign-function binding.

**Scrimage ships `cwebp` and `dwebp` binaries for five platforms and Alpine or musl is not one of them.** Its
documentation says so and offers a directory property for binaries you build yourself. The others need a library that
`apk` installs, which the Dockerfile has no stage to run.

### D — imgproxy as a serving proxy

The documented shape. Rejected by the constraints table: it needs a cache in front that we removed, and it spends
processor time for each visitor.

## Comparison

| Axis                       | A — imgproxy at import                            | B — Thumbnailator           | C — native encoder                    |
| -------------------------- | ------------------------------------------------- | --------------------------- | ------------------------------------- |
| Decodes hostile bytes      | In a separate program                             | **In the importer process** | **In the importer process**           |
| Writes AVIF and WebP       | Yes                                               | No. JPEG and PNG            | Yes                                   |
| Reads what venues serve    | Widest, WebP included                             | ImageIO formats only        | Wide                                  |
| Native dependency          | None of ours. It is their image                   | None                        | **Needs a `RUN` layer**               |
| Deployables added          | **One**                                           | None                        | None                                  |
| Images we cannot rebuild   | **One more in the nightly scan**                  | None                        | None                                  |
| Originals must be kept     | **Yes, it derives from a source**                 | No                          | No                                    |
| Container work             | A sidecar, a limit, URL signing, one network rule | None                        | A Dockerfile that breaks its own rule |
| Exit cost if it goes wrong | Fall back to B                                    | Already the fallback        | High                                  |

## Decision

**Option A. imgproxy generates the derivatives, and the importer calls it during an import.**

**The reason that settled it: hostile bytes never reach a decoder inside our own process.** The importer holds the
database connection. A malformed header, a decompression bomb or a codec vulnerability arriving from a venue is better
handled in a separate program that holds nothing.

That is a stronger control than hardening ImageIO, because it does not depend on our getting the hardening right.

Two supporting reasons, and neither would have decided it alone:

1. **AVIF and WebP output becomes available**, which the Alpine base image cannot produce without a native library.
2. **imgproxy reads more formats than ImageIO does.** Venues increasingly serve WebP, and a source we cannot read is an
   image we lose.

**What we accept.** A fourth deployable, and a third-party image that we cannot rebuild when a finding has no upstream
fix. ADR-017 is the record of what that costs, and this accepts it a second time with open eyes.

**Option B stays the exit rather than the loser.** It is one jar and it works. If imgproxy becomes a liability, taking B
costs the modern formats and returns the decoding to our process, and nothing else in ADR-019 changes.

## Consequences

### What this obliges

- **imgproxy must never fetch from a venue.** Robots.txt and per-host throttling live in our fetcher (ADR-007). A
  processor that pulls source URLs itself goes around all of it.
- **It must stay unreachable from outside the importer pod.** URL signing and one network rule, because imgproxy in front
  of a visitor is option D and a different risk.
- **Originals must be kept**, because it derives from a source. That is about ten times the storage, inside the
  subscription we already hold.
- **An original keeps its metadata**, so only derivatives may be served. Nothing may route to the stored originals.
- **Pin the image by digest**, the way the JRE base is pinned, and add it to the nightly image scan.
- The decode bounds move into its configuration: a maximum source resolution and a maximum source file size.

### What becomes easier

- No image decoding code of ours, and no ImageIO hardening to get right.
- A later width or format costs the venue nothing, because the original is already here.

## When to revisit

- **If imgproxy takes a finding with no upstream fix.** We cannot rebuild another project's image. Option B is the exit,
  and it is recorded for that reason.
- **If the sidecar's memory cost is out of proportion.** libvips trades memory for speed, and speed is not needed here.
- **If a pure-JVM encoder for AVIF or WebP appears.** That removes the only reason to prefer a separate program, other
  than the isolation, which would still stand.

## References

- [ADR-019](ADR-019_VENUE_IMAGE_DELIVERY.md) — the decision to cache, which this one serves
- [ADR-007](ADR-007_WEB_SCRAPING_STRATEGY.md), [ADR-012](ADR-012_CLOUD_PLATFORM.md),
  [ADR-017](ADR-017_JRE_BASE_IMAGE.md) — the constraints
- [#364](https://github.com/enorm-labs/event-junkie/issues/364), [#792](https://github.com/enorm-labs/event-junkie/issues/792),
  [#283](https://github.com/enorm-labs/event-junkie/issues/283)
- [imgproxy](https://github.com/imgproxy/imgproxy) — Apache 2.0, with a commercial edition we do not use
- [Thumbnailator](https://github.com/coobird/thumbnailator) · [Scrimage WebP module](https://sksamuel.github.io/scrimage/webp/)
