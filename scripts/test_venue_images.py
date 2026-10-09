#!/usr/bin/env python3
"""Asserts that venue-images.py rewrites every venue field it does not set, so its PUT erases nothing.

It also asserts that a REVIEWED.tsv cell keeps a leading quotation mark (#2932), and that a dry run
over an `own-photograph` row prints the upload and the operator's credit (#2999).

Usage:
    python3 scripts/test_venue_images.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failed one.
validate-python.yml runs it. Reaches no network, uploads nothing and writes only temporary files.
"""

import argparse
import contextlib
import importlib.util
import io
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

    archive_for = module.archive_for
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
    body = planned and planned["body"]
    if problem or body["venueTypes"] != venue["venueTypes"] or body["imageUrl"] != "https://t":
        sys.exit(f"plan_one does not keep the venue's other fields: {problem or planned}")
    checks += 1
    module.archive_for = archive_for

    # The default csv dialect strips a leading `"`, so FluxBau's file was asked for without it.
    fluxbau = '"Fluxbau", Pfuelstrasse 5 in Berlin-Kreuzberg (2017).jpg'
    with tempfile.TemporaryDirectory() as tmp:
        module.REVIEWED = pathlib.Path(tmp) / "REVIEWED.tsv"
        module.REVIEWED.write_text(f"venue\tdecision\tfile\nFluxBau\tCONFIRMED\t{fluxbau}\n", encoding="utf-8")
        _, confirmed = module.read_reviewed()
    if [r["file"] for r in confirmed] != [fluxbau]:
        sys.exit(f"read_reviewed does not keep a cell's quotation marks: {confirmed}")
    checks += 1

    checks += own_photograph_dry_run(module, venue)

    print(f"venue-images: {checks} checks passed")


def own_photograph_dry_run(module, venue):
    """A dry run over an `own-photograph` row names the file, the bucket URL and the operator's credit."""
    page = "https://event-junkie.de/about#photographs"
    with tempfile.TemporaryDirectory() as tmp:
        photo = pathlib.Path(tmp) / "loge-hof.jpg"
        photo.write_bytes(b"\xff\xd8\xff\xe0 not a real JPEG, only bytes to hash")
        module.REVIEWED = pathlib.Path(tmp) / "REVIEWED.tsv"
        module.REVIEWED.write_text(
            "venue\tdecision\tfile\tlicence_at_review\tfile_page\tfound_by\n"
            f"Loge\tCONFIRMED\t{photo}\tCC BY 4.0\t{page}\town-photograph\n",
            encoding="utf-8",
        )
        module.fetch_venues = lambda host: [{**venue, "name": "Loge", "imageUrl": None}]
        module.upload = lambda file: sys.exit("a dry run uploaded a file")
        module.put_venue = lambda *args: sys.exit("a dry run wrote a venue")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            status = module.main([])
    printed = out.getvalue()
    url = f"https://{module.OWN_BUCKET}.{module.OWN_ENDPOINT}/loge-"
    expected = ("upload  Loge", f"{photo} -> {url}", "would   Loge", "CC-BY-4.0", module.OPERATOR)
    if status != 0 or not all(part in printed for part in expected):
        sys.exit(f"the own-photograph dry run did not print the upload and the credit:\n{printed}")
    if f"{module.OPERATOR}, via" in printed:
        sys.exit(f"an own photograph credits an archive:\n{printed}")
    return 1


if __name__ == "__main__":
    main()
