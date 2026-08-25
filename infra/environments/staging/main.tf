module "environment" {
  source = "../../modules/environment"

  environment = "staging"
  # fsn1, nbg1 and hel1 are interchangeable on cost (free traffic within the eu-central zone) and
  # all three offer ARM, so this is the line to change when capacity moves — see check-capacity.sh.
  # Staging is a single node, so nothing here has to stay co-located; production does.
  #
  # nbg1 rather than fsn1, because fsn1 had nothing orderable at all — not merely no ARM, nothing of
  # any architecture. hel1 has the same x86 types as nbg1, so the tie is broken on latency:
  # Nuremberg is ~25 ms closer to a Berlin audience than Helsinki (§1).
  #
  # BEING ADVERTISED IS NOT BEING ORDERABLE, which is what this line originally moved here for and
  # what it then ran into. Three orders for a cax11 in nbg1 were refused with `unsupported location
  # for server type (invalid_input)` while the datacenters endpoint listed it under `available` and
  # cax11's own pricing listed nbg1. That error reads as though this line is wrong. It was not —
  # see `k3s_server_type` below for how it was settled, and check-capacity.sh's header for why a
  # green result from it now means "worth trying" rather than "orderable".
  #
  # **Changing this line is not free.** Both Primary IPs exist in nbg1 and a Primary IP is
  # location-bound, so they have to be destroyed before this can move. The PGDATA volume is bound the
  # same way and carries the database (#460), so moving is no longer only an addressing problem — the
  # data has to come off it first. Changing the server type below touches neither.
  #
  # Nothing in the design wanted the two environments co-located: staging has its own network
  # (10.1.0.0/16), its own firewall and its own database, and reaches production over nothing at all.
  location = "nbg1"

  # One node running everything. PostgreSQL is co-located rather than given its own node, but it is
  # still reached over the network at a private address, so the connection path the applications
  # exercise is the same shape as production's.
  #
  # **cx33 (x86): 4 vCPU / 8 GB at €10.10/month, up from cpx22 because the node ran out of memory** —
  # a global OOM killed OpenObserve, load hit 99 on two cores, and the API server flapped for half an
  # hour (#271). ADR-015's ~1.5 GB observability budget was not the problem; the node never had
  # 1.5 GB to give. k3s-server alone holds 1.08–1.24 GB, and the floor before any observability is
  # ~2.3 GB of 3.81 GB.
  #
  # **This line does not rebuild the node, and assuming it does is the trap.** `server_type` is an
  # in-place attribute within one architecture. The apply that raised it rebuilt anyway because
  # `user_data` had ALSO drifted:
  #
  #     ~ server_type = "cpx22" -> "cx33"
  #     ~ user_data   = "o5CIpx..." -> "IOxMoC..."   # forces replacement
  #
  # Reverting this line and re-planning gave a byte-identical result, which is the proof that the two
  # are independent. Any commit touching `cloud-init/` leaves staging **one `tofu apply` away from a
  # rebuild, whatever that apply is for.** That is the hazard, not this variable.
  #
  # **What the rebuild actually cost, measured rather than predicted:**
  #
  #   survived   the PGDATA volume — `postgres.sh` logged `adopting the existing cluster on the
  #              volume`, and 3,409 events / 4,149 artists / 86 sources came back, with the two
  #              failed importers still carrying their retry counts and timestamps
  #   survived   both Primary IPs, the network, the firewall — so the public address and the
  #              WireGuard endpoint are unchanged, and only the server's *host* keys rotated
  #   lost       the k3s cluster, and with it six hand-made Secrets. Only `github-dispatch` could
  #              not be regenerated; see docs/ops/CLUSTER_BOOTSTRAP.md §8, which said "two"
  #   lost       the WireGuard *server* key, so every client config needs its `PublicKey =` line
  #              replaced before the tunnel handshakes again
  #
  # Roughly 40 minutes end to end, most of it cloud-init and Flux.
  #
  # **This is both twice the node and less than half the previous bill** — cpx22 was €23.19 for
  # 2 vCPU / 4 GB, which it only ever was because ARM could not be bought (below).
  #
  # **Disk is 80 GB on both, so nothing shrinks and nothing is upgraded.** That matters because
  # `keep_disk` is unset (false): a type change that grew the disk would foreclose ever going back
  # to a smaller one. Equal sizes mean no disk operation happens at all.
  #
  # **What survives the rebuild, both checked against the plan rather than assumed:**
  # `hcloud_volume.postgres` does not appear in it at all — the check AGENTS.md names, and the one
  # #460 proved on 2026-08-17 — so the database comes through. Neither do the Primary IPs, the
  # network or the firewall, so the public address and the WireGuard endpoint come back unchanged.
  # Only `hcloud_server.k3s` and `hcloud_volume_attachment.postgres` are replaced.
  #
  # **What does not survive is the k3s cluster** — Flux, cert-manager, the issued certificates, and
  # the `events` role's password, which lives only in the `events-db` Secret and cannot be recovered
  # from a SCRAM hash. docs/ops/CLUSTER_BOOTSTRAP.md §Rebuilding a node is the procedure, and
  # AGENTS.md records the trap: a rebuild needs `ALTER ROLE events PASSWORD ...`, not `CREATE ROLE`.
  #
  # **Availability here is advertised, not promised, and it lies in BOTH directions.**
  # **cax11/cax21 (ARM) still cannot be bought in nbg1.** Re-probed 2026-08-20 by placing a real
  # order for a bare cax21 with no IPs, no network and no firewall: refused in 0.1s with
  # `HTTP 422 invalid_input: unsupported location for server type`, the same error three cax11
  # orders got on 2026-08-13. Production keeps `cax21` and keeps waiting, so the arm64 parity
  # argument in PLATFORM_SETUP §1 still holds for only two of the three. It survives because #264
  # publishes multi-arch images — the same chart and tags run on both.
  #
  # **cx33 was chosen by probing, because the API said it was NOT available here and was wrong.**
  # The `datacenters` endpoint omits cx33 from nbg1's `available` list; the order succeeded anyway.
  # check-capacity.sh's header already warns that a green result means "worth trying" rather than
  # "orderable" — this is the same unreliability running the other way, and it is why the previous
  # version of this comment called cx23 "not orderable anywhere in eu-central today". **Only an
  # order settles it.** Refusals are free and return in 0.1s; delete anything that succeeds.
  #
  # Probed 2026-08-20 in nbg1, cheapest first — everything with more headroom than cpx22:
  #
  #   cx33   €10.10  4/8GB   ORDERABLE   <- this
  #   cax21  €12.48  4/8GB   unsupported location
  #   cx43   €19.03  8/16GB  resource_unavailable  <- supported here, out of stock, may return
  #   cpx31  €20.81  4/8GB   unsupported location
  #   cax31  €24.98  8/16GB  unsupported location
  #   cpx32  €42.23  4/8GB   orderable, four times the price
  #   ccx13  €51.16  2/8GB   orderable, dedicated vCPU, five times the price
  #
  # **The two refusal codes mean different things.** `resource_unavailable` means the type is
  # supported in nbg1 and merely out of stock, so cx43's 16 GB may become buyable later.
  # `unsupported location` never self-resolves.
  #
  # `./check-capacity.sh staging` tracks whatever this line names; use `--all` to watch the others.
  #
  # GOING TO ARM IS NOT A ONE-LINE CHANGE, AND THE PLAN WILL NOT TELL YOU SO. Hetzner cannot
  # rescale between architectures; within x86 (cpx22 -> cx33) the ATTRIBUTE is an in-place update,
  # but x86 -> ARM is refused by the API *during apply*, after the plan has rendered a tidy in-place
  # change. It is a node rebuild, and while #460's volume means the database survives one, the k3s
  # cluster does not. docs/ops/CLUSTER_BOOTSTRAP.md §Rebuilding a node has the sequence.
  #
  # **"In-place" is a property of the attribute, never a prediction about the apply**, and that is
  # the lesson from getting this wrong above: any drift in `user_data` turns the whole thing into a
  # replacement regardless of what `server_type` does. **Read the plan; do not reason from the
  # field.**
  k3s_server_type      = "cx33"
  postgres_server_type = null

  # Distinct from production's 10.0.0.0/16 and 10.10.0.0/24. Both tunnels are routinely up at the
  # same time, and overlapping ranges fail in a way that looks like a firewall problem for an hour
  # before anyone checks the routing table.
  network_ip_range = "10.1.0.0/16"
  subnet_ip_range  = "10.1.1.0/24"
  k3s_private_ip   = "10.1.1.10"
  wireguard_subnet = "10.10.1.0/24"

  # Not on the public internet at all (PLATFORM_SETUP.md §4a). No 80/443 from the world, and — see
  # below — no address record either, so the name does not resolve for anyone outside the tunnel.
  public_web = false

  # No lock and no backups: this is the environment where the destroy/apply cycle actually gets
  # exercised, and there is nothing here worth paying 20% to keep. The volume is unprotected for the
  # same reason — a locked one would survive `tofu destroy` anyway (the provider lifts its own
  # locks), so the flag would buy nothing here and cost the one thing staging is for.
  #
  # It still gets a volume, and that is deliberate: this is the *only* environment where the rebuild
  # can actually be proven, because production has never been applied and must not be destroyed to
  # find out. ~€0.44/month to not be guessing.
  ip_delete_protection              = false
  postgres_volume_delete_protection = false
  enable_backups                    = false

  ssh_key_ids     = var.ssh_key_ids
  ssh_public_keys = var.ssh_public_keys
  admin_cidrs     = var.admin_cidrs
  wireguard_peers = var.wireguard_peers

  k3s_extra_tls_sans = ["staging.event-junkie.de"]
}

