#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 IT Beratung Hermann GmbH

# Renders the demo template in every format, in each demo layout, and checks each result:
#   PDF         veraPDF, PDF/UA-1
#   XHTML/email axe-core (WCAG 2.2 A/AA and best practices) in jsdom
#   ODT         ODF validator, extended conformance
#   DOCX, ODT   exported by LibreOffice (podman container) as PDF/UA, then veraPDF
# Output goes to target/formats/. Run through: mise run check-formats
set -uo pipefail
cd "$(dirname "$0")/.."

out=target/formats
jar=cli/target/pdf-ua-generator.jar
validator=target/tools/odfvalidator-0.13.0-jar-with-dependencies.jar
image=localhost/pdf-ua-generator-libreoffice

rm -rf "$out" && mkdir -p "$out"
if [ ! -f "$jar" ]; then
  mvn -B -ntp -q -pl cli -am package -DskipTests || exit 1
fi
cli() { java -Djava.awt.headless=true -jar "$jar" "$@"; }

fail=0
step() { echo; echo "== $1"; }
check() { "$@" || fail=1; }

# The same document in every demo layout: layout/ and layout-memo/.
layouts=(layout layout-memo)

step "Rendering"
for layout in "${layouts[@]}"; do
  dir="$out/$layout" && mkdir -p "$dir"
  demo=(-t demo/demo.xhtml --layout "demo/$layout" -d demo/data.json -a photo=demo/photo.png)
  for format in pdf xhtml text docx odt; do
    ext=$format; [ "$format" = text ] && ext=txt
    check cli render "${demo[@]}" -f "$format" -o "$dir/demo.$ext"
  done
  # Email HTML cannot use request attachments, so it is rendered without the photo.
  check cli render -t demo/demo.xhtml --layout "demo/$layout" -d demo/data-email.json -f email-html \
    --public-base-url https://assets.example.invalid -o "$dir/demo.email.html"
done

step "PDF/UA (veraPDF)"
for layout in "${layouts[@]}"; do
  check cli verify "$out/$layout/demo.pdf"
done

step "HTML accessibility (axe-core)"
axe=$(find "$(mise where npm:axe-core)" -name axe.min.js | head -1)
jsdom=$(find "$(mise where npm:jsdom)" -maxdepth 6 -type d -path '*node_modules/jsdom' | head -1)
for layout in "${layouts[@]}"; do
  check env AXE_SCRIPT="$axe" JSDOM_MODULE="$jsdom" node scripts/axe-check.mjs \
    "$out/$layout/demo.xhtml" "$out/$layout/demo.email.html"
done

step "ODF schema (odfvalidator, extended conformance)"
if [ ! -f "$validator" ]; then
  mvn -B -ntp -q dependency:copy -Dartifact=org.odftoolkit:odfvalidator:0.13.0:jar:jar-with-dependencies \
    -DoutputDirectory=target/tools || exit 1
fi
for layout in "${layouts[@]}"; do
  report=$(java -jar "$validator" -e "$out/$layout/demo.odt" 2>&1)
  if echo "$report" | grep -q "Error"; then
    echo "$report"
    fail=1
  else
    echo "$out/$layout/demo.odt: valid"
  fi
done

step "DOCX and ODT exported as PDF/UA by LibreOffice (veraPDF)"
if ! podman image exists "$image"; then
  podman build -q -t "$image" -f scripts/libreoffice/Containerfile scripts/libreoffice || exit 1
fi
filter='pdf:writer_pdf_Export:{"PDFUACompliance":{"type":"boolean","value":"true"}}'
for layout in "${layouts[@]}"; do
  for format in docx odt; do
    podman run --rm -v "$PWD/$out/$layout:/work:Z" -w /work "$image" \
      --convert-to "$filter" --outdir "lo-$format" "demo.$format" >/dev/null 2>&1
    check cli verify "$out/$layout/lo-$format/demo.pdf"
  done
done

echo
if [ "$fail" -ne 0 ]; then
  echo "check-formats: FAILED" >&2
  exit 1
fi
echo "check-formats: all formats passed"
