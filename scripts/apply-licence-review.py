#!/usr/bin/env python3
"""Write the licence review in docs/licence-review/ onto the event sources (#283).

Dry run by default. `--apply` is what actually writes, because this changes a legal field on every
source at once and a mistake here removes material from the site.

    python3 scripts/apply-licence-review.py                 # show the plan
    python3 scripts/apply-licence-review.py --apply         # write it
    python3 scripts/apply-licence-review.py --host http://localhost:18081 --apply

Reads RESULTS.tsv, which is keyed on the source name from docs/EVENT_DATA_SOURCES.md. The database
keys on a server-generated slug, so names are resolved against the live admin API rather than
guessed. Seven names differ between the document and the seeded sources; ALIASES is that list, and
anything it does not cover is reported rather than matched approximately. A wrong match writes a
prohibition onto the wrong venue, which is worse than doing nothing.
"""
import argparse, csv, json, sys, unicodedata, urllib.error, urllib.request

# docs/EVENT_DATA_SOURCES.md name -> event_source.name, verified against http/importer/dev-seed.http.
ALIASES = {
    "Alte Kantine Kulturbrauerei": "Alte Kantine",
    "Berghain / Panorama Bar": "Berghain",
    "Clash Club": "Clash",
    "Club der Visionäre": "Club der Visionaere",
    "Eschschloraque Rümschrümp": "Eschschloraque",
    "Matrix Club Berlin": "Matrix",
    "Maxxim Club": "MAXXIM",
}


LOCAL_HOST = "http://localhost:8081"
PAGE_SIZE = 100
MAX_PAGES = 100


def fold(s):
    """Casefold and strip accents, for the last-resort match only."""
    n = unicodedata.normalize("NFKD", s)
    return "".join(c for c in n if not unicodedata.combining(c)).casefold().strip()


def fetch_all_sources(host):
    """Read every source, one page at a time.

    The listing defaults to 20 and answers with a bare JSON array, so a response carries nothing
    that distinguishes a first page from a complete one. Reading it once looks like success and
    silently writes to a fifth of the sources.
    """
    out, page = [], 0
    while True:
        batch = request(f"{host}/api/admin/event-sources?page={page}&size={PAGE_SIZE}&sort=name,asc")
        if isinstance(batch, dict):
            batch = batch.get("content", [])
        out.extend(batch)
        if len(batch) < PAGE_SIZE:
            return out
        page += 1
        if page > MAX_PAGES:
            sys.exit(f"Stopped after {MAX_PAGES} pages. The listing is not terminating.")


def request(url, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode() or "{}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=LOCAL_HOST)
    ap.add_argument("--results", default="docs/licence-review/RESULTS.tsv")
    ap.add_argument("--apply", action="store_true", help="actually write; omit for a dry run")
    ap.add_argument(
        "--yes",
        action="store_true",
        help="confirm writing to a host other than the local default. Required there, because "
             "a forwarded port looks exactly like a local one and the mistake is silent.",
    )
    ap.add_argument(
        "--allow-missing",
        action="store_true",
        help="proceed when a reviewed source is absent from this database, e.g. a partly seeded "
             "local one. Off by default: against a full database an unmatched name means a "
             "spelling this script failed to resolve, and skipping it silently leaves a source "
             "unreviewed while the run reports success.",
    )
    args = ap.parse_args()

    try:
        sources = fetch_all_sources(args.host)
    except (urllib.error.URLError, OSError) as e:
        sys.exit(f"Cannot reach the importer at {args.host}: {e}\nStart it with scripts/dev-env.sh up")
    by_name = {s["name"]: s["slug"] for s in sources}
    by_fold = {fold(s["name"]): s["slug"] for s in sources}
    print(f"{len(by_name)} sources on {args.host}\n")

    planned, skipped, unmatched = [], [], []
    with open(args.results, newline="") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            name = row["name"]
            if not row["description_licence"] and not row["image_licence"]:
                skipped.append((name, row["licence_note"]))
                continue
            slug = by_name.get(name) or by_name.get(ALIASES.get(name, "")) or by_fold.get(fold(name))
            if not slug:
                unmatched.append(name)
                continue
            planned.append((name, slug, row))

    for name, slug, row in planned:
        print(f"  {row['description_licence']:<11} {row['image_licence']:<11} {slug:<28} {name}")
    print()
    for name, why in skipped:
        print(f"  SKIP  {name}: {why}")
    for name in unmatched:
        print(f"  UNMATCHED  {name}  <- resolve by hand or add to ALIASES")
    print(f"\n{len(planned)} to write, {len(skipped)} skipped, {len(unmatched)} unmatched")

    if unmatched and not args.allow_missing:
        sys.exit(
            "\nRefusing to write while any reviewed source is unmatched.\n"
            "Either the name needs an ALIASES entry, or this database does not hold every source.\n"
            "Add it to ALIASES, or pass --allow-missing if the absence is expected."
        )
    if unmatched:
        print(f"\nProceeding without {len(unmatched)} unmatched source(s), because --allow-missing was given.")
    if not args.apply:
        print("\nDry run. Nothing was written. Re-run with --apply.")
        return
    if args.host != LOCAL_HOST and not args.yes:
        sys.exit(
            f"\nRefusing to write to {args.host} without --yes.\n"
            f"A forwarded port is indistinguishable from a local one, so confirm the target first.\n"
            f"It holds {len(by_name)} sources and this run would write {len(planned)} of them."
        )

    ok = 0
    for name, slug, row in planned:
        body = {
            "descriptionLicence": row["description_licence"],
            "imageLicence": row["image_licence"],
            "licenceSourceUrl": row["licence_source_url"],
            "licenceNote": row["licence_note"],
        }
        try:
            request(f"{args.host}/api/admin/event-sources/{slug}", method="PATCH", body=body)
            ok += 1
        except urllib.error.HTTPError as e:
            print(f"  FAILED {slug}: {e.code} {e.read().decode()[:200]}")
    print(f"\nWrote {ok} of {len(planned)}.")
    print("licenceReviewedAt is stamped server-side, so re-running moves it. The verdicts do not change.")


if __name__ == "__main__":
    main()
