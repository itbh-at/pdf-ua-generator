# CLAUDE.md — Ground Rules for LLMs in this Project

This file tells coding agents (Claude Code and others) exactly how work is done
in this project. It takes precedence over default behaviour. It carries standing
rules and settled facts only — never plans, rationale or design (those live in
the docs).

## Project in one sentence

A Java/Quarkus service and CLI that renders Qute templates (XHTML + Print CSS),
loaded at runtime and filled with JSON data, into accessible documents
(PDF/UA via openhtmltopdf and Apache PDFBox, XHTML, email HTML, DOCX, ODT,
plain text).

## Stack

- Java, Quarkus, Qute, openhtmltopdf (`io.github.openhtmltopdf`), Apache PDFBox,
  Apache Batik (SVG), Picocli. Build with Maven through mise:
  `mise run build`.
- Base package: `at.itbh.pdfuagen`.
- Three Maven modules:
  - `core` — plain Java library: template parsing, schema derivation,
    validation, rendering. Uses standalone `qute-core`, no Quarkus. The Qute
    engine is configured here only, so CLI and server render identically.
    Template lookup goes through an interface; `core` has no database or HTTP
    code.
  - `cli` — plain Java + Picocli on top of `core`. No Quarkus, no database, no
    network access. Everything is done through command-line arguments, files and
    stdin/stdout. No editor or other interactive features.
  - `server` — Quarkus REST service on top of `core`: template store
    (PostgreSQL, schema migrations with Flyway, plain JDBC), API, security. The
    CLI does not depend on it.
- Single-tenant. No tenant concept in data model, API or UI; separate tenants
  are separate deployments (cluster or namespace).
- No rendering, schema or validation logic in `cli` or `server`; it belongs in
  `core`.
- Editor UI: server-rendered Qute pages with htmx, served by `server`. Plain
  JavaScript only for the embedded editors (TipTap/ProseMirror for blocks,
  CodeMirror 6 for code); JS dependencies via mvnpm and the Quarkus Web
  Bundler. No TypeScript, no separate Node build.
- The UI works only through the REST API. The stored format of block-edited
  templates is our own JSON format defined in `core`, not the document format of
  any editor library; `core` translates it into Qute XHTML and escapes all
  literal text, so only placeholder nodes produce Qute expressions.
- Qute and XHTML diagnostics for the editors (line, column, message) come from
  the server; the browser does not parse Qute itself.
- Templates are parsed at runtime. The server adds, changes and deletes them
  at runtime through the API; the CLI reads them from files and directories
  given as arguments. No template is part of the application build: no
  `src/main/resources/templates`, no `@CheckedTemplate`, no type-safe template
  validation at build time.
- Checks that build-time Qute would provide (parse errors, unknown expressions)
  are done at runtime when a template revision is saved.
- Every template revision has a JSON Schema (draft 2020-12) describing the data
  it needs. It is derived from the template and its field definitions and
  exposed through the API and the CLI.
- A template's descriptor `<name>.json` holds the language of the default
  variant and the field definitions (types `text`, `number`, `date`,
  `boolean`, `image`, `list`, `object`); all language variants share it.
  Every field a template reads must be defined.
- Numbers are JSON numbers and dates ISO 8601 strings in the data; templates
  format them (`.number`, `.currency('EUR')`, `.date`) for the variant's
  language. No pre-formatted amounts or dates in the data.
- `{#with}` is not available: its names cannot be resolved without the data.
- Input data is validated against that schema before rendering, and can be
  validated without rendering. Additional properties not used by the template
  are allowed; missing required values and wrong types are errors.
- All API errors are RFC 9457 problem details (`application/problem+json`),
  produced by the Quarkiverse extension
  `io.quarkiverse.httpproblem:quarkus-http-problem`. No hand-written exception
  mappers that bypass it.
- Data validation errors use the RFC 9457 `errors` member: one entry per
  violation with `detail` and `pointer` (JSON Pointer as URI fragment, e.g.
  `#/items/2/price`).
- Problem `type` values are URNs of the form
  `urn:itbh:pdf-ua-generator:problem:<name>` (e.g. `…:problem:invalid-data`).
  Every URN is explained on the problem-types reference page of the docs; a new
  URN is not used before it is documented there.
