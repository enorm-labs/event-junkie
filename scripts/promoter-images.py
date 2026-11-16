#!/usr/bin/env python3
"""Write the reviewed promoter logos from Wikimedia Commons onto the promoters (#5177).

Dry run by default. `--apply` is what writes, because a promoter PUT replaces every field.

    python3 scripts/promoter-images.py                         # show the plan
    python3 scripts/promoter-images.py --apply                 # write it
    python3 scripts/promoter-images.py --host http://localhost:18081 --apply
    python3 scripts/promoter-images.py --promoter radioeins    # one promoter

Reads docs/promoters/IMAGES.tsv, one row per logo a person matched to a promoter through its Wikidata
item. The licence and the author are read from Commons at run time, through the reader of
`venue-images.py`, and a licence that differs from `licence_at_review` stops that row.

A public-domain logo whose Commons page names no author credits the promoter by its name, because
`image_attribution` cannot be empty (V020) and public domain asks for no author. Any other licence
needs a named author, as a venue photograph does.

The PUT carries every other field of the promoter back as the admin API returned it.
"""

import argparse
import csv
import importlib.util
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
IMAGES = HERE.parent / "docs/promoters/IMAGES.tsv"
LOCAL_HOST = "http://localhost:18081"
PAGE_SIZE = 100
MAX_PAGES = 100
ARCHIVE = "Wikimedia Commons"
PUBLIC_DOMAIN = "PD"

# What `PromoterRequest` takes. A field the GET returns beyond these is read-only, and a field missing
# from the GET stops the row rather than going out as null.
REQUEST_FIELDS = (
    "name",
    "websiteUrl",
    "instagramUrl",
    "imageUrl",
    "imageAttribution",
    "imageLicenceId",
    "imageSourceUrl",
    "description",
    "descriptionLanguage",
    "descriptionAlt",
    "descriptionAltLanguage",
    "kind",
    "reviewedAt",
)


def venue_images():
    # The hyphen in the file name rules out a plain import.
    spec = importlib.util.spec_from_file_location("venue_images", HERE / "venue-images.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_images(only=None):
    with open(IMAGES, encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE))
    return [r for r in rows if r["slug"] == only] if only else rows


def fetch_promoters(host, get_json):
    promoters = []
    for page in range(MAX_PAGES):
        body = get_json(f"{host}/api/admin/promoters?page={page}&size={PAGE_SIZE}&sort=name,asc")
        promoters.extend(body.get("content", []))
        if page + 1 >= body.get("totalPages", 0):
            break
    return {p["slug"]: p for p in promoters}


def credit_for(file_info, spdx, promoter, names_an_author):
    """Who the credit names, or None where the licence needs an author and Commons names none."""
    # A trademark sign is a claim about the name, not part of it: "™/®Deutschlandradio" credits Deutschlandradio.
    artist = re.sub(r"^[\s™®©/]+", "", file_info["artist"]).strip()
    if names_an_author(artist) and "unknown author" not in artist.casefold():
        return artist
    return promoter["name"] if spdx == PUBLIC_DOMAIN else None


def plan_one(row, promoter, vi):
    """The PUT body for [promoter], or the reason it will not be written."""
    missing = [field for field in REQUEST_FIELDS if field not in promoter]
    if missing:
        return None, f"the target has no {', '.join(missing)} field"
    file_info, problem = vi.commons_file(row)
    if problem:
        return None, problem
    if file_info["licence"] != row["licence_at_review"]:
        return None, f"licence changed since review: {row['licence_at_review']} -> {file_info['licence']}"
    spdx = vi.SPDX.get(file_info["licence"])
    if not spdx:
        return None, f"no SPDX identifier for {file_info['licence']!r}"
    author = credit_for(file_info, spdx, promoter, vi.names_an_author)
    if not author:
        return None, f"{ARCHIVE} names no author for a {file_info['licence']} file"

    body = {field: promoter[field] for field in REQUEST_FIELDS}
    body["imageUrl"] = file_info["thumb"]
    body["imageAttribution"] = f"{author}, via {ARCHIVE}"
    body["imageLicenceId"] = spdx
    body["imageSourceUrl"] = file_info["page"]
    return body, None


def put_promoter(host, promoter_id, body):
    request = urllib.request.Request(
        f"{host}/api/admin/promoters/{promoter_id}",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="PUT",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.status


def main(argv=None):
    parser = argparse.ArgumentParser(description="Write the reviewed promoter logos onto the promoters.")
    parser.add_argument("--host", default=LOCAL_HOST, help=f"importer admin API (default {LOCAL_HOST})")
    parser.add_argument("--apply", action="store_true", help="write; without it nothing is changed")
    parser.add_argument("--promoter", help="only this promoter, by slug")
    parser.add_argument("--force", action="store_true", help="also replace an image a promoter already has")
    args = parser.parse_args(argv)

    rows = read_images(args.promoter)
    if not rows:
        print(f"no row in {IMAGES.name}" + (f" for {args.promoter!r}" if args.promoter else ""))
        return 1
    vi = venue_images()
    try:
        promoters = fetch_promoters(args.host, lambda url: vi.get_json(url, {"Accept": "application/json"}))
    except (urllib.error.URLError, OSError) as error:
        print(f"cannot reach {args.host}: {error}")
        return 1

    written = skipped = stopped = 0
    for row in rows:
        promoter = promoters.get(row["slug"])
        if not promoter:
            print(f"  STOP    {row['slug']:<38} not on {args.host}")
            stopped += 1
            continue
        if promoter.get("imageUrl") and not args.force:
            print(f"  skip    {row['slug']:<38} already has an image")
            skipped += 1
            continue
        try:
            body, problem = plan_one(row, promoter, vi)
        except (urllib.error.URLError, OSError) as error:
            body, problem = None, f"Commons is unreachable: {error}"
        if problem:
            print(f"  STOP    {row['slug']:<38} {problem}")
            stopped += 1
            continue
        if args.apply:
            try:
                put_promoter(args.host, promoter["id"], body)
            except urllib.error.HTTPError as error:
                print(f"  STOP    {row['slug']:<38} {error.code} {error.read().decode(errors='replace')[:160]}")
                stopped += 1
                continue
        verb = "wrote" if args.apply else "would"
        print(f"  {verb:<7} {row['slug']:<38} {body['imageLicenceId']:<5} {body['imageAttribution']}")
        written += 1

    print(f"\n{written} {'written' if args.apply else 'would be written'}, {skipped} skipped, {stopped} stopped")
    if not args.apply and written:
        print("Nothing was changed. Re-run with --apply.")
    return 1 if stopped else 0


if __name__ == "__main__":
    sys.exit(main())
