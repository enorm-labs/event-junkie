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
