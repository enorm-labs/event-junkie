#!/usr/bin/env python3
"""Write the reviewed venue images onto the venues (#1277).

Dry run by default. `--apply` is what writes, because a venue PUT replaces every field and a wrong
picture on a venue page is worse than the placeholder #811 already draws.

    python3 scripts/venue-images.py                      # show the plan
    python3 scripts/venue-images.py --apply              # write it
    python3 scripts/venue-images.py --host http://localhost:18081 --apply
    python3 scripts/venue-images.py --venue Tresor       # one venue

Reads docs/venue-images/REVIEWED.tsv, which records a person's verdict on every one of the 86
venues. Only a CONFIRMED row is written. A REJECTED row is never proposed again, which is the point
of recording it.

Two archives, chosen per row by `found_by`: Wikimedia Commons, and Flickr through its oEmbed
endpoint. Both answer without a key and both state a licence, which is what the check below needs.

The licence and the credit are read from the archive at run time and never from the file above,
because a picture can be relicensed after a review. `licence_at_review` is compared against what the
archive states now, and a difference stops that venue rather than writing a stale credit. Openverse
is never asked: it indexes Flickr rather than speaks for it, and its copy of a licence can be a year
old. Asking Flickr itself is also what catches a photo that has since been deleted.

Three things are stored per venue: the image URL, the credit, and the licence. All three or none --
V020 rejects a row with an image and no credit, and so does the admin API.

The URL stored is a Commons thumbnail, never the original file. Commons renders one on demand at
any width, it is the same image under the same licence, and the originals run to 18 MB where
`images.fetch.max-bytes` stops at 8 MiB. Flickr renders no thumbnail to order. It publishes a fixed
ladder of sizes, and everything above 1024 px needs a signed secret, so 1024 is what is stored.

Standard library only, and no key: neither archive needs one.
"""

import argparse
import csv
import html
import json
import re
import sys
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

REVIEWED = "docs/venue-images/REVIEWED.tsv"
COMMONS = "https://commons.wikimedia.org/w/api.php"
FLICKR_OEMBED = "https://www.flickr.com/services/oembed"
AGENT = "event-junkie/1.0 (https://github.com/enorm-labs/event-junkie)"
LOCAL_HOST = "http://localhost:8081"
PAGE_SIZE = 100
MAX_PAGES = 100

# Wide enough for the largest render site and small enough to stay well inside the fetcher's cap.
# Advisory rather than exact: Commons rounds up to a cached bucket, so asking for 1600 serves 1920.
# It also serves the original where the bucket would meet or exceed it, which is why MAX_BYTES below
# is checked rather than assumed.
THUMB_WIDTH = 1600

# `images.fetch.max-bytes` in events-importer/src/main/resources/application.yaml. The fetcher
# rejects on the declared Content-Length, so a URL above this is one the importer can never read.
MAX_BYTES = 8 * 1024 * 1024

HTTP_NOT_FOUND = 404

# Commons publishes a licence as a template name. `image_licence_id` holds an SPDX identifier, so
# the mapping is written out rather than derived: a pattern over "CC BY-…" also produces an
# identifier for a template Creative Commons never published, and a wrong licence is worse than a
# refused write. A name absent here stops that venue and is reported.
SPDX = {
    "CC0": "CC0-1.0",
    "CC BY 2.0": "CC-BY-2.0",
    "CC BY 2.5": "CC-BY-2.5",
    "CC BY 3.0": "CC-BY-3.0",
    "CC BY 3.0 de": "CC-BY-3.0-DE",
    "CC BY 4.0": "CC-BY-4.0",
    "CC BY-SA 2.0": "CC-BY-SA-2.0",
    "CC BY-SA 2.0 de": "CC-BY-SA-2.0-DE",
    "CC BY-SA 2.5": "CC-BY-SA-2.5",
    "CC BY-SA 3.0": "CC-BY-SA-3.0",
    "CC BY-SA 3.0 de": "CC-BY-SA-3.0-DE",
    "CC BY-SA 4.0": "CC-BY-SA-4.0",
    "Public domain": "PD",
    "Public Domain Mark": "PD",
    # Two archive templates SPDX does not name the way Commons does. `FAL` is the Free Art License,
    # whose identifier is `LAL-1.3`. The bare `Attribution` template is on no SPDX list at all, so
    # it takes the `LicenseRef-` form SPDX publishes for exactly that case (#1281).
    "FAL": "LAL-1.3",
    "Attribution": "LicenseRef-Commons-Attribution",
}

# What a venue GET returns that a PUT does not take: the id, the slug and the derived fields. The
# admin API has no PATCH, so the PUT carries everything else the GET returned: a list of fields to
# keep erases every field added after it. A new read-only field makes the PUT a 400, which stops the
# run instead of erasing data.
READ_ONLY_FIELDS = ("id", "slug", "createdAt", "updatedAt", "programmeFamilies", "programmeEventTypes")


