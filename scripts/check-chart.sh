#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 IT Beratung Hermann GmbH
#
# Renders the Helm chart with both values files and validates the manifests
# against the Kubernetes schemas. Run through `mise run check-chart`.
set -eu

CHART=deploy/helm/pdf-ua-generator
KUBERNETES_VERSION=${KUBERNETES_VERSION:-1.34.0}

render() {
    helm template pdf-ua-generator "$CHART" "$@"
}

check() {
    label=$1
    shift
    printf '%s: ' "$label"
    render "$@" | kubeconform -strict -summary -kubernetes-version "$KUBERNETES_VERSION" -
}

# Kubernetes: external database, autoscaler, import job.
check "kubernetes" \
    --set database.password=secret \
    --set import.enabled=true \
    --set import.image=registry.example.org/bundles:1
# podman: fixed replica count, database beside the service, no autoscaler.
check "podman" -f "$CHART/values-podman.yaml"
