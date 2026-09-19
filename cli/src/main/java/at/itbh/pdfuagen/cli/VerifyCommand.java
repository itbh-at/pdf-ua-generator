/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.cli;

import at.itbh.pdfuagen.core.PdfUaValidator;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "verify",
    mixinStandardHelpOptions = true,
    description = "Validates PDF files against PDF/UA-1 with veraPDF.",
    exitCodeListHeading = "%nExit codes:%n",
    exitCodeList = {
      "0:Every file conforms to PDF/UA-1.",
      "1:At least one file does not conform or cannot be read.",
      "2:Invalid command line."
    })
final class VerifyCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  @Parameters(arity = "1..*", paramLabel = "<pdf>", description = "PDF files to validate.")
  List<Path> files;

  @Override
  public Integer call() throws Exception {
    PrintWriter out = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    int exit = 0;
    for (Path file : files) {
      PdfUaValidator.Report report = PdfUaValidator.validate(Files.readAllBytes(file));
      if (report.compliant()) {
        out.println(file + ": conforms to PDF/UA-1");
      } else {
        exit = 1;
        report.failures().forEach(f -> err.println("error: " + file + ": " + f));
      }
    }
    out.flush();
    err.flush();
    return exit;
  }
}
