# itbh.at PDF UA Generator

Renders Qute templates (XHTML + Print CSS) with JSON data into accessible
documents: PDF/UA, XHTML, email HTML, DOCX, ODT and plain text. Available as a
command-line tool and as a REST service.

> **Status:** being rebuilt on the branch `epic/rebuild`. The command-line
> tool renders every format; the REST service follows in phase 3. See the
> roadmap in the documentation.

## Usage

```bash
pdf-ua-generator render -t demo/demo.xhtml -d demo/data.json -a photo=demo/photo.png --verify
pdf-ua-generator render -t demo/demo.xhtml -d demo/data.json -a photo=demo/photo.png -f docx
```

The first command renders `demo/demo.xhtml` with the data from
`demo/data.json` and the attached photo into `demo/demo.pdf` and checks it
against PDF/UA-1; the second writes `demo/demo.docx`. Formats: `pdf`, `xhtml`,
`email-html`, `text`, `docx`, `odt`. The distribution
`cli/target/pdf-ua-generator-<version>.zip` contains the launcher
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

| Module   | Content                                                        |
|----------|----------------------------------------------------------------|
| `core`   | Template parsing, schema, validation, rendering. Plain Java.   |
| `cli`    | Command-line tool (Picocli). `cli/target/pdf-ua-generator-*.zip` |
| `server` | Quarkus REST service. `server/target/quarkus-app/`             |

## Documentation

The documentation is an Antora site under `documentation/`, organised along
[Diátaxis](https://diataxis.fr/). Start with the architecture page.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE). Third-party licences are listed in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
