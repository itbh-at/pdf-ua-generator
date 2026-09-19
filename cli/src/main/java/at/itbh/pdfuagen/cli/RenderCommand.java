/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.cli;

import at.itbh.pdfuagen.core.DirectoryTemplateRepository;
import at.itbh.pdfuagen.core.DocumentRenderer;
import at.itbh.pdfuagen.core.JsonData;
import at.itbh.pdfuagen.core.OutputFormat;
import at.itbh.pdfuagen.core.PdfUaValidator;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import at.itbh.pdfuagen.core.RenderRequest;
import at.itbh.pdfuagen.core.Rendered;
import at.itbh.pdfuagen.core.ResourceFetcher;
import at.itbh.pdfuagen.core.ResourceLimits;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "render",
    mixinStandardHelpOptions = true,
    description = "Renders a template with JSON data into a document.",
    exitCodeListHeading = "%nExit codes:%n",
    exitCodeList = {
      "0:Document written.",
      "1:Template, data or resources invalid, or --verify failed.",
      "2:Invalid command line."
    })
final class RenderCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  @Option(
      names = {"-t", "--template"},
      required = true,
      paramLabel = "<file|dir>",
      description =
          "Template file, or a directory containing template.xhtml. Resources are resolved relative"
              + " to it.")
  Path template;

  @Option(
      names = {"-I", "--include-path"},
      paramLabel = "<dir>",
      description = "Additional directory for included templates and resources. Repeatable.")
  List<Path> includePaths = new ArrayList<>();

  @Option(
      names = {"-d", "--data"},
      paramLabel = "<file|->",
      description = "JSON object with the template data; '-' reads stdin. Default: {}.")
  String data;

  @Option(
      names = {"-a", "--attach"},
      paramLabel = "<name>=<file>",
      description = "Image referenced as attachment:<name>. PNG, JPEG or SVG. Repeatable.")
  Map<String, Path> attachments = new LinkedHashMap<>();

  @Option(
      names = {"-f", "--format"},
      defaultValue = "pdf",
      paramLabel = "<format>",
      converter = FormatConverter.class,
      completionCandidates = FormatCandidates.class,
      description = "Output format: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
  Format format;

  @Option(
      names = "--public-base-url",
      paramLabel = "<url>",
      description =
          "Base of the public asset URLs (<url>/assets/<sha256>) for images in email-html.")
  URI publicBaseUrl;

  @Option(
      names = {"-o", "--output"},
      paramLabel = "<file|->",
      description =
          "Output file; '-' writes to stdout. Default: next to the template, <name>.pdf,"
              + " .rendered.xhtml, .email.html, .rendered.txt, .docx or .odt.")
  String output;

  @Option(
      names = "--verify",
      description = "Validate the PDF against PDF/UA-1 with veraPDF; fail if it does not conform.")
  boolean verify;

  @Option(
      names = {"-w", "--watch"},
      description = "Render again whenever the template, data or a resource changes.")
  boolean watch;

  enum Format {
    pdf(OutputFormat.PDF, ".pdf"),
    xhtml(OutputFormat.XHTML, ".rendered.xhtml"),
    email_html(OutputFormat.EMAIL_HTML, ".email.html"),
    text(OutputFormat.TEXT, ".rendered.txt"),
    docx(OutputFormat.DOCX, ".docx"),
    odt(OutputFormat.ODT, ".odt");

    final OutputFormat outputFormat;
    final String suffix;

    Format(OutputFormat outputFormat, String suffix) {
      this.outputFormat = outputFormat;
      this.suffix = suffix;
    }
  }

  /** Accepts the format names with a hyphen ({@code email-html}). */
  static final class FormatConverter implements CommandLine.ITypeConverter<Format> {
    @Override
    public Format convert(String value) {
      return Format.valueOf(value.strip().toLowerCase(java.util.Locale.ROOT).replace('-', '_'));
    }
  }

  static final class FormatCandidates implements Iterable<String> {
    @Override
    public java.util.Iterator<String> iterator() {
      return java.util.Arrays.stream(Format.values())
          .map(f -> f.name().replace('_', '-'))
          .iterator();
    }
  }

  private DocumentRenderer renderer;

  @Override
  public Integer call() throws Exception {
    if (verify && format != Format.pdf) {
      throw new picocli.CommandLine.ParameterException(
          spec.commandLine(), "--verify requires --format pdf");
    }
    if (watch && "-".equals(output)) {
      throw new picocli.CommandLine.ParameterException(
          spec.commandLine(), "--watch cannot write to stdout");
    }
    renderer =
        new DocumentRenderer(
            ResourceFetcher.NONE, ResourceLimits.DEFAULT, Duration.ofSeconds(30), publicBaseUrl);
    if (!watch) {
      return renderOnce();
    }
    watchAndRender();
    return 0;
  }

  private int renderOnce() throws IOException {
    PrintWriter err = spec.commandLine().getErr();
    Path root = Files.isDirectory(template) ? template : template.toAbsolutePath().getParent();
    String templateId =
        Files.isDirectory(template)
            ? DirectoryTemplateRepository.DEFAULT_TEMPLATE_FILE
            : template.getFileName().toString();
    try {
      RenderRequest request =
          new RenderRequest(
              templateId,
              new DirectoryTemplateRepository(root, includePaths),
              readData(),
              readAttachments());
      Rendered rendered = renderer.render(request, format.outputFormat);
      rendered.warnings().forEach(w -> err.println("warning: " + w));
      if (verify) {
        PdfUaValidator.Report report = PdfUaValidator.validate(rendered.content());
        if (!report.compliant()) {
          report.failures().forEach(f -> err.println("error: PDF/UA: " + f));
          err.flush();
          return 1;
        }
      }
      write(rendered.content(), outputPath(root, templateId));
      err.flush();
      return 0;
    } catch (RenderException e) {
      for (Problem problem : e.problems()) {
        err.println(
            "error: "
                + problem.detail()
                + (problem.location() == null ? "" : " (" + problem.location() + ")"));
      }
      err.flush();
      return 1;
    }
  }

  private Map<String, Object> readData() throws IOException, RenderException {
    if (data == null) {
      return Map.of();
    }
    try (InputStream in = "-".equals(data) ? System.in : Files.newInputStream(Path.of(data))) {
      return JsonData.parse(in);
    }
  }

  private Map<String, byte[]> readAttachments() throws IOException {
    Map<String, byte[]> result = new LinkedHashMap<>();
    for (Map.Entry<String, Path> entry : attachments.entrySet()) {
      result.put(entry.getKey(), Files.readAllBytes(entry.getValue()));
    }
    return result;
  }

  private Path outputPath(Path root, String templateId) {
    if (output != null) {
      return "-".equals(output) ? null : Path.of(output);
    }
    String name = Path.of(templateId).getFileName().toString();
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : name;
    return root.resolve(base + format.suffix);
  }

  private static void write(byte[] content, Path path) throws IOException {
    if (path == null) {
      OutputStream out = System.out;
      out.write(content);
      out.flush();
    } else {
      Files.write(path, content);
    }
  }

  /** Renders once, then again on every change below the watched directories, until interrupted. */
  private void watchAndRender() throws IOException, InterruptedException {
    PrintWriter out = spec.commandLine().getOut();
    Set<Path> dirs = new LinkedHashSet<>();
    dirs.add(
        (Files.isDirectory(template) ? template : template.toAbsolutePath().getParent())
            .toAbsolutePath());
    includePaths.forEach(p -> dirs.add(p.toAbsolutePath()));
    if (data != null && !"-".equals(data)) {
      dirs.add(Path.of(data).toAbsolutePath().getParent());
    }
    attachments.values().forEach(p -> dirs.add(p.toAbsolutePath().getParent()));
    Path root = dirs.iterator().next();
    String templateId =
        Files.isDirectory(template)
            ? DirectoryTemplateRepository.DEFAULT_TEMPLATE_FILE
            : template.getFileName().toString();
    Path outputFile = outputPath(root, templateId).toAbsolutePath();

    try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
      for (Path dir : dirs) {
        dir.register(
            watcher,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE);
      }
      out.println(
          "Watching "
              + dirs.size()
              + " director"
              + (dirs.size() == 1 ? "y" : "ies")
              + " for changes.");
      report(out, renderOnce());
      while (true) {
        WatchKey key = watcher.take();
        boolean relevant = false;
        // Collect the burst of events an editor save produces, then render once.
        do {
          Path dir = (Path) key.watchable();
          for (var event : key.pollEvents()) {
            if (event.context() instanceof Path changed
                && !dir.resolve(changed).equals(outputFile)) {
              relevant = true;
            }
          }
          key.reset();
          key = watcher.poll(200, TimeUnit.MILLISECONDS);
        } while (key != null);
        if (relevant) {
          report(out, renderOnce());
        }
      }
    } catch (ClosedWatchServiceException e) {
      // Shutdown.
    }
  }

  private static void report(PrintWriter out, int exitCode) {
    out.println(exitCode == 0 ? "Rendered." : "Rendering failed.");
    out.flush();
  }
}
