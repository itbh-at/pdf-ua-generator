/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.cli;

import at.itbh.pdfuagen.core.DocumentRenderer;
import at.itbh.pdfuagen.core.ResourceFetcher;
import at.itbh.pdfuagen.core.ResourceLimits;
import at.itbh.pdfuagen.core.TemplateCheck;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "check",
    mixinStandardHelpOptions = true,
    description = {
      "Runs the publish checks of a template: every language variant parses, the field",
      "definitions are valid and match what the template reads, all variants need the same",
      "data, the example data is valid, and every variant renders in every format the",
      "template offers, the PDF conforming to PDF/UA-1."
    },
    exitCodeListHeading = "%nExit codes:%n",
    exitCodeList = {
      "0:All checks passed.",
      "1:At least one check failed.",
      "2:Invalid command line."
    })
final class CheckCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  @Mixin TemplateOptions template;

  @Option(
      names = {"-d", "--data"},
      required = true,
      paramLabel = "<file|->",
      description = "Example data (JSON object) rendered with every variant; '-' reads stdin.")
  String data;

  @Option(
      names = {"-a", "--attach"},
      paramLabel = "<name>=<file>",
      description = "Image referenced as attachment:<name>. PNG, JPEG or SVG. Repeatable.")
  Map<String, Path> attachments = new LinkedHashMap<>();

  @Override
  public Integer call() throws Exception {
    PrintWriter out = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    TemplateCheck.Report report;
    try {
      report =
          TemplateCheck.check(
              // Email HTML needs a base for asset URLs; the check discards the output.
              new DocumentRenderer(
                  ResourceFetcher.NONE,
                  ResourceLimits.DEFAULT,
                  Duration.ofSeconds(30),
                  URI.create("https://assets.invalid/")),
              template.repository(),
              template.templateId(),
              TemplateOptions.readData(data),
              TemplateOptions.readAttachments(attachments));
    } catch (at.itbh.pdfuagen.core.RenderException e) {
      TemplateOptions.print(err, e.problems());
      return 1;
    }
    report.warnings().forEach(w -> err.println("warning: " + w));
    TemplateOptions.print(err, report.problems());
    out.println(report.passed() ? "passed" : "failed");
    out.flush();
    return report.passed() ? 0 : 1;
  }
}
