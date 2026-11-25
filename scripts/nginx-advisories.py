#!/usr/bin/env python3
"""Compare the nginx version in the frontend's base image with nginx.org's security advisories (#5514).

    scripts/nginx-advisories.py                     # the pin in events-frontend/Dockerfile; needs docker
    scripts/nginx-advisories.py --version 1.31.7    # a version you name; no docker
    scripts/nginx-advisories.py --page saved.html   # a saved advisory page; no network
    scripts/nginx-advisories.py --json              # for nginx-advisory-reminder.yml

The image scan cannot answer this: the package comes from nginx.org, and Trivy reported none of its CVEs
for 1.31.7. The answer is about the version only, not about whether our nginx.conf reaches the bug.

Exit status: 0 not affected, 1 affected, 2 the check could not be made.
"""

import argparse
import html
import json
import pathlib
import re
import subprocess
import sys
import urllib.request

ADVISORIES_URL = "https://nginx.org/en/security_advisories.html"
DOCKERFILE = pathlib.Path(__file__).resolve().parent.parent / "events-frontend" / "Dockerfile"

# Listed as `Vulnerable: all`, `Not vulnerable: none`, so no version ever clears it.
IGNORED = {"CVE-2009-4487"}

# The page lists every advisory since 2009. Fewer than this is a page this script no longer understands.
MINIMUM_ENTRIES = 40

VERSION = re.compile(r"\d+\.\d+\.\d+")


class CheckError(Exception):
    """The check could not be made, which must never read as clean."""


def parse_version(text):
    if not VERSION.fullmatch(text):
        raise CheckError(f"not an nginx version: {text!r}")
    return tuple(int(part) for part in text.split("."))


def parse_ranges(text):
    """`1.1.3-1.1.18, 1.0.7-1.0.14`, `1.25.3` or `all`, as inclusive pairs."""
    if text == "all":
        return [((0, 0, 0), (sys.maxsize, 0, 0))]
    ranges = []
    for part in text.split(","):
        first, _, last = part.strip().partition("-")
        ranges.append((parse_version(first), parse_version(last or first)))
    return ranges


def parse_fixes(text):
    """`1.31.9+, 1.30.7+` as versions. `none` is no fix."""
    if text == "none":
        return []
    fixes = []
    for part in text.split(","):
        part = part.strip()
        if not part.endswith("+"):
            raise CheckError(f"unreadable fixed version: {part!r}")
        fixes.append(parse_version(part[:-1]))
    return fixes


def parse_page(page):
    entries = []
    for item in re.findall(r"<li><p>(.*?)</p></li>", page, re.DOTALL):
        lines = [html.unescape(re.sub(r"<[^>]+>", "", line)).strip() for line in re.split(r"<br\s*/?>", item)]
        fields = dict(line.split(": ", 1) for line in lines if line.startswith(("Vulnerable: ", "Not vulnerable: ")))
        if set(fields) != {"Vulnerable", "Not vulnerable"}:
            raise CheckError(f"an advisory without both version lines: {lines[0]!r}")
        if fields["Vulnerable"].startswith("nginx/Windows "):
            entries.append(None)
            continue
        ids = sorted(set(re.findall(r"CVE-\d{4}-\d{4,}", " ".join(lines))))
        entries.append(
            {
                "ids": ids or [lines[0]],
                "title": lines[0],
                "ranges": parse_ranges(fields["Vulnerable"]),
                "fixes": parse_fixes(fields["Not vulnerable"]),
                "fixed_in": fields["Not vulnerable"],
            }
        )
    # An advisory in markup the pattern above skips would otherwise vanish without a word.
    if len(entries) != len(re.findall(r"\bNot vulnerable\b", page)):
        raise CheckError("the page names more advisories than could be read")
    if len(entries) < MINIMUM_ENTRIES:
        raise CheckError(f"only {len(entries)} advisories on the page, expected at least {MINIMUM_ENTRIES}")
    return [entry for entry in entries if entry]


def is_fixed(version, fixes):
    """A `+` entry covers the rest of its own branch. Only the highest one covers later branches too."""
    if not fixes:
        return False
    return version >= max(fixes) or any(version[:2] == fix[:2] and version >= fix for fix in fixes)


def affecting(version, entries):
    found = []
    for entry in entries:
        if IGNORED.issuperset(entry["ids"]) or is_fixed(version, entry["fixes"]):
            continue
        # The ranges overlap the fixed stable releases, which is why the fixes are read first.
        if any(first <= version <= last for first, last in entry["ranges"]):
            found.append({key: entry[key] for key in ("ids", "title", "fixed_in")})
    return found


def pinned_image(dockerfile):
    images = re.findall(r"^FROM (\S*nginx-unprivileged\S*)", dockerfile, re.MULTILINE)
    if len(images) != 1:
        raise CheckError(f"expected one nginx-unprivileged FROM line, found {len(images)}")
    return images[0]


def image_version(image):
    # amd64, the architecture the image scan reads.
    command = ["docker", "run", "--rm", "--platform", "linux/amd64", "--entrypoint", "nginx", image, "-v"]
    result = subprocess.run(command, capture_output=True, text=True)
    match = re.search(r"nginx version: nginx/(\d+\.\d+\.\d+)", result.stderr)
    if result.returncode != 0 or not match:
        raise CheckError(f"could not read the nginx version of {image}: {result.stderr.strip()}")
    return match.group(1)


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "event-junkie-nginx-advisories"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode("utf-8")


def check(args):
    page = args.page.read_text(encoding="utf-8") if args.page else fetch(ADVISORIES_URL)
    entries = parse_page(page)
    version = args.version or image_version(pinned_image(DOCKERFILE.read_text(encoding="utf-8")))
    return version, affecting(parse_version(version), entries)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--version", help="check this nginx version instead of the pinned image's")
    parser.add_argument("--page", type=pathlib.Path, help="read the advisories from this file")
    parser.add_argument("--json", action="store_true", help="print one line of JSON")
    args = parser.parse_args(argv)

    try:
        version, found = check(args)
    except (CheckError, OSError, UnicodeDecodeError) as error:
        print(f"nginx-advisories: {error}", file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps({"version": version, "affected": found}))
    elif found:
        print(f"nginx {version} is affected by {len(found)} advisories:")
        for entry in found:
            print(f"  {', '.join(entry['ids'])}  {entry['title']} (not vulnerable: {entry['fixed_in']})")
    else:
        print(f"nginx {version} is in no advisory's vulnerable range.")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
