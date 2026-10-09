#!/usr/bin/env python3
"""Register the venues and event sources from http/importer/seed/venues/ on a cluster (#876).

Dry run by default. The dry run is also the drift report: it names what the target is missing and
what it holds that the file does not, so staging and production can be compared without writing.

    python3 scripts/seed-sources.py                                    # compare, write nothing
    python3 scripts/seed-sources.py --host http://localhost:18081      # compare a forwarded cluster
    python3 scripts/seed-sources.py --host http://localhost:18081 --apply --yes

The venue files are the source of truth (one per venue, #2824), and this reads them directly rather
than carrying a second copy. A second copy is the drift #876 is about.

**Sources are created disabled, and that is not caution.** A source with no import history is always
due, and the scheduler ticks every 60 seconds, so an enabled source is imported about a minute after
it is created — before any licence review could be applied. Two venues forbid their descriptions and
images, `NULL` displays, and V007 has already run in production, so nothing would clear what that
first import stored. The three steps are therefore ordered, and `--enable` refuses to run out of
order:

    python3 scripts/seed-sources.py        --host <host> --apply --yes   # 1. create, disabled
    python3 scripts/apply-licence-review.py --host <host> --apply --yes  # 2. the verdicts (#283)
    python3 scripts/seed-sources.py        --host <host> --enable --yes  # 3. let them import

A venue that closes for good (ADR-046) keeps its source and stops importing:

    python3 scripts/seed-sources.py --host <host> --disable <slug> --yes

**This never triggers an import.** The generated `dev-seed.http` does, for a local run; this does not.
Step 3 hands the sources to the scheduler, which picks them up on its next tick.

**`--site` is the same comparison from outside the cluster**, against the public site rather than the
admin API, so a workflow can make it without a port-forward and without credentials (#1782):

    python3 scripts/seed-sources.py --site https://event-junkie.de

It reads `/api/venues`, which lists every venue row and filters nothing, and it writes nothing at
all. Its exit code is the report: 0 for no drift, 1 for drift, 2 when the comparison could not be
made. `node-pin-reminder.yml` already reads that contract from `upstream-node-pins.sh`.

**`--site` compares venues, not sources**, because no source listing is public. A venue created
without its source therefore reads as seeded here. The realistic failure creates neither: a source
is created against its venue, and ROSA and Sisyphos were missing as pairs on both clusters.
"""

import argparse
import json
import pathlib
import sys
import urllib.error
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import seed_venues  # noqa: E402  (the shared reader, beside this script)

SEED_DIR = str(seed_venues.SEED_DIR.relative_to(seed_venues.REPO))
LOCAL_HOST = "http://localhost:8081"
# `--site` only. 1 is an answer -- the file and the site disagree. Anything above it means no answer
# was produced, which a scheduled check must not report as health.
EXIT_DRIFT = 1
EXIT_CANNOT_CHECK = 2
PAGE_SIZE = 100
MAX_PAGES = 100


def read_seed(path):
    """[(venue body, [source bodies])] from the venue files. A source body has no venueId yet."""
    return [(v["venue"], v["sources"]) for v in seed_venues.load(path)]


class ListingError(Exception):
    """A listing could not be read completely.

    Raised rather than exited, because the right exit code depends on the caller: writing on a
    partial listing is a failure, and so is reporting one as no drift, but they are not the same
    failure and `--site` has to tell them apart.
    """


def fetch_all(host, path):
    """Read every page, and check the count against the total the API reports.

    The listings carry `totalElements` since #810, so a partial read is detectable rather than
    indistinguishable from a small table."""
    out, page, total = [], 0, 0
    while True:
        body = request(f"{host}{path}?page={page}&size={PAGE_SIZE}&sort=name,asc")
        total = body["totalElements"]
        out.extend(body["content"])
        if len(out) >= total:
            break
        page += 1
        if page > MAX_PAGES:
            raise ListingError(f"Stopped after {MAX_PAGES} pages of {path}. The listing is not terminating.")
    if len(out) != total:
        raise ListingError(f"Read {len(out)} of {total} from {path}. Refusing to act on a partial listing.")
    return out


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
    except ListingError as e:
        sys.exit(str(e))
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


def disable(args):
    """Stop the scheduler importing the named sources, for a venue that closed (ADR-046).

    The rows and their past events stay; only `enabled` changes. A slug the cluster does not hold is
    reported and fails the run, because a typo here would leave a closed venue importing.
    """
    if args.host != LOCAL_HOST and not args.yes:
        sys.exit(f"Refusing to write to {args.host} without --yes. It would disable {', '.join(args.disable)}.")
    failed = []
    for slug in args.disable:
        try:
            got = request(f"{args.host}/api/admin/event-sources/{slug}", method="PATCH", body={"enabled": False})
        except urllib.error.HTTPError as e:
            failed.append(f"{slug}: {e.code}")
            continue
        except (urllib.error.URLError, OSError) as e:
            sys.exit(f"Cannot reach the importer at {args.host}: {e}")
        # Confirmed from the row that came back, not the status code (#814).
        if got.get("enabled") is False:
            print(f"  disabled  {slug}")
        else:
            failed.append(f"{slug}: still enabled")
    for f in failed:
        print(f"  NOT DISABLED {f}")
    if failed:
        sys.exit(f"{len(failed)} of {len(args.disable)} source(s) were not disabled.")
    print(f"\nDisabled {len(args.disable)} of {len(args.disable)}. Their rows and past events stay.")


