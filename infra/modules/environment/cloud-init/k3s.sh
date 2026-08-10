#!/usr/bin/env bash
#
# k3s, single server node.
#
# Traefik and ServiceLB stay enabled: they are why MetalLB is not needed here (PLATFORM_SETUP.md
# §6). NetworkPolicy enforcement also stays on — the default-deny policies that make it mean
# something are the Helm chart's job (#261), not this file's.

set -euo pipefail

# shellcheck source=/dev/null
source /etc/event-junkie/bootstrap.env

readonly INSTALLER=/opt/event-junkie/k3s-install.sh

if systemctl is-active --quiet k3s; then
    echo "k3s: already running - nothing to do"
    exit 0
fi

# Kernel parameters the CIS hardening guide requires, and `protect-kernel-defaults` below makes
# mandatory: with that flag set, the kubelet *exits* if these differ from its own defaults. They
# have to be in place before k3s starts, not after.
cat >/etc/sysctl.d/90-kubelet.conf <<'EOF'
vm.panic_on_oom = 0
vm.overcommit_memory = 1
kernel.panic = 10
kernel.panic_on_oops = 1
EOF
sysctl -p /etc/sysctl.d/90-kubelet.conf >/dev/null

# Configuration goes in a file rather than INSTALL_K3S_EXEC. The hardening guide is written in
# terms of this file, so keeping the same shape means a future item can be pasted in and diffed
# rather than translated into flags.
install -d -m 0700 /etc/rancher/k3s
{
    # 0600, not the 0644 the installer would otherwise use: the file is a cluster-admin credential.
    echo 'write-kubeconfig-mode: "0600"'

    # Encrypts Secrets at rest in etcd, and cannot be enabled later without restarting the server.
    # `secretbox` (XSalsa20-Poly1305) over the `aescbc` default because it is authenticated —
    # aescbc provides confidentiality with no integrity. Available since v1.33.0+k3s1.
    echo 'secrets-encryption: true'
    echo 'secrets-encryption-provider: secretbox'

    # Requires the sysctls above. See the CIS hardening guide.
    echo 'protect-kernel-defaults: true'

    echo "node-external-ip: ${PUBLIC_IPV4}"

    echo 'kube-apiserver-arg:'
    # Stops a compromised kubelet from editing other nodes or claiming pods it does not run.
    echo '  - "enable-admission-plugins=NodeRestriction"'

    echo 'kube-controller-manager-arg:'
    echo '  - "terminated-pod-gc-threshold=100"'

    echo 'kubelet-arg:'
    echo '  - "streaming-connection-idle-timeout=5m"'
    echo '  - "tls-cipher-suites=TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305,TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305"'

    echo 'tls-san:'
    for san in ${K3S_TLS_SANS}; do
        echo "  - \"${san}\""
    done
} >/etc/rancher/k3s/config.yaml
chmod 0600 /etc/rancher/k3s/config.yaml

curl -sfL https://get.k3s.io -o "${INSTALLER}"
chmod 0700 "${INSTALLER}"

# Pinned. Without INSTALL_K3S_VERSION the installer takes whatever is current at boot, so a
# destroy/apply cycle would quietly produce a different cluster than the one it replaced.
INSTALL_K3S_VERSION="${K3S_VERSION}" \
    INSTALL_K3S_EXEC="server" \
    "${INSTALLER}"

# `systemctl enable` is done by the installer; this only waits for the API to answer, so that a
# failure shows up in the cloud-init log rather than as a mystery two commands later.
for _ in $(seq 1 60); do
    if k3s kubectl get --raw='/readyz' >/dev/null 2>&1; then
        echo "k3s: API is ready"
        exit 0
    fi
    sleep 5
done

echo "k3s: API did not become ready within 5 minutes" >&2
exit 1
