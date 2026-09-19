/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.itbh.pdfuagen.core.model.DocumentModel.Box;
import at.itbh.pdfuagen.core.model.DocumentModel.Columns;
import at.itbh.pdfuagen.core.model.DocumentModel.Footnote;
import at.itbh.pdfuagen.core.model.DocumentModel.ImageBlock;
import at.itbh.pdfuagen.core.model.DocumentModel.Paragraph;
import at.itbh.pdfuagen.core.model.DocumentModel.Text;
import java.io.StringReader;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

class ModelBuilderTest {

  private static final ModelBuilder.ImageLoader IMAGES =
      ref ->
          Optional.of(new ModelBuilder.LoadedImage("template:/" + ref, new byte[0], "image/png"));

  private static DocumentModel build(String body, ModelBuilder builder) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document =
        factory
            .newDocumentBuilder()
            .parse(
                new InputSource(
                    new StringReader(
                        "<html lang=\"de-AT\"><head><title> T </title></head><body>"
                            + body
                            + "</body></html>")));
    return builder.build(document);
  }

  @Test
  void readsMetadataAndCollapsesWhitespace() throws Exception {
    DocumentModel model = build("<p>  a \n  <strong>b</strong>  c </p>", new ModelBuilder(IMAGES));

    assertEquals("T", model.title());
    assertEquals("de-AT", model.lang());
    Paragraph p = assertInstanceOf(Paragraph.class, model.blocks().getFirst());
    assertEquals("a ", ((Text) p.content().get(0)).text());
    assertEquals("b", ((Text) p.content().get(1)).text());
    assertEquals(" c", ((Text) p.content().get(2)).text());
  }

  @Test
  void readsBuildingBlocks() throws Exception {
    DocumentModel model =
        build(
            "<div data-block=\"columns\"><div data-block=\"col\"><p>l</p></div><div"
                + " data-block=\"col\"><p>r</p></div></div><div data-block=\"box\""
                + " class=\"info\"><p>x<span data-block=\"footnote\">n</span></p></div><img"
                + " src=\"deco.svg\" alt=\"\"/>",
            new ModelBuilder(IMAGES));

    assertEquals(2, assertInstanceOf(Columns.class, model.blocks().get(0)).columns().size());
    Box box = assertInstanceOf(Box.class, model.blocks().get(1));
    assertEquals("info", box.style());
    Paragraph p = (Paragraph) box.content().getFirst();
    assertInstanceOf(Footnote.class, p.content().get(1));
    assertTrue(assertInstanceOf(ImageBlock.class, model.blocks().get(2)).image().decorative());
  }

  @Test
  void reportsElementsOutsideTheBuildingBlocks() throws Exception {
    ModelBuilder builder = new ModelBuilder(IMAGES);
    build("<section><p>x</p></section><p><code>y</code></p>", builder);

    assertEquals(2, builder.problems().size());
    assertTrue(builder.problems().getFirst().detail().contains("<section>"));
  }
}
