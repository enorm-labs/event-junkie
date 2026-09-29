# ADR-035: Discogs is a second artist index — for the names MusicBrainz does not know, and for a link only

## Status

**Accepted (2026-09-29) — the importer asks Discogs about each artist that MusicBrainz marks `NONE`. It stores the Discogs verdict and, on an exact match, the
Discogs id. It fills `discogs_url` only when the column is empty. It reads and stores nothing else from Discogs. MusicBrainz stays the hub.**

**Implemented in [#2026](https://github.com/enorm-labs/event-junkie/issues/2026).** The chart ships the lookup switched off. A cluster turns it on when its
Secret exists.

**Does not supersede anything.** [ADR-031](ADR-031_ARTIST_IDENTITY_HUB.md) made MusicBrainz the hub and named Discogs as option D. It said that Discogs needs
its own decision. This is that decision. It changes no rule of ADR-031.

## Context

MusicBrainz marks 46.3% of the artists on `PARTY` events `NONE`. On concerts the share is 23.0% (staging, 2026-09-29). A `NONE` artist page shows a name and
nothing else.

The spike on #1549 searched Discogs for the same 5,932 staging artists as the MusicBrainz spike. It used the same equality rule on folded names.

| Discogs verdict | MB `exact` | MB `ambiguous` | MB `none` |
| --------------- | ---------: | -------------: | --------: |
| `exact`         |      2,158 |             77 |   **329** |
| `ambiguous`     |        587 |            668 |        61 |
| `none`          |        263 |             13 |     1,757 |

- **Discogs knows 329 of the 2,149 `none` rows (15.3%).** Most are small-label producers with a release and no MusicBrainz editor: `Okkyung Lee`,
  `Gebrüder Teichmann`, `Rena Volvo`, `Zoh Amba`.
- **Discogs is `ambiguous` on 587 rows that MusicBrainz resolves.** Its search returns no country, so it cannot break a tie between homonyms.
- **The two indexes overlap only in part.** Discogs can add to MusicBrainz. It cannot replace it.

ADR-031 names one trigger to revisit Discogs: an `EXACT` share on club line-ups below a third. The share was 41.5% on 2026-09-24 and 40.9% on 2026-09-29. The
trigger did not fire. Eight of nineteen club venues are below a third. The product owner decided on #1549 to act before the trigger fires.

### The constraints a candidate had to satisfy

- **The Discogs API terms** (2025-07-23). They forbid storing Content "longer than is necessary to provide a service". They forbid showing Content "more than
  six (6) hours older" than the Discogs site. They require the words "Data provided by Discogs." with a link beside data taken from Discogs.
- **The rate.** 60 requests a minute per source address, in a rolling window, with the consumer key and secret of a Discogs application. 25 without them.
- **ADR-031's rules.** A lookup never rewrites a name. A link fills an empty column only (#1319).
- **`LEGAL.md` §7.** A new source that receives personal data must be named in the privacy notice.

## Candidate options

1. **Park Discogs until the ADR-031 trigger fires.** No cost. The 329 artists keep a bare page.
2. **Discogs as a verdict source on `NONE` rows, with the id and the link only.** One request for each `NONE` artist, once.
3. **Discogs as an enrichment source**, with the profile, the picture and the name variations. The terms require a refresh every six hours for each row that
   shows them.
4. **Discogs as a second opinion on MusicBrainz `AMBIGUOUS` rows.** 77 rows in the spike.

## Decision

**Option 2.** It adds a link to about 15% of the `NONE` artists, and it stores no Discogs content.

- **Only a `NONE` row is asked.** An `EXACT` row gets its Discogs link from the MusicBrainz relations already (ADR-031, step C). Option 4 lost because Discogs
  has no country for a tie-break. It resolves fewer ties than it creates.
- **An id, a verdict, a check time and an empty link filled.** An id and a link show no Discogs content. So the six-hour rule does not apply to them. Option 3
  lost on that rule. A refresh of every shown profile every six hours needs more requests than the rate allows.
- **The match rule is the spike's, one step stricter.** A candidate counts only when its folded title equals the folded name. A homonym suffix, as in `Nails (2)`, is removed
  first. So every homonym counts, and two of them make the row `AMBIGUOUS`. A suffixed candidate is `AMBIGUOUS` even
  alone, because `Kevin (27)` proves that at least 27 artists share the name. The spike script does not apply this last rule.
- **The credit line shows only beside a link that Discogs resolved.** A link from MusicBrainz is MusicBrainz data. The terms prescribe the English words, so the
  German page shows them unchanged.
- **The consumer key and secret, not a personal token.** They identify the application, not a person's account. The OAuth flow is not used, because the
  importer acts for no Discogs user.

## Consequences

### What this obliges

- **A Secret on each cluster**, `event-junkie-discogs`, with `APP_DISCOGS_CONSUMER_KEY` and `APP_DISCOGS_CONSUMER_SECRET`. It is hand-made, as
  `docs/ops/SECRETS.md` describes. Without it the lookup stays off.
- **`LEGAL.md` §7 and both privacy notices name Discogs.** The stage name of an artist that MusicBrainz does not know goes to Zink Media, LLC (Oregon, USA).
  Discogs is a source, not a processor, for the reason §7 gives for MusicBrainz.
- **A hand review after the first staging run.** The spike's `exact` set contains names that are a night here and an act on Discogs: `Beat It!`,
  `Disco Sour`, `Power Apes`. A wrong link is visible. The review decides if the rule needs a minimum release count.
- **Each import run gets longer.** At most `maxPerRun` rows (default 100) at about 55 a minute. That adds about two minutes after the MusicBrainz lookup.

### What it does not do

- It does not change a MusicBrainz verdict, a name or a slug.
- It does not read a Discogs profile, picture, name variation, release or genre.
- It does not ask about a MusicBrainz `AMBIGUOUS` or `EXACT` row.
- **It does not take a description from a Discogs profile.** The monthly data dumps are CC0, so the API terms would not
  stop it. The text is the obstacle. On 2026-09-29, 39 local artists had an `EXACT` Discogs match. 30 of them had no profile.
  Only 3 profiles had 80 characters or more and no birth data, the rule `WikipediaLead.kt` applies. The artists dump is
  474 MB, and those 3 descriptions do not justify a monthly import of it.

## When to revisit

- **If the hand review finds more than a handful of wrong links in 40.** Require a release count, at one more request per candidate, or stop.
- **If the Discogs terms change** on storage, staleness or attribution.
- **If the club `EXACT` share falls below a third after this runs.** Then option 4 gets a second look.

## References

- [#2026](https://github.com/enorm-labs/event-junkie/issues/2026) — the implementation
- [#1549](https://github.com/enorm-labs/event-junkie/issues/1549) — the spike, the two re-measurements and the decision
- [ADR-031](ADR-031_ARTIST_IDENTITY_HUB.md) — MusicBrainz as the hub
- [#1626](https://github.com/enorm-labs/event-junkie/pull/1626) · [#1627](https://github.com/enorm-labs/event-junkie/pull/1627) — `scripts/discogs-match.py`
- [Discogs API Terms of Use](https://support.discogs.com/hc/en-us/articles/360009334593-API-Terms-of-Use)
- [`docs/LEGAL.md`](../LEGAL.md) §7
