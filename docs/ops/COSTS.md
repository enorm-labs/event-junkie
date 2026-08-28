# What this costs to run

Every recurring charge, where the number comes from, and which ones are guesses. Figures are **gross** (19% German VAT included), because that is what the
invoice says.

**Measured from the Hetzner API rather than from the price list.** See §Re-deriving at the bottom, because a costs page nobody can check goes stale silently
and confidently.

## The short answer

|                                                             | Per month         | Per year    | Source            |
| ----------------------------------------------------------- | ----------------- | ----------- | ----------------- |
| **Hetzner Cloud** — servers, backups, volumes, addresses    | **€33.21**        | €398.52     | Measured, the API |
| **Hetzner Webhosting S** + external domain — role mailboxes | €2.66             | €31.92      | Contracted        |
| **Postflex** — the imprint address                          | €3.33             | **€39.90**  | Contracted        |
| healthchecks.io                                             | €0.00 (free tier) | €0.00       | Free tier         |
| Hetzner Object Storage                                      | _to confirm_      |             | —                 |
| Domains (`event-junkie.de`, `event-junkie.com`)             | _to confirm_      |             | —                 |
| **Known total**                                             | **€39.20**        | **€470.34** |                   |

**The `Source` column is the point of this page.** _Measured_ means re-derivable from the API by the script at the bottom. _Contracted_ means a price agreed when the thing
was ordered. It is a fact, but one that lives on an order confirmation rather than in any system this repository can query. Re-check it against an
invoice once. The two `_to confirm_` rows are neither, and are marked rather than guessed.

