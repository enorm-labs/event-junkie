#!/usr/bin/env python3
"""Resolve venue addresses to coordinates with the Google Geocoding API (#357).

    python3 scripts/geocode-venues.py "Revaler Str. 99, 10245 Berlin"
        One address, printed as a `dev-seed.http` venue block. The new-venue path.

    python3 scripts/geocode-venues.py
        Audits the 86 venues in `http/importer/dev-seed.http` -- no database, no importer --
        and ranks them by how far each stored coordinate sits from Google's.

    python3 scripts/geocode-venues.py --by-name
        Asks for each venue by name as well as by address, at two lookups a venue. A name
        resolves to the business and an address to a building, so a venue carrying another
        venue's address passes the first question and fails the second. That is how `Bi Nuu`,
        `Mikropol` and `AMT` were caught (V013), and how #329's OpenStreetMap pass caught
        three the address pass had excused. It can convict a row, never clear one.

    python3 scripts/geocode-venues.py --host http://localhost:8081 --sql
        Audits a running importer's rows, and emits guarded UPDATE statements for a
        migration. `--sql` needs `--host`: the statements key on `slug`, which the server
        computes from the venue name and the seed file never carries.

**A distance is not a verdict.** #329 corrected 32 of these 86 against OpenStreetMap and found
that one disagreeing source proves nothing. So a row is flagged only where Google is confident
too: a `ROOFTOP` or `RANGE_INTERPOLATED` match that is not a `partial_match`. Anything vaguer is
reported and never flagged, because the distance is then measuring Google's own vagueness. The
postal code is compared as well -- `Mikropol` is the venue #329 could not settle, and its
*address* is the part that is wrong.

**The borough is reported and never compared, and `district` must not be filled from it.** Three
venues in one audit, and a different failure each time. `MS Hoppetosse` is a boat: Google puts its
address on the Treptow-Koepenick bank and its point in the river on the Friedrichshain-Kreuzberg
side, so the two lookups disagree. For `Huxleys Neue Welt` neither Google nor OpenStreetMap had an
answer at all. **And for `Heideglühen` both agreed, and both were wrong** -- Seestrasse 1 is in
Wedding, so the borough is Mitte, but number 1 stands at the canal where Charlottenburg-Nord begins
and each source answered for the point rather than for the address. Agreement is not correctness
here. V009 closed that column to the twelve because a wrong borough is invisible: the venue stops
appearing in its own filter and nobody reports a result that never arrived. It takes a human.

**Storing a value from here is a licensing decision.** The Maps Platform terms allow caching
Content for 30 days and a `place_id` indefinitely; a coordinate kept past that, drawn on a map
that is not Google's, is outside them. Every coordinate in the table today is OpenStreetMap's,
ODbL, credited where it is displayed. Read this tool as a detector: it says which rows to look
at again, and the value that replaces one should keep a provenance we may store.

The key comes from `http/http-client.private.env.json` (gitignored, and where the `.http`
requests read it) or from $GOOGLE_MAPS_API_KEY, never from the command line. Restrict it to the
Geocoding API, by IP rather than by HTTP referrer. Responses cache in `temp/geocode-cache.json`
for the 30 days the terms allow; `--no-cache` re-fetches.
"""
import argparse, collections, json, math, os, pathlib, re, sys, time, urllib.error, urllib.parse, urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SEED_FILE = os.path.join(REPO, "http", "importer", "dev-seed.http")
PRIVATE_ENV = os.path.join(REPO, "http", "http-client.private.env.json")
CACHE_FILE = os.path.join(REPO, "temp", "geocode-cache.json")

ENDPOINT = "https://maps.googleapis.com/maps/api/geocode/json"
ENDPOINT_V4 = "https://geocode.googleapis.com/v4/geocode/address"
KEY_VAR = "google-maps-api-key"
ENV_VAR = "GOOGLE_MAPS_API_KEY"
CACHE_SECONDS = 30 * 24 * 3600
CONFIDENT = ("ROOFTOP", "RANGE_INTERPOLATED")
RETRY_STATUS = ("UNKNOWN_ERROR", "OVER_QUERY_LIMIT")
PAGE_SIZE = 100
MAX_PAGES = 100

VENUE_POST = re.compile(r"^POST \{\{importer-host\}\}/api/admin/venues\s*$")


