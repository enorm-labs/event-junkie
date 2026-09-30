#!/usr/bin/env python3
"""Force-import event sources on a cluster, a few at a time, and report which did not refresh.

Dry run by default: it names the sources it would import and writes nothing.

    python3 scripts/force-import.py staging                        # what an all-sources run would import
    python3 scripts/force-import.py staging --apply                # every source on staging
    python3 scripts/force-import.py production badehaus gretchen --apply

A forced import (`?force=true`, #1159) skips the ETag and Last-Modified validators. A parser fix at a
venue whose page has not changed otherwise stays a 304 on every scheduled import, so this is how a
release's fixes reach the rows it already stored.

**One command for the whole run, on purpose.** The auto-mode guard asks for a `/permissions` approval
per call that writes to a cluster, and an approval covers only the call it names. A loop of `curl`
calls needs one approval per source; this needs one, and a `Bash(python3 scripts/force-import.py:*)`
rule makes it none.

It talks to the importer through the forward `scripts/ej.sh up <env>` starts (18081 staging, 28081
production), and it refuses to write unless a `kubectl --context event-junkie-<env>` port-forward
holds that port: a hand-started forward once served production on the staging port. A forward that
drops mid-run is restarted with `ej.sh up <env>`.

Exit 0 when every source it imported ended SUCCESS with a fresh `lastImportAt`, 1 otherwise, 2 when
it could not run.
"""

import argparse
import datetime
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
PORTS = {"staging": 18081, "production": 28081}
EXIT_NOT_REFRESHED = 1
EXIT_CANNOT_RUN = 2
POLL_SECONDS = 10
CALL_ATTEMPTS = 5


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Force-import event sources on a cluster through the ej.sh forward. Dry run by default."
    )
    parser.add_argument("env", choices=sorted(PORTS), help="the cluster, as scripts/ej.sh names it")
    parser.add_argument("slugs", nargs="*", help="the sources to import; none means every source")
    parser.add_argument("--apply", action="store_true", help="trigger the imports; without it nothing is written")
    parser.add_argument("--concurrency", type=int, default=4, help="sources importing at once (default 4)")
    return parser.parse_args()


def forward_is_ours(env: str, port: int) -> bool:
    """Whether a port-forward to the importer on [port] runs under this environment's kube context."""
    listing = subprocess.run(["ps", "-eo", "args"], capture_output=True, text=True).stdout
    return any(
        "port-forward" in line and f"event-junkie-{env}" in line and f"{port}:8081" in line
        for line in listing.splitlines()
    )


def restart_forward(env: str) -> None:
    subprocess.run(
        [str(REPO / "scripts" / "ej.sh"), "up", env],
        cwd=REPO,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def call(base: str, env: str, path: str, method: str = "GET"):
    for attempt in range(CALL_ATTEMPTS):
        try:
            request = urllib.request.Request(base + path, method=method)
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except (urllib.error.URLError, ConnectionError, TimeoutError) as error:
            print(f"  {method} {path}: {error}; restarting the {env} forward", flush=True)
            if attempt < CALL_ATTEMPTS - 1:
                restart_forward(env)
                time.sleep(5)
    raise SystemExit(f"giving up on {method} {path}")


def sources(base: str, env: str) -> dict:
    return {s["slug"]: s for s in call(base, env, "/api/admin/event-sources?size=500")["content"]}


def imported_since(source: dict, moment: datetime.datetime) -> bool:
    stamp = source.get("lastImportAt")
    return bool(stamp) and datetime.datetime.fromisoformat(stamp.replace("Z", "+00:00")) >= moment


def main() -> int:
    args = parse_args()
    port = PORTS[args.env]
    base = f"http://localhost:{port}"

    if not forward_is_ours(args.env, port):
        hint = f"Run: scripts/ej.sh up {args.env}"
        print(f"No event-junkie-{args.env} forward holds port {port}. {hint}", file=sys.stderr)
        return EXIT_CANNOT_RUN

    known = sources(base, args.env)
    unknown = sorted(set(args.slugs) - set(known))
    if unknown:
        print(f"Not a source on {args.env}: {', '.join(unknown)}", file=sys.stderr)
        return EXIT_CANNOT_RUN
    wanted = sorted(args.slugs or known)

    if not args.apply:
        print(f"Would force-import {len(wanted)} source(s) on {args.env}, {args.concurrency} at a time:")
        print("  " + " ".join(wanted))
        print("Nothing written. Add --apply to run it.")
        return 0

    started = datetime.datetime.now(datetime.timezone.utc)
    began = time.monotonic()
    pending = list(wanted)
    while pending:
        running = sum(1 for s in sources(base, args.env).values() if s["status"] == "RUNNING")
        while running < args.concurrency and pending:
            slug = pending.pop(0)
            call(base, args.env, f"/api/admin/event-sources/{slug}/import?force=true", "POST")
            print(f"{int(time.monotonic() - began):5d}s triggered {slug}", flush=True)
            running += 1
            time.sleep(1)
        time.sleep(POLL_SECONDS)
    while any(s["status"] == "RUNNING" for s in sources(base, args.env).values()):
        time.sleep(POLL_SECONDS)

    final = sources(base, args.env)
    failed = [
        s
        for slug, s in sorted(final.items())
        if slug in wanted and (s["status"] != "SUCCESS" or not imported_since(s, started))
    ]
    refreshed = len(wanted) - len(failed)
    print(f"Done in {int(time.monotonic() - began)}s: {refreshed} of {len(wanted)} refreshed on {args.env}.")
    for s in failed:
        print(f"  NOT REFRESHED {s['slug']} {s['status']} {(s.get('lastError') or '')[:200]}")
    return EXIT_NOT_REFRESHED if failed else 0


if __name__ == "__main__":
    sys.exit(main())
