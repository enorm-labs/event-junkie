#!/usr/bin/env python3
"""Propose which of our events belong to a festival, from the festival's published programme (#3275).

    scripts/festival-match.sh tag-der-clubkultur-2026 https://tagderclubkultur.berlin/programm/
    scripts/festival-match.sh tag-der-clubkultur-2026 https://tagderclubkultur.berlin/programm/ --env staging
    scripts/festival-match.sh tag-der-clubkultur-2026 https://tagderclubkultur.berlin/programm/ --html saved.html

Read-only. It reads the festival by its slug, every venue, and the events of each programme date from
the admin API, then fetches the programme page (or reads the saved copy `--html` names). An entry
matches on venue and date only: the entry's place equals our venue's name or street address, after
case, accents, punctuation and a trailing "Berlin" are dropped, and its date equals the event's date.

The TSV at `build/festival-match/<slug>.tsv` holds one row per proposed event. `MATCH` is the only
event at that venue on that date; `CHOOSE` is one of several, for the operator to pick. `UNMATCHED`
rows follow, one per entry, with the reason, and propose nothing. The operator sets the festival on
each chosen event in the admin editor (#3273); this script writes to no database and no API.

The programme has no common format, so each festival site needs a reader. The one here reads the
thumbnail grid of Tag der Clubkultur, whose dates carry no year: the festival's first day gives it.

Talks to the importer port `scripts/ej.sh up <env>` forwards, or to a local importer on 8081.
Exit 0 on success, 2 when it could not run.
"""

import argparse
import csv
import datetime
import html
import html.parser
import json
import pathlib
import re
import sys
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
PORTS = {"local": 8081, "staging": 18081, "production": 28081}
PAGE_SIZE = 100
MAX_PAGES = 200
EXIT_CANNOT_RUN = 2
USER_AGENT = "Mozilla/5.0 (compatible; EventJunkie/1.0; +https://github.com/enorm-labs/event-junkie)"
COLUMNS = [
    "verdict",
    "event_id",
    "event_title",
    "venue",
    "date",
    "start",
    "festival_now",
    "entry_title",
    "entry_place",
    "entry_date",
    "entry_url",
    "reason",
]
DATE = re.compile(r"\b(\d{1,2})\.(\d{1,2})\.?")


