#!/usr/bin/env python3
"""Coordinates for the venues we do not import, from OpenStreetMap (#2766).

    python3 scripts/osm-venue-coordinates.py                 # report: OSM against the stored point
    python3 scripts/osm-venue-coordinates.py --apply         # write the OSM points that agree
    python3 scripts/osm-venue-coordinates.py --venue Baiz    # one venue
    python3 scripts/osm-venue-coordinates.py --report temp/osm.md

**OpenStreetMap is the source, Google only the detector.** Every stored coordinate is
OpenStreetMap's, ODbL, which permits keeping it and asks for credit where it is shown. Google's
terms do not permit keeping one past 30 days on a map that is not Google's (`http/osm/nominatim.http`,
LEGAL.md § 9.3). So this script asks Nominatim for each venue's street address and writes the point
it returns. `geocode-venues.py` stays the Google detector, and `--by-name` there checks the address.

A point is written only when OpenStreetMap matched the house number and the result lies within
`--threshold` metres of the stored point, or when a search by the venue's name finds an object that
carries the venue's own website. The second is identity rather than proximity, the way `Mikropol` was
settled: it places a venue on a large site (RAW, Funkhaus) where the address point is the gate.
Anything else is reported for a person to settle: an address OpenStreetMap does not know, a match on
the street only, or two sources far apart.

Nominatim is a volunteer service with a usage policy: at most one request a second and a
User-Agent naming the caller. Both are enforced here. Responses cache in
`temp/osm-geocode-cache.json`, so a re-run asks again only for what changed.

Only venue blocks without a source capture are read: an imported venue's block sets
`client.global.set(...)` for its event source, a venue we do not import has none.
"""

import argparse
import json
import math
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
SEED_FILE = REPO / "http" / "importer" / "dev-seed.http"
CACHE_FILE = REPO / "temp" / "osm-geocode-cache.json"
ENDPOINT = "https://nominatim.openstreetmap.org/search"
AGENT = "event-junkie-venue-audit/1.0 (https://github.com/enorm-labs/event-junkie)"
DELAY_SECONDS = 1.1

VENUE_POST = re.compile(r"^POST \{\{importer-host\}\}/api/admin/venues\s*$")


def not_imported_venues(text):
    """Yield (name, body, body_start, body_end) for each venue block without a source capture."""
    lines = text.splitlines(keepends=True)
    offsets, pos = [], 0
    for line in lines:
        offsets.append(pos)
        pos += len(line)
    i = 0
    while i < len(lines):
        if not VENUE_POST.match(lines[i].rstrip("\n")):
            i += 1
            continue
        i += 1
        while i < len(lines) and lines[i].strip():
            i += 1
        start = i
        while i < len(lines) and not lines[i].startswith("> {%") and not lines[i].startswith("###"):
            i += 1
        end = i
        handler = []
        while i < len(lines) and not lines[i].startswith("###"):
            handler.append(lines[i])
            i += 1
        if any("client.global.set" in h for h in handler):
            continue
        body_text = "".join(lines[start:end])
        body = json.loads(body_text)
        yield body["name"], body, offsets[start], offsets[start] + len(body_text)


def street_query(address):
    """The street and first house number, for Nominatim's structured `street` field.

    `Budapester Straße 38-40` asks for 38. `Eiswerderstraße 18, Gebäude 129` asks for 18. An
    address with no number (`Große Querallee`) asks for the street, and its result will not match.
    """
    first = address.split(",")[0].strip()
    m = re.match(r"^(.*?\D)\s*(\d+)\s*([a-zA-Z]?)\b", first)
    if not m:
        return first
    return f"{m.group(2)}{m.group(3).lower()} {m.group(1).strip()}"


