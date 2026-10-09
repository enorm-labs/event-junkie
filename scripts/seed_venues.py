#!/usr/bin/env python3
"""The seed venues: one JSON file per venue, and the generated "Run all" file (#2824).

    python3 scripts/seed_venues.py               # regenerate http/importer/dev-seed.http
    python3 scripts/seed_venues.py check         # exit 1 when dev-seed.http does not match the directory
    python3 scripts/seed_venues.py split OLD.http [--venue NAME ...]
                                                 # convert old-format blocks into venue files

`http/importer/seed/venues/<slug>.json` is the source of truth for every venue we seed and its event
sources. One file holds one venue:

    {
        "venue": { ... the POST /api/admin/venues body ... },
        "sources": [ { ... the POST /api/admin/event-sources body, without venueId ... } ]
    }

`sources` is empty for a venue we do not import (#2766). The file name is the venue's slug, as
`SlugGenerator` makes it from `venue.name`; `SeedVenueFilesTest` holds the two together.

`http/importer/dev-seed.http` is generated from the directory for IntelliJ's "Run all" and for
`scripts/dev-env.sh seed-all`. Do not edit it by hand. `scripts/dev-seed-parity.sh check` fails in CI
when it does not match the directory.

`split` reads a `dev-seed.http` in the old hand-written format and writes a venue file for each
venue that has no file yet. It is for a branch that still edits the old file: see
`docs/WORKTREES.md`. `--venue NAME` also overwrites that venue's file, for a branch that changed an
existing venue.

Standard library only. The other seed scripts import `load` and `slugify` from here.
"""

import argparse
import decimal
import json
import pathlib
import re
import sys
import unicodedata

REPO = pathlib.Path(__file__).resolve().parent.parent
SEED_DIR = REPO / "http" / "importer" / "seed" / "venues"
RUN_ALL = REPO / "http" / "importer" / "dev-seed.http"
INDENT = "    "
# A plain comment, not `###`: the HTTP Client names each request after the `###` line above it.
RULE = "# " + "—" * 60

# SlugGenerator's custom replacements. Slugify applies them before NFKD, for the letters that NFKD
# cannot reduce to an ASCII base letter.
NON_DECOMPOSING_LATIN = {
    "ø": "o",
    "Ø": "O",
    "æ": "ae",
    "Æ": "Ae",
    "ð": "d",
    "Ð": "D",
    "þ": "th",
    "Þ": "Th",
    "ł": "l",
    "Ł": "L",
    "đ": "d",
    "Đ": "D",
    "ı": "i",
    "ß": "ss",
    "ẞ": "Ss",
    "œ": "oe",
    "Œ": "Oe",
}


def slugify(name):
    """The slug `SlugGenerator.slugify` makes: Slugify 4 with the replacements above and no locale bundle."""
    text = name.strip()
    for letter, base in NON_DECOMPOSING_LATIN.items():
        text = text.replace(letter, base)
    text = unicodedata.normalize("NFKD", text)
    text = re.sub(r"[^\x00-\x7f]+", "", text)
    text = re.sub(r"[^a-zA-Z0-9_]+", "-", text)
    text = re.sub(r"^-|-$", "", text)
    return text.lower()


def _parse(text, exact):
    # `exact` keeps a number's own digits, so `52.505420` is written back as it was read.
    return json.loads(text, parse_float=decimal.Decimal if exact else float)


def _read(path, exact):
    data = _parse(path.read_text(encoding="utf-8"), exact)
    if set(data) != {"venue", "sources"}:
        raise ValueError(f"{path.name}: expected the keys venue and sources, found {', '.join(sorted(data))}")
    if not isinstance(data["sources"], list):
        raise ValueError(f"{path.name}: sources must be a list")
    for source in data["sources"]:
        if "venueId" in source:
            raise ValueError(f"{path.name}: a source carries no venueId; the seed sets it from the venue")
    slug = slugify(data["venue"]["name"])
    if path.stem != slug:
        raise ValueError(f"{path.name}: the venue {data['venue']['name']!r} has the slug {slug!r}; rename the file")
    return {"slug": slug, "path": path, "venue": data["venue"], "sources": data["sources"]}


