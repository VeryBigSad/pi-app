output "instance_id" {
  value = yandex_compute_instance.pi_mobile.id
}

output "public_ip" {
  value = yandex_vpc_address.pi_mobile.external_ipv4_address[0].address
}

output "relay_url" {
  value = "wss://${local.relay_host}"
}

output "push_url" {
  value = "https://${local.push_host}"
}

output "bootstrap_command" {
  value = "ssh pimobile@${yandex_vpc_address.pi_mobile.external_ipv4_address[0].address} sudo /usr/local/bin/pimobile-bootstrap-read"
}
