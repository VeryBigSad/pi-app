variable "folder_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z0-9]{20}$", var.folder_id))
    error_message = "folder_id must be a Yandex Cloud folder identifier"
  }
}

variable "zone" {
  type    = string
  default = "ru-central1-d"
}

variable "boot_image_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z0-9]{20}$", var.boot_image_id))
    error_message = "boot_image_id must be an immutable Yandex Compute image identifier"
  }
}

variable "ssh_ingress_enabled" {
  description = "Whether TCP 22 is exposed to admin_cidr. Set false to disable SSH ingress entirely."
  type        = bool
  default     = true
}

variable "admin_cidr" {
  description = "Restricted operator IPv4 CIDR for SSH, no broader than /24. Empty only when ssh_ingress_enabled is false."
  type        = string
  default     = ""
  validation {
    condition = var.admin_cidr == "" || (
      can(regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}/(2[4-9]|3[0-2])$", var.admin_cidr)) &&
      can(cidrnetmask(var.admin_cidr))
    )
    error_message = "admin_cidr must be empty (only with ssh_ingress_enabled = false) or an IPv4 CIDR no broader than /24"
  }
}

variable "ssh_public_key" {
  type = string
  validation {
    condition     = can(regex("^ssh-ed25519 [A-Za-z0-9+/]+={0,3}( [^\\r\\n]+)?$", var.ssh_public_key))
    error_message = "ssh_public_key must be exactly one single-line Ed25519 public key"
  }
}

variable "relay_image_digest" {
  description = "Immutable sha256 digest of the relay image, pushed to the pi-mobile Yandex Container Registry by infra/local/push-relay-image.sh. The full reference cr.yandex/<registry-id>/relay@<digest> is constructed from the registry resource."
  type        = string
  validation {
    condition     = can(regex("^sha256:[0-9a-f]{64}$", var.relay_image_digest))
    error_message = "relay_image_digest must be a lowercase sha256:<64 hex> digest"
  }
}

variable "caddy_image" {
  type    = string
  default = "docker.io/library/caddy@sha256:844f60b64e4724a5aa8245e019dace0d3f199f7433ce6c57676cb30a920dbad9"
  validation {
    condition     = can(regex("^docker\\.io/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$", var.caddy_image))
    error_message = "caddy_image must be an immutable Docker Hub digest"
  }
}

variable "ntfy_image" {
  type    = string
  default = "docker.io/binwiederhier/ntfy@sha256:f2419f405127afa868f10985c1a41449e673477cee1eb19994339a5ae8b592e7"
  validation {
    condition     = can(regex("^docker\\.io/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$", var.ntfy_image))
    error_message = "ntfy_image must be an immutable Docker Hub digest"
  }
}

variable "monthly_cost_estimate_rub" {
  description = "Current Yandex Cloud calculator estimate for this deployment in RUB per month; must be refreshed immediately before plan."
  type        = number
  validation {
    condition     = var.monthly_cost_estimate_rub > 0
    error_message = "monthly_cost_estimate_rub must be a positive current calculator estimate in RUB"
  }
}

variable "max_monthly_cost_rub" {
  description = "Approved hard monthly budget cap in RUB. Raise only with explicit approval."
  type        = number
  default     = 1500
  validation {
    condition     = var.max_monthly_cost_rub > 0
    error_message = "max_monthly_cost_rub must be positive"
  }
}

variable "labels" {
  type = map(string)
  default = {
    app       = "pi-mobile"
    managedby = "terraform"
  }
}
