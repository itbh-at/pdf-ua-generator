/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.VeraPDFFoundry;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;
import org.verapdf.pdfa.validation.profiles.RuleId;

/** Checks a PDF against PDF/UA-1 with veraPDF. */
public final class PdfUaValidator {

  static {
    VeraGreenfieldFoundryProvider.initialise();
  }

  /**
   * Outcome of a validation.
   *
   * @param compliant whether the PDF conforms to PDF/UA-1
   * @param failures one line per failed rule: clause, test number, description and count
   */
  public record Report(boolean compliant, List<String> failures) {}

  private PdfUaValidator() {}

  public static Report validate(byte[] pdf) {
    VeraPDFFoundry foundry = Foundries.defaultInstance();
    try (PDFAParser parser =
            foundry.createParser(new ByteArrayInputStream(pdf), PDFAFlavour.PDFUA_1);
        PDFAValidator validator = foundry.createValidator(PDFAFlavour.PDFUA_1, false)) {
      ValidationResult result = validator.validate(parser);
      Map<RuleId, String> messages = new LinkedHashMap<>();
      Map<RuleId, Integer> counts = new LinkedHashMap<>();
      for (TestAssertion assertion : result.getTestAssertions()) {
        if (assertion.getStatus() == TestAssertion.Status.FAILED) {
          messages.putIfAbsent(assertion.getRuleId(), assertion.getMessage());
          counts.merge(assertion.getRuleId(), 1, Integer::sum);
        }
      }
      List<String> failures =
          messages.entrySet().stream()
              .map(
                  e ->
                      e.getKey().getClause()
                          + "-"
                          + e.getKey().getTestNumber()
                          + ": "
                          + e.getValue()
                          + " ("
                          + counts.get(e.getKey())
                          + "×)")
              .toList();
      return new Report(result.isCompliant(), failures);
    } catch (Exception e) {
      return new Report(false, List.of("PDF cannot be validated: " + e.getMessage()));
    }
  }
}
