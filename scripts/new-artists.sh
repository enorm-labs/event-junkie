#!/usr/bin/env bash
#
# new-artists.sh — every artist or promoter row created in a window, with the night that linked it and why it looks wrong.
#
# Usage:
#   scripts/new-artists.sh                          # production, the last 24 hours
#   scripts/new-artists.sh staging --hours 72
#   scripts/new-artists.sh --kind promoters         # the promoter rows instead
#
# Prints one tab-separated row per new artist, the flagged ones first:
#
#   flags          source  artist                   role       event
#   is-title,year  so36    Grossstadtwahnsinn 2026  HEADLINER  <date>-so36-grossstadtwahnsinn-2026-tag-1
#   -              lido    Sorry Girls              HEADLINER  <date>-lido-sorry-girls
#
# A flag is a lead, not a verdict: is-title (the name is the part of a "<name> | <tag>" title before the bar), pipe, year,
# day ("Tag 1", "Day 2"), keyword (festival, presents, tour, release, support, mit, feat. …), cut (ends in "…"),
# long (over 40 characters), unbilled (no event links it). The event shown is the earliest that bills the artist.
#
# --kind promoters prints the same shape, with `near` in place of `role`: an older promoter whose slug, without its
# hyphens, equals or starts the new one's (`d2mberlin` beside `d2m-berlin`). Its flags: is-title (the name is the night's
# title, or its part before the bar), is-venue (the name is the night's own venue), list (a comma, " + ", " / ", " & ", " und " or " and "),
# url, keyword (party, night, nacht, festival, tour, tickets, support, feat., vs), year, cut (also a trailing dash), long, near and unbilled.
# A fix lives in PromoterNormalizer.kt or the scraper; /artist-cleanup § Promoters has the verdicts.
#
# Needs the WireGuard tunnel up (scripts/ej.sh up <environment>). Reads only, over SSH as postgres, the way
# scripts/cluster-state.sh counts rows. /daily-check runs it in Step 5.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

ENVIRONMENT=production
HOURS=24
KIND=artists

while [ $# -gt 0 ]; do
    case "$1" in
        staging | production) ENVIRONMENT="$1" ;;
        --kind)
            shift
            KIND="${1:-}"
            [[ "$KIND" =~ ^(artists|promoters)$ ]] || {
                echo "new-artists: --kind takes artists or promoters" >&2
                exit 2
            }
            ;;
        --hours)
            shift
            HOURS="${1:-}"
            [[ "$HOURS" =~ ^[0-9]+$ ]] || {
                echo "new-artists: --hours needs a whole number" >&2
                exit 2
            }
            ;;
        *)
            echo "new-artists: unknown argument '$1'" >&2
            exit 2
            ;;
    esac
    shift
done

SSH_KEY="${EJ_SSH_KEY:-$HOME/.ssh/id_ed25519_hetzner}"
# Production keeps the database on its own node, reachable only by jump through k3s.
if [ "$ENVIRONMENT" = production ]; then
    DB_SSH=(ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=10 -J ops@10.10.0.1 ops@10.0.1.21)
else
    DB_SSH=(ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=10 ops@10.10.1.1)
fi

PSQL="LC_ALL=C.UTF-8 sudo -u postgres psql -tAX -v ON_ERROR_STOP=1 -F '	' -d events -v hours=$HOURS"

if [ "$KIND" = promoters ]; then
    printf 'flags\tsource\tpromoter\tnear\tevent\n'
    "${DB_SSH[@]}" "$PSQL" <<'SQL'
