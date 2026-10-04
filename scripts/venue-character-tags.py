#!/usr/bin/env python3
"""Write the verified venue character tags onto the venues (#2379).

Dry run by default. `--apply` is what writes, because a tag on a venue page is a public claim
about what that venue is, and it must rest on the venue's own words.

    python3 scripts/venue-character-tags.py                    # show the plan
    python3 scripts/venue-character-tags.py --apply            # write it
    python3 scripts/venue-character-tags.py --host http://localhost:18081 --apply --yes

Reads docs/venue-character-tags/PROPOSED.tsv. Each row names a venue slug, a tag, the venue's own
page and the sentence on it that states the tag. Only a row whose `verified` column is `yes` is
written. A person sets that after reading the page, so a proposal never reaches the site unread.

It only adds or updates tags. A tag set on a venue and absent from the file is reported and left
alone: removing a public claim is a decision for a person, through the admin API.

Standard library only.
"""

import argparse
import csv
import json
import sys
import urllib.error
import urllib.request

PROPOSED = "docs/venue-character-tags/PROPOSED.tsv"
LOCAL_HOST = "http://localhost:8081"
TAGS = (
    "queer",
    "sex-positive",
    "diy-collective",
    "awareness-team",
    "safer-space-policy",
    "quiet-room",
    "all-gender-toilets",
    "free-water",
    "smoke-free",
    "dress-code",
    "fetish-dress-code",
    "no-photo-policy",
    "cash-only",
    "wheelchair-accessible",
)
PAGE_SIZE = 100
MAX_PAGES = 100


def read_rows(path):
    """The verified rows to write and the unverified ones to report. A malformed row stops the run."""
    verified, pending = [], []
    with open(path, newline="") as f:
        for n, row in enumerate(csv.DictReader(f, delimiter="\t"), start=2):
            if row["tag"] not in TAGS:
                sys.exit(f"{path}:{n}: unknown tag '{row['tag']}'. Accepted: {', '.join(TAGS)}.")
            if not row["source_url"].startswith(("http://", "https://")):
                sys.exit(f"{path}:{n}: source_url must be an http or https URL.")
            (verified if row["verified"].strip().lower() == "yes" else pending).append(row)
    return verified, pending


def plan(rows, venue_ids, current):
    """Split verified rows into writes, rows already stored, and slugs this database lacks.

    `venue_ids` maps a slug to its id. `current` maps a venue id to {tag: source_url} as stored.
    """
    writes, unchanged, unmatched = [], [], []
    for row in rows:
        venue_id = venue_ids.get(row["slug"])
        if venue_id is None:
            unmatched.append(row)
        elif current.get(venue_id, {}).get(row["tag"]) == row["source_url"]:
            unchanged.append(row)
        else:
            writes.append((venue_id, row))
    return writes, unchanged, unmatched


def request(url, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode() or "null")


def fetch_all_venues(host):
    """Read every venue, and refuse a listing shorter than the total the API reports (#810)."""
    out, page = [], 0
    while True:
        body = request(f"{host}/api/admin/venues?page={page}&size={PAGE_SIZE}&sort=name,asc")
        out.extend(body["content"])
        if len(out) >= body["totalElements"]:
            break
        page += 1
        if page > MAX_PAGES:
            sys.exit(f"Stopped after {MAX_PAGES} pages. The listing is not terminating.")
    if len(out) != body["totalElements"]:
        sys.exit(f"Read {len(out)} of {body['totalElements']} venues. Refusing to act on a partial listing.")
    return out


def main():
    ap = argparse.ArgumentParser(description="Write the verified venue character tags onto the venues (#2379).")
    ap.add_argument("--host", default=LOCAL_HOST)
    ap.add_argument("--proposed", default=PROPOSED)
    ap.add_argument("--apply", action="store_true", help="actually write; omit for a dry run")
    ap.add_argument(
        "--yes",
        action="store_true",
        help="confirm writing to a host other than the local default. A forwarded port looks exactly like a local one.",
    )
    args = ap.parse_args()

    verified, pending = read_rows(args.proposed)
    try:
        venues = fetch_all_venues(args.host)
        venue_ids = {v["slug"]: v["id"] for v in venues}
        wanted = {venue_ids[r["slug"]] for r in verified if r["slug"] in venue_ids}
        current = {
            vid: {t["tag"]: t["sourceUrl"] for t in request(f"{args.host}/api/admin/venues/{vid}/character-tags")}
            for vid in wanted
        }
    except urllib.error.HTTPError as e:
        sys.exit(f"{args.host} answered {e.code}. A build without V099 has no character-tags endpoint.")
    except (urllib.error.URLError, OSError) as e:
        sys.exit(f"Cannot reach the importer at {args.host}: {e}\nStart it with scripts/dev-env.sh up")

    writes, unchanged, unmatched = plan(verified, venue_ids, current)
    print(f"{len(venues)} venues on {args.host}\n")
    for _venue_id, row in writes:
        print(f"  WRITE      {row['slug']:<24} {row['tag']:<16} {row['source_url']}")
    for row in unchanged:
        print(f"  UNCHANGED  {row['slug']:<24} {row['tag']}")
    for row in unmatched:
        print(f"  UNMATCHED  {row['slug']:<24} {row['tag']}  <- no venue with this slug here")
    for row in pending:
        print(f"  UNVERIFIED {row['slug']:<24} {row['tag']}  <- read {row['source_url']}, then set verified to yes")
    counts = f"{len(writes)} to write, {len(unchanged)} unchanged, {len(unmatched)} unmatched"
    print(f"\n{counts}, {len(pending)} unverified")

    if not args.apply:
        print("\nDry run. Nothing was written. Re-run with --apply.")
        return
    if args.host != LOCAL_HOST and not args.yes:
        sys.exit(f"\nRefusing to write to {args.host} without --yes. Confirm the target first.")

    ok = 0
    for venue_id, row in writes:
        url = f"{args.host}/api/admin/venues/{venue_id}/character-tags/{row['tag']}"
        try:
            got = request(url, method="PUT", body={"sourceUrl": row["source_url"]})
        except urllib.error.HTTPError as e:
            print(f"  FAILED {row['slug']} {row['tag']}: {e.code} {e.read().decode()[:200]}")
            continue
        # Verify from the row that came back, not from the status code (#814).
        if got.get("tag") == row["tag"] and got.get("sourceUrl") == row["source_url"]:
            ok += 1
        else:
            print(f"  NOT WRITTEN {row['slug']} {row['tag']}: got {got!r}")
    print(f"\nWrote {ok} of {len(writes)}, confirmed from the response of each PUT.")
    if ok != len(writes):
        sys.exit(1)


if __name__ == "__main__":
    main()
