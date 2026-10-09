#!/usr/bin/env python3
"""Write the reviewed promoter websites and descriptions onto the promoters (#328).

Dry run by default. `--apply` is what writes, because a promoter PUT replaces every field.

    python3 scripts/promoter-websites.py                       # show the plan
    python3 scripts/promoter-websites.py --apply               # write it
    python3 scripts/promoter-websites.py --host http://localhost:18081 --apply
    python3 scripts/promoter-websites.py --promoter loft-concerts

Reads docs/promoters/REVIEWED.tsv, one row per promoter the site holds, with the kind a person
gave it, the site they found, the spelling that site uses, and a description in each language
where one was written. A row with a `website`, a `description_de` or a `description_en` is
written; only those fields change, and the request carries the promoter's current name and
image fields back unchanged. German is the description and English the alternate, because
German is the site's authoritative language (ADR-013); a row with one language and not the
other stores that one as the description.

An empty `website` keeps the stored link. `none` means the promoter has no site, and clears the
link: the PUT sends `websiteUrl: null` (#2601). Replacing or clearing a link needs `--force`.

Every row in the table is a review, so every row the target holds under a matching name gets
`reviewed_at` stamped with the time of the run, whether or not another field changes (#1336).
A row already stamped and otherwise unchanged is left alone; `--restamp` moves the date on all.

A row whose stored name differs from the reviewed one is reported and skipped. The name is not
written here, because a PUT that changes the name changes the slug, and the next import then
creates the old row again unless `PromoterNormalizer` maps the raw credit onto the new spelling.
V023 renames the rows the review found, and the normalizer carries the same pins; after it has
run, the names agree and nothing is skipped.

Standard library only, and no key: the admin API is unauthenticated inside the cluster.
"""

import argparse
import csv
import datetime
import json
import sys
import urllib.error
import urllib.request

REVIEWED = "docs/promoters/REVIEWED.tsv"
NO_WEBSITE = "none"
LOCAL_HOST = "http://localhost:18081"
PAGE_SIZE = 100
MAX_PAGES = 100


def get_json(url):
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def fetch_promoters(host):
    """Every promoter the target holds, keyed by slug."""
    promoters = []
    for page in range(MAX_PAGES):
        body = get_json(f"{host}/api/admin/promoters?page={page}&size={PAGE_SIZE}&sort=name,asc")
        promoters.extend(body.get("content", []))
        if page + 1 >= body.get("totalPages", 0):
            break
    return {p["slug"]: p for p in promoters}


