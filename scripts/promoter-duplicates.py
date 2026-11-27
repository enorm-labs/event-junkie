#!/usr/bin/env python3
"""Find promoter rows that are probably one promoter (#328).

Read-only. Reads every promoter from the public BFF API, groups the ones whose names collapse
onto each other, and prints one table per group with the event count behind each row. It decides
nothing: the promoter normalizer's own KDoc says why a merge is a person's call, because a wrong
`NAME_CORRECTIONS` entry collapses two real promoters silently.

    python3 scripts/promoter-duplicates.py                        # against a port-forwarded staging BFF
    python3 scripts/promoter-duplicates.py --host http://localhost:8080
    python3 scripts/promoter-duplicates.py --tsv > build/promoter-duplicates.tsv
    python3 scripts/promoter-duplicates.py --no-counts            # skip the per-row event query
    python3 scripts/promoter-duplicates.py --unreviewed           # only rows nobody reviewed, via the admin API

Five signals put two rows in one group, and each row of the output says which one fired:

- `key`     the names are equal once lowercased and stripped of everything but letters and digits,
            which is the lookup key `PromoterNormalizer.NAME_CORRECTIONS` already uses;
- `strip`   they are equal after the trailing descriptor words the normalizer removes, plus the ones
            it does not yet (`Veranstaltungs`, `Konzertproduktionen`, `International`, ...);
- `edit`    the keys are within two edits of each other (`Rausgegangen` / `Rausgeganger`);
- `prefix`  one key is the start of the other (`Trinity` / `Trinity Music`);
- `suffix`  one key is the end of the other, which is a leading descriptor the normalizer never
            strips (`Schoneberg` / `Konzertbüro Schoneberg`).

`edit`, `prefix` and `suffix` are the noisy ones, and the table says so per row; `key` and `strip` are the
ones a `NAME_CORRECTIONS` or `STRIP_WORDS` entry can fix on the importer side. A `&`-joined
co-billing ("puschen & little league shows") is reported separately, because the fix for it is a
scraper split rather than a merge.

The event count is one request per promoter, from `/api/events?promoter=<slug>&from=2000-01-01`,
which is the number of events that would move in a merge. `--no-counts` skips it.

`--unreviewed` reads the promoters from the importer's admin API instead, with `reviewed=false`
(#1336): the rows an import minted since a person last went through the table, which is the
weekly list. A group then needs at least one unreviewed member to be reported.

Standard library only, and no key: the BFF is unauthenticated.
"""

import argparse
import itertools
import json
import pathlib
import re
import sys
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from admin_api import pages  # noqa: E402

LOCAL_HOST = "http://localhost:18080"
ADMIN_HOST = "http://localhost:18081"
AGENT = "event-junkie/1.0 (https://github.com/enorm-labs/event-junkie)"
MAX_PAGES = 100
MAX_EDITS = 2
# A key this short matches too much by `edit` and `prefix`: "act" is one edit from "atok".
MIN_FUZZY_KEY = 5

# The trailing words `PromoterNormalizer.STRIP_WORDS` removes, plus the ones the staging data shows
# it should: a legal-form or descriptor word that is the only difference between two rows.
STRIP_WORDS = {
    "gmbh", "mbh", "ug", "gbr", "kg", "ohg", "ag", "ev", "ou", "oü", "ltd", "llc", "inc", "co", "sl", "spa",
    "concert", "concerts", "konzert", "konzerte", "music", "musik", "events", "event", "booking", "agency",
    "agentur", "promotion", "promotions", "entertainment", "live", "records", "production", "productions",
    "prsnt", "prsnts", "presenting", "präsentiert", "präsentieren",
    # Not stripped by the normalizer today (#328).
    "veranstaltungs", "veranstaltungsgmbh", "konzertproduktionen", "kulturproduktionen", "konzertagentur",
    "konzertdirektion", "international", "einzelunternehmer", "berlin", "de", "magazine", "management",
}  # fmt: skip

NON_WORD = re.compile(r"[^a-z0-9äöüß]")
# "&", "+" and a comma, and not "and" / "und": "Mansions and Millions" is one label.
CO_BILLING = re.compile(r"\s[&+]\s|,\s")


def fold(name):
    """The normalizer's lookup key: lowercase, letters and digits only, diacritics kept as-is."""
    return NON_WORD.sub("", name.lower())


def ascii_fold(name):
    """`fold`, with diacritics removed too, so "Känguruh" and "Kaenguruh" meet ("ae" is not "ä")."""
    lowered = name.lower()
    for umlaut, spelled in (("ä", "ae"), ("ö", "oe"), ("ü", "ue"), ("ß", "ss")):
        lowered = lowered.replace(umlaut, spelled)
    plain = unicodedata.normalize("NFKD", lowered).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]", "", plain)


def stripped(name):
    """`ascii_fold` of the name with its trailing descriptor words removed, never below one word."""
    tokens = [t for t in re.split(r"\s+", name.strip()) if t]
    while len(tokens) > 1 and re.sub(r"[^a-z0-9äöüß]", "", tokens[-1].lower()) in STRIP_WORDS | {""}:
        tokens.pop()
    return ascii_fold(" ".join(tokens))


def edits(a, b):
    """Levenshtein distance, capped: returns MAX_EDITS + 1 as soon as it is exceeded."""
    if abs(len(a) - len(b)) > MAX_EDITS:
        return MAX_EDITS + 1
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb)))
        if min(current) > MAX_EDITS:
            return MAX_EDITS + 1
        previous = current
    return previous[-1]


