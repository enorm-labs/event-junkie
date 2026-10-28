variable "ssh_key_ids" {
  description = "From the bootstrap stack's `ssh_key_ids` output."
  type        = list(string)
  nullable    = false
}

variable "ssh_public_keys" {
  description = "The same keys as authorized_keys lines, for the unprivileged `ops` user."
  type        = list(string)
  nullable    = false
}

variable "admin_cidrs" {
  description = "Bootstrap-only source ranges for SSH and the Kubernetes API. Empty is the steady state."
  type        = list(string)
  default     = []
  nullable    = false
}

variable "wireguard_peers" {
  description = <<-EOT
    WireGuard clients. Public keys only.

    Give staging peers different tunnel addresses from production's, on a different subnet — both
    tunnels are routinely up at once, and overlapping `AllowedIPs` means one silently swallows the
    other's traffic.
  EOT
  type = list(object({
    name       = string
    public_key = string
    address    = string
  }))
  default  = []
  nullable = false
}

variable "next_pair" {
  description = "A second pair of nodes to cut over to. Pass it with -var for that apply only. See the module's variable of the same name."
  type = object({
    k3s_server_type      = string
    postgres_server_type = optional(string)
    k3s_private_ip       = optional(string)
    postgres_private_ip  = optional(string)
  })
  default = null
}