def strip_json_comments(text):
    """Drop `//` and `/* */`, which the HTTP Client's env files accept and `json` does not.

    String-aware on purpose: an env file holds hosts, and `http://localhost:8081` is not a comment.
    """
    out, i, n = [], 0, len(text)
    in_string = in_line = in_block = False
    while i < n:
        c, nxt = text[i], text[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
                out.append(c)
        elif in_block:
            if c == "*" and nxt == "/":
                in_block, i = False, i + 1
        elif in_string:
            out.append(c)
            if c == "\\" and nxt:
                out.append(nxt)
                i += 1
            elif c == '"':
                in_string = False
        elif c == '"':
            in_string = True
            out.append(c)
        elif c == "/" and nxt in ("/", "*"):
            in_line, in_block, i = nxt == "/", nxt == "*", i + 1
        else:
            out.append(c)
        i += 1
    return "".join(out)


def read_key(explicit):
    """Find the key without ever asking for it on the command line.

    Order is deliberate: the private env file first, so one secret serves both this script and the
    `.http` requests, and the environment second for CI or a one-off shell. Which environment the
    key sits under does not matter -- geocoding is a lookup run while adding a venue, not something
    the deployed stack calls -- so the first environment that holds one wins.
    """
    if explicit:
        return explicit, "--key"
    template = f"{os.path.relpath(PRIVATE_ENV, REPO)}.example"
    try:
        raw = pathlib.Path(PRIVATE_ENV).read_text(encoding="utf-8")
        try:
            envs = json.loads(raw)
        except json.JSONDecodeError:
            envs = json.loads(strip_json_comments(raw))
        for name in ("local", "staging", "production", *sorted(envs)):
            key = (envs.get(name) or {}).get(KEY_VAR)
            if key:
                return key, f"{os.path.relpath(PRIVATE_ENV, REPO)} [{name}]"
    except FileNotFoundError:
        # Having no private env file is a normal state, not an error: the key may be in the
        # environment instead, and every path that needs one and cannot find it ends at the message
        # below. A malformed file is different and stays fatal, because that is a typo to be fixed
        # rather than an absence to fall through.
        pass
    except (json.JSONDecodeError, AttributeError) as e:
        sys.exit(f"{PRIVATE_ENV} is not readable as an HTTP Client env file: {e}")
    if os.environ.get(ENV_VAR):
        return os.environ[ENV_VAR], f"${ENV_VAR}"
    sys.exit(
        f"No API key. Copy the template beside it and fill in one environment:\n\n"
        f"    cp {template} {os.path.relpath(PRIVATE_ENV, REPO)}\n\n"
        f"The copy is gitignored, and is where the .http requests read the key too.\n"
        f"Or export ${ENV_VAR}."
    )


def venues_from_seed(path):
    """Read the venue bodies out of dev-seed.http.

    The file is the source of truth for what a venue is before it exists anywhere (#876), so the
    audit runs with no database and no importer. Every block is a plain JSON body; nothing here has
    to reproduce the client's variable substitution, because a venue body interpolates nothing.
    """
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    out, i = [], 0
    while i < len(lines):
        if not VENUE_POST.match(lines[i]):
            i += 1
            continue
        i += 1
        while i < len(lines) and lines[i].strip():  # headers
            i += 1
        body = []
        while i < len(lines) and not lines[i].startswith("> {%") and not lines[i].startswith("###"):
            body.append(lines[i])
            i += 1
        out.append(json.loads("\n".join(body)))
    return out


def venues_from_host(host):
    """Read the live rows, which is the only place a slug exists.

    The count is checked against the total the listing reports (#810), so reading part of the table
    fails here rather than producing a geocode run that quietly covers a fraction of the venues."""
    out, page, total = [], 0, 0
    while True:
        url = f"{host}/api/admin/venues?page={page}&size={PAGE_SIZE}&sort=name,asc"
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as r:
            body = json.loads(r.read().decode() or "{}")
        total = body["totalElements"]
        out.extend(body["content"])
        if len(out) >= total:
            break
        page += 1
        if page > MAX_PAGES:
            sys.exit(f"Stopped after {MAX_PAGES} pages of venues. The listing is not terminating.")
    if len(out) != total:
        sys.exit(f"Read {len(out)} of {total} venues. Refusing to act on a partial listing.")
    return out


def query_for(venue):
    tail = " ".join(x for x in (venue.get("postalCode"), venue.get("city") or "Berlin") if x)
    return ", ".join(p for p in (venue.get("address"), tail) if p)


def geocode(address, key, cache, use_cache=True):
    """One lookup, cached by its request rather than by its address.

    `components` names the country as a restriction and Berlin only as a hint. Adding the postal
    code as a restriction would be tempting and wrong: it would force the answer into the postal
    code we already believe, and the disagreement between the two is a signal worth keeping.
    """
    params = {
        "address": address,
        "components": "country:DE|locality:Berlin",
        "language": "de",
        "region": "de",
    }
    slot = json.dumps(params, sort_keys=True, ensure_ascii=False)
    hit = cache.get(slot)
    if use_cache and hit and time.time() - hit["fetched"] < CACHE_SECONDS:
        return hit["response"], True

    url = f"{ENDPOINT}?{urllib.parse.urlencode(dict(params, key=key))}"
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=30) as r:
                body = json.loads(r.read().decode())
        except (urllib.error.URLError, OSError) as e:
            sys.exit(f"Cannot reach the Geocoding API: {e}")
        if body.get("status") not in RETRY_STATUS or attempt == 2:
            break
        time.sleep(2**attempt)

    if body.get("status") in ("REQUEST_DENIED", "OVER_DAILY_LIMIT"):
        sys.exit(f"Google refused the request: {body['status']} — {body.get('error_message', '')}")
    cache[slot] = {"fetched": time.time(), "response": body}
    return body, False


