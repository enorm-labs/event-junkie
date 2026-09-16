#!/usr/bin/env python3
"""Render both roles' cloud-init with sample values and measure them against Hetzner's cap.

`tofu validate` does not render `templatefile`, so nothing in CI ever saw the size of what
`cloudinit.tf` assembles. Hetzner caps `user_data` at 32 KiB, and the staging node's render
crossed it on 2026-09-16 without any check going red — a rebuild would have failed at the API
after the plan said `1 to add, 1 to destroy` (#1482). This is that check.

It mirrors `node.yaml.tftpl` and the two `files`/`runcmd` lists in `cloudinit.tf` rather than
calling OpenTofu, because the module's locals read a Primary IP and a volume device that exist
only in state. The mirror is small and this file names every place it has to follow:

    ROLES        the file lists, in the order cloudinit.tf concatenates them
    render()     the template, line for line

Two failures beyond the size: a `.sh` under cloud-init/ that no role ships, so a new script is
not forgotten, and a gzipped file that does not decode back to its source, so the encoding
the template chose is proven rather than assumed. Every rendered document is also parsed by
`yq`, which is how the template's YAML has been checked by hand until now.

    python3 infra/check_user_data.py               # the size per role; exit 1 over the limit
    python3 infra/check_user_data.py --write DIR   # also writes the two rendered documents
"""

import argparse
import base64
import gzip
import subprocess
import sys
from pathlib import Path

CLOUD_INIT = Path(__file__).resolve().parent / "modules" / "environment" / "cloud-init"
CAP = 32 * 1024
# Two KiB under the cap. A change that spends the margin is one to read before merging, and the
# gzip here is Python's, not Go's — measured 3080 against 3076 bytes for harden.sh, so the margin is real.
LIMIT = CAP - 2 * 1024

SAMPLE_ENV = {
    "PUBLIC_IPV4": "203.0.113.10",
    "PRIVATE_IPV4": "10.1.1.10",
    "PRIVATE_SUBNET": "10.1.1.0/24",
    "POD_CIDR": "10.42.0.0/16",
    "WIREGUARD_ADDRESS": "10.10.1.1/24",
    "WIREGUARD_PORT": "51820",
    "K3S_VERSION": "v1.36.4+k3s1",
    "K3S_TLS_SANS": '"203.0.113.10 10.10.1.1 10.1.1.10 staging.event-junkie.internal"',
    "POSTGRES_VERSION": "18",
    "POSTGRES_LISTEN_IP": "10.1.1.10",
    "POSTGRES_DATA_DEVICE": "/dev/disk/by-id/scsi-0HC_Volume_123456789",
    "WALG_VERSION": "v3.0.7",
    "WALG_SHA256_AMD64": "a" * 64,
    "WALG_SHA256_ARM64": "b" * 64,
    "BACKUP_BUCKET": "event-junkie-backups",
    "BACKUP_PREFIX": "staging",
    "BACKUP_ENDPOINT": "https://fsn1.your-objectstorage.com",
    "BACKUP_REGION": "fsn1",
    "BACKUP_RETENTION_DAYS": "30",
}
SSH_KEYS = [f"ssh-ed25519 {'A' * 68} key{i}" for i in range(2)]
PEERS = "".join(f"[Peer]\n# peer{i}\nPublicKey = {'x' * 43}=\nAllowedIPs = 10.10.1.{i + 2}/32\n\n" for i in range(3))
APT_CONF = 'DPkg::Lock::Timeout "600";\nAcquire::Retries "3";\n'


def script(name):
    return (f"/opt/event-junkie/{name}.sh", "0700", (CLOUD_INIT / f"{name}.sh").read_text())


def env_file(keys):
    return ("/etc/event-junkie/bootstrap.env", "0640", "".join(f"{k}={SAMPLE_ENV[k]}\n" for k in keys))


# cloudinit.tf, `k3s_user_data` with the co-located database (staging) and `postgres_user_data`
# (production's dedicated node). The co-located node is the one that binds; production's k3s node
# is the same list minus postgres and backups, so it is smaller than both and not measured.
ROLES = {
    "k3s, co-located database (staging)": [
        ("/etc/apt/apt.conf.d/99-event-junkie", "0644", APT_CONF),
        script("private-net"),
        env_file(SAMPLE_ENV.keys()),
        ("/etc/wireguard/peers.conf", "0600", PEERS),
        script("harden"),
        script("wireguard"),
        script("k3s"),
        script("postgres"),
        script("backups"),
    ],
    "PostgreSQL, dedicated (production)": [
        ("/etc/apt/apt.conf.d/99-event-junkie", "0644", APT_CONF),
        script("private-net"),
        env_file([k for k in SAMPLE_ENV if not k.startswith(("WIREGUARD", "K3S"))]),
        script("harden"),
        script("postgres"),
        script("backups"),
    ],
}
RUNCMD = [
    "install -d -m 0750 /opt/event-junkie",
    "/opt/event-junkie/private-net.sh",
    "/opt/event-junkie/harden.sh",
    "/opt/event-junkie/wireguard.sh",
    "/opt/event-junkie/postgres.sh",
    "/opt/event-junkie/backups.sh",
    "/opt/event-junkie/k3s.sh",
]