def venue_body(venue):
    """The PUT body that rewrites [venue] unchanged: every field the GET returned but the read-only ones."""
    return {field: value for field, value in venue.items() if field not in READ_ONLY_FIELDS}


def fold(s):
    """Casefold and strip accents, for the last-resort match only."""
    n = unicodedata.normalize("NFKD", s)
    return "".join(c for c in n if not unicodedata.combining(c)).casefold().strip()


# What Commons writes in `Artist` when the file has no author. A credit built from it names nobody,
# and "No machine-readable author provided. X assumed (based on copyright claims)." on a venue card
# is a sentence about copyright metadata rather than an author.
#
# Exactly this one phrase, and no guesses beside it. `Uploaded` reads like boilerplate and is the
# Flickr handle of the photographer who took the Astra Kulturhaus picture, so a wider list refuses
# real authors whose names happen to look generic.
NOT_AN_AUTHOR = ("no machine-readable author",)


def plain(value):
    """`Artist` arrives as HTML with a link in it; `image_attribution` holds a plain string."""
    text = re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", value or ""))).strip()
    # A label, not part of the name: "Photo: Andreas Praefcke" credits Andreas Praefcke.
    return re.sub(r"^(photo|foto|bild|image)\s*[:\-]\s*", "", text, flags=re.IGNORECASE).strip()


def names_an_author(credit):
    lowered = credit.casefold()
    return bool(credit) and not any(marker in lowered for marker in NOT_AN_AUTHOR)


def without_query(url):
    """Commons appends `utm_*` parameters to a thumbnail URL. They are ours to drop, not to store:
    the column holds the identity of an image, and the importer would send them on every fetch."""
    return urllib.parse.urlsplit(url)._replace(query="").geturl()


def image_size(url):
    """How many bytes the image is, asked the way `ImageFetcher` decides on it.

    A declared `Content-Length` is the cheap answer and the one the fetcher reads first. Flickr's
    CDN declares none, and the fetcher handles that by streaming and capping the buffer rather than
    refusing, so a missing header is not a refusal here either -- it is a reason to go and measure.
    One byte over the cap is enough to know, so nothing reads further.
    """
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": AGENT})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            declared = response.headers.get("Content-Length")
        if declared:
            return int(declared), None
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": AGENT}), timeout=60) as response:
            return len(response.read(MAX_BYTES + 1)), None
    except urllib.error.HTTPError as error:
        return 0, f"the image itself answers {error.code}"


