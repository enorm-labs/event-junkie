# ADR-048: Instagram posts of a venue with no website are read through Business Discovery, drafted by the model and approved by a person

## Status

**Accepted (2026-10-15). The importer reads the posts of a venue's Instagram business or creator account through the Graph API's Business Discovery.
The model drafts date, start time, title and acts from each caption. A person approves every draft in the admin app. Nothing reads an image, and no
caption reaches the public site.**

Decided in [#2592](https://github.com/enorm-labs/event-junkie/issues/2592), option 1. **Not implemented yet.** The Meta app exists, and a test call on
2026-10-15 read the posts of eight venue accounts.

**Rule 3 is superseded by [ADR-049](ADR-049_INSTAGRAM_DRAFTS_READ_THE_POST_IMAGES.md) (2026-10-16).** The model now reads the images of a post as well as
its caption. No image is stored or shown.

**Does not supersede anything.** [ADR-043](ADR-043_ONE_MAIN_SOURCE_PER_VENUE_AND_ENRICHMENT_SOURCES.md) rule 2 allows an official account as a main
source through an official API. This ADR names that API. [ADR-041](ADR-041_AI_ASSISTED_DATA_QUALITY.md) applies as written: one integration, Haiku by
default, suggest only, two spending limits.

## Context

Many small venues publish their programme only on Instagram. `docs/EVENT_DATA_SOURCES.md` records 13 clubs and bars of the techno cluster that publish
only through Instagram, Facebook or Resident Advisor. It records nine more venues with no website at all. Four bars got a venue row on 2026-10-15 without
an importer: Splitter Bar, Brauhaus Neulich, Mokka Mitte Bar and Café Cralle. The New event form
([#3015](https://github.com/enorm-labs/event-junkie/pull/3015)) covers them by hand only, and a hand-kept programme goes stale.

The constraints:

- **A lawful route only.** Instagram's terms forbid automated access. ADR-043 rule 2 allows entry by hand or an official API.
- **The model only suggests.** ADR-041 rule 3. A person applies every result.
- **Meta's Developer Policy 6.2** forbids an app that only displays or imports content without permission. The site must show facts, not posts.
- **Platform Terms §5.a.i** lets a service provider process the data only under a written contract. Anthropic's DPA is that contract (LEGAL.md §7.3a,
  [#1233](https://github.com/enorm-labs/event-junkie/issues/1233)).
- **Every processor and every source is named** in LEGAL.md §7.3a and in the privacy notice (AGENTS.md § Privacy & GDPR).

### The measurement, 2026-10-15

A test app in Development mode read accounts with Standard Access. It needed `instagram_basic`, `instagram_manage_insights`, `pages_read_engagement`
and `pages_show_list`. Without `instagram_manage_insights`, every call failed with error 10. App Review was not necessary for the test.

The census covered the posts of the last 30 days:

| Account                | Posts | Event posts | Date in the caption | Start time | Acts                  |
| ---------------------- | ----: | ----------: | ------------------: | ---------: | --------------------- |
| `rso.berlin`           |    14 |          13 |                  13 |          0 | Party series only     |
| `prisma.berlin`        |     3 |           3 |                   3 |          3 | 3, with the price     |
| `mokkamittebar`        |     7 |           5 |                   4 |          1 | 0                     |
| `cafecrallekollektiv`  |     5 |           3 |                 2–3 |          2 | 0                     |
| `momslimousineservice` |     5 |           3 |                   2 |          2 | 0, food events        |
| `neulich.bier`         |    15 |           1 |                   1 |          1 | 1, with the set times |
| `splitterbarberlin`    |     1 |           0 |                   — |          — | —                     |

Business Discovery answered error 110 for the guessed handles of OXI, Torte Bar, Sisyfass, ciao ciao Bar and JIWAR. Error 110 means a wrong handle or a
personal account. The API does not tell the two apart.

## Candidate options

1. **Business Discovery, a draft by the model, approval by a person.** It fits ADR-041 as written. A person reviews each event.
2. **The same, and the model writes the event.** No review queue. It contradicts ADR-041 rule 3 and needs a measured false-positive rate first.
3. **No API, entry by hand only.** No Meta dependency and no legal change. It is slow, and it goes stale without a reminder per venue.

## Decision

**Option 1.** The census settled it. About 27 of 28 event posts carry the date in the caption, so the model has the main fact without an image.

The rules:

1. **The route is the Instagram API with Facebook Login.** Our account `eventjunkie.berlin` is linked to the Facebook Page "Event Junkie". The Meta app
   "Event Junkie Importer" calls Business Discovery. Instagram Login has no Business Discovery.
2. **Only business and creator accounts.** A venue with a personal account stays with entry by hand.
3. **The importer reads `id`, `caption`, `permalink`, `timestamp` and `media_type`.** It never reads `media_url`. No image goes to the model.
4. **The model drafts facts.** These are date, start time, title, acts and price. The year comes from the post's date. A post can announce more than one
   event. The calls follow ADR-041: Spring AI, Haiku, a cap per run, and the monthly limit.
5. **A person approves each draft** in `events-admin`. An approved draft becomes an event through the New event form. A rejected draft creates nothing.
6. **The caption stays only while its draft waits.** Approval or rejection deletes it. A draft that waits for more than 14 days is deleted with its caption.
7. **No caption and no image reaches the public site.** The event shows the approved facts only.
8. **The importer reads public business accounts without a request for consent.** A venue that objects is switched off, and its drafts are deleted.
9. **LEGAL.md §7.3a and both privacy notices change with the first code that reads an account.** They name Meta as a source and the captions as a new
   purpose of Anthropic. They do not change before that code exists, because the notice describes what runs.

**The reason that settled it** is the census. The flyer was the main risk: a date only on the image would make a caption reader useless. The census did
not show that pattern.

## Consequences

- **The acts are the weak field.** RSO names party series as handles, not artists. Only two of seven accounts name acts. The line-up stays a fill by hand.
- **An event from a draft has no source.** No import removes it when the venue cancels. A person deletes it, as for every event from the New event form.
- **One event is announced in up to three posts.** The draft store must merge drafts on venue and date, or the operator sees duplicates.
- **A caption can name people who are not artists**, for example a host or a designer. That is personal data. Rule 6 limits how long it stays.
- **The pass depends on one Meta token.** A long-lived user token expires after 60 days. A system user token does not. The token needs an entry in
  `docs/ops/SECRETS.md` and in the expiry reminder.
- **Meta can change the access.** A Graph API version is retired about two years after its release. App Review can become necessary when the app goes
  Live. It is not known yet whether a production pass may run while the app stays in Development mode.
- **A venue handle is a fact that a person must check.** Error 110 does not say whether the handle is wrong or the account is personal.
- **The operator reviews about 30 event posts a month** for the six accounts above. Each new account adds to that.

## When to revisit

- The drafts have a measured false-positive rate, and it is low enough to consider option 2. That needs a new ADR, because it changes ADR-041 rule 3.
- The missing acts matter more than the cost of reading flyers. Reading an image sends photographs of people to Anthropic, so that is a privacy change.
- Meta requires App Review or removes Standard Access for Business Discovery.

## References

- [#2592](https://github.com/enorm-labs/event-junkie/issues/2592), the decision issue
- [ADR-041](ADR-041_AI_ASSISTED_DATA_QUALITY.md) and [ADR-043](ADR-043_ONE_MAIN_SOURCE_PER_VENUE_AND_ENRICHMENT_SOURCES.md)
- [#3015](https://github.com/enorm-labs/event-junkie/pull/3015), the New event form
- [Business Discovery reference](https://developers.facebook.com/docs/instagram-platform/instagram-graph-api/reference/ig-user/business_discovery)
- [Meta Platform Terms](https://developers.facebook.com/terms/dfc_platform_terms/) and [Developer Policies](https://developers.facebook.com/devpolicy/)
- [Graph API rate limiting](https://developers.facebook.com/docs/graph-api/overview/rate-limiting)
- `docs/LEGAL.md` §7.3a, the processor entry
