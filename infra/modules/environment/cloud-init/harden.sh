#!/usr/bin/env bash
#
# Baseline host hardening: SSH, and unattended security upgrades.
#
# Runs on every node. Idempotent — safe to re-run by hand after a config change.

set -euo pipefail

readonly OPS_AUTHORIZED_KEYS=/home/ops/.ssh/authorized_keys

# Refuse to disable root login until the unprivileged account can actually be logged into.
# Hetzner injects the SSH keys into *root*; if cloud-init's `users:` block failed for any reason,
# hardening now would leave the node reachable only through Hetzner's browser console.
if [[ ! -s "${OPS_AUTHORIZED_KEYS}" ]]; then
    echo "harden: ${OPS_AUTHORIZED_KEYS} is missing or empty - refusing to disable root login" >&2
    exit 1
fi

install -d -m 0755 /etc/ssh/sshd_config.d
cat >/etc/ssh/sshd_config.d/10-event-junkie.conf <<'EOF'
PermitRootLogin no
PasswordAuthentication no
PubkeyAuthentication yes
KbdInteractiveAuthentication no
PermitEmptyPasswords no
X11Forwarding no
MaxAuthTries 3
ClientAliveInterval 300
ClientAliveCountMax 2
EOF

# Ubuntu 24.04 socket-activates sshd; `ssh` is the unit either way.
sshd -t
systemctl reload ssh

export DEBIAN_FRONTEND=noninteractive
apt-get install -y --no-install-recommends unattended-upgrades

cat >/etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF

# Patches apply automatically; reboots do not. On a single-node cluster an unattended 04:00 reboot
# is an unannounced outage. Kernel updates therefore need a deliberate reboot — see below.
cat >/etc/apt/apt.conf.d/51-event-junkie-no-auto-reboot <<'EOF'
Unattended-Upgrade::Automatic-Reboot "false";
EOF

# Without this, unattended-upgrades achieves almost nothing.
#
# needrestart decides whether services running an updated library get restarted, and its default
# mode is interactive — which, run non-interactively from unattended-upgrades, "will fallback to
# list only mode" (its own documentation). List-only means a patched libssl lands on disk while
# every running process keeps the old one mapped. Combined with automatic reboots being off, that
# is a node reporting itself fully patched while running entirely unpatched code.
install -d -m 0755 /etc/needrestart/conf.d
cat >/etc/needrestart/conf.d/50-event-junkie.conf <<'EOF'
# Restart services automatically when a library they depend on is updated.
$nrconf{restart} = 'a';

# Except k3s: restarting it disrupts every workload on the node, and it is the one service a
# deliberate reboot was already going to cover. PostgreSQL is deliberately *not* excluded — the
# restart costs a second of dropped connections that the pool reconnects through, and the
# alternative is running a vulnerable library until somebody remembers to reboot.
$nrconf{override_rc} = {
    qr(^k3s) => 0,
};
EOF

systemctl enable --now unattended-upgrades

# Kernel and k3s updates still need a reboot, and nothing here will do it for you.
# `/var/run/reboot-required` is the flag. PLATFORM_SETUP.md §8b covers what is and is not
# automatic, and is honest that noticing that flag is still a calendar reminder rather than a
# control until #419 turns it into an alert.

echo "harden: done"
