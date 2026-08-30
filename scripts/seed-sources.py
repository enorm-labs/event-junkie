#!/usr/bin/env python3
"""Register the venues and event sources from http/importer/dev-seed.http on a cluster (#876).

Dry run by default. The dry run is also the drift report: it names what the target is missing and
what it holds that the file does not, so staging and production can be compared without writing.

    python3 scripts/seed-sources.py                                    # compare, write nothing
    python3 scripts/seed-sources.py --host http://localhost:18081      # compare a forwarded cluster
    python3 scripts/seed-sources.py --host http://localhost:18081 --apply --yes

`dev-seed.http` is the source of truth and this reads it directly rather than carrying a second copy
of the 86 venues. A second copy is the drift #876 is about.

**Sources are created disabled, and that is not caution.** A source with no import history is always
due, and the scheduler ticks every 60 seconds, so an enabled source is imported about a minute after
it is created — before any licence review could be applied. Two venues forbid their descriptions and
images, `NULL` displays, and V007 has already run in production, so nothing would clear what that
first import stored. The three steps are therefore ordered, and `--enable` refuses to run out of
order:

    python3 scripts/seed-sources.py        --host <host> --apply --yes   # 1. create, disabled
    python3 scripts/apply-licence-review.py --host <host> --apply --yes  # 2. the verdicts (#283)
    python3 scripts/seed-sources.py        --host <host> --enable --yes  # 3. let them import

**This never triggers an import.** The file's third request per venue does; it is dropped here.
Step 3 hands the sources to the scheduler, which picks them up on its next tick.
"""
import argparse, json, re, sys, urllib.error, urllib.request

SEED_FILE = "http/importer/dev-seed.http"
LOCAL_HOST = "http://localhost:8081"
PAGE_SIZE = 100
MAX_PAGES = 100

REQUEST = re.compile(r"^(POST|PUT|PATCH|GET) \{\{importer-host\}\}(\S+)")
CAPTURE = re.compile(r'client\.global\.set\("(\w+)",\s*response\.body\.id\)')
PLACEHOLDER = re.compile(r"\{\{(\w+)\}\}")


def parse_seed(path):
    """Pull (kind, name, body_text, capture_var) out of the .http file.

    The file is written for a client that keeps state between requests: a venue's response id is
    captured into a variable that the next request's body interpolates. Reproducing that is the
    whole job, so the capture name is parsed rather than the venue name guessed.
    """
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()

    steps, i = [], 0
    while i < len(lines):
        m = REQUEST.match(lines[i])
        if not m:
            i += 1
            continue
        path_part = m.group(2)
        i += 1
        while i < len(lines) and lines[i].strip():  # headers
            i += 1
        body, handler = [], []
        while i < len(lines) and not lines[i].startswith("> {%") and not REQUEST.match(lines[i]):
            body.append(lines[i])
            i += 1
        if i < len(lines) and lines[i].startswith("> {%"):
            while i < len(lines) and not lines[i].startswith("%}"):
                handler.append(lines[i])
                i += 1
        text = "\n".join(body).strip()
        cap = CAPTURE.search("\n".join(handler))
        if path_part == "/api/admin/venues":
            steps.append(("venue", text, cap.group(1) if cap else None))
        elif path_part == "/api/admin/event-sources":
            steps.append(("source", text, None))
        # Anything else is an import trigger. Deliberately dropped -- see the module docstring.
    return steps


def fetch_all(host, path):
    """Read every page. The listings answer with a bare array, so a short page is the only end
    marker a caller gets (#810). Reading one page looks exactly like reading all of them."""
    out, page = [], 0
    while True:
        batch = request(f"{host}{path}?page={page}&size={PAGE_SIZE}&sort=name,asc")
        if isinstance(batch, dict):
            batch = batch.get("content", [])
        out.extend(batch)
        if len(batch) < PAGE_SIZE:
            return out
        page += 1
        if page > MAX_PAGES:
            sys.exit(f"Stopped after {MAX_PAGES} pages of {path}. The listing is not terminating.")


def request(url, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode() or "{}")


