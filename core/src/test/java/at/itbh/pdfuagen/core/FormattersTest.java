/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormattersTest {

  private static final String TEMPLATE =
      "{amount.number}|{amount.number(1)}|{count.number}|{amount.currency('EUR')}"
          + "|{day.date}|{day.date('long')}|{day.date('short')}";

  private static final char NBSP = (char) 0xa0;

  private final DocumentRenderer renderer = new DocumentRenderer();

  private String render(String templateId, MapTemplateRepository repository) throws Exception {
    return renderer.renderSource(
        new RenderRequest(
            templateId,
            repository,
            Map.of(
                "amount",
                new BigDecimal("1234.56"),
                "count",
                BigInteger.valueOf(1000),
                "day",
                "2026-09-19"),
            Map.of()));
  }

  private static MapTemplateRepository repository(String language) {
    return new MapTemplateRepository()
        .template("t.xhtml", TEMPLATE)
        .resource(
            "t.json",
            "{\"language\": \""
                + language
                + "\", \"fields\": {\"amount\": {\"type\": \"number\"},"
                + " \"count\": {\"type\": \"number\"}, \"day\": {\"type\": \"date\"}}}");
  }

  @Test
  void formatsForTheTemplateLanguage() throws Exception {
    assertEquals(
        "1,234.56|1,234.6|1,000|€1,234.56|Sep 19, 2026|September 19, 2026|9/19/26",
        render("t.xhtml", repository("en")));
    // CLDR: Austrian numbers group with a no-break space (written _ here), amounts with a dot.
    assertEquals(
        "1_234,56|1_234,6|1_000|€_1.234,56|19.09.2026|19. September 2026|19.09.26"
            .replace('_', NBSP),
        render("t.xhtml", repository("de-AT")));
  }

  @Test
  void withoutDescriptorTheLanguageIsNeutral() throws Exception {
    String result = render("t.xhtml", new MapTemplateRepository().template("t.xhtml", TEMPLATE));
    assertTrue(result.startsWith("1,234.56|"), result);
  }

  @Test
  void rejectsBadArguments() {
    for (String expression :
        List.of("{amount.currency('XYZ')}", "{amount.number(-1)}", "{day.date('huge')}")) {
      MapTemplateRepository repository =
          new MapTemplateRepository().template("t.xhtml", expression);
      RenderException e =
          assertThrows(RenderException.class, () -> render("t.xhtml", repository), expression);
      assertEquals(Problem.TEMPLATE_ERROR, e.problems().getFirst().type());
    }
    MapTemplateRepository notADate =
        new MapTemplateRepository().template("t.xhtml", "{amount.date}");
    assertThrows(RenderException.class, () -> render("t.xhtml", notADate));
  }
}
