# Licence review of the event sources

The evidence behind the `description_licence` and `image_licence` columns on `event_source`
([#283](https://github.com/enorm-labs/event-junkie/issues/283)). `RESULTS.tsv` beside this file is
the record. `scripts/apply-licence-review.py` writes it to a database.

## The short version

1. **All 86 sources were read on 2026-08-28.** The result was 83 `UNCLEAR`, 2 `PROHIBITED` and one
   source that our agent cannot fetch.
2. **No source grants a reuse we can rely on.** `PERMITTED` is zero.
3. **The standard German copyright boilerplate is `UNCLEAR`, not `PROHIBITED`.** §2 says why, and it
   decides most of the corpus.
4. **The two prohibitions are venues whose own wording names texts and images**, without the
   statutory carve-out the boilerplate carries.
5. **This file is evidence, not a decision.** The display rule lives in
   [SCRAPING_POSITION.md](../SCRAPING_POSITION.md) §3.1.
6. [#808](https://github.com/enorm-labs/event-junkie/issues/808) is how an `UNCLEAR` becomes a
   `PERMITTED`. Nothing else moves it.

## 1. What was read, per source

The first of these that decided the question, and the page recorded in `licence_source_url`:

1. **A press or media page**, which is the likeliest place to find a real grant.
2. **The Impressum**, and any `Nutzungsbedingungen` or `AGB`.
3. **The image credits**, because an agency credit is a third party holding the rights.
4. **A licence notice.** Creative Commons is rare here and decides the question when present.

The page is recorded even when the answer is `UNCLEAR`. That is what stops the next reviewer
repeating the search.

## 2. The rule that decides most of the corpus

Most German venue sites carry a version of this, usually from the e-recht24 Impressum template:

> Die durch die Seitenbetreiber erstellten Inhalte und Werke auf diesen Seiten unterliegen dem
> deutschen Urheberrecht. Die Vervielfältigung, Bearbeitung, Verbreitung und jede Art der Verwertung
> **außerhalb der Grenzen des Urheberrechtes** bedürfen der schriftlichen Zustimmung.

**It reads like a prohibition and it is not one.** Three reasons:

1. **It restates the statute.** "Außerhalb der Grenzen des Urheberrechtes" keeps every statutory
   exception by its own wording. It forbids nothing that the law permits.
2. **It is template text.** It appears on very many German sites, and it says the same thing whatever
   the operator thinks.
3. **To read it as `PROHIBITED` would empty most of the corpus.** That decision would come from data
   entry rather than from a person, and it would reverse #283 without anybody deciding.

## 3. What each verdict needs

| Verdict      | Needs                                                                          |
| ------------ | ------------------------------------------------------------------------------ |
| `PERMITTED`  | A written grant whose scope covers an events listing                           |
| `PROHIBITED` | A prohibition beyond the statute for this material, or a visible agency credit |
| `UNCLEAR`    | Everything else, the boilerplate above included, and silence                   |
| _null_       | Nobody looked yet. Never an outcome of a review                                |

**Three traps, in the order they come up:**

1. **Silence is not `PERMITTED`.** Most sites say nothing. That is `UNCLEAR`.
2. **A footer `©` is not `PROHIBITED`.** Almost every site has one.
3. **A press grant may not cover us.** "Im Rahmen der Berichterstattung" is written for journalists.
   Record `UNCLEAR` with the quote rather than claim a grant we would have to defend.

**An agent may set `UNCLEAR`. A person confirms `PERMITTED` and `PROHIBITED`.** One removes material
from the site. The other is a claim we would rely on if a venue ever asked.

## 4. The two prohibitions

Both name texts **and** image material, and neither carries the statutory carve-out.

**Der Weiße Hase**, <https://derweissehase.club/impressum>:

> Die Vervielfältigung von Informationen oder Daten, insbesondere die Verwendung von Texten,
> Textteilen oder Bildmaterial bedarf der jeweiligen vorherigen Zustimmung vom weissen Hasen.

**Kulturhaus Peter Edel**, <https://www.peteredel.de/impressum/>:

<!-- ste-lint: allow verbatim quotation of the venue's own wording, which is the evidence for a PROHIBITED verdict -->

> Vervielfältigungen und sonstige Verwertungen von Informationen oder Daten, insbesondere Verwendung
> von Texten, Textteilen oder Bildmaterial, bedürfen, soweit nicht anders vermerkt, der vorherigen
> Zustimmung des Kommunalen Bildungswerkes e. V.

**The caveat belongs here rather than nowhere.** The two are near-identical, so they are probably a
second and older template rather than anything bespoke. They are stronger than the e-recht24 text.
Neither is a venue that wrote a rule about us.

## 5. Three near-misses, so nobody re-checks them

- **Gärten der Welt** grants use of **its own press-kit photos**, with attribution, "in der Presse
  sowie in analogen Publikationen ausschließlich zur Bewerbung unserer Parkanlagen". Different
  images and a different purpose.
- **Admiralspalast** has a press download area whose terms restate the statute, carve-out included.
- **Huxleys Neue Welt** has a press page that says the press area is still in preparation.

## 6. What the method missed

1. **Only homepages were searched for links.** A press page reachable from a sub-page was missed. The
   13 sources that link no legal page are the least well read.
2. **Uber Eats Music Hall answers our user agent with `406`.** Not reviewed, and worth knowing
   separately, because the importer meets the same wall.
3. **Link discovery followed third-party domains** on two sources. Those hits are noise and
   `RESULTS.tsv` does not use them.

## 7. Applying it

`RESULTS.tsv` is keyed on the source **name**, because a slug is generated when a source is created.
`scripts/apply-licence-review.py` resolves the name against the live admin API.

```sh
python3 scripts/apply-licence-review.py                    # dry run, writes nothing
python3 scripts/apply-licence-review.py --apply            # write it
```

### Against staging or production

**The importer has no ingress backend on any cluster.** That is the design, not an oversight, and it
means the admin API has no credentials either. The tunnel is the control
([ops/CLUSTER_ACCESS.md](../ops/CLUSTER_ACCESS.md) §6a).

So the route is the one every `http/importer/` file already uses:

```sh
kubectl --context event-junkie-staging port-forward -n event-junkie     svc/event-junkie-importer 18081:8081

python3 scripts/apply-licence-review.py --host http://localhost:18081 --apply --yes
```

**Forward to 18081 and not 8081.** The local importer owns 8081. CLUSTER_ACCESS.md §6a says what that
costs. A forward that lands on a local stack is how somebody writes to the wrong database, and
believes they wrote to the cluster.

**`--apply` needs `--yes` for any host but the local default.** A forwarded port looks exactly like a
local one, and the mistake is silent. The refusal names the host and the number of sources it holds.

**One thing the timestamp does not capture.** `licenceReviewedAt` is stamped by the server when the
row is written. That is what keeps a status and its date from disagreeing. Applying this record
months later therefore stamps a fresh date on an old review. The review date is in this file, and
`licence_source_url` is the page that was read, so the evidence survives. Do not read the column as
the date somebody looked.

**Seven names differ between `docs/EVENT_DATA_SOURCES.md` and the seeded sources.** The script
carries that list and refuses to write when a name is unmatched. A wrong match writes a prohibition
onto the wrong venue, which is worse than writing nothing.

`--allow-missing` is for a partly seeded local database. **Do not use it against a full one.** There,
an unmatched name is a spelling the script failed to resolve. Skipping it leaves a source unreviewed
while the run reports success.
