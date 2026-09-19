/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.cli;

import at.itbh.pdfuagen.core.DocumentRenderer;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "validate",
    mixinStandardHelpOptions = true,
    description =
        "Validates JSON data against the schema of a template without rendering. Every violation"
            + " is reported with a JSON Pointer to the offending value.",
    exitCodeListHeading = "%nExit codes:%n",
    exitCodeList = {
      "0:The data is valid.",
      "1:The data is invalid, or the template has errors.",
      "2:Invalid command line."
    })
final class ValidateCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  @Mixin TemplateOptions template;

  @Option(
      names = {"-d", "--data"},
      required = true,
      paramLabel = "<file|->",
      description = "JSON object with the template data; '-' reads stdin.")
  String data;

  @Override
  public Integer call() throws Exception {
    PrintWriter err = spec.commandLine().getErr();
    try {
      List<Problem> problems =
          new DocumentRenderer()
              .schema(template.repository(), template.templateId())
              .validate(TemplateOptions.readData(data));
      if (!problems.isEmpty()) {
        TemplateOptions.print(err, problems);
        return 1;
      }
      PrintWriter out = spec.commandLine().getOut();
      out.println("valid");
      out.flush();
      return 0;
    } catch (RenderException e) {
      TemplateOptions.print(err, e.problems());
      return 1;
    }
  }
}