def geocode_by_name(name, key, cache, use_cache=True):
    """Ask for the venue by name rather than by address, which is a different question.

    An address resolves to a building and a name resolves to the business, so a venue carrying
    another venue's address satisfies the first lookup and fails this one. #329 found three venues
    that way with OpenStreetMap, after its address pass had excused them, and the same query settled
    `Bi Nuu`, `Mikropol` and `AMT` here (V013).

    This is the v4 endpoint, which takes an unstructured string. It reports a refusal as an HTTP
    error with a JSON body, where v3 answers 200 and a `status` field.
    """
    query = f"{name}, Berlin"
    slot = json.dumps({"v4-name": query, "languageCode": "de"}, sort_keys=True, ensure_ascii=False)
    hit = cache.get(slot)
    if use_cache and hit and time.time() - hit["fetched"] < CACHE_SECONDS:
        return hit["response"], True

    url = f"{ENDPOINT_V4}/{urllib.parse.quote(query, safe='')}?languageCode=de"
    request = urllib.request.Request(url, headers={"X-Goog-Api-Key": key, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as r:
            body = json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        error = json.loads(e.read().decode() or "{}").get("error", {})
        if e.code in (401, 403):
            sys.exit(f"The key cannot use the v4 endpoint: {error.get('message', e.reason)}")
        body = {"results": [], "error": error}
    except (urllib.error.URLError, OSError) as e:
        sys.exit(f"Cannot reach the v4 Geocoding API: {e}")
    cache[slot] = {"fetched": time.time(), "response": body}
    return body, False


def best_v4(response):
    """v4 in v3's words, so one report can print both. `granularity` is v3's `location_type`."""
    results = response.get("results") or []
    if not results:
        return None
    top = results[0]
    location = top.get("location", {})
    if location.get("latitude") is None:
        return None
    return {
        "lat": location["latitude"],
        "lng": location["longitude"],
        "type": top.get("granularity", ""),
        "formatted": top.get("formattedAddress", ""),
        "place_id": top.get("placeId", ""),
        "poi": "point_of_interest" in (top.get("types") or []),
        "count": len(results),
    }


def component(result, kind):
    for c in result.get("address_components", []):
        if kind in c.get("types", []):
            return c.get("long_name", "")
    return ""


def best(response):
    """The first result and the fields that decide whether to believe it."""
    if response.get("status") != "OK" or not response.get("results"):
        return None
    top = response["results"][0]
    location = top["geometry"]["location"]
    return {
        "lat": location["lat"],
        "lng": location["lng"],
        "type": top["geometry"].get("location_type", ""),
        "formatted": top.get("formatted_address", ""),
        "place_id": top.get("place_id", ""),
        "partial": bool(top.get("partial_match")),
        "postal": component(top, "postal_code"),
        "area": component(top, "sublocality_level_1") or component(top, "sublocality"),
        "count": len(response["results"]),
    }


def metres(lat1, lon1, lat2, lon2):
    radius = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(a))


def judge(row, threshold):
    """CHECK only where Google is precise enough, and specific enough, for a disagreement to mean
    something.

    `shared` is the third bucket #329 asked for. Seven venues give their address as Revaler Str. 99,
    the whole RAW-Gelaende, and Google answers all seven with one point -- so the distance stops
    measuring our error and starts measuring the size of the site. Our per-venue coordinates are the
    better data there, and calling them wrong would stack seven pins on one doorway.

    It is not an excuse, though. `Bi Nuu` lands on `Monarch` because the two carry the same street
    address, and the one that is wrong is the address.
    """
    if row["google"] is None:
        return "NO MATCH"
    if row["distance"] is None:
        return "NO COORDINATE"
    if row["distance"] <= threshold:
        return "ok"
    if row.get("shared", 1) > 1:
        return "shared"
    return "CHECK" if row["google"]["type"] in CONFIDENT and not row["google"]["partial"] else "unsure"


def judge_by_name(row, verdict, threshold):
    """The name lookup convicts a row the address lookup excused, or says which field is suspect.

    **It never clears one, and that is load-bearing.** A rule that dismissed a row because the name
    agrees would have silently cleared `Bi Nuu`, which was 1535 m out by address and 46 m by name --
    the largest error in the estate, and the only signal was the address pass. So a disagreement the
    name lookup contradicts becomes `CHECK-address` rather than `ok`: the coordinate matches the
    venue's own point, so the *address* is the field to look at. That is what `Bi Nuu` turned out to
    be, and what a venue spread over a large site looks like too.
    """
    hit, distance = row.get("byname"), row.get("name_distance")
    if not hit or distance is None or not hit["poi"] or hit["type"] not in CONFIDENT:
        return verdict
    if distance <= threshold:
        return "CHECK-address" if verdict == "CHECK" else verdict
    return "CHECK-name" if verdict in ("ok", "shared") else verdict


def audit(venues, key, cache, args):
    rows, billed, lookups = [], 0, 0
    for venue in venues:
        address = query_for(venue)
        if not address:
            rows.append({"venue": venue, "google": None, "distance": None, "note": "no address"})
            continue
        response, cached = geocode(address, key, cache, not args.no_cache)
        billed, lookups = billed + (0 if cached else 1), lookups + 1
        found = best(response)
        stored = (venue.get("latitude"), venue.get("longitude"))
        distance = None
        if found and stored[0] is not None and stored[1] is not None:
            distance = metres(float(stored[0]), float(stored[1]), found["lat"], found["lng"])
        row = {
            "venue": venue,
            "query": address,
            "google": found,
            "distance": distance,
            "status": response.get("status"),
        }
        if args.by_name:
            name_response, name_cached = geocode_by_name(venue["name"], key, cache, not args.no_cache)
            billed, lookups = billed + (0 if name_cached else 1), lookups + 1
            row["byname"] = best_v4(name_response)
            if row["byname"] and stored[0] is not None and stored[1] is not None:
                row["name_distance"] = metres(
                    float(stored[0]), float(stored[1]), row["byname"]["lat"], row["byname"]["lng"]
                )
        rows.append(row)
        if sys.stdout.isatty():
            print(".", end="", flush=True)
    if sys.stdout.isatty():
        print()

    # Verdicts need every row, because one of them counts how many venues share a point.
    claims = collections.Counter(point(r) for r in rows if r.get("google"))
    for row in rows:
        row["shared"] = claims[point(row)] if row.get("google") else 1
        row["verdict"] = judge(row, args.threshold)
        if args.by_name:
            row["verdict"] = judge_by_name(row, row["verdict"], args.threshold)
    return rows, billed, lookups


def point(row):
    return round(row["google"]["lat"], 6), round(row["google"]["lng"], 6)


def rank(row):
    order = {"CHECK": 0, "CHECK-address": 1, "CHECK-name": 2, "NO MATCH": 3, "NO COORDINATE": 3,
             "shared": 4, "unsure": 5, "ok": 6}
    return order.get(row.get("verdict"), 1), -(row.get("distance") or 0)


def print_report(rows, args):
    for row in sorted(rows, key=rank):
        venue, found = row["venue"], row.get("google")
        head = f"{row.get('verdict', '?'):>13}  {venue['name'][:28]:<28}"
        if not found:
            print(f"{head}  {row.get('status') or row.get('note')}  ({row.get('query', '')})")
            continue
        distance = f"{row['distance']:>6.0f} m" if row["distance"] is not None else "     — "
        flags = "".join(
            [
                " partial" if found["partial"] else "",
                f" postal {venue.get('postalCode')}→{found['postal']}"
                if found["postal"] and venue.get("postalCode") and found["postal"] != venue["postalCode"]
                else "",
                f" shared with {row['shared'] - 1} more" if row.get("shared", 1) > 1 else "",
            ]
        )
        print(f"{head}  {distance}  {found['type']:<18} {found['lat']:.6f},{found['lng']:.6f}{flags}")
        if row["verdict"] in ("CHECK", "CHECK-name", "NO COORDINATE", "shared") or args.verbose:
            print(f"{'':>15}stored {venue.get('latitude')},{venue.get('longitude')}  ·  {found['formatted']}")
        by = row.get("byname")
        if by and (row["verdict"].startswith("CHECK") or args.verbose):
            distance = f"{row['name_distance']:.0f} m" if row.get("name_distance") is not None else "—"
            kind = "POI" if by["poi"] else "not a POI"
            print(f"{'':>15}by name {distance}  {by['type']} {kind}  ·  {by['formatted']}")

    tally = collections.Counter(r.get("verdict") for r in rows)
    print(
        f"\n{len(rows)} venues · {tally['CHECK']} to check over {args.threshold} m "
        + (f"· {tally['CHECK-address']} where the coordinate agrees and the address does not "
           f"· {tally['CHECK-name']} the name lookup convicts on its own " if args.by_name else "")
        + f"· {tally['shared']} over it on a point another venue also claims "
        f"· {tally['unsure']} over it on a vague match · {tally['ok']} clear"
    )
    return [r for r in rows if r.get("verdict") == "CHECK"]


def write_report(rows, args, path):
    """The same ranking as the terminal, in a file that can be read away from one.

    #329 kept `temp/venue-coordinate-check.md` for exactly this, and every row carries the query it
    was asked so a disagreement can be re-checked by hand rather than taken on trust.
    """
    out = [
        "# Venue coordinates against the Google Geocoding API",
        "",
        f"`scripts/geocode-venues.py`, threshold {args.threshold:.0f} m, {len(rows)} venues.",
        "",
        "`CHECK` is a disagreement Google is confident and specific about. `shared` is a point more",
        "than one venue resolves to, where the distance measures the size of a site rather than our",
        "error. `unsure` is a disagreement on a vague match. **Google agreeing is not proof either.**",
        "",
        "| Verdict | Venue | Distance | Match | Google's point | Stored | Query |",
        "| --- | --- | ---: | --- | --- | --- | --- |",
    ]
    if args.by_name:
        out[7:7] = [
            "",
            "`CHECK-name` is a row the address lookup excused and the name lookup convicts.",
            "`CHECK-address` is the reverse: the coordinate agrees with the venue's own point, so the",
            "stored **address** is the suspect field. Both distances are in the `by name` column.",
        ]
        out[-2] = "| Verdict | Venue | Distance | Match | Google's point | Stored | By name | Query |"
        out[-1] = "| --- | --- | ---: | --- | --- | --- | --- | --- |"
    for row in sorted(rows, key=rank):
        venue, found = row["venue"], row.get("google")
        by = ""
        if args.by_name:
            hit = row.get("byname")
            distance = row.get("name_distance")
            by = (f" {distance:.0f} m, {hit['type']}, {hit['formatted']} |"
                  if hit and distance is not None else " — |")
        if not found:
            out.append(
                f"| {row.get('verdict')} | {venue['name']} | — | {row.get('status') or row.get('note')} "
                f"| — | {venue.get('latitude')},{venue.get('longitude')} |{by} `{row.get('query', '')}` |"
            )
            continue
        note = f"{found['type']}"
        if found["partial"]:
            note += ", partial"
        if row.get("shared", 1) > 1:
            note += f", shared×{row['shared']}"
        if found["postal"] and venue.get("postalCode") and found["postal"] != venue["postalCode"]:
            note += f", postal {venue['postalCode']}→{found['postal']}"
        distance = f"{row['distance']:.0f} m" if row["distance"] is not None else "—"
        out.append(
            f"| {row['verdict']} | {venue['name']} | {distance} | {note} "
            f"| {found['lat']:.6f},{found['lng']:.6f} | {venue.get('latitude')},{venue.get('longitude')} "
            f"|{by} `{row['query']}` |"
        )
    pathlib.Path(path).write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"Report written to {path}")


