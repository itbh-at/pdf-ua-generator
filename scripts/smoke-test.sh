#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 IT Beratung Hermann GmbH
#
# Runs the rendered chart under podman: the service image with PostgreSQL, the
# import job with the demo bundles, then a render through the API. Everything
# is removed again afterwards. Run through `mise run smoke-test`.
set -eu

CHART=deploy/helm/pdf-ua-generator
# The deployable image is the one carrying the AOT cache, which `mise run image`
# tags with the suffix "-aot".
TAG=${TAG:-2.0.0-SNAPSHOT-aot}
IMAGE=${IMAGE:-itbh/pdf-ua-generator:$TAG}
BUNDLES=${BUNDLES:-localhost/pdf-ua-generator-bundles:smoke}
NETWORK=${NETWORK:-podman-default-kube-network}
BASE=http://pdf-ua-generator-pod:8080
WORK=$(mktemp -d)

# podman publishes no Services, and the chart uses no hostPort, so the requests
# run inside the network — with curl from the bundle image.
api() {
    podman run --rm -i --network "$NETWORK" --entrypoint curl "$BUNDLES" "$@"
}

cleanup() {
    podman kube down "$WORK/manifests.yaml" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

fail() {
    echo "smoke test failed: $1" >&2
    podman logs "$(podman ps -aq --filter label=app.kubernetes.io/name=pdf-ua-generator | head -1)" 2>&1 | tail -30 >&2 || true
    exit 1
}

podman image exists "$IMAGE" || fail "$IMAGE is missing; run 'mise run image' first"
podman build -q -f deploy/bundles/Containerfile -t "$BUNDLES" . >/dev/null

helm template pdf-ua-generator "$CHART" -f "$CHART/values-podman.yaml" \
    --set image.repository="${IMAGE%:*}" --set image.tag="${IMAGE##*:}" \
    --set image.pullPolicy=Never \
    --set import.enabled=true --set import.image="$BUNDLES" --set import.pullPolicy=Never \
    > "$WORK/manifests.yaml"

echo "starting the pods"
podman kube play "$WORK/manifests.yaml" >/dev/null

echo "waiting for readiness"
ready=0
i=0
while [ "$i" -lt 120 ]; do
    if api -fsS -o /dev/null "$BASE/q/health/ready" 2>/dev/null; then
        ready=1
        break
    fi
    i=$((i + 1))
    sleep 2
done
[ "$ready" = 1 ] || fail "the service did not become ready"

echo "waiting for the import"
i=0
while [ "$i" -lt 120 ]; do
    published=$(api -fsS "$BASE/templates" | tr ',' '\n' | grep -c '"publishedRevision"' || true)
    [ "$published" -ge 3 ] && break
    i=$((i + 1))
    sleep 2
done
[ "${published:-0}" -ge 3 ] || fail "the import job did not publish the three demo bundles"

echo "rendering"
api -fsS -H 'Content-Type: application/json' -H 'Accept: application/pdf' \
    --data-binary @- -o - "$BASE/templates/demo/render" \
    < demo/data-email.json > "$WORK/demo.pdf" || fail "the render request failed"
head -c 5 "$WORK/demo.pdf" | grep -q '%PDF-' || fail "the answer is not a PDF"

if [ -f cli/target/pdf-ua-generator.jar ]; then
    java -jar cli/target/pdf-ua-generator.jar verify "$WORK/demo.pdf" >/dev/null \
        || fail "the rendered PDF is not PDF/UA"
    echo "PDF/UA verified"
else
    echo "cli/target/pdf-ua-generator.jar is missing; PDF/UA not verified (run 'mise run build')"
fi

# The import is idempotent: running the job again must not add a revision.
before=$(api -fsS "$BASE/templates/demo" | tr ',' '\n' | grep '"latestRevision"')
podman kube play --replace "$WORK/manifests.yaml" >/dev/null
i=0
while [ "$i" -lt 60 ]; do
    api -fsS -o /dev/null "$BASE/q/health/ready" 2>/dev/null && break
    i=$((i + 1))
    sleep 2
done
sleep 10
after=$(api -fsS "$BASE/templates/demo" | tr ',' '\n' | grep '"latestRevision"')
[ "$before" = "$after" ] || fail "the second import created a revision: $before -> $after"

echo "smoke test passed"