# ---------------------------------------------------------------------------
# There are deliberately no DNS records in this file.
#
# `staging.event-junkie.de` does not resolve on the public internet — that is the design, not an
# omission (§4a). Name resolution happens over the tunnel, via its DNS or a `hosts` entry pointing
# at the node's WireGuard address.
#
# The consequence, worth stating where someone will look for it: TLS here cannot use the HTTP-01
# challenge, because HTTP-01 requires Let's Encrypt to reach the host and this design exists to
# stop that. cert-manager uses DNS-01 against the Hetzner DNS API instead, which needs no inbound
# access at all — so a hostname with no public address still gets a real ACME certificate. The TXT
# record is public; the A record never exists.
#
# **That certificate is not publicly trusted, and that is separate from the above.** DNS-01 solves
# reachability; it says nothing about which CA signs. deploy/clusters/staging/helm-release.yaml
# points at `acme-staging-v02` — Let's Encrypt's *staging* CA, whose root is in no trust store — so
# browsers and `curl` warn, by design, and the production cluster uses `acme-v02` instead. This
# comment previously claimed "a genuine, publicly-trusted certificate", which conflated the two;
# corrected 2026-08-17 after watching an issued certificate still fail `curl` without `-k`.
#
# The chart renders that solver (#261); #265 installs cert-manager and the Hetzner webhook that
# answers it, both as Flux HelmReleases in deploy/clusters/staging/.
#
# The token it needs is an **hcloud** token — the same kind this stack authenticates with, from the
# Hetzner Console. The old dns.hetzner.com API and its separate DNS tokens were shut down in May
# 2026, which is also why `hcloud_zone` in bootstrap/ is the official provider's resource rather
# than a community DNS provider's.
# ---------------------------------------------------------------------------