def metres(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def lookup(street, postal, cache, offline=False):
    key = f"{street}|{postal}"
    if key in cache:
        return cache[key]
    if offline:
        return None
    query = urllib.parse.urlencode(
        {
            "street": street,
            "postalcode": postal,
            "city": "Berlin",
            "countrycodes": "de",
            "format": "jsonv2",
            "addressdetails": 1,
            "limit": 1,
        }
    )
    request = urllib.request.Request(f"{ENDPOINT}?{query}", headers={"User-Agent": AGENT, "Accept-Language": "de"})
    time.sleep(DELAY_SECONDS)
    with urllib.request.urlopen(request, timeout=30) as response:
        result = json.loads(response.read().decode("utf-8"))
    cache[key] = result
    return result


def host(url):
    try:
        return urllib.parse.urlparse(url if "//" in url else f"//{url}").hostname.removeprefix("www.").lower()
    except (AttributeError, ValueError):
        return None


def lookup_name(name, cache, offline=False):
    key = f"name|{name}"
    if key in cache:
        return cache[key]
    if offline:
        return None
    query = urllib.parse.urlencode(
        {"q": f"{name}, Berlin", "countrycodes": "de", "format": "jsonv2", "extratags": 1, "limit": 5}
    )
    request = urllib.request.Request(f"{ENDPOINT}?{query}", headers={"User-Agent": AGENT, "Accept-Language": "de"})
    time.sleep(DELAY_SECONDS)
    with urllib.request.urlopen(request, timeout=30) as response:
        result = json.loads(response.read().decode("utf-8"))
    cache[key] = result
    return result


def by_identity(venue, results):
    """The point of a name hit whose OpenStreetMap object names the venue's own website, or None."""
    own = host(venue.get("websiteUrl") or "")
    if not own:
        return None
    for hit in results or []:
        tags = hit.get("extratags") or {}
        sites = [tags.get(k) for k in ("website", "contact:website", "url")]
        if any(s and host(s) == own for s in sites):
            return round(float(hit["lat"]), 6), round(float(hit["lon"]), 6)
    return None


def judge(venue, result, threshold):
    """(verdict, osm point or None, distance or None, note) for one venue."""
    if not result:
        return "missing", None, None, "OpenStreetMap does not know this address"
    hit = result[0]
    lat, lon = round(float(hit["lat"]), 6), round(float(hit["lon"]), 6)
    has_number = "house_number" in (hit.get("address") or {})
    stored = venue.get("latitude"), venue.get("longitude")
    distance = None
    if stored[0] is not None and stored[1] is not None:
        distance = metres(float(stored[0]), float(stored[1]), lat, lon)
    if not has_number:
        return "street-only", (lat, lon), distance, "matched the street, not the house number"
    if distance is not None and distance > threshold:
        return "far", (lat, lon), distance, f"{distance:.0f} m from the stored point"
    return "ok", (lat, lon), distance, ""


def apply_point(text, body_start, body_end, lat, lon):
    """Rewrite the latitude and longitude inside one body, leaving every other byte as it was."""
    body = text[body_start:body_end]
    body = re.sub(r'"latitude": -?[\d.]+', f'"latitude": {lat:.6f}', body, count=1)
    body = re.sub(r'"longitude": -?[\d.]+', f'"longitude": {lon:.6f}', body, count=1)
    return text[:body_start] + body + text[body_end:]


def load_cache():
    try:
        return json.loads(CACHE_FILE.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def save_cache(cache):
    CACHE_FILE.parent.mkdir(exist_ok=True)
    CACHE_FILE.write_text(json.dumps(cache, ensure_ascii=False, indent=1), encoding="utf-8")


def main():
    p = argparse.ArgumentParser(description="Coordinates for the venues we do not import, from OpenStreetMap.")
    p.add_argument("--apply", action="store_true", help="write the OSM points that agree into dev-seed.http")
    p.add_argument("--venue", action="append", help="only this venue, by name; repeatable")
    p.add_argument("--threshold", type=float, default=100.0, help="metres OSM may differ from the stored point (100)")
    p.add_argument("--report", metavar="PATH", help="also write the table to a Markdown file")
    p.add_argument("--offline", action="store_true", help="use the cache only, ask Nominatim nothing")
    args = p.parse_args()

    text = SEED_FILE.read_text(encoding="utf-8")
    venues = [v for v in not_imported_venues(text) if not args.venue or v[0] in args.venue]
    cache = load_cache()
    print(f"{len(venues)} venues without a source in {SEED_FILE.relative_to(REPO)}, at most one request a second")
    rows, edits = [], []
    try:
        for name, body, start, end in venues:
            street = street_query(body.get("address", ""))
            try:
                result = lookup(street, body.get("postalCode", ""), cache, args.offline)
            except (urllib.error.URLError, OSError) as e:
                rows.append((name, body.get("address", ""), "error", None, None, str(e)))
                continue
            verdict, point, distance, note = judge(body, result, args.threshold)
            if verdict != "ok":
                identity = by_identity(body, lookup_name(name, cache, args.offline))
                if identity:
                    stored = body.get("latitude"), body.get("longitude")
                    distance = metres(float(stored[0]), float(stored[1]), *identity) if stored[0] else None
                    verdict, point, note = "ok-by-name", identity, f"name match carries {host(body['websiteUrl'])}"
            rows.append((name, body.get("address", ""), verdict, point, distance, note))
            if verdict in ("ok", "ok-by-name"):
                edits.append((start, end, point))
    finally:
        save_cache(cache)

    if args.apply:
        for start, end, (lat, lon) in sorted(edits, reverse=True):
            text = apply_point(text, start, end, lat, lon)
        SEED_FILE.write_text(text, encoding="utf-8")

    lines = ["| Venue | Address | Verdict | OSM point | Distance to stored | Note |", "|---|---|---|---|---|---|"]
    order = {"far": 0, "missing": 1, "street-only": 2, "error": 3, "ok-by-name": 4, "ok": 5}
    for name, address, verdict, point, distance, note in sorted(rows, key=lambda r: (order[r[2]], r[0].casefold())):
        where = f"{point[0]:.6f}, {point[1]:.6f}" if point else "—"
        dist = f"{distance:.0f} m" if distance is not None else "—"
        lines.append(f"| {name} | {address} | {verdict} | {where} | {dist} | {note} |")
    table = "\n".join(lines) + "\n"
    print(table)
    counts = {k: sum(1 for r in rows if r[2] == k) for k in order}
    print(" · ".join(f"{k} {v}" for k, v in counts.items() if v))
    print(f"{'Wrote' if args.apply else 'Would write'} {len(edits)} OSM points.")
    if args.report:
        pathlib.Path(args.report).write_text(table, encoding="utf-8")
    sys.exit(0)


if __name__ == "__main__":
    main()