def compare_site(args):
    """Compare the seed file with the public site, and report through the exit code (#1782).

    The failure this exists for is silent by construction. A source is a row created through the
    admin API, never by a migration, so a source added to the seed file and never seeded is simply
    absent: no `FAILED` status, no `lastError`, and no metric, because
    `importer.source.events_future` publishes its explicit zero only for the sources a cluster holds
    (#618). ROSA and Sisyphos were complete, documented and released, and reached no cluster for as
    long as nobody thought to run the dry run.

    This reads the public API instead of the admin API, so the check needs no port-forward and no
    credentials, and a scheduled workflow can make it. Staging is not on the public internet, so
    this covers production alone.
    """
    site = args.site.rstrip("/")
    try:
        seed = read_seed(args.seed)
    except (OSError, ValueError, KeyError) as e:
        print(f"Cannot read {args.seed}: {e}", file=sys.stderr)
        return EXIT_CANNOT_CHECK

    want = {venue["name"] for venue, _ in seed}
    try:
        have = {v["name"] for v in fetch_all(site, "/api/venues")}
    except ListingError as e:
        print(f"{e}", file=sys.stderr)
        return EXIT_CANNOT_CHECK
    except (urllib.error.URLError, OSError, ValueError, KeyError) as e:
        print(f"Cannot read the venues at {site}: {e}", file=sys.stderr)
        return EXIT_CANNOT_CHECK

    missing = sorted(want - have)
    extra = sorted(have - want)
    print(f"{args.seed}: {len(want)} venues")
    print(f"{site} serves {len(have)} venues\n")
    for n in missing:
        print(f"  MISSING FROM THE SITE  {n}")
    for n in extra:
        print(f"  ONLY ON THE SITE       {n}")
    if not missing and not extra:
        print("  No drift. Every venue in the seed file is served.")
        return 0
    # A venue the file does not carry is drift to explain as well: it was created by hand, or the
    # file lost a row. Neither is removed here, by the same rule the admin dry run states.
    print(f"\n{len(missing)} venue(s) in the seed file are not served, {len(extra)} served venue(s) are not in it.")
    return EXIT_DRIFT


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=LOCAL_HOST)
    ap.add_argument("--seed", default=SEED_DIR, help="the directory of venue files")
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
        "--disable",
        nargs="+",
        metavar="SLUG",
        help="stop importing these sources, for a venue that closed for good (ADR-046). Keeps the "
        "rows and their past events.",
    )
    ap.add_argument(
        "--site",
        help="compare the seed file with the public site at this origin and report through the exit "
        "code: 0 no drift, 1 drift, 2 the comparison could not be made. Reads the public API, "
        "writes nothing, and needs no port-forward.",
    )
    ap.add_argument(
        "--allow-unreviewed",
        action="store_true",
        help="enable despite unreviewed sources. One is expected -- the venue whose site answers "
        "our user agent with 406, recorded in docs/licence-review/README.md section 6.",
    )
    args = ap.parse_args()

    if args.site:
        # Refused rather than ignored: --site names a public origin, and the write flags name a host
        # that is an admin API. A run that quietly dropped one of the two would be reporting on a
        # different target than the one it was given.
        if args.apply or args.enable or args.disable:
            ap.error("--site is read-only and cannot be combined with --apply, --enable or --disable")
        sys.exit(compare_site(args))

    if args.enable and args.disable:
        ap.error("--enable and --disable cannot be combined")
    if args.disable:
        disable(args)
        return

    if args.enable:
        enable(args)
        return

    try:
        seed = read_seed(args.seed)
    except (OSError, ValueError, KeyError) as e:
        sys.exit(f"Cannot read {args.seed}: {e}")
    want_venues = {venue["name"]: venue for venue, _ in seed}
    want_sources = {source["name"]: (source, venue["name"]) for venue, sources in seed for source in sources}
    print(f"{args.seed}: {len(want_venues)} venues, {len(want_sources)} event sources\n")

    try:
        have_venues = {v["name"]: v["id"] for v in fetch_all(args.host, "/api/admin/venues")}
        have_sources = {s["name"]: s["slug"] for s in fetch_all(args.host, "/api/admin/event-sources")}
    except ListingError as e:
        sys.exit(str(e))
    except (urllib.error.URLError, OSError) as e:
        sys.exit(f"Cannot reach the importer at {args.host}: {e}")

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
    for name, venue in want_venues.items():
        if name in have_venues:
            continue
        got = request(f"{args.host}/api/admin/venues", method="POST", body=venue)
        ids[name] = got["id"]
        print(f"  venue  {got['id']:>4}  {name}")

    made, failed = 0, []
    for name, (source, venue_name) in want_sources.items():
        if name in have_sources:
            continue
        body = {"venueId": ids[venue_name], **source}
        # Overrides the venue file, which enables every source for a local run where that is what you
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
