# PGDATA lives here so the database outlives the node (#460). Declared standalone, `location`
# and never `server_id`, and attached separately: nothing about the volume references a server, so
# no edit to a server can plan to replace it, and `user_data` is a force-new attribute. Volumes
# are location-bound like the Primary IPs: moving an environment means dealing with the volume
# first.
resource "hcloud_volume" "postgres" {
  name     = "${var.environment}-pgdata"
  size     = var.postgres_volume_size
  location = var.location

  # Formatted once, by the provider, which is why postgres.sh contains no `mkfs`: a destructive
  # command that does not exist cannot be made conditional wrongly.
  format = "ext4"

  delete_protection = var.postgres_volume_delete_protection

  labels = local.labels
}

# Attached to whichever node runs PostgreSQL. `automount = false` because Hetzner's automount
# writes its own /mnt/HC_Volume_* fstab entry, and the mountpoint and ordering are postgres.sh's.
resource "hcloud_volume_attachment" "postgres" {
  volume_id = hcloud_volume.postgres.id
  server_id = local.dedicated_postgres ? hcloud_server.postgres[0].id : hcloud_server.k3s.id
  automount = false
}