**Postflex is billed annually and the monthly figure is derived**, which is why it is the one row where the year is bold. It is also the only line here that
buys a legal capability rather than compute. It is a _ladungsfähige Anschrift_ for § 5 DDG, without which the site cannot lawfully be published at all
([LEGAL.md](../LEGAL.md) §8.3, [#273](https://github.com/enorm-labs/event-junkie/issues/273)). At €39.90 it is 8% of the annual bill and the cheapest go-live
blocker to clear — no engineering, no dependency, just an order.

## Hetzner Cloud, line by line

| Resource                                | Type          | Monthly    | Note                                                                                  |
| --------------------------------------- | ------------- | ---------- | ------------------------------------------------------------------------------------- |
| `staging-k3s`                           | `cx33`        | €10.10     | 4 vCPU / 8 GB. No backups — staging is the environment that gets destroyed on purpose |
| `production-k3s`                        | `cx33`        | €10.10     | Same machine, different job                                                           |
| ↳ backups                               | 20%           | €2.02      | Hetzner's automated daily snapshots                                                   |
| `production-postgres`                   | `cx23`        | €6.53      | 2 vCPU / 4 GB, dedicated database node                                                |
| ↳ backups                               | 20%           | €1.31      |                                                                                       |
| `staging-pgdata`                        | Volume, 10 GB | €0.68      | `PGDATA`, so the database outlives the node (#460)                                    |
| `production-pgdata`                     | Volume, 10 GB | €0.68      |                                                                                       |
| Primary IPv4 × 3                        |               | €1.79      | €0.595 each. **IPv6 is free**, which is why every node has one                        |
| Networks, subnets, firewalls, DNS zones |               | €0.00      | Not billed                                                                            |
| **Total**                               |               | **€33.21** |                                                                                       |

**Two things worth noticing in that table.**

The **backup line is production-only and deliberate.** It is 20% of the server price for daily snapshots. The volume already survives a node
rebuild, and `wal-g` archives continuously to Object Storage. It is the third copy, not the first — [BACKUPS.md](BACKUPS.md) has the reasoning.

**Traffic is absent because it is included.** Every plan ships 20 TB, and traffic inside the `eu-central` network zone is free. That is why a node in `nbg1`
reaching a bucket in `fsn1` costs nothing, and why the buckets pin no server anywhere.

## What is not in the API, and therefore not measured here

**Object Storage is a separate subscription** and does not appear in `/v1/pricing`. What it stores today:

| Bucket                 | Objects    | Size         |
| ---------------------- | ---------- | ------------ |
| `event-junkie-o2`      | 94,791     | 689.4 MB     |
| `event-junkie-backups` | 114        | 46.6 MB      |
| `event-junkie-tfstate` | 3          | 0.0 MB       |
| **Total**              | **94,908** | **736.0 MB** |

Comfortably inside the included tier, so this is a flat line rather than a growing one — **for now**. The object count is the number to watch, not the size.
94,791 objects accumulated in about nine hours of observability, which is what #625 is about.

**Domains are at INWX, not Hetzner**, so they are invisible to everything else in this repository. `.de` and `.com` renew annually at the registrar's list price.

**The mailboxes and the imprint address are contracts, not resources.** Neither appears in any API. Webhosting S is €1.90/month, plus €0.76 for
`event-junkie.de` as an external domain. That domain is registered at INWX, so Hetzner charges to host mail for it ([EMAIL.md](EMAIL.md) §2). Postflex is
€39.90/year, billed annually. Both figures come from the order rather than from an invoice. Correct them the first time a real invoice disagrees, and do not
quietly promote them to "measured".

**Take both numbers off an invoice rather than a price list.** Neither is guessed here, on purpose. A costs page whose figures were plausible-but-invented is worse
than one with two gaps, because nobody knows which rows to trust.

## What changes the number

| Change                      | Effect                                                                                                                                 |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| **Go-live**                 | **Nothing.** Publishing DNS costs €0. The full production stack is already running and billed — that is what standing it up dark means |
| Observability on production | Roughly staging's shape again — no new servers, but memory pressure on the `cx33` is what would eventually force a bigger one          |
| `cx33` → `cx43`             | +€8.93/month for 8 vCPU / 16 GB. Currently out of stock in `nbg1`; `check-capacity.sh --probe` is how you find out                     |
| Returning to ARM            | **Costs more**, as of 2026-08-21: `cax21` + `cax11` is €19.61 against `cx33` + `cx23`'s €16.63                                         |
| A second production node    | +€6.53 and up. Nothing needs one; #460's volume already decouples the database from the node                                           |
| Object Storage past 1 TB    | The first line item that would grow on its own. #625's stream reduction is the lever                                                   |

**The cheapest thing here is the thing most likely to be cut first, and it should not be.** Staging is €10.10 of €33.21, under a third, and it is where the
destroy/apply cycle, the rebuild drill and every risky change get proven. The production apply hit three separate faults that staging had already taught us how
to read.

## Re-deriving these numbers

Do not trust this page. Regenerate it. Everything above except Object Storage and the domains comes from the API:

```sh
cd infra && direnv exec . python3 - <<'PY'
import json, os, subprocess
T = os.environ["HCLOUD_TOKEN"]
def get(p):
    return json.loads(subprocess.run(
        ["curl", "-sS", "-H", "Authorization: Bearer " + T,
         "https://api.hetzner.cloud/v1/" + p], capture_output=True, text=True).stdout)

pricing = get("pricing")["pricing"]
types = {t["name"]: t for t in get("server_types?per_page=100")["server_types"]}
ipv4 = float(next(p["price_monthly"]["gross"]
                  for e in pricing["primary_ips"] if e["type"] == "ipv4"
                  for p in e["prices"] if p["location"] == "nbg1"))
gb = float(pricing["volume"]["price_per_gb_month"]["gross"])

total = 0.0
for s in get("servers")["servers"]:
    loc = s["datacenter"]["location"]["name"] if s.get("datacenter") else "nbg1"
    price = float(next(p["price_monthly"]["gross"]
                       for p in types[s["server_type"]["name"]]["prices"]
                       if p["location"] == loc))
    backup = price * float(pricing["server_backup"]["percentage"]) / 100 if s.get("backup_window") else 0.0
    total += price + backup
    print("%-24s %-6s EUR %6.2f%s" % (s["name"], s["server_type"]["name"], price,
                                      "  + %.2f backups" % backup if backup else ""))
for v in get("volumes")["volumes"]:
    total += gb * v["size"]
    print("%-24s %2dGB   EUR %6.2f" % (v["name"], v["size"], gb * v["size"]))
n4 = sum(1 for ip in get("primary_ips")["primary_ips"] if ip["type"] == "ipv4")
total += n4 * ipv4
print("%-24s x%-5d EUR %6.2f" % ("primary IPv4", n4, n4 * ipv4))
print("\nTOTAL EUR %.2f / month, EUR %.2f / year" % (total, total * 12))
PY
```

Bucket usage, which the pricing API does not cover:

```sh
cd infra
for b in event-junkie-tfstate event-junkie-o2 event-junkie-backups event-junkie-images; do
    direnv exec . sh -c "aws s3 ls s3://$b --recursive --summarize" | tail -2 | tr '\n' ' ' \
      | awk -v b="$b" '{printf "%-24s %8s objects  %10.1f MB\n", b, $3, $6/1048576}'
done
```

## Where the comparison lives

Why this platform rather than a PaaS is [ADR-012](../adr/ADR-012_CLOUD_PLATFORM.md), which priced the same workload at **€28.80/month per GB-of-RAM container**
on Scalingo and $25 on Render. The whole stack here — three nodes, two databases, backups, object storage — costs about what one managed container would.

The sizing arithmetic, and why Flux rather than ArgoCD is what keeps this on 8 GB nodes, is [PLATFORM_SETUP.md](PLATFORM_SETUP.md) §1.
