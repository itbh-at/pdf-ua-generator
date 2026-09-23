# itbh.at PDF UA Generator

Renders document templates (Qute XHTML + Print CSS) with JSON data into
accessible documents: PDF/UA, XHTML, email HTML, DOCX, ODT and plain text.
Available as a command-line tool and as a REST service.

> **Status:** being rebuilt on the branch `epic/rebuild`. The command-line
> tool renders every format; the REST service follows in phase 3. See the
> roadmap in the documentation.

## Usage

```bash
pdf-ua-generator render -t demo/content/template.xhtml -L demo/layout -d demo/content/example.json -a photo=demo/content/example/photo.png --verify
pdf-ua-generator render -t demo/content/template.xhtml -L demo/layout-memo -d demo/content/example.json -a photo=demo/content/example/photo.png -f docx
pdf-ua-generator schema -t demo/content/template.xhtml -L demo/layout
pdf-ua-generator check -t demo/content/template.xhtml -L demo/layout -d demo/content/example.json -a photo=demo/content/example/photo.png
```

The first command renders the document template whose default variant is
`demo/content/template.xhtml` in the layout `demo/layout` with the data from
`demo/content/example.json` and the attached photo into
`demo/content/template.pdf` and checks it against PDF/UA-1; the second writes
the same document as `demo/content/template.docx` in the memo layout. Formats:
`pdf`, `xhtml`, `email-html`, `text`, `docx`, `odt`. `schema` prints the JSON
Schema of the data the document template needs, derived from its language
variants and its field definitions in `demo/content/template.json`; `check` runs
the checks a document template must pass before it is published. The
distribution `cli/target/pdf-ua-generator-<version>.zip` contains the launcher
`bin/pdf-ua-generator`.

## Build

All toolchains are pinned in `mise.toml`.

```bash
mise install          # JDK, Maven, Antora, …
mise run setup-hooks  # once per clone: refuse commits with unformatted code
mise run build        # compile, test and package all modules
mise run check-formats  # render the demo in every format and check each
mise run docs         # build the documentation into documentation/build/site
```

Modules:

| Module   | Content                                                                         |
|----------|---------------------------------------------------------------------------------|
| `core`   | Parsing of document templates and layouts, schema, validation, rendering. Plain Java. |
| `cli`    | Command-line tool (Picocli). `cli/target/pdf-ua-generator-*.zip`                |
| `server` | Quarkus REST service. `server/target/quarkus-app/`                              |

## Documentation

The documentation is an Antora site under `documentation/`, organised along
[Diátaxis](https://diataxis.fr/). Start with the architecture page.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE). Third-party licences are listed in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
