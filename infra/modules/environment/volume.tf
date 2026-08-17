# PGDATA lives here rather than on the node's local disk, so the database outlives the node (#460).
#
# The volume is declared standalone — `location`, never `server_id` — and attached separately. Both
# forms produce the same two objects, but this one states the lifetimes: nothing about the volume
# references a server, so no edit to a server can plan to replace it. That matters because
# `user_data` is a force-new attribute, and every edit under cloud-init/ therefore rebuilds the node
# underneath this.
#
# **Volumes are location-bound, exactly like the Primary IPs.** Moving an environment to another
# location means dealing with the volume first; it is not carried along by changing `location`.
resource "hcloud_volume" "postgres" {
  name     = "${var.environment}-pgdata"
  size     = var.postgres_volume_size
  location = var.location

  # Formatted once, by the provider, at creation. This is the reason postgres.sh contains no `mkfs`
  # at all: the script runs on every boot against a volume that already holds a cluster, and a
  # destructive command that does not exist cannot be made conditional wrongly.
  format = "ext4"

  delete_protection = var.postgres_volume_delete_protection

  labels = local.labels
}

# Attached to whichever node actually runs PostgreSQL: its own in production, the k3s node in
# staging. `automount = false` because Hetzner's automount writes its own /mnt/HC_Volume_* fstab
# entry, and the mountpoint, options and service ordering are postgres.sh's to decide.
resource "hcloud_volume_attachment" "postgres" {
  volume_id = hcloud_volume.postgres.id
  server_id = local.dedicated_postgres ? hcloud_server.postgres[0].id : hcloud_server.k3s.id
  automount = false
}