def get_json(url, headers=None):
    request = urllib.request.Request(url, headers={"Accept": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def read_reviewed(only=None):
    with open(REVIEWED, encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    confirmed = [r for r in rows if r["decision"] == "CONFIRMED"]
    if only:
        confirmed = [r for r in confirmed if fold(r["venue"]) == fold(only)]
    return rows, confirmed


def fetch_venues(host):
    """Every venue the target holds, keyed by name and by folded name."""
    venues = []
    for page in range(MAX_PAGES):
        url = f"{host}/api/admin/venues?page={page}&size={PAGE_SIZE}&sort=name,asc"
        body = get_json(url)
        venues.extend(body.get("content", []))
        if page + 1 >= body.get("totalPages", 1):
            break
    return venues


def commons_file(row):
    """Thumbnail URL, licence and credit for one Commons file, as the API states them."""
    params = {
        "action": "query",
        "titles": "File:" + row["file"],
        "prop": "imageinfo",
        "iiprop": "url|extmetadata|size|mime",
        "iiurlwidth": THUMB_WIDTH,
        "format": "json",
    }
    body = get_json(f"{COMMONS}?{urllib.parse.urlencode(params)}", {"User-Agent": AGENT})
    page = next(iter(body.get("query", {}).get("pages", {}).values()), {})
    if "missing" in page:
        return None, "no such file on Commons"
    info = next(iter(page.get("imageinfo") or []), {})
    if not info.get("thumburl"):
        return None, "Commons rendered no thumbnail"
    # Commons serves the original file, not a rendering, where the requested width meets or exceeds
    # it. That is correct and it is also the one path that can hand back something over the cap.
    served_original = info.get("thumburl") == info.get("url")
    if served_original and info.get("size", 0) > MAX_BYTES:
        return None, f"Commons serves the original at {info['size'] // 1024 // 1024} MB, over the fetcher's cap"

    meta = info.get("extmetadata", {})
    return {
        "thumb": without_query(info["thumburl"]),
        "licence": plain(meta.get("LicenseShortName", {}).get("value")),
        "artist": plain(meta.get("Artist", {}).get("value")),
        "page": info.get("descriptionurl"),
    }, None


def flickr_photo(row):
    """Image URL, licence and credit for one Flickr photo, as Flickr states them.

    oEmbed rather than the Flickr API, because it answers the same three questions without a key.
    A 404 here is the answer the Openverse index cannot give: the photo has been deleted or made
    private since it was reviewed, so there is no licence to honour and no page to link.
    """
    query = urllib.parse.urlencode({"format": "json", "url": row["file_page"]})
    try:
        photo = get_json(f"{FLICKR_OEMBED}?{query}", {"User-Agent": AGENT})
    except urllib.error.HTTPError as error:
        if error.code == HTTP_NOT_FOUND:
            return None, "the photo is gone from Flickr, so nothing states its licence now"
        raise

    image = photo.get("url")
    if not image:
        return None, "Flickr returned no image URL"
    size, problem = image_size(image)
    if problem:
        return None, problem
    if size > MAX_BYTES:
        return None, f"Flickr serves {size // 1024 // 1024} MB, over the fetcher's cap"

    return {
        "thumb": without_query(image),
        "licence": photo.get("license") or "",
        "artist": plain(photo.get("author_name")),
        "page": photo.get("web_page") or row["file_page"],
    }, None


# Which resolver answers for a row, and how the credit names the archive it came from.
ARCHIVES = (
    (("commons-", "wikidata-"), commons_file, "Wikimedia Commons"),
    (("openverse-flickr",), flickr_photo, "Flickr"),
)


def archive_for(found_by):
    for prefixes, resolver, name in ARCHIVES:
        if found_by.startswith(prefixes):
            return resolver, name
    return None, None


def plan_one(row, venue):
    """What this venue would be written, or the reason it will not be."""
    resolver, archive = archive_for(row["found_by"])
    if not resolver:
        return None, f"no archive reads a {row['found_by']!r} row"
    file_info, problem = resolver(row)
    if problem:
        return None, problem

    if file_info["licence"] != row["licence_at_review"]:
        return None, f"licence changed since review: {row['licence_at_review']} -> {file_info['licence']}"

    spdx = SPDX.get(file_info["licence"])
    if not spdx:
        return None, f"no SPDX identifier for {file_info['licence']!r}"
    if not names_an_author(file_info["artist"]):
        stated = file_info["artist"] or "nothing"
        return None, f"{archive} names no author, it states {stated[:60]!r}"

    body = venue_body(venue)
    body["imageUrl"] = file_info["thumb"]
    body["imageAttribution"] = f"{file_info['artist']}, via {archive}"
    body["imageLicenceId"] = spdx
    body["imageSourceUrl"] = file_info["page"] or row["file_page"]
    return body, None


def put_venue(host, venue_id, body):
    request = urllib.request.Request(
        f"{host}/api/admin/venues/{venue_id}",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="PUT",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.status


def main():
    parser = argparse.ArgumentParser(description="Write the reviewed Commons images onto the venues.")
    parser.add_argument("--host", default=LOCAL_HOST, help=f"importer admin API (default {LOCAL_HOST})")
    parser.add_argument("--apply", action="store_true", help="write; without it nothing is changed")
    parser.add_argument("--venue", help="only this venue, by name")
    parser.add_argument("--force", action="store_true", help="also replace an image a venue already has")
    args = parser.parse_args()

    rows, confirmed = read_reviewed(args.venue)
    if not confirmed:
        print(f"no CONFIRMED row in {REVIEWED}" + (f" for {args.venue!r}" if args.venue else ""))
        return 1
    print(f"{len(rows)} reviewed, {len(confirmed)} confirmed\n")

    try:
        venues = fetch_venues(args.host)
    except (urllib.error.URLError, OSError) as error:
        print(f"cannot reach {args.host}: {error}")
        return 1

    by_name = {v["name"]: v for v in venues}
    by_fold = {fold(v["name"]): v for v in venues}

    written = skipped = stopped = 0
    for row in confirmed:
        venue = by_name.get(row["venue"]) or by_fold.get(fold(row["venue"]))
        if not venue:
            print(f"  STOP    {row['venue']:<26} no venue of that name on {args.host}")
            stopped += 1
            continue
        if venue.get("imageUrl") and not args.force:
            print(f"  skip    {row['venue']:<26} already has an image")
            skipped += 1
            continue

        try:
            body, problem = plan_one(row, venue)
        except (urllib.error.URLError, OSError) as error:
            body, problem = None, f"Commons unreachable: {error}"
        if problem:
            print(f"  STOP    {row['venue']:<26} {problem}")
            stopped += 1
            continue

        if not args.apply:
            print(f"  would   {row['venue']:<26} {body['imageLicenceId']:<16} {body['imageAttribution']}")
            written += 1
            continue

        try:
            put_venue(args.host, venue["id"], body)
        except urllib.error.HTTPError as error:
            print(f"  STOP    {row['venue']:<26} {error.code} {error.read().decode()[:160]}")
            stopped += 1
            continue
        print(f"  wrote   {row['venue']:<26} {body['imageLicenceId']:<16} {body['imageAttribution']}")
        written += 1

    verb = "written" if args.apply else "would be written"
    print(f"\n{written} {verb}, {skipped} skipped, {stopped} stopped")
    if not args.apply and written:
        print("Nothing was changed. Re-run with --apply.")
    return 1 if stopped else 0


if __name__ == "__main__":
    sys.exit(main())
