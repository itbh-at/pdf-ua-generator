/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.writer;

import at.itbh.pdfuagen.core.model.DocumentModel;
import at.itbh.pdfuagen.core.model.DocumentModel.Block;
import at.itbh.pdfuagen.core.model.DocumentModel.Box;
import at.itbh.pdfuagen.core.model.DocumentModel.Cell;
import at.itbh.pdfuagen.core.model.DocumentModel.Columns;
import at.itbh.pdfuagen.core.model.DocumentModel.Footnote;
import at.itbh.pdfuagen.core.model.DocumentModel.Heading;
import at.itbh.pdfuagen.core.model.DocumentModel.Image;
import at.itbh.pdfuagen.core.model.DocumentModel.ImageBlock;
import at.itbh.pdfuagen.core.model.DocumentModel.Inline;
import at.itbh.pdfuagen.core.model.DocumentModel.InlineImage;
import at.itbh.pdfuagen.core.model.DocumentModel.LineBreak;
import at.itbh.pdfuagen.core.model.DocumentModel.Link;
import at.itbh.pdfuagen.core.model.DocumentModel.ListBlock;
import at.itbh.pdfuagen.core.model.DocumentModel.Mark;
import at.itbh.pdfuagen.core.model.DocumentModel.PageBreak;
import at.itbh.pdfuagen.core.model.DocumentModel.Paragraph;
import at.itbh.pdfuagen.core.model.DocumentModel.Row;
import at.itbh.pdfuagen.core.model.DocumentModel.Table;
import at.itbh.pdfuagen.core.model.DocumentModel.Text;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Writes WordprocessingML (DOCX) from the model, without an office library. Accessibility
 * structure: heading styles with outline levels, alt text ({@code docPr/@descr}) and the decorative
 * flag on images, repeating table header rows with a table caption, real numbered lists, real
 * footnotes, document title and language.
 */
public final class DocxWriter {

  static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  static final String WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
  static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
  static final String PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture";
  static final String REL_TYPE =
      "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

  /** Page size and margins without a Word template: A4 with 2 cm margins, in twips. */
  private static final Map<String, String> PAGE_SIZE = Map.of("w:w", "11906", "w:h", "16838");

  private static final Map<String, String> PAGE_MARGINS =
      Map.of(
          "w:top",
          "1134",
          "w:right",
          "1134",
          "w:bottom",
          "1134",
          "w:left",
          "1134",
          "w:header",
          "709",
          "w:footer",
          "709",
          "w:gutter",
          "0");

  private record Relationship(String id, String type, String target, boolean external) {}

  private record Run(Set<Mark> marks, String lang, String charStyle) {
    static final Run PLAIN = new Run(Set.of(), null, null);
  }

  private final DocumentModel model;
  private final List<Relationship> relationships = new ArrayList<>();
  private final Map<String, byte[]> media = new LinkedHashMap<>();
  private final List<Boolean> numbering = new ArrayList<>();
  private final Set<String> paragraphStyles = new LinkedHashSet<>();
  private final Set<String> characterStyles = new LinkedHashSet<>();
  private final Xml footnotes = new Xml();
  private int footnoteCount;
  private int drawingCount;
  private int bookmarkCount;

  /** From the layout's Word template: styles, page setup, theme, fonts to embed. */
  private final OfficeTemplate template;

  private final Document templateStyles;
  private final Map<String, String> templateStyleIds = new HashMap<>();
  private final Map<String, String> pageSize = new LinkedHashMap<>(PAGE_SIZE);
  private final Map<String, String> pageMargins = new LinkedHashMap<>(PAGE_MARGINS);
  private final byte[] theme;
  private final Map<String, byte[]> embeddedFonts = new LinkedHashMap<>();

  /** The template's default font, also for list markers; {@code null} without a template. */
  private String defaultFont;

  /** Text width in twentieths of a point and in EMU, from page width and margins. */
  private final int textWidthTwips;

  private final long textWidthEmu;

  private DocxWriter(DocumentModel model, OfficeTemplate template) throws IOException {
    this.model = model;
    this.template = template;
    if (template.dotx() != null) {
      templateStyles =
          OfficeTemplate.parse(
              OfficeTemplate.part(template.dotx(), "word/styles.xml")
                  .orElseThrow(() -> new IOException("the Word template has no styles")));
      for (Element style : OfficeTemplate.descendants(templateStyles, W, "style")) {
        OfficeTemplate.child(style, W, "name")
            .ifPresent(
                n ->
                    templateStyleIds.put(
                        n.getAttributeNS(W, "val"), style.getAttributeNS(W, "styleId")));
      }
      Optional<byte[]> document = OfficeTemplate.part(template.dotx(), "word/document.xml");
      if (document.isPresent()) {
        List<Element> sections =
            OfficeTemplate.descendants(OfficeTemplate.parse(document.get()), W, "sectPr");
        if (!sections.isEmpty()) {
          Element section = sections.getLast();
          OfficeTemplate.child(section, W, "pgSz").ifPresent(e -> attributes(e, pageSize));
          OfficeTemplate.child(section, W, "pgMar").ifPresent(e -> attributes(e, pageMargins));
        }
      }
      theme = OfficeTemplate.part(template.dotx(), "word/theme/theme1.xml").orElse(null);
      OfficeTemplate.child(templateStyles.getDocumentElement(), W, "docDefaults")
          .flatMap(d -> OfficeTemplate.descendants(d, W, "rFonts").stream().findFirst())
          .map(f -> f.getAttributeNS(W, "ascii"))
          .filter(f -> !f.isEmpty())
          .ifPresent(f -> defaultFont = f);
      for (Element fonts : OfficeTemplate.descendants(templateStyles, W, "rFonts")) {
        for (String attribute : List.of("ascii", "hAnsi", "cs", "eastAsia")) {
          String family = fonts.getAttributeNS(W, attribute);
          if (template.fonts().containsKey(family)) {
            embeddedFonts.put(family, template.fonts().get(family));
          }
        }
      }
    } else {
      templateStyles = null;
      theme = null;
    }
    textWidthTwips =
        Integer.parseInt(pageSize.get("w:w"))
            - Integer.parseInt(pageMargins.get("w:left"))
            - Integer.parseInt(pageMargins.get("w:right"));
    textWidthEmu = textWidthTwips * 635L;
    relationships.add(new Relationship("rIdStyles", REL_TYPE + "styles", "styles.xml", false));
    relationships.add(
        new Relationship("rIdNumbering", REL_TYPE + "numbering", "numbering.xml", false));
    relationships.add(
        new Relationship("rIdFootnotes", REL_TYPE + "footnotes", "footnotes.xml", false));
    relationships.add(
        new Relationship("rIdSettings", REL_TYPE + "settings", "settings.xml", false));
  }

