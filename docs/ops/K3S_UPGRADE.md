# Upgrading k3s

How to move `k3s_version` without replacing the node. One command on the node, four rules, and three
checks afterwards.

**A rebuild is not required and is the expensive way to take a bump.** `user_data` is force-new, so
the OpenTofu plan says replace the server — but k3s upgrades in place through the same installer the
node booted with. A rebuild destroys the cluster, Flux, the six hand-made Secrets,
`/etc/wal-g/credentials.env`, the WireGuard server key and OpenObserve's dashboards.
[CLUSTER_BOOTSTRAP.md](CLUSTER_BOOTSTRAP.md) § _Rebuilding a node_ is that procedure, for when one is
happening anyway.

## The sequence

```sh
# 1. Compare the packaged Traefik chart across the two versions. See "Traefik" below.
for v in v1.36.3+k3s1 v1.36.4+k3s1; do
  curl -fsSL "https://raw.githubusercontent.com/k3s-io/k3s/${v//+/%2B}/manifests/traefik.yaml" \
    | grep -oE 'traefik-[0-9.]+\+up[0-9.]+\.tgz'
done

# 2. Bump `k3s_version` in infra/modules/environment/variables.tf, in the same change.

# 3. On the node, as root. Staging first, production only after staging has held.
#    `sudo sh -c` because the pipe runs as the caller otherwise, and the installer needs root.
ssh ops@10.10.1.1 \
  "sudo sh -c 'curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION=v1.36.4+k3s1 INSTALL_K3S_EXEC=server sh -s -'"

# 4. Verify, in this order. `10.10.1.1` is staging, `10.10.0.1` production — CLUSTER_ACCESS.md.
ssh ops@10.10.1.1 'k3s --version'
kubectl get nodes -o wide                                          # Ready, and the new version
kubectl -n kube-system get svc traefik -o jsonpath='{.spec.type}'  # still ClusterIP
kubectl -n kube-system get pods | grep svclb                       # must return nothing
kubectl get pods -A                                                # everything back, none restarting
flux get all -A                                                    # everything Ready
```

**The last check differs by environment, because staging has no public address.** Traefik binds the
node's ports itself, so ask the node:

```sh
# staging — the Let's Encrypt staging CA is why -k is correct
ssh ops@10.10.1.1 'curl -sS -o /dev/null -w "%{http_code}\n" -k https://localhost/'

# production — from anywhere
curl -sS -o /dev/null -w '%{http_code}\n' https://<the host>/
```

## The four rules

- **The environment and the arguments must match what the node already runs.** The install script
  rewrites the systemd unit from what it is given. Upstream writes it as
  `<EXISTING_K3S_ENV> sh -s - <EXISTING_K3S_ARGS>`. Here that is `INSTALL_K3S_EXEC=server` and no
  positional arguments, which is what `cloud-init/k3s.sh` passes. Everything else the server reads
  lives in `/etc/rancher/k3s/config.yaml`, and the script does not touch it.
- **Run the installer, not `k3s.sh`.** That script rewrites `/etc/rancher/k3s/config.yaml` from
  `bootstrap.env`, which a live node does not have to hand.
- **Take a baseline first.** `kubectl get pods -A` and `flux get all -A` before the upgrade are what
  make the checks afterwards a comparison rather than an impression.
- **Never `INSTALL_K3S_CHANNEL`.** The pin exists so a destroy and apply cycle cannot produce a
  different cluster. A channel defeats that from the other direction.
- **Bump the pin in the same change.** A node upgraded without it is a node the next rebuild silently
  downgrades.

## What it costs

**The script restarts k3s itself**, so no `systemctl restart` follows it. containerd is replaced with
the binary. Every workload on the node restarts, and the API is briefly away. On a single-node
cluster that is a short outage of everything, which is why staging goes first.

**A downgrade is not promised.** The datastore schema can move forward between minors. Within a patch
line it is usually fine. The recovery for a bad upgrade is the rebuild this document exists to avoid.

## Traefik

**k3s packages the Traefik chart, so a k3s upgrade can move it.** Upstream did exactly that between
v1.31 and v1.32, where Traefik went from v2 to v3.

`deploy/clusters/*/traefik-host-ports.yaml` sets `service.spec.type: ClusterIP`, which frees ports 80
and 443 for Traefik's own `hostPort`. A chart release that stops reading that key brings svclb back,
svclb takes the ports, and the site stops answering. **So compare the chart before upgrading, not
after** — step 1 above. Identical output either side means the bump does not touch the ingress.

## Why not `system-upgrade-controller`

It is k3s's other documented method, and it installs a controller, a CRD and a plan to drain and
upgrade nodes in order. There is one node per cluster and no agents, so it automates a single
command. Reconsider it when there is more than one node.

## Links

- [k3s manual upgrades](https://docs.k3s.io/upgrades/manual) — the commands above come from here
- [k3s automated upgrades](https://docs.k3s.io/upgrades/automated) — `system-upgrade-controller`
- [CLUSTER_BOOTSTRAP.md](CLUSTER_BOOTSTRAP.md) § _Rebuilding a node_ — when a rebuild is the point
- [PLATFORM_SETUP.md](PLATFORM_SETUP.md) §8.2 — what else is and is not patched automatically
- [ADR-024](../adr/ADR-024_DEPENDENCY_UPDATE_BOUNDARY.md) — why a bot watches this pin and never
  proposes it