- Template data is JSON. No `Map<String, String>` or other flattening of the data.
- A server template revision is a bundle laid out like a CLI template
  directory (`template.xhtml`, `template.json`, variants, assets) plus
  `example.json` and `example/` for the publish checks. Revisions are
  immutable; in-memory caches are keyed by content hash, and status is always
  read from the database.

## Template styling

- A layout is a template with `layout.json` (style catalog, internal classes,
  areas, components with their parameters, fonts, free-styling permission,
  fields), `template.xhtml` with `{#insert}` areas, `components/<name>.xhtml`,
  `messages.json` plus `messages.<tag>.json`, `email.css`, and optionally
  `layout.dotx` and `layout.ott`.
- A content template's descriptor pins one layout revision
  (`"layout": "corporate@3"`); the content is one `{#include layout}` filling
  declared areas. The layout's files appear under `layout/`.
- Default: content templates are styled only through the style catalog (named
  CSS classes) and the components (Qute user tags) of their layout. No `style`
  attribute, no `<style>` block, no `<link>`, no font declarations; class
  values are literal. These rules are checked on the template source.
- Fallback: free CSS (`<style>` blocks and `style` attributes) is an explicit
  per-revision opt-in (`styling: free`), allowed only if the layout permits it.
  The flag is visible in the API and the UI.
- Free CSS still has hard limits: no `@import`, no `!important`, `url()` only to
  template assets or request attachments, `font-family` only fonts declared by
  the layout.
- Free CSS never bypasses the publish checks: element whitelist, alt texts,
  heading structure and veraPDF apply unchanged.
- A template with `styling: free` is rendered to PDF and XHTML only. DOCX and
  ODT are not offered for it (see Output formats).

## Language variants

- A template may, but need not, exist in several languages: the default file
  (`template.xhtml`) plus optional variants named with a BCP 47 tag
  (`template.de-AT.xhtml`, `template.en.xhtml`). Layout texts come from the
  layout's message files per language.
- Selection: the API matches `Accept-Language` by RFC 4647 lookup; a `lang`
  query parameter takes precedence; the CLI uses `--lang`. No matching variant
  means the default variant — never an error. Responses carry
  `Content-Language` and `Vary: Accept-Language`.
- All variants of a template use the same data fields (one JSON Schema); the
  publish check verifies this. Date, number and currency formatting follow the
  selected language.

## Output formats

- Formats: PDF (openhtmltopdf), XHTML, email HTML, DOCX, ODT, plain text. Qute
  renders once; every format is produced from the same rendered XHTML.
- DOCX and ODT are produced from a format-neutral document model in `core`. The
  XHTML is translated into that model; the accessibility checker for DOCX and
  ODT runs on the model, once for both formats.
- DOCX and ODT writers: no office library — JDK StAX and `ZipOutputStream`
  (DOCX: WordprocessingML; ODT: ODF 1.3). No docx4j, no ODF Toolkit.
- Writers are Java code, not Qute templates. XML and HTML output (DOCX, ODT,
  email HTML) is written through the StAX writer `Xml`, never assembled from
  strings; plain text is the only output built as a string.
- Running headers and footers come from the `@page` margin boxes of the
  template CSS (strings, `counter(page)`, `counter(pages)`) and are written to
  PDF, DOCX and ODT; anything else in their `content` is an error.
- Decorative images (`alt=""` or `role="presentation"`) become CSS backgrounds
  before PDF rendering, so openhtmltopdf marks them as artifacts.
- The model supports a fixed set of building blocks only: headings,
  paragraphs, lists, tables with header rows, images (with alt text or marked
  decorative), links, `strong`/`em`, `lang` changes, page breaks, catalog
  styles, and the blocks `columns`, `col`, `box`, `footnote`. Components mark
  these blocks with `data-block="…"`. Anything outside this set is an error,
  never silently approximated.
- Page layout and styles for DOCX and ODT come from a template file per layout:
  `.dotx` for DOCX, `.ott` for ODT. Every catalog style has the same name in
  the layout CSS, the `.dotx` and the `.ott`; the publish check verifies this.
- Email: `email-html` is a separate rendition — layout-provided `email.css`,
  styles inlined into `style` attributes, absolute image URLs, layout tables
  with `role="presentation"`. Not offered for `styling: free`.
