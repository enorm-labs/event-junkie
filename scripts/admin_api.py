#!/usr/bin/env python3
"""The importer's admin API through the forward `scripts/ej.sh up <env>` opens: its URL, a GET, every page of a list.

Usage:
    import admin_api    # from a sibling script, after sys.path.insert(0, <scripts/>)

Imported, not run: run alone it prints this help. Standard library only, so a script that imports it stays one too.
"""

import argparse
import json
import urllib.request

PORTS = {"staging": 18081, "production": 28081}
PAGE_SIZE = 100
MAX_PAGES = 200


def base_url(env: str) -> str:
    return f"http://localhost:{PORTS[env]}/api/admin"


def http_get(url: str):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def pages(get, url: str, max_pages: int = MAX_PAGES) -> list:
    """Every row of a paged admin list. `get` is the GET to call, so a test can pass a stub."""
    rows, page = [], 0
    while page < max_pages:
        body = get(f"{url}{'&' if '?' in url else '?'}size={PAGE_SIZE}&page={page}")
        rows += body["content"]
        page += 1
        if page >= body["totalPages"]:
            return rows
    raise RuntimeError(f"{url} has more than {max_pages} pages")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.parse_args()
    parser.print_help()
