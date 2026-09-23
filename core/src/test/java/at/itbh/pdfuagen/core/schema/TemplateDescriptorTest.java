/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import at.itbh.pdfuagen.core.RenderException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The layouts a content template may be rendered with. */
class TemplateDescriptorTest {

  private static TemplateDescriptor parse(String layouts) throws RenderException {
    String json = "{\"language\": \"en\", " + layouts + "\"fields\": {}}";
    return TemplateDescriptor.parse(json.getBytes(StandardCharsets.UTF_8), "template.json");
  }

  private static String problems(String layouts) {
    RenderException e = assertThrows(RenderException.class, () -> parse(layouts));
    return e.problems().stream()
        .map(p -> p.detail() + " (" + p.location() + ")")
        .toList()
        .toString();
  }

  @Test
  void listsLayoutsTheFirstBeingTheDefault() throws Exception {
    TemplateDescriptor d = parse("\"layouts\": [\"corporate@3\", \"memo@1\"], ");
    assertEquals(
        List.of(
            new TemplateDescriptor.LayoutRef("corporate", 3),
            new TemplateDescriptor.LayoutRef("memo", 1)),
        d.layouts());
    assertEquals(new TemplateDescriptor.LayoutRef("corporate", 3), d.layout());
  }

  @Test
  void acceptsASingleLayout() throws Exception {
    assertEquals(
        List.of(new TemplateDescriptor.LayoutRef("corporate", 3)),
        parse("\"layout\": \"corporate@3\", ").layouts());
  }

  @Test
  void withoutALayoutTheListIsEmpty() throws Exception {
    TemplateDescriptor d = parse("");
    assertEquals(List.of(), d.layouts());
    assertNull(d.layout());
  }

  @Test
  void rejectsBadLists() {
    assertEquals(
        "[give either 'layout' or 'layouts', not both (template.json#)]",
        problems("\"layout\": \"a@1\", \"layouts\": [\"a@1\"], "));
    assertEquals(
        "['layouts' must be a non-empty list of layout revisions, e.g. [\"corporate@3\"]"
            + " (template.json#/layouts)]",
        problems("\"layouts\": [], "));
    assertEquals(
        "[layout 'a' is listed twice (template.json#/layouts/1)]",
        problems("\"layouts\": [\"a@1\", \"a@2\"], "));
    assertEquals(
        "[must name a layout revision, e.g. \"corporate@3\" (template.json#/layouts/0)]",
        problems("\"layouts\": [\"a\"], "));
  }
}
