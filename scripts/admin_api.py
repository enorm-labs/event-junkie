#!/usr/bin/env python3
"""The importer's admin API through the forward `scripts/ej.sh up <env>` opens: its URL, a GET, every page of a list.

Usage:
    import admin_api    # from a sibling script, after sys.path.insert(0, <scripts/>)

Imported, not run: run alone it prints this help. Standard library only, so a script that imports it stays one too.
"""

import argparse
import json
import time
import urllib.request

PORTS = {"staging": 18081, "production": 28081}
PAGE_SIZE = 100
MAX_PAGES = 200


def base_url(env: str) -> str:
    return f"http://localhost:{PORTS[env]}/api/admin"


def http_get(url: str):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


class PartialListing(SystemExit):
    """A listing read short of its `totalElements`, or past its page cap. Uncaught, it prints and exits 1."""


def pages(get, url: str, max_pages: int = MAX_PAGES, pause: float = 0.0) -> list:
    """Every row of a paged list, or `PartialListing`. `get` is the GET to call, so a test can pass a stub.

    The count is checked because a first page and a complete list look alike: see #810.
    """
    rows, page, separator = [], 0, "&" if "?" in url else "?"
    while True:
        body = get(f"{url}{separator}page={page}&size={PAGE_SIZE}")
        total = body["totalElements"]
        rows += body["content"]
        if not body["content"] or len(rows) >= total:
            break
        page += 1
        if page >= max_pages:
            raise PartialListing(f"Stopped after {max_pages} pages of {url}. The listing is not terminating.")
        time.sleep(pause)
    if len(rows) != total:
        raise PartialListing(f"Read {len(rows)} of {total} from {url}. Refusing to act on a partial listing.")
    return rows


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.parse_args()
    parser.print_help()
