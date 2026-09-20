locals {
  # Reduce every key to `<type> <base64>`. Hetzner deduplicates keys by fingerprint across the whole
  # project, so one added by hand fails the first apply with `uniqueness_error` and has to be
  # imported; and the API stores the key without its comment, so an imported key reads back as
  # `ssh-ed25519 AAAA…` while the config says `ssh-ed25519 AAAA… you@laptop`, and `public_key`
  # forces replacement on every apply. The `ops` user's `authorized_keys` keeps the comment, because
  # humans read that file to decide which key to revoke.
  ssh_public_keys = {
    for name, key in var.ssh_public_keys :
    name => replace(trimspace(key), "/^(\\S+)\\s+(\\S+).*$/", "$1 $2")
  }
}

resource "hcloud_ssh_key" "admin" {
  for_each = local.ssh_public_keys

  name       = each.key
  public_key = each.value

  labels = {
    managed-by = "opentofu"
    project    = "event-junkie"
  }
}