def print_sql(checks):
    print("\n-- Coordinates from the Google Geocoding API. Storing them is a licensing decision the")
    print("-- table has not made before: every value in it today is OpenStreetMap's, ODbL, credited")
    print("-- where it is displayed. Read the module docstring before pasting this into a migration.")
    print("-- Each UPDATE names the value it replaces, so a re-run corrects nothing twice (ADR-004).")
    for row in sorted(checks, key=lambda r: -r["distance"]):
        venue, found = row["venue"], row["google"]
        print(f"\n-- {venue['name']}: {row['distance']:.0f} m out, {found['type']}")
        print(f"UPDATE venue SET latitude = {found['lat']:.6f}, longitude = {found['lng']:.6f}")
        print(
            f"WHERE slug = '{venue['slug']}' "
            f"AND latitude = {venue['latitude']} AND longitude = {venue['longitude']};"
        )


def print_one(address, found, response):
    if not found:
        sys.exit(f"{response.get('status')} for {address!r}. Nothing to paste.")
    print(f"\n{address}")
    print(f"  Google  {found['formatted']}")
    print(f"  Match   {found['type']}{', partial' if found['partial'] else ''}, {found['count']} result(s)")
    print(f"  Place   {found['place_id']}")
    if found["area"]:
        print(f"  Area    {found['area']}")
        print("          Informational. Do not fill `district` from it -- see the module docstring.")
    if found["type"] not in CONFIDENT:
        print("\n  Not a precise match. Google has placed this on the street or the district rather")
        print("  than the building. Check it on a map before storing it.")
    print(f'\n    "latitude": {found["lat"]:.6f},')
    print(f'    "longitude": {found["lng"]:.6f},')


