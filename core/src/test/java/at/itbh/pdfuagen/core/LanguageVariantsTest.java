/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LanguageVariantsTest {

  private static final Path VARIANTS = Path.of("src/test/resources/schema/variants");

  private final DocumentRenderer renderer = new DocumentRenderer();
  private final TemplateRepository repository =
      new DirectoryTemplateRepository(VARIANTS, List.of());

  @Test
  void parsesVariantIds() {
    assertEquals(
        new LanguageVariants.Variant("invoice.xhtml", "de-AT"),
        LanguageVariants.parse("invoice.de-AT.xhtml"));
    assertEquals(
        new LanguageVariants.Variant("a/b.xhtml", "sr-Latn"),
        LanguageVariants.parse("a/b.sr-Latn.xhtml"));
    // Not language tags: a plain name, an output file, a long language subtag.
    assertEquals(
        new LanguageVariants.Variant("invoice.xhtml", null),
        LanguageVariants.parse("invoice.xhtml"));
    assertEquals(
        new LanguageVariants.Variant("demo.rendered.xhtml", null),
        LanguageVariants.parse("demo.rendered.xhtml"));
    assertEquals("invoice.json", LanguageVariants.descriptorPath("invoice.xhtml"));
    assertEquals("invoice.fr.xhtml", LanguageVariants.variantId("invoice.xhtml", "fr"));
    assertTrue(LanguageVariants.isLanguageTag("de-AT"));
    assertFalse(LanguageVariants.isLanguageTag("english"));
    assertFalse(LanguageVariants.isLanguageTag("de_AT"));
  }

  @Test
  void listsVariantsOfADirectory() {
    assertEquals(
        List.of("template.xhtml", "template.de-AT.xhtml", "template.fr.xhtml"),
        renderer.variants(repository, "template.xhtml"));
  }

  @Test
  void selectsByRfc4647Lookup() {
    Map<String, String> cases =
        Map.of(
            "de-AT", "template.de-AT.xhtml",
            "de-AT-x-private, en;q=0.5", "template.de-AT.xhtml",
            "fr-CH, de;q=0.9", "template.fr.xhtml",
            // Lookup truncates the range, never the tag: de does not match de-AT.
            "de", "template.xhtml",
            "it, en;q=0.8, fr;q=0.5", "template.xhtml",
            "it", "template.xhtml",
            "*", "template.xhtml");
    cases.forEach(
        (ranges, expected) ->
            assertEquals(
                expected,
                renderer.selectVariant(
                    repository, "template.xhtml", LanguageVariants.ranges(ranges)),
                ranges));
  }

  @Test
  void rendersEachVariantInItsLanguage() throws Exception {
    Map<String, Object> data =
        Map.of("name", "Jane", "total", new java.math.BigDecimal("1450"), "due", "2026-12-31");
    assertTrue(
        renderer
            .renderSource(new RenderRequest("template.xhtml", repository, data, Map.of()))
            .contains("please pay €1,450.00 by December 31, 2026."));
    assertTrue(
        renderer
            .renderSource(new RenderRequest("template.de-AT.xhtml", repository, data, Map.of()))
            .contains("bis 31. Dezember 2026."));
    assertTrue(
        renderer
            .renderSource(new RenderRequest("template.fr.xhtml", repository, data, Map.of()))
            .contains("avant le 31 décembre 2026."));
    assertEquals(Locale.ENGLISH, renderer.language(repository, "template.xhtml"));
    assertEquals(Locale.of("de", "AT"), renderer.language(repository, "template.de-AT.xhtml"));
  }
}