WITH fresh AS (
    SELECT p.id, p.name, p.slug, p.created_at
    FROM events.promoter p
    WHERE p.created_at > now() - make_interval(hours => :hours)
), linked AS (
    SELECT f.name, e.title, e.slug AS event_slug, s.slug AS source, v.name AS venue,
        (SELECT o.slug
         FROM events.promoter o
         WHERE o.id <> f.id
           AND o.created_at < f.created_at
           AND (starts_with(replace(f.slug, '-', ''), replace(o.slug, '-', ''))
                OR starts_with(replace(o.slug, '-', ''), replace(f.slug, '-', '')))
           AND length(replace(o.slug, '-', '')) >= 4
         ORDER BY length(o.slug) DESC
         LIMIT 1) AS near
    FROM fresh f
    LEFT JOIN LATERAL (
        SELECT ep.event_id
        FROM events.event_promoter ep
        JOIN events.event ev ON ev.id = ep.event_id
        WHERE ep.promoter_id = f.id
        ORDER BY ev.event_date, ev.id
        LIMIT 1
    ) first ON true
    LEFT JOIN events.event e ON e.id = first.event_id
    LEFT JOIN events.event_source s ON s.id = e.event_source_id
    LEFT JOIN events.venue v ON v.id = e.venue_id
), flagged AS (
    SELECT *,
        concat_ws(',',
            CASE WHEN lower(name) IN (lower(title), lower(split_part(title, ' | ', 1))) THEN 'is-title' END,
            CASE WHEN lower(name) = lower(venue) THEN 'is-venue' END,
            CASE WHEN name ~ '(,| \+ | / | & | und | and )' THEN 'list' END,
            CASE WHEN name ~* '(https?:|www\.|\.(de|com|net|org|fm|berlin|io)\M)' THEN 'url' END,
            CASE WHEN name ~* '(\mparty\M|\mnight\M|nacht\M|festival|\mtour\M|\mtickets?\M|\msupport\M|\mfeat\M|\mvs\M)' THEN 'keyword' END,
            CASE WHEN name ~ '\m(19|20)[0-9]{2}\M' THEN 'year' END,
            CASE WHEN name ~ '(…|\.\.\.|[-–])$' THEN 'cut' END,
            CASE WHEN length(name) > 40 THEN 'long' END,
            CASE WHEN near IS NOT NULL THEN 'near' END,
            CASE WHEN title IS NULL THEN 'unbilled' END
        ) AS flags
    FROM linked
)
SELECT coalesce(nullif(flags, ''), '-'), coalesce(source, '-'), name, coalesce(near, '-'), coalesce(event_slug, '-')
FROM flagged
ORDER BY flags = '', source, name;
SQL
    exit 0
fi

printf 'flags\tsource\tartist\trole\tevent\n'
"${DB_SSH[@]}" "$PSQL" <<'SQL'
WITH fresh AS (
    SELECT a.id, a.name
    FROM events.artist a
    WHERE a.created_at > now() - make_interval(hours => :hours)
), billed AS (
    SELECT f.name, first.role, e.title, e.slug AS event_slug, s.slug AS source
    FROM fresh f
    LEFT JOIN LATERAL (
        SELECT ea.event_id, ea.role
        FROM events.event_artist ea
        JOIN events.event ev ON ev.id = ea.event_id
        WHERE ea.artist_id = f.id
        ORDER BY ev.event_date, ev.id
        LIMIT 1
    ) first ON true
    LEFT JOIN events.event e ON e.id = first.event_id
    LEFT JOIN events.event_source s ON s.id = e.event_source_id
), flagged AS (
    SELECT *,
        concat_ws(',',
            CASE WHEN title LIKE '% | %' AND lower(name) = lower(split_part(title, ' | ', 1)) THEN 'is-title' END,
            CASE WHEN name LIKE '%|%' THEN 'pipe' END,
            CASE WHEN name ~ '\m(19|20)[0-9]{2}\M' THEN 'year' END,
            CASE WHEN name ~* '\m(tag|day)\s*[0-9]' THEN 'day' END,
            CASE WHEN name ~* '(festival|\mpresents\M|präsentiert|\mtour\M|\mrelease\M|aftershow|\mtickets?\M|\msupport\M|special guests?|\mmit\M|\mfeat\M)' THEN 'keyword' END,
            CASE WHEN name ~ '(…|\.\.\.)$' THEN 'cut' END,
            CASE WHEN length(name) > 40 THEN 'long' END,
            CASE WHEN title IS NULL THEN 'unbilled' END
        ) AS flags
    FROM billed
)
SELECT coalesce(nullif(flags, ''), '-'), coalesce(source, '-'), name, coalesce(role, '-'), coalesce(event_slug, '-')
FROM flagged
ORDER BY flags = '', source, name;
SQL