- Plain text is written from the document model (headings underlined, links as
  `text <url>`, images as alt text, footnotes collected, UTF-8, wrapped at 72).
  A template may ship its own `template.txt`, which then replaces it.
- `Accept: multipart/alternative` returns plain text and email HTML from one
  render (text first, HTML last, RFC 2046). The service never sends mail.
- Images: PNG, JPEG and SVG only. Template assets are served publicly at
  `/assets/{sha256}` (immutable). External image URLs are allowed only from a
  configured host allowlist: passed through unchanged in email HTML, fetched
  (HTTPS only, no cross-host redirects, no private address ranges, time and
  size limits) for PDF, DOCX and ODT. Request attachments (`attachment:`) are
  not usable in email HTML.
- Request attachments (`attachment:<name>`) must be PNG, JPEG or SVG images;
  fonts, stylesheets and anything else are rejected.
- Fonts are assets of the layout (stored and versioned with the revision,
  loaded via `@font-face` from the layout CSS; CLI: template directory or
  include paths). No fonts in the container image, no shared volume, no system
  fonts: a font that is not an asset would fall back to a non-embedded standard
  font and fail PDF/UA. Uploading a font checks its embedding permission (OS/2
  `fsType`); fonts that forbid embedding are rejected.
- Every template declares the formats it offers. A request for a format the
  template does not offer is answered with 406 and the problem type
  `urn:itbh:pdf-ua-generator:problem:format-not-supported`.

## Runtime and deployment

- JVM mode only; no GraalVM native image.
- The REST service runs standalone on a JVM, as an OCI image under podman, and
  horizontally scaled in Kubernetes.
- Service instances are stateless: no local session or output state, no shared
  cache between instances. Every instance can serve every request.
- All configuration via MicroProfile Config (`application.properties`,
  overridable by environment variables). Nothing hard-coded that differs
  between environments.
- The service exposes readiness and liveness endpoints and shuts down
  gracefully.
- One Helm chart serves both Kubernetes and `podman kube play`: chart under
  `deploy/helm/pdf-ua-generator/`, `values.yaml` for Kubernetes,
  `values-podman.yaml` for podman. podman consumes the rendered chart:
  `helm template … -f values-podman.yaml | podman kube play -`.
- The chart renders only plain manifests: no hooks, no subcharts, no lookups,
  no `hostPort`. PostgreSQL for podman is a minimal template in the chart,
  enabled only by `values-podman.yaml`; on Kubernetes the database is external
  and configured through values.
- Every chart change is verified by rendering it with both values files.

## Accessibility

- Every generated output must be accessible. PDF output must pass veraPDF
  PDF/UA validation; a change that breaks this is a failing change.
- veraPDF is a library dependency of `core` (used by the CLI `check` command and
  by the server when a template revision is published), not an external tool.
- `mise run check-formats` renders the demo template in every format and checks
  each (veraPDF, axe-core, ODF validator, LibreOffice export as PDF/UA). It
  must pass after every change to rendering or a writer.
- Demo and test templates use semantic XHTML: `lang` attribute, document title,
  heading hierarchy, `alt` on images, `th`/`scope` on tables, embedded fonts.

## Language

- **Everything in this repository is English** — code, comments, doc-comments,
  AsciiDoc pages, README, config comments, commit messages, logs, CLI output,
  API error messages, and this file.
- Chat with the user is in the language the user uses.

## Communication

Answers are crisp, specific, and to the point — no filler, no slang, no
marketing tone, no self-praise. Adopt the stance of a seasoned, pragmatic
old-school senior developer: direct and minimal.

- **No small talk, no preamble, no narration.** Start straight with the answer or
  the code. Do not announce what you are about to do, do not retell the steps at
  the end.
- **No jargon, no buzzwords, no marketing language.** No flowery description of
  your thought process ("I solved this elegantly…"), no self-congratulatory
  explanations.
- **Report only failures and assumptions** — a failing test or check, and any
  assumption you had to make. Nothing else: no summary of what was done.
- **Explain only on request.** When code is asked for, deliver only the code plus
  the minimum necessary inline comments — no prose around it.
- **Recommend, do not enumerate.** Give one recommendation instead of listing
  every option.

