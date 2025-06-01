
# itbh.at PDF UA Generator

A command-line utility to convert XHTML + Print CSS templates into **PDF/UA-compliant** documents. It optionally supports dynamic content generation using **Quarkus Qute** template engine.

This tool is particularly suited for generating accessible, print-ready PDF documents using standards-compliant HTML and CSS input.

## Features

- Convert XHTML documents with Print CSS into PDF/UA format.
- Supports dynamic templating with [Quarkus Qute](https://quarkus.io/guides/qute).
- CLI interface powered by [Picocli](https://picocli.info/).
- Optional file watching for automatic rebuilds on changes.
- Embedded SVG rendering support via [OpenHTML to PDF](https://github.com/danfickle/openhtmltopdf).
- Built on the [Quarkus](https://quarkus.io) framework for fast startup and low memory usage.

## Usage

```bash
java -jar pdf-ua-generator.jar [options] <source-file.xhtml>
```

### Options

| Option                     | Description                                                                 |
|---------------------------|-----------------------------------------------------------------------------|
| `<source file>`           | The XHTML source file to be converted into PDF/UA                           |
| `-p`, `--params`          | Key=value pairs to be used as template variables (comma-separated)         |
| `-w`, `--watch`           | Watch the source file for changes and automatically rebuild on change      |

## Example

```bash
java -jar pdf-ua-generator.jar -p name=John,date=2025-06-01 template.xhtml
```

This reads `template.xhtml`, substitutes `name` and `date` where needed (if Qute markup is used), and outputs a PDF.

## Input Format

- XHTML formatted documents.
- Must include CSS for print layout (`@media print`).
- Can reference local resources like images and fonts relative to the source file.
- Can optionally include Qute expressions like `{name}` to be rendered dynamically.

## Dependencies

The project relies on the following key libraries:

- **[OpenHTML to PDF](https://github.com/openhtmltopdf/openhtmltopdf)** – for rendering HTML and CSS to PDF/UA format.
- **[Apache Batik](https://xmlgraphics.apache.org/batik/)** – for SVG rendering support in PDFs.
- **[Quarkus Qute](https://quarkus.io/guides/qute)** – for template parsing and variable substitution.
- **[Picocli](https://picocli.info/)** – for robust command-line parsing and help documentation.
- **[Quarkus](https://quarkus.io)** – Java framework used to build and run the application.

## Development

This project consists of two main Java classes:

- **`TemplateRenderService`**  
  Handles Qute template parsing and rendering, and integrates with OpenHTML to PDF for output generation.

- **`BuildCommand`**  
  Defines the CLI interface, handles parameter parsing, manages file watching for rebuilds, and coordinates rendering via `TemplateRenderService`.

## Building the Project

You can build the project using `./mvnw clean package`. This creates a [Quarkus Uber-Jar](https://quarkus.io/guides/maven-tooling#uber-jar-maven).

## License

This project is licensed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).

---
