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
    step        = file("${local.cloud_init_dir}/step.sh")
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
    local.script_file.step,
  ]

  base_runcmd = [
    "install -d -m 0750 /opt/event-junkie",
    # First, on both roles: k3s registers with `--node-ip` and PostgreSQL binds the private address,
    # and neither exists until this has run.
    "/opt/event-junkie/step.sh private-net",
    "/opt/event-junkie/step.sh harden",
  ]

  # Last on both roles: step.sh keeps going past a failed script, so this is what fails the boot.
  final_runcmd = [
    "/opt/event-junkie/step.sh --result",
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
  # The running node, and the one built beside it (#4128). The next node boots with the real
  # Primary IPv4, which it holds after the cutover, and skips PostgreSQL until the volume reaches it.
  # Its temporary IPv4 is no SAN: kubectl reaches it over the tunnel, and the render stays plannable.
  k3s_nodes = merge(
    {
      current = {
        private_ip    = var.k3s_private_ip
        postgres_ip   = local.postgres_ip
        boot_postgres = !local.dedicated_postgres
      }
    },
    local.next_pair ? {
      next = {
        private_ip    = local.next_k3s_private_ip
        postgres_ip   = local.dedicated_postgres ? local.next_postgres_private_ip : local.next_k3s_private_ip
        boot_postgres = false
      }
    } : {},
  )

  # Every address a kubeconfig might name: public IPv4 (break-glass), tunnel address, private address.
  k3s_tls_sans = {
    for name, node in local.k3s_nodes : name => concat(
      [
        hcloud_primary_ip.k3s_ipv4.ip_address,
        cidrhost(var.wireguard_subnet, 1),
        node.private_ip,
      ],
      var.k3s_extra_tls_sans,
    )
  }

  k3s_env = { for name, node in local.k3s_nodes : name => <<-EOT
    PUBLIC_IPV4=${hcloud_primary_ip.k3s_ipv4.ip_address}
    PRIVATE_IPV4=${node.private_ip}
    PRIVATE_SUBNET=${var.subnet_ip_range}
    POD_CIDR=${local.pod_cidr}
    WIREGUARD_ADDRESS=${local.wireguard_address}
    WIREGUARD_PORT=${var.wireguard_port}
    K3S_VERSION=${var.k3s_version}
    K3S_TLS_SANS="${join(" ", local.k3s_tls_sans[name])}"
    POSTGRES_VERSION=${var.postgres_version}
    POSTGRES_LISTEN_IP=${node.postgres_ip}
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
  }

  # Only shipped where the database is on the k3s node: `user_data` is capped at 32 KiB, these two
  # are more than half of what this node renders, and the co-located node is where the cap binds.
  colocated_postgres_files = local.dedicated_postgres ? [] : [
    local.script_file.postgres,
    local.script_file.backups,
  ]

  k3s_user_data_by_node = { for name, node in local.k3s_nodes : name => templatefile("${local.cloud_init_dir}/node.yaml.tftpl", {
    hostname        = "${var.environment}-k3s"
    ssh_public_keys = var.ssh_public_keys
    files = concat(local.base_files, [
      {
        path        = "/etc/event-junkie/bootstrap.env"
        permissions = "0640"
        content     = local.k3s_env[name]
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
      ["/opt/event-junkie/step.sh wireguard"],
      # backups.sh immediately after postgres.sh, only where PostgreSQL runs: it turns on
      # `archive_mode`, which needs a restart, so the cluster has to exist first.
      node.boot_postgres ? ["/opt/event-junkie/step.sh postgres", "/opt/event-junkie/step.sh backups"] : [],
      ["/opt/event-junkie/step.sh k3s"],
      local.final_runcmd,
    )
  }) }

  k3s_user_data = local.k3s_user_data_by_node.current
}

# ---------------------------------------------------------------------------
# Dedicated PostgreSQL node
# ---------------------------------------------------------------------------

locals {
  # As for k3s above: the next node boots without PostgreSQL, which the database cutover starts.
  postgres_nodes = merge(
    { current = { private_ip = var.postgres_private_ip, boot_postgres = true } },
    local.next_pair && local.dedicated_postgres ? { next = { private_ip = local.next_postgres_private_ip, boot_postgres = false } } : {},
  )

  postgres_env = { for name, node in local.postgres_nodes : name => <<-EOT
    PUBLIC_IPV4=
    PRIVATE_IPV4=${node.private_ip}
    PRIVATE_SUBNET=${var.subnet_ip_range}
    POD_CIDR=${local.pod_cidr}
    POSTGRES_VERSION=${var.postgres_version}
    POSTGRES_LISTEN_IP=${node.private_ip}
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
  }

  # No WireGuard: no public IPv4 to run an endpoint on, and it is reached over the private network
  # from the k3s node, which is behind the tunnel.
  postgres_user_data_by_node = { for name, node in local.postgres_nodes : name => templatefile("${local.cloud_init_dir}/node.yaml.tftpl", {
    hostname        = "${var.environment}-postgres"
    ssh_public_keys = var.ssh_public_keys
    files = concat(local.base_files, [
      {
        path        = "/etc/event-junkie/bootstrap.env"
        permissions = "0640"
        content     = local.postgres_env[name]
      },
      local.script_file.harden,
      local.script_file.postgres,
      local.script_file.backups,
    ])
    runcmd = concat(local.base_runcmd, node.boot_postgres ? [
      "/opt/event-junkie/step.sh postgres",
      "/opt/event-junkie/step.sh backups",
    ] : [], local.final_runcmd)
  }) }

  postgres_user_data = local.postgres_user_data_by_node.current
}

# What the provider keeps in state for `user_data`: base64 of the raw SHA-1, not the script
# (hcloud's `userDataHashSum`). HCL has only the hex digest, so this re-encodes it, three bytes at
# a time. servers.tf compares it with what each node booted (#4129).
locals {
  base64_alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
  user_data_hash = { for name, user_data in merge(
    { k3s = local.k3s_user_data, postgres = local.postgres_user_data },
    { for node, user_data in local.k3s_user_data_by_node : "k3s_next" => user_data if node == "next" },
    { for node, user_data in local.postgres_user_data_by_node : "postgres_next" => user_data if node == "next" },
    ) :
    name => "${substr(join("", [
      for i in range(7) : join("", [
        for shift in [262144, 4096, 64, 1] :
        substr(local.base64_alphabet, floor(parseint(substr("${sha1(user_data)}00", i * 6, 6), 16) / shift) % 64, 1)
      ])
    ]), 0, 27)}="
  }
}
