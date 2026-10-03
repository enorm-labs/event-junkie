#!/usr/bin/env bash
#
# Baseline host hardening: SSH, unattended security upgrades, and the metric that says a reboot is
# pending (#419). Every node; idempotent.

set -euo pipefail

readonly OPS_AUTHORIZED_KEYS=/home/ops/.ssh/authorized_keys

# Refuse to disable root login until the unprivileged account can be logged into: Hetzner injects
# the keys into root, and a failed `users:` block would leave the node reachable only through
# Hetzner's browser console.
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
# is an outage, so kernel updates need a deliberate reboot (below).
cat >/etc/apt/apt.conf.d/51-event-junkie-no-auto-reboot <<'EOF'
Unattended-Upgrade::Automatic-Reboot "false";
EOF

# Without this, unattended-upgrades achieves almost nothing: needrestart's default mode is
# interactive, which run non-interactively "will fallback to list only mode", so a patched libssl
# lands on disk while every running process keeps the old one mapped.
install -d -m 0755 /etc/needrestart/conf.d
cat >/etc/needrestart/conf.d/50-event-junkie.conf <<'EOF'
# Restart services automatically when a library they depend on is updated.
$nrconf{restart} = 'a';

# Keys added to Ubuntu's own exclusions, never a new hash: assigning one dropped dbus, docker and the
# apt units from the defaults. These three cut every connection on the node when restarted (#2318);
# the next deliberate reboot loads the new library, and `node_service_restart_pending_age_seconds`
# says when one is waiting.
$nrconf{override_rc}{qr(^k3s)} = 0;
$nrconf{override_rc}{qr(^systemd-networkd)} = 0;
$nrconf{override_rc}{qr(^postgresql)} = 0;
EOF

systemctl enable --now unattended-upgrades

# Kernel and k3s updates still need a reboot, and nothing here does it. `/var/run/reboot-required`
# is the flag; a timer writes its age, the age of a service restart needrestart left waiting, and
# the updater's last-run age, as node_exporter textfile metrics on the private address, where the
# collector gateway scrapes them (#419). Textfile only: hostmetrics already covers the k3s node.
# shellcheck source=/dev/null
source /etc/event-junkie/bootstrap.env
apt-get install -y --no-install-recommends prometheus-node-exporter
cat >/etc/default/prometheus-node-exporter <<EOF
ARGS="--web.listen-address=${PRIVATE_IPV4}:9100 --collector.disable-defaults --collector.textfile --collector.textfile.directory=/var/lib/prometheus/node-exporter"
EOF
install -d -m 0755 /var/lib/prometheus/node-exporter
# A missing flag is a node that needs no reboot, so 0. A missing updater stamp is the whole epoch,
# so the alert fires rather than staying quiet.
cat >/usr/local/sbin/ej-patch-state <<'EOF'
#!/bin/sh
d=/var/lib/prometheus/node-exporter; now=$(date +%s)
age() { if [ -e "$1" ]; then echo $((now - $(stat -c %Y "$1"))); else echo "$2"; fi; }
# needrestart lists an excluded service it did not restart as NEEDRESTART-SVC. The stamp marks the
# first run that saw one, so the age grows until a reboot clears the list.
pending="$d/.restart-pending-since"
if needrestart -b -r l 2>/dev/null | grep -q '^NEEDRESTART-SVC:'; then
  [ -e "$pending" ] || touch "$pending"
else
  rm -f "$pending"
fi
{
  echo "node_reboot_required_age_seconds $(age /var/run/reboot-required 0)"
  echo "node_service_restart_pending_age_seconds $(age "$pending" 0)"
  echo "node_unattended_upgrades_last_run_age_seconds $(age /var/lib/apt/periodic/unattended-upgrades-stamp "$now")"
  echo "node_patch_state_timestamp_seconds $now"
} >"$d/.patch-state.prom" && mv "$d/.patch-state.prom" "$d/patch-state.prom"
EOF
chmod 0755 /usr/local/sbin/ej-patch-state
cat >/etc/systemd/system/ej-patch-state.service <<'EOF'
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/ej-patch-state
EOF
cat >/etc/systemd/system/ej-patch-state.timer <<'EOF'
[Timer]
OnBootSec=2min
OnUnitActiveSec=10min
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now ej-patch-state.timer
/usr/local/sbin/ej-patch-state
systemctl restart prometheus-node-exporter

echo "harden: done"