def get_json(host, path, query):
    return get_url(f"{host}{path}?{urllib.parse.urlencode(query)}")


def get_url(url):
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def fetch_promoters(host, path="/api/promoters", query=None):
    url = f"{host}{path}?{urllib.parse.urlencode(query)}" if query else f"{host}{path}"
    return pages(get_url, url, max_pages=MAX_PAGES)


def event_count(host, slug):
    body = get_json(host, "/api/events", {"promoter": slug, "size": 1, "from": "2000-01-01"})
    return body.get("totalElements", 0)


class Groups:
    """Union-find over promoter ids, remembering which signal joined each pair."""

    def __init__(self, ids):
        self.parent = {i: i for i in ids}
        self.reasons = {}

    def find(self, i):
        while self.parent[i] != i:
            self.parent[i] = self.parent[self.parent[i]]
            i = self.parent[i]
        return i

    def join(self, a, b, reason):
        self.reasons.setdefault(frozenset((a, b)), reason)
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.parent[max(ra, rb)] = min(ra, rb)

    def members(self):
        by_root = {}
        for i in self.parent:
            by_root.setdefault(self.find(i), []).append(i)
        return [sorted(ids) for ids in by_root.values() if len(ids) > 1]


def group(promoters):
    groups = Groups([p["id"] for p in promoters])
    keyed = [(p, fold(p["name"]), ascii_fold(p["name"]), stripped(p["name"])) for p in promoters]
    for (pa, ka, aa, sa), (pb, kb, ab, sb) in itertools.combinations(keyed, 2):
        if ka == kb or aa == ab:
            groups.join(pa["id"], pb["id"], "key")
        elif sa == sb:
            groups.join(pa["id"], pb["id"], "strip")
        elif len(sa) >= MIN_FUZZY_KEY and len(sb) >= MIN_FUZZY_KEY:
            if sa.startswith(sb) or sb.startswith(sa):
                groups.join(pa["id"], pb["id"], "prefix")
            elif sa.endswith(sb) or sb.endswith(sa):
                groups.join(pa["id"], pb["id"], "suffix")
            elif edits(sa, sb) <= MAX_EDITS:
                groups.join(pa["id"], pb["id"], "edit")
    return groups


def reason_for(groups, pid, ids):
    """The strongest signal that ties [pid] to any other member: key > strip > prefix > suffix > edit."""
    order = {"key": 0, "strip": 1, "prefix": 2, "suffix": 3, "edit": 4}
    found = [groups.reasons.get(frozenset((pid, other))) for other in ids if other != pid]
    found = [r for r in found if r]
    return min(found, key=order.get) if found else "-"


def main():
    parser = argparse.ArgumentParser(description="Find promoter rows that are probably one promoter.")
    parser.add_argument("--host", default=LOCAL_HOST, help=f"public BFF API (default {LOCAL_HOST})")
    parser.add_argument("--tsv", action="store_true", help="tab-separated rows instead of tables")
    parser.add_argument("--no-counts", action="store_true", help="skip the per-promoter event count")
    parser.add_argument("--unreviewed", action="store_true", help="only groups with a row nobody reviewed yet")
    parser.add_argument(
        "--admin-host", default=ADMIN_HOST, help=f"importer admin API, for --unreviewed (default {ADMIN_HOST})"
    )
    args = parser.parse_args()

    try:
        promoters = fetch_promoters(args.host)
        unreviewed = None
        if args.unreviewed:
            new_rows = fetch_promoters(args.admin_host, "/api/admin/promoters", {"reviewed": "false"})
            unreviewed = {p["id"] for p in new_rows}
    except (urllib.error.URLError, OSError) as error:
        print(f"cannot reach the API: {error}", file=sys.stderr)
        return 1
    by_id = {p["id"]: p for p in promoters}
    groups = group(promoters)
    members = sorted(groups.members(), key=lambda ids: by_id[ids[0]]["name"].lower())
    if unreviewed is not None:
        members = [ids for ids in members if any(pid in unreviewed for pid in ids)]

    counts = {}
    if not args.no_counts:
        for ids in members:
            for pid in ids:
                counts[pid] = event_count(args.host, by_id[pid]["slug"])

    co_billed = [p for p in promoters if CO_BILLING.search(p["name"])]

    if args.tsv:
        print("group\tsignal\tname\tslug\tevents")
        for number, ids in enumerate(members, 1):
            for pid in ids:
                p = by_id[pid]
                print(f"{number}\t{reason_for(groups, pid, ids)}\t{p['name']}\t{p['slug']}\t{counts.get(pid, '')}")
        for p in co_billed:
            print(f"co-billing\tsplit\t{p['name']}\t{p['slug']}\t{counts.get(p['id'], '')}")
        return 0

    print(f"{len(promoters)} promoters, {len(members)} groups, {sum(len(ids) for ids in members)} rows in them\n")
    for number, ids in enumerate(members, 1):
        print(f"group {number}")
        for pid in ids:
            p = by_id[pid]
            count = f"{counts[pid]:>4}" if pid in counts else "   ?"
            flag = "  new" if unreviewed is not None and pid in unreviewed else ""
            print(f"  {count}  {reason_for(groups, pid, ids):6}  {p['name']}  ({p['slug']}){flag}")
        print()
    if co_billed:
        print(f"{len(co_billed)} co-billings, one row for two or more promoters (a scraper split, not a merge):")
        for p in sorted(co_billed, key=lambda p: p["name"].lower()):
            print(f"  {p['name']}  ({p['slug']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
