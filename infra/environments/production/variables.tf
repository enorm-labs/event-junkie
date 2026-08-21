variable "ssh_key_ids" {
  description = "From the bootstrap stack's `ssh_key_ids` output."
  type        = list(string)
  nullable    = false
}

variable "ssh_public_keys" {
  description = "The same keys as authorized_keys lines, for the unprivileged `ops` user. See the module's variable of the same name for why both are needed."
  type        = list(string)
  nullable    = false
}

variable "admin_cidrs" {
  description = <<-EOT
    Bootstrap-only source ranges for SSH and the Kubernetes API. Empty is the steady state — set it
    for the first apply, then clear it once the tunnel works:

        ADMIN="[\"$(curl -s https://ifconfig.me)/32\",\"$(dig +short myip.opendns.com @resolver1.opendns.com | tail -1)/32\"]"
        tofu apply -var "admin_cidrs=$ADMIN"

    Two addresses because a corporate HTTP proxy egresses from a different one than unproxied SSH
    and WireGuard do; an allowlist built from `ifconfig.me` alone refuses the connection it was
    meant to admit.
  EOT
  type        = list(string)
  default     = []
  nullable    = false
}

variable "wireguard_peers" {
  description = "WireGuard clients. Public keys only."
  type = list(object({
    name       = string
    public_key = string
    address    = string
  }))
  default  = []
  nullable = false
}

variable "domain" {
  description = "Zone the address records are written into. The zone itself is owned by the bootstrap stack."
  type        = string
  default     = "event-junkie.de"
  nullable    = false
}

variable "dns_ttl" {
  description = "TTL for the address records. Keep it low until go-live, then raise it."
  type        = number
  default     = 300
  nullable    = false
}

variable "publish_dns" {
  description = <<-EOT
    Whether the apex and `www` resolve to this environment — in other words, whether the site is
    live. **This is the go-live switch, and it is the only one.** `public_web` opens 80/443 in the
    firewall, but with nothing resolving to the node that buys an attacker a Traefik 404; the A and
    AAAA records at `@` are what put the site in front of people.

    False publishes a single throwaway name, `prod-check`, at the same addresses instead. That is
    not a decoration: production solves **HTTP-01**, which needs Let's Encrypt to reach the host by
    name, so with no name at all the certificate cannot be issued and the entire TLS path — the
    firewall, the ingress, the CAA record, ACME reachability — stays unrehearsed until the moment
    the domain goes live. One name that nobody is looking for rehearses all of it.

    Swapped rather than added, so there is never a forgotten record: at go-live `prod-check`
    disappears in the same apply that publishes the apex.

    **Defaults to false, and flipping it is the launch.** Not a `terraform.tfvars` value, which is
    gitignored and would make go-live an act with no record: this is a one-line commit, reviewed and
    dated like anything else that matters. A fresh environment comes up dark, which is the only
    sensible default for a switch whose other position is "the public can see this".
  EOT
  type        = bool
  default     = false
  nullable    = false
}
