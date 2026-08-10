locals {
  # Reduce every key to `<type> <base64>`, dropping the trailing comment and normalising
  # whitespace.
  #
  # Two Hetzner behaviours make this necessary rather than tidy. Keys are deduplicated by
  # **fingerprint across the whole project**, so declaring one that already exists — added by hand
  # when the project was set up, for instance — fails the first apply with `uniqueness_error` and
  # has to be imported. And the API stores the key **without its comment**, so an imported key
  # reads back as `ssh-ed25519 AAAA…` while the config says `ssh-ed25519 AAAA… you@laptop`.
  # `public_key` forces replacement, so that cosmetic difference would destroy and recreate the
  # key on every single apply. Comparing only the parts that identify the key removes the whole
  # class of problem.
  #
  # The `ops` user's `authorized_keys` keeps its comment — see the environment module. Comments are
  # useful there, because that file is read by humans deciding which key to revoke.
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
