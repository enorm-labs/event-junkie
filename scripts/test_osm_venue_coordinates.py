#!/usr/bin/env python3
"""Checks for osm-venue-coordinates.py: the query it builds, the verdicts, and the one edit it makes.

    python3 scripts/test_osm_venue_coordinates.py

No network: every Nominatim answer here is written out by hand.
"""

import argparse
import importlib.util
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


SEED = """### --- Create Imported venue ---
POST {{importer-host}}/api/admin/venues
Content-Type: application/json

{
    "name": "Imported",
    "address": "Revaler Str. 99",
    "latitude": 52.5,
    "longitude": 13.4
}

> {%
    client.global.set("imported_venue_id", response.body.id);
%}

### --- Create Not Imported venue ---
POST {{importer-host}}/api/admin/venues
Content-Type: application/json

{
    "name": "Not Imported",
    "address": "Budapester Straße 38-40",
    "postalCode": "10787",
    "latitude": 52.505432,
    "longitude": 13.338769,
    "websiteUrl": "https://www.example-club.de/"
}

> {%
    client.test("Not Imported venue created", function () {});
%}
"""


def main():
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()

    venues = list(osm.not_imported_venues(SEED))
    check("only the block without a capture", [v[0] for v in venues], ["Not Imported"])

    check("range asks for the first number", osm.street_query("Budapester Straße 38-40"), "38 Budapester Straße")
    check("letter suffix is kept", osm.street_query("Schönhauser Allee 26A"), "26a Schönhauser Allee")
    check("spaced suffix is kept", osm.street_query("Prinzenstraße 85 B"), "85b Prinzenstraße")
    check("text after a comma is dropped", osm.street_query("Eiswerderstraße 18, Gebäude 129"), "18 Eiswerderstraße")
    check("no number asks for the street", osm.street_query("Große Querallee"), "Große Querallee")

    _, body, start, end = venues[0]
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

    edited = osm.apply_point(SEED, start, end, 52.5055, 13.3388)
    check("only the two numbers change", edited.count("52.505500") + edited.count("13.338800"), 2)
    check("the imported block is untouched", edited.split("### --- Create Not")[0], SEED.split("### --- Create Not")[0])
    check("the other fields stay", '"websiteUrl": "https://www.example-club.de/"' in edited, True)

    total = 16
    for f in failures:
        print(f"FAIL {f}")
    print(f"{total - len(failures)} of {total} checks passed")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
