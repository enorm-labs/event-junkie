#!/usr/bin/env python3
"""Asserts that venue-images.py rewrites every venue field it does not set, so its PUT erases nothing.

It also asserts that a REVIEWED.tsv cell keeps a leading quotation mark (#2932).

Usage:
    python3 scripts/test_venue_images.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failed one.
validate-python.yml runs it. Reaches no network and writes only a temporary file.
"""

import argparse
import importlib.util
import pathlib
import re
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
VENUE = HERE.parent / "events-importer/src/main/kotlin/de/norm/events/venue"


def load():
    # The hyphen in the file name rules out a plain import.
    spec = importlib.util.spec_from_file_location("venue_images", HERE / "venue-images.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def properties(kotlin_file):
    """The constructor properties a Kotlin DTO declares, `override val` included."""
    return set(re.findall(r"^\s+(?:override )?val (\w+)\b", (VENUE / kotlin_file).read_text(), re.M))


def main():
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()
    module = load()
    checks = 0

    request = properties("VenueRequest.kt")
    response = properties("VenueResponse.kt")
    writable = response - set(module.READ_ONLY_FIELDS)
    if writable != request:
        sys.exit(
            "The admin GET minus READ_ONLY_FIELDS is not what a PUT takes. "
            f"Missing from the PUT, so erased: {sorted(request - writable)}. "
            f"Refused by the PUT: {sorted(writable - request)}."
        )
    checks += 1

    venue = {field: f"value of {field}" for field in response}
    body = module.venue_body(venue)
    if set(body) != request or any(body[field] != venue[field] for field in body):
        sys.exit(f"venue_body does not carry every writable field unchanged: {sorted(body)}")
    checks += 1

    module.archive_for = lambda found_by: (
        lambda row: (
            {"licence": "CC BY-SA 4.0", "artist": "A. Photographer", "thumb": "https://t", "page": "https://p"},
            None,
        ),
        "Wikimedia Commons",
    )
    planned, problem = module.plan_one(
        {"found_by": "commons", "licence_at_review": "CC BY-SA 4.0", "file_page": "https://p"}, venue
    )
    if problem or planned["venueTypes"] != venue["venueTypes"] or planned["imageUrl"] != "https://t":
        sys.exit(f"plan_one does not keep the venue's other fields: {problem or planned}")
    checks += 1

    # The default csv dialect strips a leading `"`, so FluxBau's file was asked for without it.
    fluxbau = '"Fluxbau", Pfuelstrasse 5 in Berlin-Kreuzberg (2017).jpg'
    with tempfile.TemporaryDirectory() as tmp:
        module.REVIEWED = pathlib.Path(tmp) / "REVIEWED.tsv"
        module.REVIEWED.write_text(f"venue\tdecision\tfile\nFluxBau\tCONFIRMED\t{fluxbau}\n", encoding="utf-8")
        _, confirmed = module.read_reviewed()
    if [r["file"] for r in confirmed] != [fluxbau]:
        sys.exit(f"read_reviewed does not keep a cell's quotation marks: {confirmed}")
    checks += 1

    print(f"venue-images: {checks} checks passed")


if __name__ == "__main__":
    main()