def load_cache():
    try:
        with open(CACHE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def save_cache(cache):
    os.makedirs(os.path.dirname(CACHE_FILE), exist_ok=True)
    with open(CACHE_FILE, "w", encoding="utf-8") as f:
        json.dump(cache, f)


def main():
    p = argparse.ArgumentParser(
        description="Geocode venue addresses with the Google Geocoding API.",
        epilog="With no address, audits every venue. See the module docstring for the key and the licence.",
    )
    p.add_argument("address", nargs="?", help="A single address to geocode, e.g. 'Revaler Str. 99, 10245 Berlin'")
    p.add_argument("--host", help="Audit a running importer's rows instead of dev-seed.http")
    p.add_argument("--threshold", type=float, default=100.0, help="Metres before a row is worth checking (default 100)")
    p.add_argument("--sql", action="store_true", help="Emit UPDATE statements for the flagged rows; needs --host")
    p.add_argument("--report", metavar="PATH", help="Also write the ranked table to a Markdown file")
    p.add_argument("--by-name", action="store_true",
                   help="Also ask v4 for each venue by name, which catches a borrowed address. Doubles the lookups")
    p.add_argument("--verbose", action="store_true", help="Show the stored pair and Google's address for every row")
    p.add_argument("--no-cache", action="store_true", help="Re-fetch even where a cached response is still fresh")
    p.add_argument("--key", help="The API key, if it is not in the private env file or the environment")
    args = p.parse_args()

    if args.sql and not args.host:
        p.error("--sql needs --host: an UPDATE keys on a slug, and only the database holds one.")

    key, source = read_key(args.key)
    cache = load_cache()

    if args.address:
        response, _ = geocode(args.address, key, cache, not args.no_cache)
        save_cache(cache)
        print_one(args.address, best(response), response)
        return

    if args.host:
        try:
            venues = venues_from_host(args.host)
        except (urllib.error.URLError, OSError) as e:
            sys.exit(f"Cannot reach the importer at {args.host}: {e}")
        where = args.host
    else:
        venues = venues_from_seed(SEED_FILE)
        where = os.path.relpath(SEED_FILE, REPO)

    print(f"{len(venues)} venues from {where}, key from {source}, threshold {args.threshold:.0f} m")
    if args.by_name:
        print("Asking for each venue by name as well as by address, so this run costs two lookups a venue.")
    try:
        rows, billed, lookups = audit(venues, key, cache, args)
    finally:
        save_cache(cache)
    checks = print_report(rows, args)
    print(f"{lookups} lookups · {billed} billed · {lookups - billed} from the 30-day cache")
    if args.report:
        write_report(rows, args, args.report)
    if args.sql and checks:
        print_sql(checks)


if __name__ == "__main__":
    main()
