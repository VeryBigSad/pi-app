resource "yandex_vpc_network" "pi_mobile" {
  name   = "pi-mobile"
  labels = var.labels
}

resource "yandex_vpc_subnet" "pi_mobile" {
  name           = "pi-mobile"
  zone           = var.zone
  network_id     = yandex_vpc_network.pi_mobile.id
  v4_cidr_blocks = ["10.91.0.0/28"]
  labels         = var.labels
}

resource "yandex_vpc_address" "pi_mobile" {
  name   = "pi-mobile"
  labels = var.labels
  external_ipv4_address {
    zone_id = var.zone
  }
}

resource "yandex_vpc_security_group" "pi_mobile" {
  name       = "pi-mobile"
  network_id = yandex_vpc_network.pi_mobile.id
  labels     = var.labels

  ingress {
    protocol       = "TCP"
    description    = "HTTPS relay and push"
    v4_cidr_blocks = ["0.0.0.0/0"]
    port           = 443
  }

  dynamic "ingress" {
    for_each = var.ssh_ingress_enabled ? [1] : []
    content {
      protocol       = "TCP"
      description    = "Restricted operator SSH"
      v4_cidr_blocks = [var.admin_cidr]
      port           = 22
    }
  }

  lifecycle {
    precondition {
      condition     = !var.ssh_ingress_enabled || var.admin_cidr != ""
      error_message = "admin_cidr must be set when ssh_ingress_enabled is true."
    }
  }

  egress {
    protocol       = "ANY"
    description    = "System updates, image pulls, ACME, and push delivery"
    v4_cidr_blocks = ["0.0.0.0/0"]
    from_port      = 0
    to_port        = 65535
  }
}

locals {
  public_ip_token = replace(yandex_vpc_address.pi_mobile.external_ipv4_address[0].address, ".", "-")
  relay_host      = "relay.${local.public_ip_token}.sslip.io"
  push_host       = "push.${local.public_ip_token}.sslip.io"
  cloud_init = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    relay_host  = local.relay_host
    push_host   = local.push_host
    relay_image = var.relay_image
    caddy_image = var.caddy_image
    ntfy_image  = var.ntfy_image
  })
}

resource "yandex_compute_instance" "pi_mobile" {
  name                      = "pi-mobile"
  hostname                  = "pi-mobile"
  platform_id               = "standard-v4a"
  zone                      = var.zone
  allow_stopping_for_update = true
  labels                    = var.labels

  resources {
    cores         = 2
    core_fraction = 20
    memory        = 2
  }

  boot_disk {
    auto_delete = true
    initialize_params {
      image_id = var.boot_image_id
      type     = "network-hdd"
      size     = 13
    }
  }

  network_interface {
    subnet_id          = yandex_vpc_subnet.pi_mobile.id
    nat                = true
    nat_ip_address     = yandex_vpc_address.pi_mobile.external_ipv4_address[0].address
    security_group_ids = [yandex_vpc_security_group.pi_mobile.id]
  }

  scheduling_policy {
    preemptible = false
  }

  metadata = {
    serial-port-enable = "0"
    ssh-keys           = "pimobile:${var.ssh_public_key}"
    user-data          = local.cloud_init
  }

  lifecycle {
    precondition {
      condition     = var.monthly_cost_estimate_rub <= var.max_monthly_cost_rub
      error_message = "monthly_cost_estimate_rub exceeds max_monthly_cost_rub; raise the cap only with explicit budget approval."
    }
  }
}
