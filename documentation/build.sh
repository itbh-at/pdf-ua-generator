#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 IT Beratung Hermann GmbH

# Builds the Antora docs.
#
#   ./build.sh          # build once into build/site
#   ./build.sh watch    # live mode: rebuild on file change, serve with reload
#
# `antora`, `watchexec` and `browser-sync` are pinned in mise.toml. Run through
# `mise run docs` / `mise run docs-watch`, or prefix with `mise exec --`.
set -euo pipefail
cd "$(dirname "$0")"

# The lunr search extension is a library, not a command: antora resolves the
# playbook's `require:` entry from `node_modules` beside the playbook, not via
# NODE_PATH. mise gives every npm package its own prefix, so link the module
# here; the link is refreshed on every build to survive upgrades. The
# directory is git-ignored.
if ! lunr_prefix="$(mise where "npm:@antora/lunr-extension" 2>/dev/null)" || [ -z "$lunr_prefix" ]; then
  echo "build.sh: @antora/lunr-extension is missing — run: mise install" >&2
  exit 1
fi
lunr_module="$(find "$lunr_prefix" -type d -path '*/@antora/lunr-extension' -print 2>/dev/null | head -1)"
if [ -z "$lunr_module" ]; then
  echo "build.sh: @antora/lunr-extension installed at $lunr_prefix but its module dir was not found" >&2
  exit 1
fi
mkdir -p node_modules/@antora
ln -sfn "$lunr_module" node_modules/@antora/lunr-extension

case "${1:-build}" in
  watch)
    antora antora-playbook.yml
    browser-sync start --server build/site --startPath / \
      --files 'build/site/**' --no-ui --no-notify &
    bs_pid=$!
    trap 'kill "$bs_pid" 2>/dev/null' EXIT INT TERM
    # Ignore the output dir, otherwise antora's own writes retrigger the build.
    watchexec --debounce 500ms --ignore 'build/**' \
      --exts adoc,yml,hbs,css,js,svg -- antora antora-playbook.yml
    ;;
  build)
    antora antora-playbook.yml
    ;;
  *)
    echo "Usage: $0 [build|watch]" >&2
    exit 1
    ;;
esac