  public static byte[] write(DocumentModel model) throws IOException {
    return write(model, OfficeTemplate.NONE);
  }

  /**
   * @param template the layout's Word template and fonts: its styles, default font and page setup
   *     are the base; styles it lacks are added
   */
  public static byte[] write(DocumentModel model, OfficeTemplate template) throws IOException {
    return new DocxWriter(model, template).write();
  }

  private static String[] flatten(Map<String, String> attributes) {
    return attributes.entrySet().stream()
        .flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue()))
        .toArray(String[]::new);
  }

  private static void attributes(Element element, Map<String, String> into) {
    for (int i = 0; i < element.getAttributes().getLength(); i++) {
      org.w3c.dom.Node attribute = element.getAttributes().item(i);
      if (W.equals(attribute.getNamespaceURI())) {
        into.put("w:" + attribute.getLocalName(), attribute.getNodeValue());
      }
    }
  }

  private byte[] write() throws IOException {
    footnotes.open("w:footnotes", "xmlns:w", W, "xmlns:r", R);
    footnotes
        .open("w:footnote", "w:type", "separator", "w:id", "-1")
        .open("w:p")
        .open("w:r")
        .empty("w:separator")
        .close()
        .close()
        .close();
    footnotes
        .open("w:footnote", "w:type", "continuationSeparator", "w:id", "0")
        .open("w:p")
        .open("w:r")
        .empty("w:continuationSeparator")
        .close()
        .close()
        .close();

    Xml doc = new Xml();
    doc.open(
            "w:document",
            "xmlns:w",
            W,
            "xmlns:r",
            R,
            "xmlns:wp",
            WP,
            "xmlns:a",
            A,
            "xmlns:pic",
            PIC)
        .open("w:body");
    blocks(doc, model.blocks(), 0);
    doc.open("w:sectPr");
    if (!model.header().isEmpty()) {
      relationships.add(new Relationship("rIdHeader", REL_TYPE + "header", "header1.xml", false));
      doc.empty("w:headerReference", "w:type", "default", "r:id", "rIdHeader");
    }
    if (!model.footer().isEmpty()) {
      relationships.add(new Relationship("rIdFooter", REL_TYPE + "footer", "footer1.xml", false));
      doc.empty("w:footerReference", "w:type", "default", "r:id", "rIdFooter");
    }
    doc.empty("w:pgSz", flatten(pageSize));
    doc.empty("w:pgMar", flatten(pageMargins));
    doc.close();
    doc.close().close();
    footnotes.close();

    Zip zip = new Zip();
    zip.add("[Content_Types].xml", contentTypes());
    zip.add("_rels/.rels", packageRelationships());
    zip.add("docProps/core.xml", coreProperties());
    zip.add("docProps/app.xml", appProperties());
    zip.add("word/document.xml", doc.bytes());
    zip.add("word/styles.xml", styles());
    zip.add("word/numbering.xml", numberingPart());
    zip.add("word/footnotes.xml", footnotes.bytes());
    zip.add("word/settings.xml", settings());
    if (!model.header().isEmpty()) {
      zip.add("word/header1.xml", pageBand("w:hdr", "Header", model.header()));
    }
    if (!model.footer().isEmpty()) {
      zip.add("word/footer1.xml", pageBand("w:ftr", "Footer", model.footer()));
    }
    if (theme != null) {
      relationships.add(
          new Relationship("rIdTheme", REL_TYPE + "theme", "theme/theme1.xml", false));
      zip.add("word/theme/theme1.xml", theme);
    }
    if (!embeddedFonts.isEmpty()) {
      relationships.add(
          new Relationship("rIdFontTable", REL_TYPE + "fontTable", "fontTable.xml", false));
      fontTable(zip);
    }
    zip.add("word/_rels/document.xml.rels", documentRelationships());
    for (Map.Entry<String, byte[]> entry : media.entrySet()) {
      zip.add("word/" + entry.getKey(), entry.getValue());
    }
    return zip.finish();
  }

  // --- blocks -------------------------------------------------------------------------------

  private void blocks(Xml x, List<Block> blocks, int listDepth) throws IOException {
    for (Block block : blocks) {
      switch (block) {
        case Heading h -> {
          x.open("w:p").open("w:pPr").empty("w:pStyle", "w:val", "Heading" + h.level()).close();
          if (h.id() != null) {
            int id = ++bookmarkCount;
            x.empty("w:bookmarkStart", "w:id", String.valueOf(id), "w:name", bookmarkName(h.id()));
            inlines(x, h.content(), Run.PLAIN);
            x.empty("w:bookmarkEnd", "w:id", String.valueOf(id));
          } else {
            inlines(x, h.content(), Run.PLAIN);
          }
          x.close();
        }
        case Paragraph p -> paragraph(x, paragraphStyle(p.style()), p.content());
        case ListBlock list -> list(x, list, listDepth);
        case Table table -> table(x, table);
        case ImageBlock image -> {
          x.open("w:p");
          image(x, image.image());
          x.close();
        }
        case Columns columns -> layoutTable(x, columns.columns(), false, null);
        case Box box -> layoutTable(x, List.of(box.content()), true, box.style());
        case PageBreak pageBreak ->
            x.open("w:p").open("w:r").empty("w:br", "w:type", "page").close().close();
      }
    }
  }

  private void paragraph(Xml x, String style, List<Inline> content) throws IOException {
    x.open("w:p");
    if (style != null) {
      x.open("w:pPr").empty("w:pStyle", "w:val", style).close();
    }
    inlines(x, content, Run.PLAIN);
    x.close();
  }

  private void list(Xml x, ListBlock list, int depth) throws IOException {
    numbering.add(list.ordered());
    String numId = String.valueOf(numbering.size());
    int level = Math.min(depth, 8);
    for (List<Block> item : list.items()) {
      boolean first = true;
      for (Block block : item) {
        if (block instanceof Paragraph p) {
          x.open("w:p").open("w:pPr").empty("w:pStyle", "w:val", "ListParagraph");
          if (first) {
            x.open("w:numPr")
                .empty("w:ilvl", "w:val", String.valueOf(level))
                .empty("w:numId", "w:val", numId)
                .close();
          } else {
            x.empty("w:ind", "w:left", String.valueOf(720 * (level + 1)));
          }
          x.close();
          inlines(x, p.content(), Run.PLAIN);
          x.close();
        } else if (block instanceof ListBlock nested) {
          list(x, nested, depth + 1);
        } else {
          blocks(x, List.of(block), depth + 1);
        }
        first = false;
      }
    }
  }

  private void table(Xml x, Table table) throws IOException {
    String caption = text(table.caption());
    if (!caption.isEmpty()) {
      paragraph(x, "Caption", table.caption());
    }
    int columns = columns(table);
    x.open("w:tbl").open("w:tblPr");
    x.empty("w:tblStyle", "w:val", "TableGrid");
    x.empty("w:tblW", "w:w", "5000", "w:type", "pct");
    x.empty(
        "w:tblLook",
        "w:val",
        "04A0",
        "w:firstRow",
        table.head().isEmpty() ? "0" : "1",
        "w:lastRow",
        "0",
        "w:firstColumn",
        "0",
        "w:lastColumn",
        "0",
        "w:noHBand",
        "1",
        "w:noVBand",
        "1");
    if (!caption.isEmpty()) {
      x.empty("w:tblCaption", "w:val", caption);
    }
    x.close();
    grid(x, columns);
    section(x, table.head(), columns, true);
    section(x, table.body(), columns, false);
    section(x, table.foot(), columns, false);
    x.close();
    // Word needs a paragraph between two tables and after a table at the end of a cell.
    x.empty("w:p");
  }

  private void section(Xml x, List<Row> rows, int columns, boolean header) throws IOException {
    for (List<TableGrid.Slot> slots : TableGrid.layout(rows)) {
      row(x, slots, columns, header);
    }
  }

  private void row(Xml x, List<TableGrid.Slot> slots, int columns, boolean header)
      throws IOException {
    x.open("w:tr");
    if (header) {
      x.open("w:trPr").empty("w:tblHeader").close();
    }
    for (TableGrid.Slot slot : slots) {
      if (slot instanceof TableGrid.Covered covered) {
        // A cell merged upwards: same width, continue the vertical merge, keep a paragraph.
        x.open("w:tc").open("w:tcPr");
        x.empty(
            "w:tcW",
            "w:w",
            String.valueOf(textWidthTwips * covered.columns() / columns),
            "w:type",
            "dxa");
        if (covered.columns() > 1) {
          x.empty("w:gridSpan", "w:val", String.valueOf(covered.columns()));
        }
        x.empty("w:vMerge");
        x.close();
        x.empty("w:p");
        x.close();
        continue;
      }
      Cell cell = ((TableGrid.Placed) slot).cell();
      x.open("w:tc").open("w:tcPr");
      x.empty(
          "w:tcW",
          "w:w",
          String.valueOf(textWidthTwips * cell.colspan() / columns),
          "w:type",
          "dxa");
      if (cell.colspan() > 1) {
        x.empty("w:gridSpan", "w:val", String.valueOf(cell.colspan()));
      }
      if (cell.rowspan() > 1) {
        x.empty("w:vMerge", "w:val", "restart");
      }
      x.close();
      cellContent(x, cell.content(), cell.header() ? "TableHeading" : null);
      x.close();
    }
    x.close();
  }

  /** Columns and boxes: a table without header, marked as layout by having no caption. */
  private void layoutTable(Xml x, List<List<Block>> cells, boolean framed, String style)
      throws IOException {
    x.open("w:tbl").open("w:tblPr");
    x.empty("w:tblW", "w:w", "5000", "w:type", "pct");
    x.open("w:tblBorders");
    for (String side : List.of("top", "left", "bottom", "right")) {
      x.empty(
          "w:" + side,
          "w:val",
          framed ? "single" : "nil",
          "w:sz",
          framed ? "8" : null,
          "w:space",
          framed ? "0" : null,
          "w:color",
          framed ? "808080" : null);
    }
    x.empty("w:insideH", "w:val", "nil").empty("w:insideV", "w:val", "nil").close();
    x.empty(
        "w:tblLook",
        "w:val",
        "0000",
        "w:firstRow",
        "0",
        "w:lastRow",
        "0",
        "w:firstColumn",
        "0",
        "w:lastColumn",
        "0",
        "w:noHBand",
        "1",
        "w:noVBand",
        "1");
    x.close();
    grid(x, cells.size());
    x.open("w:tr");
    for (List<Block> cell : cells) {
      x.open("w:tc").open("w:tcPr");
      x.empty("w:tcW", "w:w", String.valueOf(textWidthTwips / cells.size()), "w:type", "dxa");
      if (framed) {
        x.empty("w:shd", "w:val", "clear", "w:color", "auto", "w:fill", "F2F2F2");
      }
      x.close();
      cellContent(x, cell, style == null ? null : paragraphStyle(style));
      x.close();
    }
    x.close().close();
    x.empty("w:p");
  }

  private void cellContent(Xml x, List<Block> content, String paragraphStyle) throws IOException {
    if (content.isEmpty()) {
      x.empty("w:p");
      return;
    }
    for (Block block : content) {
      if (block instanceof Paragraph p && paragraphStyle != null && p.style() == null) {
        paragraph(x, paragraphStyle, p.content());
      } else {
        blocks(x, List.of(block), 0);
      }
    }
    if (content.getLast() instanceof Table) {
      x.empty("w:p");
    }
  }

  private void grid(Xml x, int columns) {
    x.open("w:tblGrid");
    for (int i = 0; i < columns; i++) {
      x.empty("w:gridCol", "w:w", String.valueOf(textWidthTwips / Math.max(1, columns)));
    }
    x.close();
  }

  private static int columns(Table table) {
    int max = 1;
    for (List<Row> rows : List.of(table.head(), table.body(), table.foot())) {
      for (Row row : rows) {
        max = Math.max(max, row.cells().stream().mapToInt(Cell::colspan).sum());
      }
    }
    return max;
  }

  // --- inlines ------------------------------------------------------------------------------

  private void inlines(Xml x, List<Inline> inlines, Run run) throws IOException {
    for (Inline inline : inlines) {
      switch (inline) {
        case Text t ->
            textRun(
                x,
                t.text(),
                new Run(
                    t.marks(),
                    t.lang(),
                    t.style() == null ? run.charStyle() : characterStyle(t.style())));
        case LineBreak lb -> x.open("w:r").empty("w:br").close();
        case InlineImage image -> image(x, image.image());
        case Footnote footnote -> footnote(x, footnote);
        case Link link -> {
          if (link.href().startsWith("#")) {
            x.open(
                "w:hyperlink",
                "w:anchor",
                bookmarkName(link.href().substring(1)),
                "w:tooltip",
                text(link.content()));
          } else {
            String id = "rIdLink" + relationships.size();
            relationships.add(new Relationship(id, REL_TYPE + "hyperlink", link.href(), true));
            x.open("w:hyperlink", "r:id", id, "w:tooltip", text(link.content()), "w:history", "1");
          }
          inlines(x, link.content(), new Run(run.marks(), run.lang(), "Hyperlink"));
          x.close();
        }
      }
    }
  }

  private void textRun(Xml x, String text, Run run) {
    if (text.isEmpty()) {
      return;
    }
    x.open("w:r");
    runProperties(x, run);
    x.element("w:t", text, "xml:space", "preserve");
    x.close();
  }

  private void runProperties(Xml x, Run run) {
    boolean lang = run.lang() != null && !run.lang().equalsIgnoreCase(model.lang());
    if (run.charStyle() == null && run.marks().isEmpty() && !lang) {
      return;
    }
    x.open("w:rPr");
    if (run.charStyle() != null) {
      x.empty("w:rStyle", "w:val", run.charStyle());
    }
    if (run.marks().contains(Mark.STRONG)) {
      x.empty("w:b");
    }
    if (run.marks().contains(Mark.EMPHASIS)) {
      x.empty("w:i");
    }
    if (lang) {
      x.empty("w:lang", "w:val", run.lang());
    }
    x.close();
  }

  private void footnote(Xml x, Footnote footnote) throws IOException {
    String id = String.valueOf(++footnoteCount);
    x.open("w:r")
        .open("w:rPr")
        .empty("w:rStyle", "w:val", "FootnoteReference")
        .close()
        .empty("w:footnoteReference", "w:id", id)
        .close();
    footnotes.open("w:footnote", "w:id", id).open("w:p");
    footnotes.open("w:pPr").empty("w:pStyle", "w:val", "FootnoteText").close();
    footnotes
        .open("w:r")
        .open("w:rPr")
        .empty("w:rStyle", "w:val", "FootnoteReference")
        .close()
        .empty("w:footnoteRef")
        .close();
    footnotes.open("w:r").element("w:t", " ", "xml:space", "preserve").close();
    inlines(footnotes, footnote.content(), Run.PLAIN);
    footnotes.close().close();
  }

  private void image(Xml x, Image image) throws IOException {
    Images.Raster raster = Images.raster(image);
    int number = ++drawingCount;
    String name = "media/image" + number + "." + raster.extension();
    media.put(name, raster.bytes());
    String relId = "rIdImage" + number;
    relationships.add(new Relationship(relId, REL_TYPE + "image", name, false));
    // An SVG is embedded as vector with the rasterized PNG as the fallback blip, so viewers
    // without SVG support (and the PDF/UA export) still show the image.
    String svgRelId = null;
    if ("image/svg+xml".equals(image.mediaType())) {
      String svgName = "media/image" + number + ".svg";
      media.put(svgName, image.bytes());
      svgRelId = "rIdImage" + number + "svg";
      relationships.add(new Relationship(svgRelId, REL_TYPE + "image", svgName, false));
    }
    long cx = raster.width() * 9525L;
    long cy = raster.height() * 9525L;
    if (cx > textWidthEmu) {
      cy = cy * textWidthEmu / cx;
      cx = textWidthEmu;
    }
    String alt = image.decorative() ? "" : image.alt() == null ? "" : image.alt();
    x.open("w:r").open("w:drawing");
    x.open("wp:inline", "distT", "0", "distB", "0", "distL", "0", "distR", "0");
    x.empty("wp:extent", "cx", String.valueOf(cx), "cy", String.valueOf(cy));
    x.open("wp:docPr", "id", String.valueOf(number), "name", "Image " + number, "descr", alt);
    if (image.decorative()) {
      x.open("a:extLst")
          .open("a:ext", "uri", "{C183D7F6-B498-43B3-948B-1728B52AA6E4}")
          .empty(
              "adec:decorative",
              "xmlns:adec",
              "http://schemas.microsoft.com/office/drawing/2017/decorative",
              "val",
              "1")
          .close()
          .close();
    }
    x.close();
    x.open("wp:cNvGraphicFramePr").empty("a:graphicFrameLocks", "noChangeAspect", "1").close();
    x.open("a:graphic").open("a:graphicData", "uri", PIC);
    x.open("pic:pic")
        .open("pic:nvPicPr")
        .empty(
            "pic:cNvPr",
            "id",
            "0",
            "name",
            "image" + number + "." + raster.extension(),
            "descr",
            alt)
        .empty("pic:cNvPicPr")
        .close();
    x.open("pic:blipFill").open("a:blip", "r:embed", relId);
    if (svgRelId != null) {
      x.open("a:extLst")
          .open("a:ext", "uri", "{96DAC541-7B7A-43D3-8B79-37D633B846F1}")
          .empty(
              "asvg:svgBlip",
              "xmlns:asvg",
              "http://schemas.microsoft.com/office/drawing/2016/SVG/main",
              "r:embed",
              svgRelId)
          .close()
          .close();
    }
    x.close(); // a:blip
    x.open("a:stretch").empty("a:fillRect").close();
    x.close(); // pic:blipFill
    x.open("pic:spPr")
        .open("a:xfrm")
        .empty("a:off", "x", "0", "y", "0")
        .empty("a:ext", "cx", String.valueOf(cx), "cy", String.valueOf(cy))
        .close()
        .open("a:prstGeom", "prst", "rect")
        .empty("a:avLst")
        .close()
        .close();
    x.close().close().close().close().close().close();
  }

  // --- styles and parts ---------------------------------------------------------------------

  /** Header or footer: left, center and right box separated by the style's tab stops. */
  private static byte[] pageBand(String root, String style, List<DocumentModel.PageBox> boxes) {
    Xml x = new Xml();
    x.open(root, "xmlns:w", W, "xmlns:r", R);
    x.open("w:p").open("w:pPr").empty("w:pStyle", "w:val", style).close();
    for (DocumentModel.Align align : DocumentModel.Align.values()) {
      if (align != DocumentModel.Align.LEFT) {
        x.open("w:r").empty("w:tab").close();
      }
      for (DocumentModel.PageBox box : boxes) {
        if (box.align() != align) {
          continue;
        }
        for (DocumentModel.PagePart part : box.parts()) {
          switch (part) {
            case DocumentModel.Literal literal ->
                x.open("w:r").element("w:t", literal.text(), "xml:space", "preserve").close();
            case DocumentModel.PageNumber number -> field(x, "PAGE");
            case DocumentModel.PageCount count -> field(x, "NUMPAGES");
          }
        }
      }
    }
    x.close().close();
    return x.bytes();
  }

  private static void field(Xml x, String instruction) {
    x.open("w:fldSimple", "w:instr", " " + instruction + " \\* MERGEFORMAT ")
        .open("w:r")
        .element("w:t", "1")
        .close()
        .close();
  }

  private String paragraphStyle(String catalogName) {
    if (catalogName == null) {
      return null;
    }
    if (templateStyleIds.containsKey(catalogName)) {
      return templateStyleIds.get(catalogName);
    }
    paragraphStyles.add(catalogName);
    return "Catalog-" + styleId(catalogName);
  }

  private String characterStyle(String catalogName) {
    if (templateStyleIds.containsKey(catalogName)) {
      return templateStyleIds.get(catalogName);
    }
    characterStyles.add(catalogName);
    return "CatalogChar-" + styleId(catalogName);
  }

  private static String styleId(String name) {
    return name.replaceAll("[^A-Za-z0-9-]", "-");
  }

  private static String bookmarkName(String id) {
    String name = "_" + id.replaceAll("[^A-Za-z0-9_]", "_");
    return name.length() > 40 ? name.substring(0, 40) : name;
  }

  private byte[] styles() throws IOException {
    byte[] own = ownStyles();
    if (templateStyles == null) {
      return own;
    }
    // The template's styles and defaults win; the writer's fill in what it lacks.
    Document merged = (Document) templateStyles.cloneNode(true);
    Element root = merged.getDocumentElement();
    java.util.Set<String> ids = new java.util.HashSet<>(templateStyleIds.values());
    Document ours = OfficeTemplate.parse(own);
    if (OfficeTemplate.child(root, W, "docDefaults").isEmpty()) {
      root.insertBefore(
          merged.importNode(
              OfficeTemplate.child(ours.getDocumentElement(), W, "docDefaults").orElseThrow(),
              true),
          root.getFirstChild());
    }
    for (Element style : OfficeTemplate.children(ours.getDocumentElement(), W, "style")) {
      if (!ids.contains(style.getAttributeNS(W, "styleId"))) {
        root.appendChild(merged.importNode(style, true));
      }
    }
    // The document language is the rendered one, not the template's.
    Element defaults = OfficeTemplate.child(root, W, "docDefaults").orElseThrow();
    Element runDefaults = childOrNew(childOrNew(defaults, "rPrDefault"), "rPr");
    Element lang = childOrNew(runDefaults, "lang");
    String language = model.lang().isBlank() ? "en-US" : model.lang();
    lang.setAttributeNS(W, "w:val", language);
    lang.setAttributeNS(W, "w:eastAsia", language);
    lang.setAttributeNS(W, "w:bidi", language);
    return OfficeTemplate.serialize(merged);
  }

  private static Element childOrNew(Element parent, String localName) {
    return OfficeTemplate.child(parent, W, localName)
        .orElseGet(
            () -> {
              Element e = parent.getOwnerDocument().createElementNS(W, "w:" + localName);
              parent.appendChild(e);
              return e;
            });
  }

  /** Embeds the layout's fonts the Word template names, obfuscated as Word requires. */
  private void fontTable(Zip zip) throws IOException {
    Xml table = new Xml();
    table.open("w:fonts", "xmlns:w", W, "xmlns:r", R);
    Xml rels = new Xml();
    rels.open(
        "Relationships", "xmlns", "http://schemas.openxmlformats.org/package/2006/relationships");
    int n = 0;
    for (Map.Entry<String, byte[]> font : embeddedFonts.entrySet()) {
      n++;
      String key = OfficeTemplate.fontKey(font.getValue());
      table.open("w:font", "w:name", font.getKey());
      table.empty("w:embedRegular", "r:id", "rIdFont" + n, "w:fontKey", key);
      table.close();
      rels.empty(
          "Relationship",
          "Id",
          "rIdFont" + n,
          "Type",
          REL_TYPE + "font",
          "Target",
          "fonts/font" + n + ".odttf");
      zip.add("word/fonts/font" + n + ".odttf", OfficeTemplate.obfuscate(font.getValue(), key));
    }
    table.close();
    rels.close();
    zip.add("word/fontTable.xml", table.bytes());
    zip.add("word/_rels/fontTable.xml.rels", rels.bytes());
  }

  private byte[] ownStyles() {
    String lang = model.lang().isBlank() ? "en-US" : model.lang();
    Xml x = new Xml();
    x.open("w:styles", "xmlns:w", W);
    x.open("w:docDefaults");
    x.open("w:rPrDefault").open("w:rPr");
    x.empty(
        "w:rFonts", "w:ascii", "Arial", "w:hAnsi", "Arial", "w:eastAsia", "Arial", "w:cs", "Arial");
    x.empty("w:sz", "w:val", "22").empty("w:szCs", "w:val", "22");
    x.empty("w:lang", "w:val", lang, "w:eastAsia", lang, "w:bidi", lang);
    x.close().close();
    x.open("w:pPrDefault").open("w:pPr").empty("w:spacing", "w:after", "120").close().close();
    x.close();
    style(x, "paragraph", "Normal", "Normal", null, true);
    int[] sizes = {32, 28, 26, 24, 22, 22};
    for (int level = 1; level <= 6; level++) {
      x.open("w:style", "w:type", "paragraph", "w:styleId", "Heading" + level);
      x.empty("w:name", "w:val", "heading " + level);
      x.empty("w:basedOn", "w:val", "Normal").empty("w:next", "w:val", "Normal").empty("w:qFormat");
      x.open("w:pPr")
          .empty("w:keepNext")
          .empty("w:spacing", "w:before", "240", "w:after", "120")
          .empty("w:outlineLvl", "w:val", String.valueOf(level - 1))
          .close();
      x.open("w:rPr").empty("w:b").empty("w:sz", "w:val", String.valueOf(sizes[level - 1])).close();
      x.close();
    }
    for (String band : List.of("Header", "Footer")) {
      x.open("w:style", "w:type", "paragraph", "w:styleId", band);
      x.empty("w:name", "w:val", band.toLowerCase(java.util.Locale.ROOT))
          .empty("w:basedOn", "w:val", "Normal");
      x.open("w:pPr").open("w:tabs");
      x.empty("w:tab", "w:val", "center", "w:pos", String.valueOf(textWidthTwips / 2));
      x.empty("w:tab", "w:val", "right", "w:pos", String.valueOf(textWidthTwips));
      x.close().empty("w:spacing", "w:after", "0").close();
      x.open("w:rPr").empty("w:sz", "w:val", "18").close();
      x.close();
    }
    x.open("w:style", "w:type", "paragraph", "w:styleId", "Caption");
    x.empty("w:name", "w:val", "caption").empty("w:basedOn", "w:val", "Normal").empty("w:qFormat");
    x.open("w:rPr").empty("w:i").close().close();
    x.open("w:style", "w:type", "paragraph", "w:styleId", "TableHeading");
    x.empty("w:name", "w:val", "Table Heading").empty("w:basedOn", "w:val", "Normal");
    x.open("w:rPr").empty("w:b").close().close();
    x.open("w:style", "w:type", "paragraph", "w:styleId", "ListParagraph");
    x.empty("w:name", "w:val", "List Paragraph").empty("w:basedOn", "w:val", "Normal");
    x.open("w:pPr").empty("w:spacing", "w:after", "0").close().close();
    x.open("w:style", "w:type", "paragraph", "w:styleId", "FootnoteText");
    x.empty("w:name", "w:val", "footnote text").empty("w:basedOn", "w:val", "Normal");
    x.open("w:rPr").empty("w:sz", "w:val", "18").close().close();
    x.open("w:style", "w:type", "character", "w:styleId", "FootnoteReference");
    x.empty("w:name", "w:val", "footnote reference");
    x.open("w:rPr").empty("w:vertAlign", "w:val", "superscript").close().close();
    x.open("w:style", "w:type", "character", "w:styleId", "Hyperlink");
    x.empty("w:name", "w:val", "Hyperlink");
    x.open("w:rPr").empty("w:color", "w:val", "0563C1").empty("w:u", "w:val", "single").close();
    x.close();
    x.open("w:style", "w:type", "table", "w:styleId", "TableGrid");
    x.empty("w:name", "w:val", "Table Grid");
    x.open("w:tblPr").open("w:tblBorders");
    for (String side : List.of("top", "left", "bottom", "right", "insideH", "insideV")) {
      x.empty("w:" + side, "w:val", "single", "w:sz", "4", "w:space", "0", "w:color", "auto");
    }
    x.close().close().close();
    for (String name : paragraphStyles) {
      style(x, "paragraph", "Catalog-" + styleId(name), name, "Normal", false);
    }
    for (String name : characterStyles) {
      style(x, "character", "CatalogChar-" + styleId(name), name, null, false);
    }
    x.close();
    return x.bytes();
  }

  private static void style(
      Xml x, String type, String id, String name, String basedOn, boolean isDefault) {
    x.open("w:style", "w:type", type, "w:default", isDefault ? "1" : null, "w:styleId", id);
    x.empty("w:name", "w:val", name);
    if (basedOn != null) {
      x.empty("w:basedOn", "w:val", basedOn);
    }
    x.empty("w:qFormat");
    x.close();
  }

  private byte[] numberingPart() {
    // Characters every text font has; ◦ and ▪ are missing in many and fall back to another font.
    String[] bullets = {"•", "–", "·"};
    Xml x = new Xml();
    x.open("w:numbering", "xmlns:w", W);
    for (int abstractId = 0; abstractId < 2; abstractId++) {
      boolean ordered = abstractId == 1;
      x.open("w:abstractNum", "w:abstractNumId", String.valueOf(abstractId));
      x.empty("w:multiLevelType", "w:val", "hybridMultilevel");
      for (int level = 0; level < 9; level++) {
        x.open("w:lvl", "w:ilvl", String.valueOf(level));
        x.empty("w:start", "w:val", "1");
        x.empty("w:numFmt", "w:val", ordered ? "decimal" : "bullet");
        x.empty(
            "w:lvlText",
            "w:val",
            ordered ? "%" + (level + 1) + "." : bullets[level % bullets.length]);
        x.empty("w:lvlJc", "w:val", "left");
        if (defaultFont != null) {
          // Without a font, markers take the application's default font, not the layout's.
          x.open("w:rPr").empty("w:rFonts", "w:ascii", defaultFont, "w:hAnsi", defaultFont).close();
        }
        x.open("w:pPr")
            .empty("w:ind", "w:left", String.valueOf(720 * (level + 1)), "w:hanging", "360")
            .close();
        x.close();
      }
      x.close();
    }
    for (int i = 0; i < numbering.size(); i++) {
      x.open("w:num", "w:numId", String.valueOf(i + 1))
          .empty("w:abstractNumId", "w:val", numbering.get(i) ? "1" : "0");
      // Every list starts at 1.
      x.open("w:lvlOverride", "w:ilvl", "0").empty("w:startOverride", "w:val", "1").close();
      x.close();
    }
    x.close();
    return x.bytes();
  }

  private byte[] settings() {
    Xml x = new Xml();
    x.open("w:settings", "xmlns:w", W);
    if (!embeddedFonts.isEmpty()) {
      x.empty("w:embedTrueTypeFonts");
    }
    x.open("w:footnotePr")
        .empty("w:footnote", "w:id", "-1")
        .empty("w:footnote", "w:id", "0")
        .close();
    x.open("w:compat")
        .empty(
            "w:compatSetting",
            "w:name",
            "compatibilityMode",
            "w:uri",
            "http://schemas.microsoft.com/office/word",
            "w:val",
            "15")
        .close();
    x.empty("w:themeFontLang", "w:val", model.lang().isBlank() ? "en-US" : model.lang());
    x.close();
    return x.bytes();
  }

  private byte[] contentTypes() {
    String ns = "http://schemas.openxmlformats.org/package/2006/content-types";
    String wml = "application/vnd.openxmlformats-officedocument.wordprocessingml.";
    Xml x = new Xml();
    x.open("Types", "xmlns", ns);
    x.empty(
        "Default",
        "Extension",
        "rels",
        "ContentType",
        "application/vnd.openxmlformats-package.relationships+xml");
    x.empty("Default", "Extension", "xml", "ContentType", "application/xml");
    x.empty("Default", "Extension", "png", "ContentType", "image/png");
    x.empty("Default", "Extension", "jpeg", "ContentType", "image/jpeg");
    x.empty("Default", "Extension", "svg", "ContentType", "image/svg+xml");
    if (!embeddedFonts.isEmpty()) {
      x.empty(
          "Default",
          "Extension",
          "odttf",
          "ContentType",
          "application/vnd.openxmlformats-officedocument.obfuscatedFont");
      x.empty("Override", "PartName", "/word/fontTable.xml", "ContentType", wml + "fontTable+xml");
    }
    if (theme != null) {
      x.empty(
          "Override",
          "PartName",
          "/word/theme/theme1.xml",
          "ContentType",
          "application/vnd.openxmlformats-officedocument.theme+xml");
    }
    x.empty("Override", "PartName", "/word/document.xml", "ContentType", wml + "document.main+xml");
    x.empty("Override", "PartName", "/word/styles.xml", "ContentType", wml + "styles+xml");
    x.empty("Override", "PartName", "/word/numbering.xml", "ContentType", wml + "numbering+xml");
    x.empty("Override", "PartName", "/word/footnotes.xml", "ContentType", wml + "footnotes+xml");
    x.empty("Override", "PartName", "/word/settings.xml", "ContentType", wml + "settings+xml");
    if (!model.header().isEmpty()) {
      x.empty("Override", "PartName", "/word/header1.xml", "ContentType", wml + "header+xml");
    }
    if (!model.footer().isEmpty()) {
      x.empty("Override", "PartName", "/word/footer1.xml", "ContentType", wml + "footer+xml");
    }
    x.empty(
        "Override",
        "PartName",
        "/docProps/core.xml",
        "ContentType",
        "application/vnd.openxmlformats-package.core-properties+xml");
    x.empty(
        "Override",
        "PartName",
        "/docProps/app.xml",
        "ContentType",
        "application/vnd.openxmlformats-officedocument.extended-properties+xml");
    x.close();
    return x.bytes();
  }

  private static byte[] packageRelationships() {
    Xml x = new Xml();
    x.open(
        "Relationships", "xmlns", "http://schemas.openxmlformats.org/package/2006/relationships");
    x.empty(
        "Relationship",
        "Id",
        "rId1",
        "Type",
        REL_TYPE + "officeDocument",
        "Target",
        "word/document.xml");
    x.empty(
        "Relationship",
        "Id",
        "rId2",
        "Type",
        "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties",
        "Target",
        "docProps/core.xml");
    x.empty(
        "Relationship",
        "Id",
        "rId3",
        "Type",
        REL_TYPE + "extended-properties",
        "Target",
        "docProps/app.xml");
    x.close();
    return x.bytes();
  }

  private byte[] documentRelationships() {
    Xml x = new Xml();
    x.open(
        "Relationships", "xmlns", "http://schemas.openxmlformats.org/package/2006/relationships");
    for (Relationship rel : relationships) {
      x.empty(
          "Relationship",
          "Id",
          rel.id(),
          "Type",
          rel.type(),
          "Target",
          rel.target(),
          "TargetMode",
          rel.external() ? "External" : null);
    }
    x.close();
    return x.bytes();
  }

  private byte[] coreProperties() {
    Xml x = new Xml();
    x.open(
        "cp:coreProperties",
        "xmlns:cp",
        "http://schemas.openxmlformats.org/package/2006/metadata/core-properties",
        "xmlns:dc",
        "http://purl.org/dc/elements/1.1/",
        "xmlns:dcterms",
        "http://purl.org/dc/terms/");
    x.element("dc:title", model.title());
    if (!model.description().isBlank()) {
      x.element("dc:description", model.description());
    }
    if (!model.lang().isBlank()) {
      x.element("dc:language", model.lang());
    }
    x.close();
    return x.bytes();
  }

  private static byte[] appProperties() {
    Xml x = new Xml();
    x.open(
        "Properties",
        "xmlns",
        "http://schemas.openxmlformats.org/officeDocument/2006/extended-properties");
    x.element("Application", at.itbh.pdfuagen.core.DocumentRenderer.PRODUCER);
    x.close();
    return x.bytes();
  }

  static String text(List<Inline> inlines) {
    StringBuilder out = new StringBuilder();
    for (Inline inline : inlines) {
      if (inline instanceof Text t) {
        out.append(t.text());
      } else if (inline instanceof Link link) {
        out.append(text(link.content()));
      }
    }
    return out.toString();
  }
}
