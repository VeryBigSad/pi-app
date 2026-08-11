terraform {
  required_version = ">= 1.5.7, < 2.0.0"

  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "0.220.0"
    }
  }
}

provider "yandex" {
  folder_id = var.folder_id
  zone      = var.zone
}