def parse_args(argv=None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Propose a festival's events from its programme; writes nothing.")
    parser.add_argument("slug", help="the festival's slug on the admin Festivals page")
    parser.add_argument("url", help="the festival's programme page")
    parser.add_argument(
        "--env", choices=sorted(PORTS), default="production", help="whose importer (default production)"
    )
    parser.add_argument(
        "--html", type=pathlib.Path, help="read this saved copy of the programme instead of fetching it"
    )
    parser.add_argument("--out", type=pathlib.Path, help="the TSV path (default build/festival-match/<slug>.tsv)")
    return parser.parse_args(argv)


class ThumbnailGrid(html.parser.HTMLParser):
    """Tag der Clubkultur: one `<a class="thumb">` per entry, its `p._eventinfo` lines the place, then the date."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.entries = []
        self.entry = None
        self.field = None
        self.text = []

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        classes = (attributes.get("class") or "").split()
        if tag == "a" and "thumb" in classes:
            self.entry = {"url": attributes.get("href") or "", "title": "", "info": []}
        elif self.entry is not None and tag == "span" and "title" in classes:
            self.field, self.text = "title", []
        elif self.entry is not None and tag == "p" and "_eventinfo" in classes:
            self.field, self.text = "info", []

    def handle_data(self, data):
        if self.field:
            self.text.append(data)

    def handle_endtag(self, tag):
        if self.field == "title" and tag == "span" or self.field == "info" and tag == "p":
            # WordPress escapes the entities twice, so one unescape is left to do.
            value = " ".join(html.unescape("".join(self.text)).split())
            if self.field == "title":
                self.entry["title"] = value
            elif value:
                self.entry["info"].append(value)
            self.field = None
        elif tag == "a" and self.entry is not None:
            self.entries.append(self.entry)
            self.entry = None


def entry_date(text: str, first_day: datetime.date, last_day: datetime.date):
    """The first `DD.MM.` in the text, in the year that puts it inside the festival, else the first day's year."""
    found = DATE.search(text)
    if not found:
        return None
    day, month = int(found.group(1)), int(found.group(2))
    for year in sorted({first_day.year, last_day.year}):
        try:
            candidate = datetime.date(year, month, day)
        except ValueError:
            return None
        if first_day <= candidate <= last_day:
            return candidate
    try:
        return datetime.date(first_day.year, month, day)
    except ValueError:
        return None


def read_programme(page: str, base_url: str, first_day: datetime.date, last_day: datetime.date) -> list:
    grid = ThumbnailGrid()
    grid.feed(page)
    entries = []
    for raw in grid.entries:
        info = raw["info"]
        dated = next((line for line in info[1:] if DATE.search(line)), info[1] if len(info) > 1 else "")
        entries.append(
            {
                "title": raw["title"],
                "place": info[0] if info else "",
                "date_text": dated,
                "date": entry_date(dated, first_day, last_day),
                "url": urllib.parse.urljoin(base_url, raw["url"]),
            }
        )
    return entries


def key(text) -> str:
    """Case, accents, punctuation and a trailing "Berlin" do not tell two places apart."""
    folded = unicodedata.normalize("NFKD", (text or "").casefold().replace("ß", "ss"))
    plain = "".join(c for c in folded if c.isalnum())
    plain = re.sub(r"(strasse|str)(?=\d|$)", "str", plain)
    return plain[: -len("berlin")] if plain.endswith("berlin") and len(plain) > len("berlin") else plain


def venue_index(venues: list) -> dict:
    index = {}
    for venue in venues:
        for value in (venue.get("name"), venue.get("address")):
            if key(value):
                index.setdefault(key(value), venue)
    return index


def event_row(verdict: str, event: dict, venue: dict, entry: dict) -> dict:
    return {
        "verdict": verdict,
        "event_id": event["id"],
        "event_title": event["title"],
        "venue": venue["name"],
        "date": event["eventDate"],
        "start": event.get("startTime") or "",
        "festival_now": "" if event.get("festivalId") is None else event["festivalId"],
        **entry_columns(entry),
        "reason": "",
    }


def entry_columns(entry: dict) -> dict:
    return {
        "entry_title": entry["title"],
        "entry_place": entry["place"],
        "entry_date": entry["date"].isoformat() if entry["date"] else entry["date_text"],
        "entry_url": entry["url"],
    }


def match(entries: list, venues: list, events_on, first_day: datetime.date, last_day: datetime.date) -> list:
    """Proposed rows first, in programme order, then one UNMATCHED row per entry that proposes nothing."""
    index = venue_index(venues)
    proposed, unmatched = [], []
    for entry in entries:
        venue = index.get(key(entry["place"]))
        reason = None
        if entry["date"] is None:
            reason = "no date"
        elif not first_day <= entry["date"] <= last_day:
            reason = "outside the festival's days"
        elif venue is None:
            reason = "unknown venue"
        if reason is None:
            events = [e for e in events_on(entry["date"]) if e["venueId"] == venue["id"]]
            if not events:
                reason = "no event at the venue that day"
            verdict = "MATCH" if len(events) == 1 else "CHOOSE"
            proposed += [event_row(verdict, e, venue, entry) for e in sorted(events, key=lambda e: e["id"])]
        if reason:
            unmatched.append(
                {**dict.fromkeys(COLUMNS, ""), "verdict": "UNMATCHED", **entry_columns(entry), "reason": reason}
            )
    return proposed + unmatched


def write_tsv(rows: list, path: pathlib.Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=COLUMNS, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def get_json(url: str):
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def pages(get, url: str) -> list:
    rows, page = [], 0
    while page < MAX_PAGES:
        body = get(f"{url}{'&' if '?' in url else '?'}size={PAGE_SIZE}&page={page}")
        rows += body["content"]
        page += 1
        if page >= body["totalPages"]:
            return rows
    raise RuntimeError(f"{url} has more than {MAX_PAGES} pages")


def fetch_page(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode(response.headers.get_content_charset() or "utf-8", errors="replace")


def run(args, get=get_json, fetch=fetch_page) -> int:
    importer = f"http://localhost:{PORTS[args.env]}"
    try:
        festivals = pages(get, f"{importer}/api/admin/festivals")
    except (urllib.error.URLError, OSError) as error:
        hint = "start the importer" if args.env == "local" else f"run scripts/ej.sh up {args.env}"
        print(f"festival-match: the {args.env} importer does not answer ({error}) — {hint}", file=sys.stderr)
        return EXIT_CANNOT_RUN
    festival = next((f for f in festivals if f["slug"] == args.slug), None)
    if festival is None:
        print(f"festival-match: no festival {args.slug} on {args.env}", file=sys.stderr)
        return EXIT_CANNOT_RUN
    first_day = datetime.date.fromisoformat(festival["firstDay"])
    last_day = datetime.date.fromisoformat(festival["lastDay"])

    page = args.html.read_text(encoding="utf-8") if args.html else fetch(args.url)
    entries = read_programme(page, args.url, first_day, last_day)
    if not entries:
        print(
            f"festival-match: no programme entry read from {args.html or args.url}; it needs a reader", file=sys.stderr
        )
        return EXIT_CANNOT_RUN

    venues = pages(get, f"{importer}/api/admin/venues")
    cache = {}

    def events_on(day):
        if day not in cache:
            cache[day] = pages(get, f"{importer}/api/admin/events?date={day.isoformat()}")
        return cache[day]

    rows = match(entries, venues, events_on, first_day, last_day)
    out = args.out or REPO / "build" / "festival-match" / f"{args.slug}.tsv"
    write_tsv(rows, out)
    counts = {v: sum(r["verdict"] == v for r in rows) for v in ("MATCH", "CHOOSE", "UNMATCHED")}
    print(
        f"festival-match: {len(entries)} entries, {counts['MATCH']} MATCH, {counts['CHOOSE']} CHOOSE, "
        f"{counts['UNMATCHED']} UNMATCHED -> {out}"
    )
    return 0


def main() -> int:
    return run(parse_args())


if __name__ == "__main__":
    sys.exit(main())
