#!/usr/bin/env python3
"""Asserts that promoter-images.py keeps every promoter field it does not set, and credits a logo correctly.

Usage:
    python3 scripts/test_promoter_images.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failed one.
validate-python.yml runs it. Reaches no network and writes nothing.
"""

import argparse
import importlib.util
import pathlib
import re
import sys
import types

HERE = pathlib.Path(__file__).resolve().parent
REQUEST = HERE.parent / "events-importer/src/main/kotlin/de/norm/events/promoter/PromoterRequest.kt"


def load(name, file):
    # The hyphen in the file name rules out a plain import.
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


pi = vi = None
checks = 0


def check(condition, message):
    global checks
    if not condition:
        print(f"FAIL: {message}")
        sys.exit(1)
    checks += 1


def fake_archive(licence, artist):
    """venue-images.py with its Commons reader answering one file, and its tables as they are."""
    return types.SimpleNamespace(
        commons_file=lambda row: (
            {
                "thumb": "https://upload.wikimedia.org/x.png",
                "licence": licence,
                "artist": artist,
                "page": "https://commons.wikimedia.org/wiki/File:X",
            },
            None,
        ),
        SPDX=vi.SPDX,
        names_an_author=vi.names_an_author,
    )


PROMOTER = {
    "id": 7,
    "slug": "radioeins",
    "createdAt": "2026-01-01T00:00:00Z",
    "name": "radioeins",
    "websiteUrl": "https://www.radioeins.de/",
    "instagramUrl": "https://www.instagram.com/radioeins/",
    "imageUrl": None,
    "imageAttribution": None,
    "imageLicenceId": None,
    "imageSourceUrl": None,
    "description": "Der Sender.",
    "descriptionLanguage": "de",
    "descriptionAlt": "The station.",
    "descriptionAltLanguage": "en",
    "kind": "media",
    "reviewedAt": "2026-11-08T10:00:00Z",
}
ROW = {"slug": "radioeins", "file": "Radio Eins.svg", "licence_at_review": "Public domain"}


def main():
    global pi, vi
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()
    pi = load("promoter_images", "promoter-images.py")
    vi = load("venue_images", "venue-images.py")
    declared = set(re.findall(r"^\s+(?:override )?val (\w+)\b", REQUEST.read_text(), re.M))
    drift = sorted(declared ^ set(pi.REQUEST_FIELDS))
    check(not drift, f"REQUEST_FIELDS differs from PromoterRequest: {drift}")

    body, problem = pi.plan_one(ROW, PROMOTER, fake_archive("Public domain", "Unknown author Unknown author"))
    check(problem is None, f"a public-domain logo with no author stopped: {problem}")
    check(body["imageAttribution"] == "radioeins, via Wikimedia Commons", f"credit was {body['imageAttribution']!r}")
    check(body["imageLicenceId"] == "PD", "public domain is not stored as PD")
    check(set(body) == set(pi.REQUEST_FIELDS), "the PUT carries a read-only field or misses one")
    for field in ("websiteUrl", "instagramUrl", "description", "descriptionAlt", "kind", "reviewedAt"):
        check(body[field] == PROMOTER[field], f"the PUT changes {field}")

    body, _ = pi.plan_one(ROW, PROMOTER, fake_archive("Public domain", "™/®Deutschlandradio"))
    check(body["imageAttribution"] == "Deutschlandradio, via Wikimedia Commons", "a named author is not credited")

    cc_row = {**ROW, "licence_at_review": "CC BY-SA 4.0"}
    _, problem = pi.plan_one(cc_row, PROMOTER, fake_archive("CC BY-SA 4.0", "Unknown author Unknown author"))
    check(problem and "no author" in problem, "a CC logo with no author was written")

    _, problem = pi.plan_one(ROW, PROMOTER, fake_archive("CC BY-SA 4.0", "Someone"))
    check(problem and "licence changed" in problem, "a changed licence was written")

    old = {k: v for k, v in PROMOTER.items() if k != "kind"}
    _, problem = pi.plan_one(ROW, old, fake_archive("Public domain", "Someone"))
    check(problem and "kind" in problem, "a target without a field was written with it as null")

    rows = pi.read_images()
    check(len({r["slug"] for r in rows}) == len(rows), "IMAGES.tsv repeats a slug")
    for r in rows:
        check(None not in r and len(r) == 5, f"IMAGES.tsv row {r.get('slug')} does not have five columns")
        check(r["licence_at_review"] in vi.SPDX, f"{r['slug']}: no SPDX identifier for {r['licence_at_review']!r}")
        check(re.fullmatch(r"Q\d+", r["wikidata"]), f"{r['slug']}: {r['wikidata']!r} is not a Wikidata id")

    print(f"ok: {checks} checks")


if __name__ == "__main__":
    main()