def load(seed_dir=SEED_DIR, exact=False):
    """Every venue file, sorted by slug, as dicts with `slug`, `path`, `venue` and `sources`."""
    paths = sorted(pathlib.Path(seed_dir).glob("*.json"))
    if not paths:
        raise FileNotFoundError(f"no venue files in {seed_dir}")
    return [_read(p, exact) for p in paths]


def _scalar(value):
    if isinstance(value, decimal.Decimal):
        return str(value)
    return json.dumps(value, ensure_ascii=False)


def dumps(value, level=0):
    """JSON in the layout the .http bodies always had: four spaces, a list of scalars on one line."""
    pad = INDENT * (level + 1)
    if isinstance(value, dict):
        if not value:
            return "{}"
        rows = [f"{pad}{json.dumps(k, ensure_ascii=False)}: {dumps(v, level + 1)}" for k, v in value.items()]
        return "{\n" + ",\n".join(rows) + "\n" + INDENT * level + "}"
    if isinstance(value, list):
        if all(not isinstance(v, (dict, list)) for v in value):
            return "[" + ", ".join(_scalar(v) for v in value) + "]"
        return "[\n" + ",\n".join(pad + dumps(v, level + 1) for v in value) + "\n" + INDENT * level + "]"
    return _scalar(value)


def venue_file_text(venue, sources):
    return dumps({"venue": venue, "sources": sources}) + "\n"


HEADER = """### ============================================================
### Dev Seed — bootstrap a local database with every venue and event source
###
### GENERATED from http/importer/seed/venues/*.json by scripts/dev-seed-parity.sh.
### Do not edit this file. Edit or add a venue file and run scripts/dev-seed-parity.sh;
### CI runs `scripts/dev-seed-parity.sh check` and fails when the two disagree (#2824).
###
### Run all requests in this file sequentially (▶▶ "Run All") after starting the importer
### with a fresh database. A venue or source that exists already answers 409.
###
### Import triggers are asynchronous: each returns 202 Accepted immediately and the
### import runs in the background, so events keep landing for up to ~a minute after the
### run finishes. Poll `GET /api/admin/event-sources` to watch each source's status.
###
### Removed, and deliberately not in the directory:
###   arkaoda — the club closed and the venue is gone from both clusters (#1788).
###   Do not add it back: V051 deletes the venue, its source row and its one past event.
### ============================================================
"""


def _test(label, status):
    return (
        f'    client.test("{label}", function () {{\n'
        f'        client.assert(response.status === {status}, "Expected {status} but got " + response.status);\n'
        "    });\n"
    )


def render(venues):
    """The Run-all file: per venue, its POST, then per source a POST and an import trigger."""
    out = [HEADER]
    for entry in venues:
        venue, sources, slug = entry["venue"], entry["sources"], entry["slug"]
        name = venue["name"]
        var = "venue_id_" + slug.replace("-", "_")
        out.append(f"\n{RULE}\n# {name}{'' if sources else ' — not imported'}\n{RULE}\n")
        out.append(f"\n### --- Create {name} venue ---\nPOST {{{{importer-host}}}}/api/admin/venues\n")
        out.append(f"Content-Type: application/json\n\n{dumps(venue)}\n\n> {{%\n")
        if sources:
            out.append(f'    client.global.set("{var}", response.body.id);\n')
        out.append(_test(f"{name} venue created", 201) + "%}\n")
        for source in sources:
            body = {"venueId": f"{{{{{var}}}}}", **source}
            text = dumps(body).replace(f'"{{{{{var}}}}}"', f"{{{{{var}}}}}", 1)
            label = source["name"]
            out.append(f"\n### --- Create {label} event source ---\n")
            out.append("POST {{importer-host}}/api/admin/event-sources\n")
            out.append(f"Content-Type: application/json\n\n{text}\n\n> {{%\n")
            out.append(_test(f"{label} event source created", 201) + "%}\n")
            out.append(f"\n### --- Trigger initial {label} import ---\n")
            out.append(f"POST {{{{importer-host}}}}/api/admin/event-sources/{slugify(label)}/import\n")
            out.append("Accept: application/json\n\n> {%\n" + _test(f"{label} import triggered", 202) + "%}\n")
    return "".join(out)