## Scope

- **Change nothing that is not directly related to the request.**
- A genuine problem with the request is worth one or two sentences — then finish
  the work under a stated assumption rather than stopping.

## Toolchain — mise only

- **All** toolchains and dev tools (JDK, Maven, Node, Antora, Helm, axe-core,
  …) are pinned in `mise.toml` and used through `mise`. No SDKMAN, no system JDK, no
  `./mvnw`.
- `mise run <task>` when a task exists (the list is in `mise.toml`), otherwise
  `mise exec -- <cmd>`.
- Use mise inside container images too (same `mise.toml`), not an
  `eclipse-temurin:`/`maven:` base image.
- Not managed by mise, and therefore a host prerequisite: `podman`, with its
  user socket active (`systemctl --user enable --now podman.socket`); the
  server tests and dev mode start PostgreSQL through it (`DOCKER_HOST` is set
  in `mise.toml`). LibreOffice
  for the format check runs in a podman image
  (`scripts/libreoffice/Containerfile`), never from the host.

## Documentation

- AsciiDoc, built with Antora. Antora component under **`documentation/`** (not
  `docs/`), laid out as in https://github.com/itbh-at/wusel/tree/main/documentation.
- UI bundle: the ITBH theme, checked in unpacked under `documentation/ui-bundle`,
  taken from https://github.com/itbh-at/wusel/tree/main/documentation/ui-bundle
  (adapted from the Antora Default UI; MPL-2.0 — keep its `LICENSE` and
  `NOTICE`).
- Build: `mise run docs` (official) or `mise run docs-watch` (live).
- The docs follow **Diátaxis** (https://diataxis.fr/): `tutorials/`, `how-to/`,
  `reference/`, `explanation/` and `project/` under
  `documentation/modules/ROOT/pages/`. Put a page in the quadrant that matches
  what it *does*, and do not mix modes on one page — a how-to explains nothing,
  an explanation instructs nobody.
- Plans, rationale and design live only in the docs, never in this file. Start
  at the [Roadmap](documentation/modules/ROOT/pages/project/roadmap.adoc)
  (phases and their status) and the
  [Architecture](documentation/modules/ROOT/pages/explanation/architecture.adoc).
  Update the roadmap status when a phase is completed.

## Branches

- The rebuild is done directly on the branch `epic/rebuild`, created from
  `main`. No phase branches: phases are strictly serial (0, 1, 1a, 2, …), each
  completed before the next starts.
- `main` receives no rebuild work until the epic is merged as a whole.
- `epic/rebuild` stays buildable after every commit.
- Work stays local for now: no push to `origin`.

## Licence

- The project is licensed under Apache-2.0. Every source file carries an SPDX
  header (`SPDX-License-Identifier: Apache-2.0`, `Copyright <year> IT Beratung
  Hermann GmbH`).
- The root `NOTICE` holds only the project name and copyright. It is not a
  dependency list; that is `THIRD_PARTY_LICENSES.md` (informational).
- Every binary distribution (CLI with `lib/`, container image) ships its own
  `NOTICE` and the licence texts of the bundled libraries, assembled by the
  build from the bundled JARs (including their own `NOTICE` files), never
  maintained by hand.
- LGPL libraries are shipped as separate JARs (CLI: `lib/` directory; server:
  Quarkus fast-jar), never merged into an uber-jar.

## Code style

- Java sources follow Google Java Style, enforced by Spotless
  (`google-java-format`). Run `mise run fmt` before every commit; the build
  (`mise run build`) fails on unformatted code.
- No commit without Spotless formatting: the pre-commit hook in `.githooks/`
  refuses it. Activate it once per clone with `mise run setup-hooks`; never
  bypass it (`--no-verify`).

## Dependencies

- Keep third-party dependencies to a minimum.
- Runtime dependencies must be Apache-2.0, MIT, BSD, EPL, MPL-2.0 or LGPL
  licensed. No GPL/AGPL at runtime; GPL tools only as test or build tools.
  Dual-licensed libraries (e.g. veraPDF, GPL-3.0 or MPL-2.0) are used under the
  permissive option.
- Every added or removed dependency is reflected in `THIRD_PARTY_LICENSES.md`.
- All openhtmltopdf artifacts share one groupId and one version.
