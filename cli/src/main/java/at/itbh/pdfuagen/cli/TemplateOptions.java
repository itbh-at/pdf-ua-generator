/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.cli;

import at.itbh.pdfuagen.core.DirectoryTemplateRepository;
import at.itbh.pdfuagen.core.JsonData;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
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

  DirectoryTemplateRepository repository() {
    return new DirectoryTemplateRepository(root(), includePaths);
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