REQUEST = re.compile(r"^(POST|PUT|PATCH|GET) \{\{importer-host\}\}(\S+)")
CAPTURE = re.compile(r'client\.global\.set\("(\w+)",\s*response\.body\.id\)')
VENUE_ID = re.compile(r'^\s*"venueId":\s*\{\{(\w+)\}\},?\s*$', re.M)


def split_http(text):
    """Read the old hand-written format: [(venue, [sources])], a source joined to its venue by the captured id."""
    lines = text.splitlines()
    venues, by_var, i = [], {}, 0
    while i < len(lines):
        m = REQUEST.match(lines[i])
        if not m:
            i += 1
            continue
        path = m.group(2)
        i += 1
        while i < len(lines) and lines[i].strip():
            i += 1
        body, handler = [], []
        while i < len(lines) and not lines[i].startswith("> {%") and not lines[i].startswith("###"):
            body.append(lines[i])
            i += 1
        if i < len(lines) and lines[i].startswith("> {%"):
            while i < len(lines) and not lines[i].startswith("%}"):
                handler.append(lines[i])
                i += 1
        body_text = "\n".join(body).strip()
        if path == "/api/admin/venues":
            entry = (_parse(body_text, exact=True), [])
            venues.append(entry)
            cap = CAPTURE.search("\n".join(handler))
            if cap:
                by_var[cap.group(1)] = entry
        elif path == "/api/admin/event-sources":
            var = VENUE_ID.search(body_text)
            if not var or var.group(1) not in by_var:
                raise ValueError(f"a source block names no venue captured above it:\n{body_text[:200]}")
            source = _parse(VENUE_ID.sub("", body_text), exact=True)
            by_var[var.group(1)][1].append(source)
    return venues


def split(args):
    venues = split_http(pathlib.Path(args.old).read_text(encoding="utf-8"))
    force = set(args.venue or [])
    unknown = force - {v["name"] for v, _ in venues}
    if unknown:
        sys.exit(f"Not a venue in {args.old}: {', '.join(sorted(unknown))}")
    SEED_DIR.mkdir(parents=True, exist_ok=True)
    wrote = kept = 0
    for venue, sources in venues:
        path = SEED_DIR / f"{slugify(venue['name'])}.json"
        if path.exists() and venue["name"] not in force:
            kept += 1
            continue
        path.write_text(venue_file_text(venue, sources), encoding="utf-8")
        print(f"  wrote {path.relative_to(REPO)}")
        wrote += 1
    print(f"{len(venues)} venues in {args.old}: wrote {wrote}, kept {kept} existing file(s).")
    print("Now run scripts/dev-seed-parity.sh to regenerate dev-seed.http.")


def parity(check):
    try:
        want = render(load(exact=True))
    except (OSError, ValueError, KeyError) as e:
        sys.exit(f"dev-seed-parity: {e}")
    have = RUN_ALL.read_text(encoding="utf-8") if RUN_ALL.exists() else ""
    if have == want:
        print(f"{RUN_ALL.relative_to(REPO)} matches {SEED_DIR.relative_to(REPO)}.")
        return 0
    if check:
        print(
            f"{RUN_ALL.relative_to(REPO)} does not match {SEED_DIR.relative_to(REPO)}.\n"
            "It is generated. Run scripts/dev-seed-parity.sh and commit the result.",
            file=sys.stderr,
        )
        return 1
    RUN_ALL.write_text(want, encoding="utf-8")
    print(f"Wrote {RUN_ALL.relative_to(REPO)}.")
    return 0


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = p.add_subparsers(dest="mode")
    sub.add_parser("fix", help="regenerate dev-seed.http (the default)")
    sub.add_parser("check", help="exit 1 when dev-seed.http does not match the directory; write nothing")
    sp = sub.add_parser("split", help="write venue files from a dev-seed.http in the old format")
    sp.add_argument("old", help="the old-format file, e.g. from `git show <branch>:http/importer/dev-seed.http`")
    sp.add_argument("--venue", action="append", metavar="NAME", help="overwrite this venue's existing file; repeatable")
    args = p.parse_args()
    if args.mode == "split":
        split(args)
        return
    sys.exit(parity(args.mode == "check"))


if __name__ == "__main__":
    main()
