/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.itbh.pdfuagen.core.ComposedTemplateRepository;
import at.itbh.pdfuagen.core.Demo;
import at.itbh.pdfuagen.core.DirectoryTemplateRepository;
import at.itbh.pdfuagen.core.TemplateRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The names the demo may use, as an editor offers them. */
class VocabularyTest {

  @Test
  void aDocumentTemplateUsesItsFieldsAndItsLayoutsNames() {
    Vocabulary v = Vocabulary.of(Demo.repository("layout"), "template.xhtml");
    assertTrue(
        v.fields()
            .contains(
                new Vocabulary.FieldName("customer.name", "text", "Name", "Used in the greeting.")),
        v.fields().toString());
    assertTrue(
        v.fields().stream()
            .anyMatch(
                f -> f.path().equals("order.positions[].price") && f.type().equals("number")));
    assertTrue(
        v.fields().stream().anyMatch(f -> f.path().equals("items[]") && f.type().equals("text")));
    assertEquals(
        List.of("title", "head", "body"),
        v.areas().stream().map(Vocabulary.AreaName::name).toList());
    Vocabulary.ComponentName box =
        v.components().stream().filter(c -> c.name().equals("box")).findFirst().orElseThrow();
    assertEquals(List.of("title"), box.parameters());
    assertTrue(
        v.styles().stream()
            .anyMatch(s -> s.name().equals("highlight") && s.kind().equals("character")));
    assertEquals(List.of("header", "page", "logo"), v.texts());
  }

  @Test
  void aLayoutOnItsOwnHasNoFieldsOfADocumentTemplate() {
    TemplateRepository layout =
        new DirectoryTemplateRepository(Demo.DIR.resolve("layout"), List.of());
    Vocabulary v = Vocabulary.of(new ComposedTemplateRepository(layout, layout), "template.xhtml");
    assertTrue(v.fields().isEmpty());
    assertEquals(List.of("header", "page", "logo"), v.texts());
  }

  @Test
  void severalLayoutsOfferWhatAllOfThemHave() {
    Vocabulary plain = Vocabulary.of(Demo.repository("layout"), "template.xhtml");
    Vocabulary memo = Vocabulary.of(Demo.repository("layout-memo"), "template.xhtml");
    Vocabulary common = Vocabulary.common(List.of(plain, memo));
    assertTrue(
        common.texts().stream()
            .allMatch(t -> plain.texts().contains(t) && memo.texts().contains(t)));
    assertEquals(plain.fields(), common.fields());
  }
}
