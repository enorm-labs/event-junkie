# ADR-019: Venue image delivery

## Status

**Accepted (2026-08-28) — cache each venue image on our own origin, in a bucket. The site stops hotlinking, and the
visitor's browser never contacts the venue.**

**How the derivatives get generated is [ADR-020](ADR-020_IMAGE_PROCESSING.md).** That question follows from this one and
reverses independently, so it has its own record.

**Not implemented.** The site still hotlinks. [#364](https://github.com/enorm-labs/event-junkie/issues/364) closes with this
ADR, and [#792](https://github.com/enorm-labs/event-junkie/issues/792) closes when the work lands.

**[#283](https://github.com/enorm-labs/event-junkie/issues/283) is now a blocker.** A stored copy needs a per-source licence
position. The embedding we do today does not. No caching code starts before #283 records one.

**It supersedes nothing.** Three neighbouring ADRs touch the area and none of them decided it:

- [ADR-007](ADR-007_WEB_SCRAPING_STRATEGY.md) decided how we collect data. It did not decide what the site displays.
- [ADR-012](ADR-012_CLOUD_PLATFORM.md) chose Hetzner and removed Cloudflare. That fixes where a cached file lives.
- [ADR-013](ADR-013_LOCALISATION.md) fixed the rule that German is authoritative. Any notice change inherits it.

**The comparison below is kept in full, and it is the reason to read this document.** Option A remains a reasonable choice on
copyright grounds. A reader who wants to reopen this must start from §Comparison rather than from the decision.

## Context

### What forced it

[#792](https://github.com/enorm-labs/event-junkie/issues/792) found a defect in a document, not in code. The site sends every
visitor's IP address to a server we do not operate, and the privacy notice does not say so.

These places carry the venue's URL today. The four `<img>` tags make the visitor's browser fetch the
file. The two metadata writers publish the URL for a crawler to fetch instead.

| Where                            | What it does                                                              |
| -------------------------------- | ------------------------------------------------------------------------- |
| `EventCard.vue`, `VenueCard.vue` | `<img :src="…imageUrl">` at 80 px, on every list row                      |
| `BaseDetailView.vue`             | The same tag at 96 px, in the venue, artist and promoter detail header    |
| `EventDetailView.vue`            | Its own tag at full width, and not the one above. The largest on the site |
| `pageMeta.ts`                    | Puts the same URL in `og:image`                                           |
| `structuredData.ts`              | Puts the same URL in the JSON-LD `image` field                            |
| `EventResponses.kt`              | The BFF returns `imageUrl` unfiltered, so the URL is the venue's          |

The notice is worse than silent. §5 of both privacy pages states that no content delivery network, edge provider or proxy sits
in front of the site. It then states that the visitor's request reaches our servers in Germany directly. For images that is not
true.

`LAST_REVIEWED` in `events-frontend/src/lib/legal.ts` reads `2026-08-08`. Nobody checked the notice against this behaviour.

### The three questions, and why one answer does not serve all three

[`docs/SCRAPING_POSITION.md`](../SCRAPING_POSITION.md) §3.6 states the problem plainly. An answer that improves one question
makes another worse:

1. **Privacy.** Hotlinking discloses the visitor's IP address to the venue. Caching stops that disclosure.
2. **Copyright.** Hotlinking copies nothing. Caching is a reproduction under § 16 UrhG.
3. **Load.** Hotlinking sends one request to the venue for every visitor. Caching sends one request for every import.

Privacy and load point the same way. Copyright points the other way. That is the whole of the decision.

### Constraints any option must satisfy

- **The notice must describe what the system does** (`docs/LEGAL.md` §7.7). Both options change the notice. Neither leaves it
  alone.
- **German is authoritative and both locales ship together** (ADR-013 §2, `LEGAL.md` §6.1).
- **A new processor needs an Art. 28 contract** (`AGENTS.md` § Privacy & GDPR). Hetzner is the only processor today, under a
  contract concluded on 2026-08-19.
- **`INFRASTRUCTURE_IS_PROPOSED` is still `true`.** Nothing runs yet.
- **Per-source licence status is not decided.** [#283](https://github.com/enorm-labs/event-junkie/issues/283) owns it and is
  open.
- **The guiding principle is to aggregate and link back, and not to republish** (`SCRAPING_POSITION.md` § The short version).

## Candidate options

### Option A — keep the hotlink, and describe it

The browser continues to request the file from the venue. Both privacy notices gain an entry that names the disclosure. §5 loses
its unqualified claim that no third party is involved.

**Cost:** one pull request across two `.vue` notices, `legal.ts`, `docs/LEGAL.md` §7 and `SCRAPING_POSITION.md`. No code changes.

### Option B — cache the image on our own origin

The importer fetches each image once and stores it. The site serves the file from our domain. The visitor's browser never
contacts the venue.

Hetzner Object Storage already holds two managed buckets, `event-junkie-o2` and `event-junkie-backups`, declared in
`infra/bootstrap/storage.tf`. **A third bucket adds no processor and no transfer mechanism.** The privacy notice still changes,
because §4 must say that we store venue imagery.

**Cost:** work in the importer, the BFF, the Helm chart and OpenTofu. It also needs a deletion route, because a venue takedown
no longer removes our copy.

### Options rejected early

- **Omit images.** It stops the disclosure and costs nothing to build. It also removes the feature that
  [#364](https://github.com/enorm-labs/event-junkie/issues/364) exists to decide, so it answers the wrong question.
- **Proxy each request without storing it.** It stops the IP disclosure. It does not reduce load on the venue, and it adds our
  own. § 44a UrhG may cover a transient copy, but we did not test that reading. Without a cache in front, it takes the cost of
  both options and the benefit of one.

## Comparison

| Axis                              | A — hotlink                                     | B — cache on our origin                                |
| --------------------------------- | ----------------------------------------------- | ------------------------------------------------------ |
| Visitor IP reaches the venue      | **Yes**, on every image                         | No                                                     |
| Referer reaches the venue         | **Yes**, so the venue learns which page is open | No                                                     |
| Privacy notice §5 claim           | Must be qualified                               | Becomes true as written                                |
| Copyright act performed           | None                                            | **Reproduction under § 16 UrhG**                       |
| Case law relied on                | _Svensson_ C-466/12, _BestWater_ C-348/13       | None. A licence question replaces it                   |
| Known limit of that reading       | _VG Bild-Kunst_ C-392/19, on technical measures | Not applicable                                         |
| Requests to the venue             | One per visitor, so it grows with our success   | One per import                                         |
| Image freshness                   | Always current                                  | Stale until the next import                            |
| Venue takedown                    | Propagates by itself                            | **Needs a deletion route we must build**               |
| Format and size control           | None. A 4 MB flyer fills an 80 px card          | Full. AVIF or WebP at the rendered size                |
| Source image moves or returns 404 | The card shows a broken image                   | Our copy keeps working                                 |
| New processor                     | None                                            | None. Hetzner already holds two buckets                |
| New infrastructure                | None                                            | A bucket, a fetch step, a serving path, an expiry rule |
| Who pays for delivery             | The venue                                       | We do                                                  |
| Work to build                     | One documentation pull request                  | Importer, BFF, chart and OpenTofu                      |
| Cost to reverse                   | Low                                             | Higher. Stored files need deletion                     |

## Decision

**Option B. We cache each venue image on our own origin and serve it from our domain.**

**The reason that settled it: B removes the disclosure, and A only describes it.** A privacy notice that accurately reports
an avoidable disclosure is still a worse outcome than no disclosure. Option A was honest. It was not better.

Two supporting reasons, in the order they carried weight:

1. **B is the only option that separates our load on a venue from our own traffic.** Under A the venue pays more as we grow,
   which contradicts the politeness position in [`SCRAPING_POSITION.md`](../SCRAPING_POSITION.md) §3.4.
2. **B adds no processor.** Hetzner Object Storage already holds two managed buckets under the Art. 28 contract of
   2026-08-19. The cost most often assumed to block B does not exist here.

**A cached image goes in a bucket and not in the database, and the reason is backups.** A cached image is the only data here
that we can make again. If we lose every one, the importer fetches them again.

`wal-g` archives the database continuously, and `docs/LEGAL.md` §7.3a states a 30-day window with a 35-day bucket ceiling.
Data in Postgres enters that regime and stays in it across many overlapping copies. #270 restores from it. **Throwaway data
must not sit inside the backup set for data we cannot replace**, so it needs a store that we never restore.

That reasoning holds at any size, and a cost argument would not. Object Storage carries a separate 1 TB subscription that
holds 736 MB today ([`docs/ops/COSTS.md`](../ops/COSTS.md)), so price does not decide this.

**What we accept by choosing B.** Caching is a reproduction under § 16 UrhG, and embedding is not. We give up the strongest
part of our copyright position on purpose. The privacy property is what we buy with it. #283 is the work that limits the
exposure, and that is why it now blocks.

**What did not decide it.** Format control, image freshness and broken-image behaviour all favour B. None of them would
justify the copyright cost on its own.

## Consequences

### What this obliges

- **[#283](https://github.com/enorm-labs/event-junkie/issues/283) must land first.** It changes from a neighbour to a
  blocker, and no storage code starts before it.
- **A deletion route must work before the notice describes one.** The venue opt-out in `SCRAPING_POSITION.md` §5 has to
  remove stored objects, and `ForVenuesView` already promises that route to venues.
- §4 of both privacy notices must say that we store venue imagery. §5 keeps its no-third-party claim, which becomes true.
- `docs/LEGAL.md` §7.3a needs review. Artist `imageUrl` is declared as personal master data, and a stored file differs from a
  URL.
- `SCRAPING_POSITION.md` §2, §3.6 and §4 must describe reproduction instead of embedding.

### What becomes harder

- **Serving third-party bytes from our origin creates risks that hotlinking did not have.** An SVG from our origin runs
  script in our origin. The importer also fetches URLs taken from scraped HTML, which is server-side request forgery. Both
  need controls that do not exist today. [ADR-020](ADR-020_IMAGE_PROCESSING.md) moves the decoding half out of our
  process. The fetching half stays here.
- **A venue takedown stops working by itself.** Under hotlinking the file simply disappeared. Now something of ours must
  delete it.
- **Storage grows and nothing expires it.** The other two buckets carry a lifecycle rule and this one must not, because the
  objects are live content. An orphan sweep replaces the rule, and the sweep is load-bearing.
- **A bucket has no referential integrity, and the sweep is the price of that.** A database row deletes its own image. An
  object does not, so a separate mechanism must find the ones nothing points at.
- Storage cost stays inside the subscription we already hold, on an estimate rather than a measurement. Traffic inside the
  `eu-central` zone is free, so the fetch path adds nothing.
- The BFF response field `imageUrl` changes meaning rather than name. The change is invisible to our frontend and visible to
  any other API consumer.

### What becomes possible

- `Content-Security-Policy: img-src 'self'` becomes available. Hotlinking blocks that value today, and the header is not set
  yet.
- We control format and size, so a 4 MB flyer stops reaching an 80 px card.

## When to revisit

- **If #283 finds that a source forbids a stored copy.** That source keeps no image at all. It does not return to
  hotlinking, because the privacy property is the point.
- **If storage or egress cost becomes visible.** The load argument here is reasoning, not measurement. Real numbers would
  test it.
- **If a legal opinion arrives.** [#282](https://github.com/enorm-labs/event-junkie/issues/282) deferred one. § 16 UrhG is
  the question to put first.

## References

- [#792](https://github.com/enorm-labs/event-junkie/issues/792) — the privacy notice does not mention hotlinked venue images
- [#364](https://github.com/enorm-labs/event-junkie/issues/364) — decide whether to display descriptions and source images
- [#283](https://github.com/enorm-labs/event-junkie/issues/283) — per-source licence status
- [#282](https://github.com/enorm-labs/event-junkie/issues/282) — the deferred legal opinion
- [`docs/SCRAPING_POSITION.md`](../SCRAPING_POSITION.md) §3.6 and §4
- [`docs/LEGAL.md`](../LEGAL.md) §7.3a, §7.7 and §14
- [ADR-007](ADR-007_WEB_SCRAPING_STRATEGY.md), [ADR-012](ADR-012_CLOUD_PLATFORM.md), [ADR-013](ADR-013_LOCALISATION.md)
- [ADR-020](ADR-020_IMAGE_PROCESSING.md) — how the derivatives are generated
- CJEU: _Svensson_ C-466/12, _BestWater_ C-348/13, _VG Bild-Kunst_ C-392/19
