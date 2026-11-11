#!/usr/bin/env python3
"""Mask the e-mail addresses in the scraper fixtures and the tests that quote them (#4855).

Usage:
  scripts/mask-fixture-contacts.py          # rewrite every address to <first letter>-masked@example.de
  scripts/mask-fixture-contacts.py check    # list what is still unmasked, masked; exit 1 if anything is

An address is a contact wherever it stands, except on an iCalendar `UID` line, where it is an event id
(and some sources' `sourceId`). A `%40` in a URL is left too: those are `@2x` image names and handles.
A Cloudflare `data-cfemail` / `email-protection#` value is decoded, masked and encoded again with its key.
Output never prints an address in clear: AGENTS.md § Privacy.
"""

import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
ROOTS = [REPO / "events-importer/src/test/resources/scraper"]
CODE_ROOTS = [REPO / "events-importer/src", REPO / "events-bff/src", REPO / "events-core/src"]

ADDRESS = re.compile(r"[A-Za-z0-9._+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,6}\b")
NOT_AN_ADDRESS = re.compile(
    r"@example\.|\.(?:png|jpe?g|webp|svg|gif|js|css|avif)$|@[0-9]x|wixpress|sentry|@media|@font|@import|@keyframes|@context",
    re.IGNORECASE,
)
# Not a third party's contact: the project's own senders, a push endpoint's URL, and the invented
# placeholders ContactDetailsTest feeds the contact detector it tests.
OWN_OR_INVENTED = re.compile(
    r"@(?:[a-z0-9-]+\.)*event-junkie\.de$|@fcm\.googleapis\.com$|@example-kuenstler\.de$|@venue\.berlin$", re.IGNORECASE
)
ESCAPED_UNICODE = re.compile(r"u[0-9a-fA-F]{4}.")
ESCAPED_PERCENT = re.compile(r"[0-9a-fA-F]{2}.")
UID_LINE = re.compile(r"^\s*UID[;:]", re.IGNORECASE)
CF_HEX = re.compile(r"(data-cfemail=\"|email-protection#)([0-9a-fA-F]{4,})")
MASKED_DOMAIN = "example.de"
USAGE = "scripts/mask-fixture-contacts.py [check]"


def masked(address: str) -> str:
    """Still a valid address, so a parser that drops a contact line still sees one."""
    return f"{address[0]}-masked@{MASKED_DOMAIN}"


def escape_prefix(text: str, start: int, address: str) -> int:
    """Length of a `\\uXXXX`, `\\n`-style or `%XX` escape the address pattern swallowed in front of the address."""
    if text[start - 1 : start] == "\\" and ESCAPED_UNICODE.match(address):
        return 5
    if text[start - 1 : start] == "\\" and address[0] in "nrtbf":
        return 1
    if text[start - 1 : start] == "%" and ESCAPED_PERCENT.match(address):
        return 2
    return 0


def is_contact(address: str) -> bool:
    return not NOT_AN_ADDRESS.search(address) and not OWN_OR_INVENTED.search(address)


def is_contact_at(address: str) -> bool:
    """A contact with a local part: `@fitzroy.berlin` after an escape is a social handle."""
    return not address.startswith("@") and is_contact(address)


def cf_decode(hex_value: str) -> str:
    key = int(hex_value[:2], 16)
    return "".join(chr(int(hex_value[i : i + 2], 16) ^ key) for i in range(2, len(hex_value), 2))


def cf_encode(text: str, key: int) -> str:
    return f"{key:02x}" + "".join(f"{ord(c) ^ key:02x}" for c in text)


def mask_line(line: str) -> tuple[str, int]:
    if UID_LINE.match(line):
        return line, 0
    count = 0

    def plain(match: re.Match[str]) -> str:
        nonlocal count
        address = match.group(0)
        keep = escape_prefix(match.string, match.start(), address)
        if not is_contact_at(address[keep:]):
            return address
        count += 1
        return address[:keep] + masked(address[keep:])

    def cloudflare(match: re.Match[str]) -> str:
        nonlocal count
        prefix, hex_value = match.groups()
        decoded = cf_decode(hex_value)
        if not ADDRESS.fullmatch(decoded) or decoded.endswith("@" + MASKED_DOMAIN):
            return match.group(0)
        count += 1
        return prefix + cf_encode(masked(decoded), int(hex_value[:2], 16))

    line = CF_HEX.sub(cloudflare, line)
    return ADDRESS.sub(plain, line), count


def unmasked(line: str) -> list[str]:
    if UID_LINE.match(line):
        return []
    found = []
    for match in ADDRESS.finditer(line):
        rest = match.group(0)[escape_prefix(line, match.start(), match.group(0)) :]
        if is_contact_at(rest):
            found.append(rest)
    for _, hex_value in CF_HEX.findall(line):
        decoded = cf_decode(hex_value)
        if ADDRESS.fullmatch(decoded) and not decoded.endswith("@" + MASKED_DOMAIN):
            found.append(decoded)
    return found


def files() -> list[pathlib.Path]:
    found = [p for root in ROOTS for p in root.rglob("*") if p.is_file()]
    found += [p for root in CODE_ROOTS for p in root.rglob("*.kt")]
    return sorted(set(found))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0], usage=USAGE)
    parser.add_argument("mode", nargs="?", choices=["check"], help="list what is still unmasked; exit 1 if anything is")
    check = parser.parse_args().mode == "check"
    total = changed = 0
    for path in files():
        raw = path.read_bytes()
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            continue
        if check:
            for number, line in enumerate(text.splitlines(), 1):
                for address in unmasked(line):
                    print(f"{path.relative_to(REPO)}:{number}: {masked(address)}")
                    total += 1
            continue
        lines = text.splitlines(keepends=True)
        out = []
        file_count = 0
        for line in lines:
            new, n = mask_line(line)
            out.append(new)
            file_count += n
        if file_count:
            path.write_bytes("".join(out).encode("utf-8"))
            total += file_count
            changed += 1
    if check:
        print(f"{total} unmasked address(es)")
        return 1 if total else 0
    print(f"masked {total} address(es) in {changed} file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