def indent(n, text):
    """OpenTofu's `indent()`: every line but the first."""
    first, *rest = text.split("\n")
    return "\n".join([first] + [(" " * n + line) if line else line for line in rest])


def render(hostname, files):
    """node.yaml.tftpl, with the same branches."""
    out = [
        "#cloud-config",
        f"hostname: {hostname}",
        f"fqdn: {hostname}",
        "preserve_hostname: false",
        "",
        "package_update: true",
        "package_upgrade: true",
        "",
        "users:",
        "  - name: ops",
        "    groups: [sudo]",
        "    shell: /bin/bash",
        '    sudo: "ALL=(ALL) NOPASSWD:ALL"',
        "    lock_passwd: true",
        "    ssh_authorized_keys:",
    ]
    out += [f'      - "{key}"' for key in SSH_KEYS]
    out += ["", "write_files:"]
    for path, permissions, content in files:
        out += [f"  - path: {path}", "    owner: root:root", f'    permissions: "{permissions}"']
        if path.endswith(".sh"):
            encoded = base64.b64encode(gzip.compress(content.encode(), compresslevel=6)).decode()
            out += ["    encoding: gz+b64", f"    content: {encoded}"]
        else:
            out += ["    content: |", f"      {indent(6, content)}"]
    out += ["", "runcmd:"]
    out += [f"  - {command}" for command in RUNCMD]
    out += ["", 'final_message: "cloud-init finished after $UPTIME seconds"', ""]
    return "\n".join(out)


def check_round_trip(document, files):
    """Every gz+b64 block decodes back to the source it came from."""
    lines = document.split("\n")
    encoded = [line.split(": ", 1)[1] for line in lines if line.startswith("    content: ") and "|" not in line]
    scripts = [content for path, _, content in files if path.endswith(".sh")]
    if len(encoded) != len(scripts):
        return f"{len(scripts)} scripts, {len(encoded)} encoded blocks"
    for blob, source in zip(encoded, scripts, strict=True):
        if gzip.decompress(base64.b64decode(blob)).decode() != source:
            return "a gzipped script does not decode back to its source"
    return None


def check_yaml(document):
    """`yq` parses it and sees every file — the check the template has had by hand until now."""
    result = subprocess.run(["yq", "-e", ".write_files | length"], input=document, capture_output=True, text=True)
    if result.returncode != 0:
        return f"yq: {result.stderr.strip()}"
    return None


def main():
    parser = argparse.ArgumentParser(description="Render both cloud-init roles and measure them against Hetzner's cap.")
    parser.add_argument("--write", metavar="DIR", type=Path, help="also write the rendered documents into DIR")
    write_dir = parser.parse_args().write
    if write_dir:
        write_dir.mkdir(parents=True, exist_ok=True)

    failures = []
    # The mirror cannot see the template change under it, so the three lines it depends on are
    # asserted to still be there. A template that stopped encoding would render fine here and
    # fail at the API.
    template = (CLOUD_INIT / "node.yaml.tftpl").read_text()
    for needle in ('endswith(file.path, ".sh")', "encoding: gz+b64", "base64gzip(file.content)"):
        if needle not in template:
            failures.append(f"node.yaml.tftpl no longer contains `{needle}`, which render() assumes")
    shipped = {Path(path).name for files in ROLES.values() for path, _, _ in files if path.endswith(".sh")}
    for orphan in sorted(p.name for p in CLOUD_INIT.glob("*.sh") if p.name not in shipped):
        failures.append(f"{orphan} exists under cloud-init/ and no role in ROLES ships it")

    for role, files in ROLES.items():
        hostname = "staging-k3s" if "k3s" in role else "production-postgres"
        document = render(hostname, files)
        size = len(document.encode())
        verdict = "ok" if size <= LIMIT else "OVER"
        print(f"{role:40} {size:6} bytes  {size / 1024:5.1f} KiB  {size / CAP:4.0%} of the cap  {verdict}")
        if size > LIMIT:
            failures.append(f"{role}: {size} bytes is over the {LIMIT}-byte limit ({CAP} cap minus 2 KiB)")
        for problem in (check_round_trip(document, files), check_yaml(document)):
            if problem:
                failures.append(f"{role}: {problem}")
        if write_dir:
            (write_dir / f"{hostname}.yaml").write_text(document)

    for failure in failures:
        print(f"FAIL {failure}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