def enable(args):
    """Step 3. Separated from creation so the licence review has somewhere to happen in between."""
    try:
        sources = fetch_all(args.host, "/api/admin/event-sources")
    except (urllib.error.URLError, OSError) as e:
        sys.exit(f"Cannot reach the importer at {args.host}: {e}")

    unreviewed = [s["name"] for s in sources if not s.get("descriptionLicence") and not s.get("imageLicence")]
    disabled = [s for s in sources if not s.get("enabled")]
    print(f"{args.host}: {len(sources)} sources, {len(disabled)} disabled, {len(unreviewed)} unreviewed")
    for n in unreviewed:
        print(f"  UNREVIEWED  {n}")
    if unreviewed and not args.allow_unreviewed:
        sys.exit(
            "\nRefusing to enable while a source is unreviewed.\n"
            "Run scripts/apply-licence-review.py first. An unreviewed source displays everything,\n"
            "and the first import stores it -- which is what this order exists to prevent.\n"
            "Pass --allow-unreviewed if the absence is the known one."
        )
    if not disabled:
        print("\nEvery source is already enabled. Nothing to do.")
        return
    if args.host != LOCAL_HOST and not args.yes:
        sys.exit(f"\nRefusing to write to {args.host} without --yes. It would enable {len(disabled)} sources.")

    ok = 0
    for s in disabled:
        got = request(f"{args.host}/api/admin/event-sources/{s['slug']}", method="PATCH", body={"enabled": True})
        # Confirmed from the row that came back, not the status code (#814).
        if got.get("enabled"):
            ok += 1
        else:
            print(f"  NOT ENABLED {s['slug']}")
    print(f"\nEnabled {ok} of {len(disabled)}. The scheduler picks them up within a minute.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=LOCAL_HOST)
    ap.add_argument("--seed", default=SEED_FILE)
    ap.add_argument("--apply", action="store_true", help="actually create; omit for the drift report")
    ap.add_argument(
        "--yes",
        action="store_true",
        help="confirm writing to a host other than the local default. Required there, because a "
             "forwarded port looks exactly like a local one and the mistake is silent.",
    )
    ap.add_argument(
        "--enable",
        action="store_true",
        help="step 3: hand the already-seeded sources to the scheduler. Refuses while any source "
             "is unreviewed, because that is the order the licence gate depends on.",
    )
    ap.add_argument(
        "--allow-unreviewed",
        action="store_true",
        help="enable despite unreviewed sources. One is expected -- the venue whose site answers "
             "our user agent with 406, recorded in docs/licence-review/README.md section 6.",
    )
    args = ap.parse_args()

    if args.enable:
        enable(args)
        return

    steps = parse_seed(args.seed)
    venues = [(t, c) for kind, t, c in steps if kind == "venue"]
    sources = [t for kind, t, _ in steps if kind == "source"]
    print(f"{args.seed}: {len(venues)} venues, {len(sources)} event sources\n")

    try:
        have_venues = {v["name"]: v["id"] for v in fetch_all(args.host, "/api/admin/venues")}
        have_sources = {s["name"]: s["slug"] for s in fetch_all(args.host, "/api/admin/event-sources")}
    except (urllib.error.URLError, OSError) as e:
        sys.exit(f"Cannot reach the importer at {args.host}: {e}")

    want_venues = {json.loads(t)["name"]: (t, c) for t, c in venues}
    want_sources = {}
    for t in sources:
        name = json.loads(PLACEHOLDER.sub("0", t))["name"]
        want_sources[name] = t

    new_venues = [n for n in want_venues if n not in have_venues]
    new_sources = [n for n in want_sources if n not in have_sources]
    extra_venues = [n for n in have_venues if n not in want_venues]
    extra_sources = [n for n in have_sources if n not in want_sources]

    print(f"{args.host} holds {len(have_venues)} venues and {len(have_sources)} sources")
    print(f"  to create: {len(new_venues)} venues, {len(new_sources)} sources")
    for n in new_sources:
        print(f"    + {n}")
    for n in extra_venues:
        print(f"    ONLY ON TARGET (venue)  {n}")
    for n in extra_sources:
        print(f"    ONLY ON TARGET (source) {n}")
    if extra_venues or extra_sources:
        print("\n  Nothing is removed. A row the file does not carry is drift to explain, not to delete.")

    if not args.apply:
        print("\nDry run. Nothing was written. Re-run with --apply.")
        return
    if args.host != LOCAL_HOST and not args.yes:
        sys.exit(
            f"\nRefusing to write to {args.host} without --yes.\n"
            f"A forwarded port is indistinguishable from a local one, so confirm the target first.\n"
            f"It holds {len(have_sources)} sources and this run would create {len(new_sources)}."
        )

    ids = dict(have_venues)
    captured = {}
    for name, (text, cap) in want_venues.items():
        if name in have_venues:
            if cap:
                captured[cap] = have_venues[name]
            continue
        got = request(f"{args.host}/api/admin/venues", method="POST", body=json.loads(text))
        ids[name] = got["id"]
        if cap:
            captured[cap] = got["id"]
        print(f"  venue  {got['id']:>4}  {name}")

    made, failed = 0, []
    for name, text in want_sources.items():
        if name in have_sources:
            continue
        missing = [v for v in PLACEHOLDER.findall(text) if v not in captured]
        if missing:
            failed.append((name, f"unresolved venue id for {', '.join(missing)}"))
            continue
        body = json.loads(PLACEHOLDER.sub(lambda m: str(captured[m.group(1)]), text))
        # Overrides the file, which enables every source for a local run where that is what you
        # want. Here it would start 86 imports before step 2 could write a single verdict.
        body["enabled"] = False
        try:
            got = request(f"{args.host}/api/admin/event-sources", method="POST", body=body)
        except urllib.error.HTTPError as e:
            failed.append((name, f"{e.code} {e.read().decode()[:160]}"))
            continue
        made += 1
        print(f"  source {got.get('slug', '?'):<28} {name}")

    print(f"\nCreated {made} of {len(new_sources)} sources.")
    for name, why in failed:
        print(f"  FAILED {name}: {why}")
    print(
        "\nNo import was triggered. Apply the licence review before the first one:\n"
        f"  python3 scripts/apply-licence-review.py --host {args.host} --apply --yes"
    )
    if failed:
        sys.exit(f"{len(failed)} source(s) were not created.")


if __name__ == "__main__":
    main()
