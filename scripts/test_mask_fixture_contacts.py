#!/usr/bin/env python3
"""Asserts what mask-fixture-contacts.py masks and what it leaves: a valid mask, escapes kept whole,
Cloudflare values re-encoded, and iCalendar UIDs, handles and the project's own senders untouched (#4855);
and a third party's Google API key masked wherever it stands, and never printed whole (#4964).

Usage:
    python3 scripts/test_mask_fixture_contacts.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failure.
validate-python.yml runs it. Reaches no network and no git, and writes nothing.
The addresses and keys below are invented: none belongs to a venue or a person.
"""

import argparse
import importlib.util
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent


def load():
    spec = importlib.util.spec_from_file_location("mask", HERE / "mask-fixture-contacts.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0]).parse_args()
    mask = load()
    checks = 0

    def same(actual, expected, what):
        nonlocal checks
        if actual != expected:
            print(f"FAIL {what}: expected {expected!r}, got {actual!r}")
            sys.exit(1)
        checks += 1

    line, n = mask.mask_line("Tickets: kasse@club-invented.de, Presse: presse@club-invented.de")
    same(line, "Tickets: k-masked@example.de, Presse: p-masked@example.de", "a plain address becomes a valid mask")
    same(n, 2, "both addresses count")
    same(bool(mask.ADDRESS.fullmatch("k-masked@example.de")), True, "the mask is still an address")

    same(mask.mask_line('href="mailto:info@club-invented.de"')[0], 'href="mailto:i-masked@example.de"', "a mailto link")
    same(
        mask.mask_line('"text":"Mail \\u003einfo@club-invented.de"')[0],
        '"text":"Mail \\u003ei-masked@example.de"',
        "a \\u escape stays",
    )
    same(
        mask.mask_line('"text":"Fragen?\\ninfo@club-invented.de"')[0],
        '"text":"Fragen?\\ni-masked@example.de"',
        "a \\n escape stays",
    )
    same(mask.mask_line("mailto%3Ainfo@club-invented.de")[0], "mailto%3Ai-masked@example.de", "a %XX escape stays")
    same(
        mask.mask_line('"text":"Folgt uns\\n@club.invented"')[0],
        '"text":"Folgt uns\\n@club.invented"',
        "a handle after an escape stays",
    )

    same(mask.mask_line("UID:4711@club-invented.de\n")[0], "UID:4711@club-invented.de\n", "an iCalendar UID stays")
    same(
        mask.mask_line("ORGANIZER;CN=Club:mailto:booking@club-invented.de")[0],
        "ORGANIZER;CN=Club:mailto:b-masked@example.de",
        "an ORGANIZER is a contact",
    )
    same(
        mask.mask_line("logo@2x.png and noreply@notify.event-junkie.de")[0],
        "logo@2x.png and noreply@notify.event-junkie.de",
        "an image and our own sender stay",
    )
    same(mask.mask_line("already x-masked@example.de")[1], 0, "a mask is not masked again")

    encoded = mask.cf_encode("info@club-invented.de", 0x42)
    masked_line = mask.mask_line(f'<a data-cfemail="{encoded}">[email protected]</a>')[0]
    value = masked_line.split('data-cfemail="', 1)[1].split('"', 1)[0]
    same(mask.cf_decode(value), "i-masked@example.de", "a Cloudflare value decodes to the mask")
    same(value[:2], "42", "a Cloudflare value keeps its key")

    same(mask.unmasked("Tickets: kasse@club-invented.de"), ["kasse@club-invented.de"], "check finds a plain address")
    same(
        mask.unmasked(f'<a href="/cdn-cgi/l/email-protection#{encoded}">'),
        ["info@club-invented.de"],
        "check decodes a Cloudflare value",
    )
    same(mask.unmasked("UID:4711@club-invented.de"), [], "check skips a UID")
    same(mask.unmasked("Tickets: k-masked@example.de"), [], "check passes a mask")

    key = "AIza" + "Sy" + "0123456789abcdefghijklmnopqrstuvw"
    same(len(key), 39, "the invented key has a real key's length")
    same(
        mask.mask_line(f'<iframe src="https://maps.example.de/embed?key={key}&q=Berlin">')[0],
        f'<iframe src="https://maps.example.de/embed?key={mask.MASKED_KEY}&q=Berlin">',
        "a key in an attribute is masked",
    )
    same(mask.mask_line(f"webcard.mapsKey='{key}';")[1], 1, "a key in a script is masked and counted")
    same(mask.mask_line(f"UID:{key}")[1], 1, "a key on a UID line is masked too")
    same(mask.unmasked_keys(f"src=x?key={mask.MASKED_KEY}"), [], "check passes the placeholder")
    same(mask.unmasked_keys("AIzaShort and AIza-word"), [], "a short AIza word is no key")
    found = mask.unmasked_keys(f"key={key}")
    same(found, ["AIzaSy01…"], "check finds a key and shows only its start")
    same(key in "".join(found), False, "check never prints the whole key")

    print(f"OK {checks} checks")
    return 0


if __name__ == "__main__":
    sys.exit(main())
