/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.cli;

import at.itbh.pdfuagen.core.ComposedTemplateRepository;
import at.itbh.pdfuagen.core.DirectoryTemplateRepository;
import at.itbh.pdfuagen.core.JsonData;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import at.itbh.pdfuagen.core.TemplateRepository;
import at.itbh.pdfuagen.core.schema.LayoutDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Option;

/** Options shared by the commands that work on a template. */
final class TemplateOptions {

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
      names = {"-L", "--layout"},
      paramLabel = "<dir>",
      description =
          "Layout directory for a template whose descriptor names a layout; its files appear under"
              + " layout/.")
  Path layout;

  /** The directory the template is resolved in. */
  Path root() {
    return Files.isDirectory(template) ? template : template.toAbsolutePath().getParent();
  }

  /** The template id within {@link #root()}. */
  String templateId() {
    return Files.isDirectory(template)
        ? DirectoryTemplateRepository.DEFAULT_TEMPLATE_FILE
        : template.getFileName().toString();
  }

  /**
   * The template's files; with {@code --layout}, the layout's under {@code layout/}. A layout on
   * its own (a directory with layout.json) appears under {@code layout/} too, as for a template
   * that fills it.
   */
  TemplateRepository repository() {
    DirectoryTemplateRepository files = new DirectoryTemplateRepository(root(), includePaths);
    if (layout != null) {
      return new ComposedTemplateRepository(
          files, new DirectoryTemplateRepository(layout, List.of()));
    }
    if (Files.isRegularFile(root().resolve(LayoutDescriptor.FILE))) {
      return new ComposedTemplateRepository(files, files);
    }
    return files;
  }

  /** Reads a JSON object from a file, or stdin for {@code -}; {@code null} gives {@code {}}. */
  static Map<String, Object> readData(String data) throws IOException, RenderException {
    if (data == null) {
      return Map.of();
    }
    try (InputStream in = "-".equals(data) ? System.in : Files.newInputStream(Path.of(data))) {
      return JsonData.parse(in);
    }
  }

  static Map<String, byte[]> readAttachments(Map<String, Path> attachments) throws IOException {
    Map<String, byte[]> result = new LinkedHashMap<>();
    for (Map.Entry<String, Path> entry : attachments.entrySet()) {
      result.put(entry.getKey(), Files.readAllBytes(entry.getValue()));
    }
    return result;
  }

  static void print(PrintWriter err, List<Problem> problems) {
    for (Problem problem : problems) {
      err.println(
          "error: "
              + problem.detail()
              + (problem.location() == null ? "" : " (" + problem.location() + ")"));
    }
    err.flush();
  }
}
