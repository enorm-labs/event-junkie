#!/usr/bin/env bash
#
# k3s, single server node. Traefik and ServiceLB stay enabled, which is why MetalLB is not needed
# (PLATFORM_SETUP.md §6). NetworkPolicy enforcement stays on; the default-deny policies are the
# Helm chart's job (#261).

set -euo pipefail

# shellcheck source=/dev/null
source /etc/event-junkie/bootstrap.env

readonly INSTALLER=/opt/event-junkie/k3s-install.sh

if systemctl is-active --quiet k3s; then
    echo "k3s: already running - nothing to do"
    exit 0
fi

# Kernel parameters the CIS hardening guide requires: with `protect-kernel-defaults` the kubelet
# exits if these differ from its defaults, so they go in before k3s starts.
cat >/etc/sysctl.d/90-kubelet.conf <<'EOF'
vm.panic_on_oom = 0
vm.overcommit_memory = 1
kernel.panic = 10
kernel.panic_on_oops = 1
EOF
sysctl -p /etc/sysctl.d/90-kubelet.conf >/dev/null

# A file rather than INSTALL_K3S_EXEC, because the hardening guide is written in terms of this
# file, so a future item can be pasted in and diffed.
install -d -m 0700 /etc/rancher/k3s
{
    # 0600: the file is a cluster-admin credential.
    echo 'write-kubeconfig-mode: "0600"'

    # Encrypts Secrets at rest in etcd; cannot be enabled later without a restart. `secretbox` over
    # the `aescbc` default because it is authenticated. Available since v1.33.0+k3s1.
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
    # Container log retention, the only bound enforced today (#276). The privacy notice must state
    # the retention that is configured, and until OpenObserve ships its bucket policy (#271, ADR-015)
    # this pair is it; the kubelet default is "until the disk fills". A size bound, not a duration,
    # and the notice must say so: 10Mi x 3 per container is roughly a fortnight of this site's
    # traffic, and the number to revisit is the duration it buys once there is traffic to measure.
    echo '  - "container-log-max-size=10Mi"'
    echo '  - "container-log-max-files=3"'
    echo '  - "tls-cipher-suites=TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305,TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305"'

    echo 'tls-san:'
    for san in ${K3S_TLS_SANS}; do
        echo "  - \"${san}\""
    done
} >/etc/rancher/k3s/config.yaml
chmod 0600 /etc/rancher/k3s/config.yaml

curl -sfL https://get.k3s.io -o "${INSTALLER}"
chmod 0700 "${INSTALLER}"

# Pinned: without INSTALL_K3S_VERSION a destroy/apply cycle would produce a different cluster.
INSTALL_K3S_VERSION="${K3S_VERSION}" \
    INSTALL_K3S_EXEC="server" \
    "${INSTALLER}"

# `systemctl enable` is the installer's; this waits for the API so a failure shows in the
# cloud-init log rather than two commands later.
for _ in $(seq 1 60); do
    if k3s kubectl get --raw='/readyz' >/dev/null 2>&1; then
        echo "k3s: API is ready"
        exit 0
    fi
    sleep 5
done

echo "k3s: API did not become ready within 5 minutes" >&2
exit 1
