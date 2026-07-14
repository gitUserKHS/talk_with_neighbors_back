#!/usr/bin/env bash

readonly K3S_DESIRED_CLUSTER_CIDR="10.244.0.0/16"
readonly K3S_DESIRED_SERVICE_CIDR="10.96.0.0/16"
readonly K3S_DESIRED_CLUSTER_DNS="10.96.0.10"

k3s_expected_reinitialize_confirmation() {
  local instance_id="${1:?instance id is required}"
  printf 'REINITIALIZE_K3S_NETWORK:%s:pods=%s:services=%s:dns=%s' \
    "$instance_id" \
    "$K3S_DESIRED_CLUSTER_CIDR" \
    "$K3S_DESIRED_SERVICE_CIDR" \
    "$K3S_DESIRED_CLUSTER_DNS"
}

k3s_is_legacy_pod_cidr() {
  local pod_cidr="${1:-}"
  [[ "$pod_cidr" =~ ^10\.42\.[0-9]{1,3}\.[0-9]{1,3}/(1[6-9]|2[0-9]|3[0-2])$ ]]
}

k3s_is_desired_pod_cidr() {
  local pod_cidr="${1:-}"
  [[ "$pod_cidr" =~ ^10\.244\.[0-9]{1,3}\.[0-9]{1,3}/(1[6-9]|2[0-9]|3[0-2])$ ]]
}

k3s_is_desired_service_ip() {
  local service_ip="${1:-}"
  [[ "$service_ip" =~ ^10\.96\.[0-9]{1,3}\.[0-9]{1,3}$ ]]
}

k3s_config_has_desired_network() {
  local config_file="${1:?config file is required}"
  [[ -s "$config_file" ]] &&
    grep -Fqx 'cluster-cidr: "10.244.0.0/16"' "$config_file" &&
    grep -Fqx 'service-cidr: "10.96.0.0/16"' "$config_file" &&
    grep -Fqx 'cluster-dns: "10.96.0.10"' "$config_file"
}

k3s_wait_for_single_node_registration() {
  local attempts="${1:-120}"
  local attempt node_name
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if node_name="$(
      k3s kubectl get nodes -o json 2>/dev/null \
        | jq -er 'if (.items | length) == 1 then .items[0].metadata.name else empty end'
    )" && [[ -n "$node_name" ]]; then
      printf '%s\n' "$node_name"
      return 0
    fi
    sleep 5
  done
  return 1
}
