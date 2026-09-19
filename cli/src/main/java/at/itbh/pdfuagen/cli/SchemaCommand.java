/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.cli;

import at.itbh.pdfuagen.core.DocumentRenderer;
import at.itbh.pdfuagen.core.RenderException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "schema",
    mixinStandardHelpOptions = true,
    description =
        "Prints the JSON Schema (draft 2020-12) of the data a template needs, derived from the"
            + " template and its field definitions (<name>.json).",
    exitCodeListHeading = "%nExit codes:%n",
    exitCodeList = {
      "0:Schema written.",
      "1:The template, a language variant or the field definitions have errors.",
      "2:Invalid command line."
    })
final class SchemaCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  @Mixin TemplateOptions template;

  @Option(
      names = {"-o", "--output"},
      paramLabel = "<file>",
      description = "Output file. Default: stdout.")
  Path output;

  @Override
  public Integer call() throws Exception {
    PrintWriter err = spec.commandLine().getErr();
    DocumentRenderer renderer = new DocumentRenderer();
    try {
      String json = renderer.schema(template.repository(), template.templateId()).toJson() + "\n";
      renderer
          .schemaWarnings(template.repository(), template.templateId())
          .forEach(w -> err.println("warning: " + w));
      err.flush();
      if (output == null) {
        PrintWriter out = spec.commandLine().getOut();
        out.print(json);
        out.flush();
      } else {
        Files.writeString(output, json, StandardCharsets.UTF_8);
      }
      return 0;
    } catch (RenderException e) {
      TemplateOptions.print(err, e.problems());
      return 1;
    }
  }
}
