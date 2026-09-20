#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 IT Beratung Hermann GmbH

# Prints the DOCKER_HOST that the server tests and the smoke test use to reach
# podman, so development works on both Linux and macOS. mise reads it into the
# environment (see mise.toml).
#
#   - An explicit DOCKER_HOST in the environment always wins (and costs nothing:
#     no podman is invoked).
#   - On macOS podman runs in a VM whose socket path is dynamic, so it is read
#     from `podman machine inspect`.
#   - On Linux the rootless socket lives under XDG_RUNTIME_DIR; the host
#     prerequisite is `systemctl --user enable --now podman.socket`.
#
# Always exits 0 so a machine without podman can still run the non-server tasks.

if [ -n "$DOCKER_HOST" ]; then
  printf '%s' "$DOCKER_HOST"
elif [ "$(uname -s)" = Darwin ] && command -v podman >/dev/null 2>&1; then
  printf 'unix://%s' \
    "$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}' 2>/dev/null)"
else
  printf 'unix://%s/podman/podman.sock' "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
fi
