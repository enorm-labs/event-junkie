#!/usr/bin/env python3
"""Checks for osm-venue-coordinates.py: the query it builds, the verdicts, and the one edit it makes.

    python3 scripts/test_osm_venue_coordinates.py

No network: every Nominatim answer here is written out by hand.
"""

import argparse
import importlib.util
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("osm", HERE / "osm-venue-coordinates.py")
osm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(osm)

failures = []


def check(name, got, want):
    if got != want:
        failures.append(f"{name}: got {got!r}, want {want!r}")


NOT_IMPORTED = {
    "name": "Not Imported",
    "address": "Budapester Straße 38-40",
    "postalCode": "10787",
    "latitude": 52.505432,
    "longitude": 13.338769,
    "websiteUrl": "https://www.example-club.de/",
}
ENTRIES = [
    {"venue": {"name": "Imported", "address": "Revaler Str. 99"}, "sources": [{"name": "Imported"}], "path": None},
    {"venue": NOT_IMPORTED, "sources": [], "path": None},
]
FILE = osm.seed_venues.venue_file_text(NOT_IMPORTED, [])


def main():
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()

    venues = list(osm.not_imported_venues(ENTRIES))
    check("only the file without a source", [v[0] for v in venues], ["Not Imported"])

    check("range asks for the first number", osm.street_query("Budapester Straße 38-40"), "38 Budapester Straße")
    check("letter suffix is kept", osm.street_query("Schönhauser Allee 26A"), "26a Schönhauser Allee")
    check("spaced suffix is kept", osm.street_query("Prinzenstraße 85 B"), "85b Prinzenstraße")
    check("text after a comma is dropped", osm.street_query("Eiswerderstraße 18, Gebäude 129"), "18 Eiswerderstraße")
    check("no number asks for the street", osm.street_query("Große Querallee"), "Große Querallee")

    _, body, _ = venues[0]
    house = [{"lat": "52.5055", "lon": "13.3388", "address": {"house_number": "38"}}]
    street = [{"lat": "52.5055", "lon": "13.3388", "address": {"road": "Budapester Straße"}}]
    far = [{"lat": "52.5155", "lon": "13.3388", "address": {"house_number": "38"}}]
    check("house match nearby is ok", osm.judge(body, house, 100)[0], "ok")
    check("street match is not written", osm.judge(body, street, 100)[0], "street-only")
    check("a kilometre away is far", osm.judge(body, far, 100)[0], "far")
    check("no answer is missing", osm.judge(body, [], 100)[0], "missing")

    own = [{"lat": "52.506", "lon": "13.339", "extratags": {"website": "http://example-club.de"}}]
    other = [{"lat": "52.506", "lon": "13.339", "extratags": {"website": "https://somewhere-else.de"}}]
    check("website on the object is identity", osm.by_identity(body, own), (52.506, 13.339))
    check("another website is not", osm.by_identity(body, other), None)

    edited = osm.apply_point(FILE, 0, len(FILE), 52.5055, 13.3388)
    check("only the two numbers change", edited.count("52.505500") + edited.count("13.338800"), 2)

    def others(text):
        return [line for line in text.splitlines() if "itude" not in line]

    check("every other line is untouched", others(edited), others(FILE))
    check("the other fields stay", '"websiteUrl": "https://www.example-club.de/"' in edited, True)
    check("a second run changes nothing", osm.apply_point(edited, 0, len(edited), 52.5055, 13.3388), edited)

    bare = {
        "name": "Not Imported",
        "address": "Budapester Straße 38-40",
        "postalCode": "10787",
        "district": "tiergarten",
        "websiteUrl": "https://www.example-club.de/",
    }
    bare_file = osm.seed_venues.venue_file_text(bare, [])
    inserted = osm.apply_point(bare_file, 0, len(bare_file), 52.5055, 13.3388)
    keys = list(json.loads(inserted)["venue"])
    check("a file without a point gets one", json.loads(inserted)["venue"]["latitude"], 52.5055)
    check("the point follows district", keys[keys.index("district") :][:3], ["district", "latitude", "longitude"])
    check("every other line is kept on insert", others(inserted), others(bare_file))

    no_district = {k: v for k, v in bare.items() if k != "district"}
    no_district_file = osm.seed_venues.venue_file_text(no_district, [])
    keys = list(json.loads(osm.apply_point(no_district_file, 0, len(no_district_file), 52.5055, 13.3388))["venue"])
    check("without district the point follows postalCode", keys[keys.index("postalCode") + 1], "latitude")

    district_last = {"name": "Last", "address": "Revaler Str. 99", "district": "friedrichshain"}
    last_file = osm.seed_venues.venue_file_text(district_last, [])
    last_venue = json.loads(osm.apply_point(last_file, 0, len(last_file), 52.5055, 13.3388))["venue"]
    check("district as the last key still loads", list(last_venue)[-2:], ["latitude", "longitude"])

    total = 22
    for f in failures:
        print(f"FAIL {f}")
    print(f"{total - len(failures)} of {total} checks passed")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
