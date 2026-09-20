# cloud-init is assembled here so the shell lives in real `.sh` files that shellcheck can read and
# a human can run by hand; the templates carry no logic beyond "which scripts, in what order".
#
# Everything variable passes through one sourced env file, and nothing secret goes in it: `user_data`
# is state. The WireGuard server key is generated on the node, database credentials belong to #261,
# and the S3 key wal-g archives with is written by hand into /etc/wal-g/credentials.env (#270).

locals {
  cloud_init_dir = "${path.module}/cloud-init"

  scripts = {
    # Runs before everything, on both roles: on a first apply cloud-init does not bring the private
    # network up. See the script.
    private-net = file("${local.cloud_init_dir}/private-net.sh")
    harden      = file("${local.cloud_init_dir}/harden.sh")
    wireguard   = file("${local.cloud_init_dir}/wireguard.sh")
    k3s         = file("${local.cloud_init_dir}/k3s.sh")
    postgres    = file("${local.cloud_init_dir}/postgres.sh")
    backups     = file("${local.cloud_init_dir}/backups.sh")
  }

  # Derived rather than a variable so the two environments can never share a prefix: a retention
  # sweep run under production's prefix from staging would delete real backups (#270).
  backup_prefix = var.environment

  # k3s's default `--cluster-cidr`. Not a variable, because k3s.sh does not override the flag.
  pod_cidr = "10.42.0.0/16"

  wireguard_prefix  = split("/", var.wireguard_subnet)[1]
  wireguard_address = "${cidrhost(var.wireguard_subnet, 1)}/${local.wireguard_prefix}"

  wireguard_peers_conf = join("\n", [
    for peer in var.wireguard_peers : join("\n", [
      "[Peer]",
      "# ${peer.name}",
      "PublicKey = ${peer.public_key}",
      "AllowedIPs = ${peer.address}/32",
      "",
    ])
  ])

  # Every address a kubeconfig might name: public IPv4 (break-glass), tunnel address, private address.
  k3s_tls_sans = concat(
    [
      hcloud_primary_ip.k3s_ipv4.ip_address,
      cidrhost(var.wireguard_subnet, 1),
      var.k3s_private_ip,
    ],
    var.k3s_extra_tls_sans,
  )

  # Every apt invocation on the box, so `package_upgrade` and the unattended-upgrades timer cannot
  # collide with the scripts below and fail the boot.
  apt_lock_timeout = <<-EOT
    DPkg::Lock::Timeout "600";
    Acquire::Retries "3";
  EOT

  base_files = [
    {
      path        = "/etc/apt/apt.conf.d/99-event-junkie"
      permissions = "0644"
      content     = local.apt_lock_timeout
    },
    local.script_file["private-net"],
  ]

  base_runcmd = [
    "install -d -m 0750 /opt/event-junkie",
    # First, on both roles: k3s registers with `--node-ip` and PostgreSQL binds the private address,
    # and neither exists until this has run.
    "/opt/event-junkie/private-net.sh",
    "/opt/event-junkie/harden.sh",
  ]

  script_file = {
    for name, content in local.scripts : name => {
      path        = "/opt/event-junkie/${name}.sh"
      permissions = "0700"
      content     = content
    }
  }
}

# ---------------------------------------------------------------------------
# k3s node - and, when there is no dedicated database node, PostgreSQL alongside it
# ---------------------------------------------------------------------------

