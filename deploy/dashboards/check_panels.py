#!/usr/bin/env python3
"""Run every panel query in a dashboard against the live instance and report which returned rows.

**A dashboard whose panels have never returned a row is a hypothesis, not a dashboard.** Three of
the nine panels here were wrong on the first attempt and every one of them failed silently — a
blank panel and a negative number, not an error. This exists so "it renders" is a checked claim.

Invoked by apply.sh --check; it runs on the node, because OpenObserve has no ingress route.

    python3 check_panels.py "$AUTH" "$SVC" /tmp/ej-dashboard.json

**Dashboard variables are resolved against the instance first, not substituted from the file.** A
`query_values` variable stores an empty `value`, so sending the query text verbatim asks for
`$namespace` literally and every panel reports NO DATA — measured on the imported upstream
dashboard: 0 of 41, where the same queries return 40 of 40 with the variable filled in (#971).
Resolving it here also makes the variable itself a checked claim, and it is the single point of
failure on a dashboard that filters every panel through one: if it resolves to nothing, everything
below is blank for a reason that has nothing to do with the panels.
"""
import json
import subprocess
import sys
import time
import urllib.parse

auth, svc = sys.argv[1], sys.argv[2]
path = sys.argv[3] if len(sys.argv) > 3 else "/tmp/ej-dashboard.json"

end = int(time.time())
start = end - 6 * 3600

with open(path) as f:
    dash = json.load(f)
panels = [p for tab in dash["tabs"] for p in tab["panels"]]


def get(url):
    return subprocess.run(
        ["curl", "-sS", "-m", "60", "-H", "Authorization: " + auth, url],
        capture_output=True, text=True,
    ).stdout


def resolve_variables():
    """Each `query_values` variable, resolved to its first value on this instance.

    Returns (values, failures). A multi-select variable takes its first value: this asks whether the
    panels return anything, not whether every combination does.
    """
    values, failures = {}, []
    for variable in (dash.get("variables") or {}).get("list") or []:
        name = variable.get("name")
        data = variable.get("query_data") or {}
        stream, field = data.get("stream"), data.get("field")
        if not (name and stream and field):
            continue
        url = "http://%s:5080/api/default/%s/_values?%s" % (svc, stream, urllib.parse.urlencode({
            "fields": field, "type": data.get("stream_type", "metrics"),
            "start_time": start * 1_000_000, "end_time": end * 1_000_000, "size": 10,
        }))
        try:
            hits = json.loads(get(url)).get("hits") or []
        except ValueError:
            failures.append("$%s did not resolve: %s/%s returned non-JSON" % (name, stream, field))
            continue
        found = [v["zo_sql_key"] for h in hits for v in h.get("values") or [] if v.get("zo_sql_key")]
        if not found:
            failures.append("$%s resolved to nothing from %s.%s" % (name, stream, field))
            continue
        values[name] = found[0]
    return values, failures


variables, failures = resolve_variables()
for name, value in sorted(variables.items()):
    print("variable $%s = %s" % (name, value))
for f in failures:
    print("%-34s %s" % ("", f))


def fill(query):
    """Substitute resolved variables. A markdown panel carries a query entry whose text is null.

    **Longest name first.** One variable's name can be a prefix of another's — `$k8s_cluster` and
    `$k8s_cluster_name` are both real — and substituting in dictionary order turns the longer into
    `production_name`, which matches nothing and reads as a dashboard with no data (#974).
    """
    query = query or ""
    for name in sorted(variables, key=len, reverse=True):
        query = query.replace("$" + name, variables[name])
    return query


def run_sql(query, stream_type):
    """A SQL panel goes to the search API, not the Prometheus one.

    Returns (rows, error). The Prometheus endpoint answers 200 with an empty result for SQL, so
    without this every panel on a SQL dashboard reports NO DATA and the check is worse than absent.
    """
    body = json.dumps({"query": {
        "sql": query, "start_time": start * 1_000_000, "end_time": end * 1_000_000,
        "from": 0, "size": 1,
    }})
    out = subprocess.run(
        ["curl", "-sS", "-m", "60", "-H", "Authorization: " + auth,
         "-H", "Content-Type: application/json", "-X", "POST",
         "http://%s:5080/api/default/_search?type=%s" % (svc, stream_type or "logs"),
         "--data-binary", body],
        capture_output=True, text=True,
    ).stdout
    try:
        d = json.loads(out)
    except ValueError:
        return None, "PARSE-FAIL %s" % out[:90]
    if "hits" not in d:
        return None, "ERROR %s" % str(d.get("message") or d)[:100]
    return d["hits"], None


for p in panels:
    # Every query, not just the first: a two-query panel whose second query is broken looks fine
    # until you notice one line missing from the chart.
    for n, query in enumerate(p["queries"]):
        q = fill(query["query"])
        if not q.strip():
            continue
        # The title, not the id: an upstream dashboard's ids are `Panel_ID2939910`, which names
        # nothing a reader can act on.
        name = p.get("title") or p["id"]
        label = name if len(p["queries"]) == 1 else "%s[%d]" % (name, n)
        if p.get("queryType") == "sql":
            rows, err = run_sql(q, (query.get("fields") or {}).get("stream_type"))
            if err:
                print("%-40s %s" % (label[:40], err))
                failures.append(label)
                continue
            print("%-40s rows=%-4d%s" % (label[:40], len(rows), "" if rows else "   <-- NO DATA"))
            if not rows:
                failures.append(label)
            continue
        url = "http://%s:5080/api/default/prometheus/api/v1/query_range?%s" % (
            svc,
            urllib.parse.urlencode({"query": q, "start": start, "end": end, "step": 300}),
        )
        out = get(url)
        try:
            d = json.loads(out)
        except ValueError:
            print("%-40s PARSE-FAIL %s" % (label[:40], out[:100]))
            failures.append(label)
            continue
        if d.get("status") != "success":
            print("%-40s ERROR %s" % (label[:40], str(d.get("error"))[:110]))
            failures.append(label)
            continue
        r = d["data"]["result"]
        # A spot-check, not a summary: the value is the newest point of the FIRST series only,
        # while series= counts them all. Labelled last[0] so a multi-series panel cannot be read
        # as if one number described every line on the chart. The pass/fail test below is
        # "did any series come back", which is what this script is actually for.
        sample = r[0]["values"][-1][1] if r and r[0].get("values") else None
        print("%-40s series=%-4d last[0]=%-19s%s" % (label[:40], len(r), sample, "" if r else "   <-- NO DATA"))
        if not r:
            failures.append(label)

total = sum(1 for p in panels for q in p["queries"] if fill(q["query"]).strip())
print("\n%d/%d queries returned data" % (total - len(failures), total))
sys.exit(1 if failures else 0)
