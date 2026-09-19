# itbh.at PDF UA Generator

Renders Qute templates (XHTML + Print CSS) with JSON data into accessible
documents: PDF/UA, XHTML, email HTML, DOCX, ODT and plain text. Available as a
command-line tool and as a REST service.

> **Status:** being rebuilt on the branch `epic/rebuild`. The previous
> command-line tool has been removed; its functionality returns in phase 1.
> See the roadmap in the documentation.

## Build

All toolchains are pinned in `mise.toml`.

```bash
mise install          # JDK, Maven, Antora, …
mise run build        # compile, test and package all modules
mise run docs         # build the documentation into documentation/build/site
```

Modules:

| Module   | Content                                                        |
|----------|----------------------------------------------------------------|
| `core`   | Template parsing, schema, validation, rendering. Plain Java.   |
| `cli`    | Command-line tool (Picocli). `cli/target/pdf-ua-generator.jar` |
| `server` | Quarkus REST service. `server/target/quarkus-app/`             |

## Documentation

The documentation is an Antora site under `documentation/`, organised along
[Diátaxis](https://diataxis.fr/). Start with the architecture page.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE). Third-party licences are listed in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