locals {
  k3s_env = <<-EOT
    PUBLIC_IPV4=${hcloud_primary_ip.k3s_ipv4.ip_address}
    PRIVATE_IPV4=${var.k3s_private_ip}
    PRIVATE_SUBNET=${var.subnet_ip_range}
    POD_CIDR=${local.pod_cidr}
    WIREGUARD_ADDRESS=${local.wireguard_address}
    WIREGUARD_PORT=${var.wireguard_port}
    K3S_VERSION=${var.k3s_version}
    K3S_TLS_SANS="${join(" ", local.k3s_tls_sans)}"
    POSTGRES_VERSION=${var.postgres_version}
    POSTGRES_LISTEN_IP=${local.postgres_ip}
    POSTGRES_DATA_DEVICE=${hcloud_volume.postgres.linux_device}
    WALG_VERSION=${var.walg_version}
    WALG_SHA256_AMD64=${var.walg_checksums.amd64}
    WALG_SHA256_ARM64=${var.walg_checksums.arm64}
    BACKUP_BUCKET=${var.backup_bucket}
    BACKUP_PREFIX=${local.backup_prefix}
    BACKUP_ENDPOINT=${var.backup_endpoint}
    BACKUP_REGION=${var.backup_region}
    BACKUP_RETENTION_DAYS=${var.backup_retention_days}
  EOT

  # Only shipped where the database is on the k3s node: `user_data` is capped at 32 KiB, these two
  # are more than half of what this node renders, and the co-located node is where the cap binds.
  colocated_postgres_files = local.dedicated_postgres ? [] : [
    local.script_file.postgres,
    local.script_file.backups,
  ]

  k3s_user_data = templatefile("${local.cloud_init_dir}/node.yaml.tftpl", {
    hostname        = "${var.environment}-k3s"
    ssh_public_keys = var.ssh_public_keys
    files = concat(local.base_files, [
      {
        path        = "/etc/event-junkie/bootstrap.env"
        permissions = "0640"
        content     = local.k3s_env
      },
      {
        path        = "/etc/wireguard/peers.conf"
        permissions = "0600"
        content     = local.wireguard_peers_conf
      },
      local.script_file.harden,
      local.script_file.wireguard,
      local.script_file.k3s,
    ], local.colocated_postgres_files)
    runcmd = concat(
      local.base_runcmd,
      # WireGuard before k3s: it is how you get back in if anything after it fails.
      ["/opt/event-junkie/wireguard.sh"],
      # backups.sh immediately after postgres.sh, only where PostgreSQL runs: it turns on
      # `archive_mode`, which needs a restart, so the cluster has to exist first.
      local.dedicated_postgres ? [] : ["/opt/event-junkie/postgres.sh", "/opt/event-junkie/backups.sh"],
      ["/opt/event-junkie/k3s.sh"],
    )
  })
}

# ---------------------------------------------------------------------------
# Dedicated PostgreSQL node
# ---------------------------------------------------------------------------

locals {
  postgres_env = <<-EOT
    PUBLIC_IPV4=
    PRIVATE_IPV4=${var.postgres_private_ip}
    PRIVATE_SUBNET=${var.subnet_ip_range}
    POD_CIDR=${local.pod_cidr}
    POSTGRES_VERSION=${var.postgres_version}
    POSTGRES_LISTEN_IP=${var.postgres_private_ip}
    POSTGRES_DATA_DEVICE=${hcloud_volume.postgres.linux_device}
    WALG_VERSION=${var.walg_version}
    WALG_SHA256_AMD64=${var.walg_checksums.amd64}
    WALG_SHA256_ARM64=${var.walg_checksums.arm64}
    BACKUP_BUCKET=${var.backup_bucket}
    BACKUP_PREFIX=${local.backup_prefix}
    BACKUP_ENDPOINT=${var.backup_endpoint}
    BACKUP_REGION=${var.backup_region}
    BACKUP_RETENTION_DAYS=${var.backup_retention_days}
  EOT

  # No WireGuard: no public IPv4 to run an endpoint on, and it is reached over the private network
  # from the k3s node, which is behind the tunnel.
  postgres_user_data = templatefile("${local.cloud_init_dir}/node.yaml.tftpl", {
    hostname        = "${var.environment}-postgres"
    ssh_public_keys = var.ssh_public_keys
    files = concat(local.base_files, [
      {
        path        = "/etc/event-junkie/bootstrap.env"
        permissions = "0640"
        content     = local.postgres_env
      },
      local.script_file.harden,
      local.script_file.postgres,
      local.script_file.backups,
    ])
    runcmd = concat(local.base_runcmd, [
      "/opt/event-junkie/postgres.sh",
      "/opt/event-junkie/backups.sh",
    ])
  })
}