def put_promoter(host, promoter_id, body):
    request = urllib.request.Request(
        f"{host}/api/admin/promoters/{promoter_id}",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="PUT",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.status


def read_reviewed(only=None):
    with open(REVIEWED, encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    to_write = [r for r in rows if r["slug"] == only] if only else list(rows)
    return rows, to_write


def described(row):
    """The four description fields for [row]: German first, English as the alternate."""
    texts = [(row["description_de"], "de"), (row["description_en"], "en")]
    texts = [(text, language) for text, language in texts if text]
    if not texts:
        return {}
    (text, language), *rest = texts
    alt, alt_language = rest[0] if rest else (None, None)
    return {
        "description": text,
        "descriptionLanguage": language,
        "descriptionAlt": alt,
        "descriptionAltLanguage": alt_language,
    }


def changes(promoter, row, stamp, restamp=False):
    """The fields the row would change on [promoter], as {field: (current, wanted)}."""
    wanted = dict(described(promoter_as_row(promoter)))
    wanted.update(described(row))
    if row["website"] == NO_WEBSITE:
        wanted["websiteUrl"] = None
    elif row["website"]:
        wanted["websiteUrl"] = row["website"]
    changed = {field: (promoter.get(field), value) for field, value in wanted.items() if promoter.get(field) != value}
    if changed or restamp or not promoter.get("reviewedAt"):
        changed["reviewedAt"] = (promoter.get("reviewedAt"), stamp)
    return changed


def promoter_as_row(promoter):
    """What the target already holds, in the TSV's shape, so an unwritten column keeps its value."""
    by_language = {promoter.get("descriptionLanguage"): promoter.get("description")}
    by_language[promoter.get("descriptionAltLanguage")] = promoter.get("descriptionAlt")
    return {"description_de": by_language.get("de") or "", "description_en": by_language.get("en") or ""}


def main():
    parser = argparse.ArgumentParser(description="Write the reviewed promoter websites onto the promoters.")
    parser.add_argument("--host", default=LOCAL_HOST, help=f"importer admin API (default {LOCAL_HOST})")
    parser.add_argument("--apply", action="store_true", help="write; without it nothing is changed")
    parser.add_argument("--promoter", help="only this promoter, by slug")
    parser.add_argument(
        "--force", action="store_true", help="also replace a website or description a promoter already has"
    )
    parser.add_argument("--restamp", action="store_true", help="move reviewed_at to now on every row, changed or not")
    args = parser.parse_args()
    stamp = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    rows, to_write = read_reviewed(args.promoter)
    if not to_write:
        print(f"no row in {REVIEWED}" + (f" for {args.promoter!r}" if args.promoter else ""))
        return 1
    described_rows = sum(1 for r in rows if r["description_de"] or r["description_en"])
    with_site = sum(1 for r in rows if r["website"] and r["website"] != NO_WEBSITE)
    without_site = sum(1 for r in rows if r["website"] == NO_WEBSITE)
    print(f"{len(rows)} reviewed, {with_site} with a website, {without_site} with none, {described_rows} described\n")

    try:
        promoters = fetch_promoters(args.host)
    except (urllib.error.URLError, OSError) as error:
        print(f"cannot reach {args.host}: {error}")
        return 1

    written = skipped = 0
    for row in to_write:
        promoter = promoters.get(row["slug"])
        if promoter is None:
            print(f"  skip  {row['slug']}: not on the target")
            skipped += 1
            continue
        if promoter["name"] != row["name"]:
            print(f"  skip  {row['slug']}: stored as {promoter['name']!r}, reviewed as {row['name']!r}")
            skipped += 1
            continue
        changed = changes(promoter, row, stamp, args.restamp)
        if not changed:
            continue
        # A target older than the column answers without the field and would drop it from a PUT in silence.
        missing = [field for field in changed if field not in promoter]
        if missing:
            print(f"  skip  {row['slug']}: the target has no {', '.join(missing)} field yet")
            skipped += 1
            continue
        replaced = [field for field, (current, _) in changed.items() if current and field != "reviewedAt"]
        if replaced and not args.force:
            print(f"  skip  {row['slug']}: already has {', '.join(replaced)} (--force replaces it)")
            skipped += 1
            continue
        body = {
            "name": promoter["name"],
            "websiteUrl": promoter.get("websiteUrl"),
            "imageUrl": promoter.get("imageUrl"),
            "imageAttribution": promoter.get("imageAttribution"),
            "imageLicenceId": promoter.get("imageLicenceId"),
            "imageSourceUrl": promoter.get("imageSourceUrl"),
            "description": promoter.get("description"),
            "descriptionLanguage": promoter.get("descriptionLanguage"),
            "descriptionAlt": promoter.get("descriptionAlt"),
            "descriptionAltLanguage": promoter.get("descriptionAltLanguage"),
            "reviewedAt": promoter.get("reviewedAt"),
        }
        body.update({field: wanted for field, (_, wanted) in changed.items()})
        verb = "write" if args.apply else "would write"
        print(f"  {verb}  {row['slug']}: {', '.join(changed)}")
        if args.apply:
            try:
                put_promoter(args.host, promoter["id"], body)
            except urllib.error.HTTPError as error:
                print(f"  fail  {row['slug']}: {error.code} {error.read().decode(errors='replace')[:200]}")
                skipped += 1
                continue
        written += 1

    print(f"\n{'Wrote' if args.apply else 'Would write'} {written}, skipped {skipped}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
