/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.writer;

import at.itbh.pdfuagen.core.DocumentRenderer;
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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Writes an OpenDocument text document (ODF 1.3) from the model with JDK StAX and ZIP.
 * Accessibility structure: headings with outline levels, alt text ({@code svg:title}) and the
 * decorative flag on images, table header rows and table titles, real lists, real footnotes,
 * document title and language.
 */
public final class OdtWriter {

  private static final String[][] NAMESPACES = {
    {"office", "urn:oasis:names:tc:opendocument:xmlns:office:1.0"},
    {"style", "urn:oasis:names:tc:opendocument:xmlns:style:1.0"},
    {"text", "urn:oasis:names:tc:opendocument:xmlns:text:1.0"},
    {"table", "urn:oasis:names:tc:opendocument:xmlns:table:1.0"},
    {"draw", "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"},
    {"fo", "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"},
    {"svg", "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"},
    {"xlink", "http://www.w3.org/1999/xlink"},
    {"dc", "http://purl.org/dc/elements/1.1/"},
    {"meta", "urn:oasis:names:tc:opendocument:xmlns:meta:1.0"},
    {"loext", "urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0"},
  };

  static final String OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
  static final String STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
  static final String FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";
  static final String SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0";
  static final String XLINK = "http://www.w3.org/1999/xlink";

  /** Page width, height and margins without an ODF template: A4 with 2 cm margins. */
  private static final Map<String, String> PAGE =
      Map.of(
          "fo:page-width",
          "21cm",
          "fo:page-height",
          "29.7cm",
          "fo:margin-top",
          "2cm",
          "fo:margin-bottom",
          "2cm",
          "fo:margin-left",
          "2cm",
          "fo:margin-right",
          "2cm");

  private record TextStyleKey(Set<Mark> marks, String lang, String catalog) {}

  private final DocumentModel model;
  private final Map<String, byte[]> pictures = new LinkedHashMap<>();
  private final Map<TextStyleKey, String> textStyles = new LinkedHashMap<>();
  private final Set<String> paragraphCatalog = new LinkedHashSet<>();
  private final Set<String> characterCatalog = new LinkedHashSet<>();
  private int footnoteCount;
  private int imageCount;
  private int tableCount;

  /** From the layout's ODF template: styles, page setup, fonts to embed. */
  private final Document templateStyles;

  /** Template styles by display name and by name, for catalog styles. */
  private final Map<String, String> templateStyleNames = new HashMap<>();

  private final Map<String, String> page = new LinkedHashMap<>(PAGE);
  private final Map<String, byte[]> fonts;
  private final Map<String, byte[]> embeddedFonts = new LinkedHashMap<>();
  private final double textWidthCm;

  private OdtWriter(DocumentModel model, OfficeTemplate template) throws IOException {
    this.model = model;
    this.fonts = template.fonts();
    if (template.ott() != null) {
      templateStyles =
          OfficeTemplate.parse(
              OfficeTemplate.part(template.ott(), "styles.xml")
                  .orElseThrow(() -> new IOException("the ODF template has no styles.xml")));
      for (Element style : OfficeTemplate.descendants(templateStyles, STYLE, "style")) {
        String name = style.getAttributeNS(STYLE, "name");
        templateStyleNames.put(name, name);
        String display = style.getAttributeNS(STYLE, "display-name");
        if (!display.isEmpty()) {
          templateStyleNames.put(display, name);
        }
      }
      List<Element> layouts =
          OfficeTemplate.descendants(templateStyles, STYLE, "page-layout-properties");
      if (!layouts.isEmpty()) {
        for (String attribute : PAGE.keySet()) {
          String value = layouts.getFirst().getAttributeNS(FO, attribute.substring(3));
          if (!value.isEmpty()) {
            page.put(attribute, value);
          }
        }
      }
    } else {
      templateStyles = null;
    }
    textWidthCm =
        OfficeTemplate.centimetres(page.get("fo:page-width"))
            - OfficeTemplate.centimetres(page.get("fo:margin-left"))
            - OfficeTemplate.centimetres(page.get("fo:margin-right"));
  }

  public static byte[] write(DocumentModel model) throws IOException {
    return write(model, OfficeTemplate.NONE);
  }

  /**
   * @param template the layout's ODF template and fonts: its styles, fonts and page setup are the
   *     base; styles it lacks are added
   */
  public static byte[] write(DocumentModel model, OfficeTemplate template) throws IOException {
    return new OdtWriter(model, template).write();
  }

  private byte[] write() throws IOException {
    // The body is written first: it determines the automatic styles content.xml starts with.
    Xml body = new Xml(false);
    body.open("office:text");
    blocks(body, model.blocks(), 0);
    body.close();

    Zip zip = new Zip();
    zip.addStored(
        "mimetype", "application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.US_ASCII));
    byte[] content = content(body);
    byte[] styles = styles();
    zip.add("META-INF/manifest.xml", manifest());
    zip.add("content.xml", content);
    zip.add("styles.xml", styles);
    zip.add("meta.xml", meta());
    for (Map.Entry<String, byte[]> font : embeddedFonts.entrySet()) {
      zip.add(font.getKey(), font.getValue());
    }
    for (Map.Entry<String, byte[]> entry : pictures.entrySet()) {
      zip.add(entry.getKey(), entry.getValue());
    }
    return zip.finish();
  }

  // --- blocks -------------------------------------------------------------------------------

  private void blocks(Xml x, List<Block> blocks, int depth) throws IOException {
    for (Block block : blocks) {
      switch (block) {
        case Heading h -> {
          x.open(
              "text:h",
              "text:style-name",
              "Heading_20_" + h.level(),
              "text:outline-level",
              String.valueOf(h.level()));
          if (h.id() != null) {
            x.empty("text:bookmark", "text:name", h.id());
          }
          inlines(x, h.content(), null);
          x.close();
        }
        case Paragraph p -> paragraph(x, paragraphStyle(p.style(), "Text_20_body"), p.content());
        case ListBlock list -> list(x, list, depth);
        case Table table -> table(x, table);
        case ImageBlock image -> {
          x.open("text:p", "text:style-name", "Text_20_body");
          image(x, image.image());
          x.close();
        }
        case Columns columns -> layoutTable(x, columns.columns(), "LayoutCell", null);
        case Box box -> layoutTable(x, List.of(box.content()), "BoxCell", box.style());
        case PageBreak pageBreak -> x.empty("text:p", "text:style-name", "P_break");
      }
    }
  }

  private void paragraph(Xml x, String style, List<Inline> content) throws IOException {
    x.open("text:p", "text:style-name", style);
    inlines(x, content, null);
    x.close();
  }

  private void list(Xml x, ListBlock list, int depth) throws IOException {
    x.open(
        "text:list",
        "text:style-name",
        depth == 0 ? (list.ordered() ? "L_number" : "L_bullet") : null);
    for (List<Block> item : list.items()) {
      x.open("text:list-item");
      for (Block block : item) {
        if (block instanceof Paragraph p) {
          paragraph(x, paragraphStyle(p.style(), "List_20_Contents"), p.content());
        } else if (block instanceof ListBlock nested) {
          list(x, nested, depth + 1);
        } else {
          // Lists may hold only paragraphs, headings and lists in ODF; wrap the rest.
          x.open("text:p", "text:style-name", "List_20_Contents").close();
          blocks(x, List.of(block), depth + 1);
        }
      }
      x.close();
    }
    x.close();
  }

  private void table(Xml x, Table table) throws IOException {
    String caption = DocxWriter.text(table.caption());
    if (!caption.isEmpty()) {
      paragraph(x, "Caption", table.caption());
    }
    int columns = 1;
    for (List<Row> rows : List.of(table.head(), table.body(), table.foot())) {
      for (Row row : rows) {
        columns = Math.max(columns, row.cells().stream().mapToInt(Cell::colspan).sum());
      }
    }
    x.open("table:table", "table:name", "Table" + (++tableCount), "table:style-name", "Tbl");
    if (!caption.isEmpty()) {
      x.element("table:title", caption);
    }
    x.empty("table:table-column", "table:number-columns-repeated", String.valueOf(columns));
    if (!table.head().isEmpty()) {
      x.open("table:table-header-rows");
      section(x, table.head());
      x.close();
    }
    section(x, table.body());
    section(x, table.foot());
    x.close();
  }

  private void section(Xml x, List<Row> rows) throws IOException {
    for (List<TableGrid.Slot> slots : TableGrid.layout(rows)) {
      row(x, slots);
    }
  }

  private void row(Xml x, List<TableGrid.Slot> slots) throws IOException {
    x.open("table:table-row");
    for (TableGrid.Slot slot : slots) {
      if (slot instanceof TableGrid.Covered covered) {
        for (int i = 0; i < covered.columns(); i++) {
          x.empty("table:covered-table-cell");
        }
        continue;
      }
      Cell cell = ((TableGrid.Placed) slot).cell();
      x.open(
          "table:table-cell",
          "table:style-name",
          "TblCell",
          "office:value-type",
          "string",
          "table:number-columns-spanned",
          cell.colspan() > 1 ? String.valueOf(cell.colspan()) : null,
          "table:number-rows-spanned",
          cell.rowspan() > 1 ? String.valueOf(cell.rowspan()) : null);
      cellContent(x, cell.content(), cell.header() ? "Table_20_Heading" : "Table_20_Contents");
      x.close();
      for (int i = 1; i < cell.colspan(); i++) {
        x.empty("table:covered-table-cell");
      }
    }
    x.close();
  }

  private void layoutTable(Xml x, List<List<Block>> cells, String cellStyle, String catalog)
      throws IOException {
    x.open("table:table", "table:name", "Table" + (++tableCount), "table:style-name", "Layout");
    x.empty("table:table-column", "table:number-columns-repeated", String.valueOf(cells.size()));
    x.open("table:table-row");
    for (List<Block> cell : cells) {
      x.open("table:table-cell", "table:style-name", cellStyle, "office:value-type", "string");
      cellContent(x, cell, paragraphStyle(catalog, "Text_20_body"));
      x.close();
    }
    x.close().close();
  }

  private void cellContent(Xml x, List<Block> content, String paragraphStyle) throws IOException {
    if (content.isEmpty()) {
      x.empty("text:p", "text:style-name", paragraphStyle);
      return;
    }
    for (Block block : content) {
      if (block instanceof Paragraph p && p.style() == null) {
        paragraph(x, paragraphStyle, p.content());
      } else {
        blocks(x, List.of(block), 0);
      }
    }
  }

  // --- inlines ------------------------------------------------------------------------------

  private void inlines(Xml x, List<Inline> inlines, String linkStyle) throws IOException {
    for (Inline inline : inlines) {
      switch (inline) {
        case Text t -> {
          String style = textStyle(t);
          if (style == null) {
            x.text(t.text());
          } else {
            x.open("text:span", "text:style-name", style).text(t.text()).close();
          }
        }
        case LineBreak lb -> x.empty("text:line-break");
        case InlineImage image -> image(x, image.image());
        case Footnote footnote -> {
          String number = String.valueOf(++footnoteCount);
          x.open("text:note", "text:id", "ftn" + number, "text:note-class", "footnote");
          x.element("text:note-citation", number);
          x.open("text:note-body").open("text:p", "text:style-name", "Footnote");
          inlines(x, footnote.content(), null);
          x.close().close().close();
        }
        case Link link -> {
          x.open(
              "text:a",
              "xlink:type",
              "simple",
              "xlink:href",
              link.href(),
              "office:name",
              DocxWriter.text(link.content()),
              "office:title",
              DocxWriter.text(link.content()),
              "text:style-name",
              "Internet_20_link",
              "text:visited-style-name",
              "Visited_20_Internet_20_Link");
          inlines(x, link.content(), "Internet_20_link");
          x.close();
        }
      }
    }
  }

  private String textStyle(Text t) {
    boolean lang = t.lang() != null && !t.lang().equalsIgnoreCase(model.lang());
    if (t.marks().isEmpty() && !lang && t.style() == null) {
      return null;
    }
    if (t.style() != null && !templateStyleNames.containsKey(t.style())) {
      characterCatalog.add(t.style());
    }
    TextStyleKey key = new TextStyleKey(t.marks(), lang ? t.lang() : null, t.style());
    return textStyles.computeIfAbsent(key, k -> "T" + (textStyles.size() + 1));
  }

  private void image(Xml x, Image image) throws IOException {
    Images.Raster raster = Images.raster(image);
    String base = "Pictures/image" + (++imageCount);
    String name = base + "." + raster.extension();
    pictures.put(name, raster.bytes());
    // An SVG is kept as vector with the rasterized PNG as a fallback; ODF renders the first
    // draw:image it supports, so the SVG comes first and the PNG after it.
    String svgName = null;
    if ("image/svg+xml".equals(image.mediaType())) {
      svgName = base + ".svg";
      pictures.put(svgName, image.bytes());
    }
    double width = raster.width() / 96.0 * 2.54;
    double height = raster.height() / 96.0 * 2.54;
    if (width > textWidthCm) {
      height = height * textWidthCm / width;
      width = textWidthCm;
    }
    x.open(
        "draw:frame",
        "draw:style-name",
        image.decorative() ? "FrameDecorative" : "Frame",
        "draw:name",
        "Image " + imageCount,
        "text:anchor-type",
        "as-char",
        "svg:width",
        cm(width),
        "svg:height",
        cm(height),
        "draw:z-index",
        "0");
    if (svgName != null) {
      x.empty(
          "draw:image",
          "xlink:href",
          svgName,
          "xlink:type",
          "simple",
          "xlink:show",
          "embed",
          "xlink:actuate",
          "onLoad",
          "draw:mime-type",
          "image/svg+xml");
    }
    x.empty(
        "draw:image",
        "xlink:href",
        name,
        "xlink:type",
        "simple",
        "xlink:show",
        "embed",
        "xlink:actuate",
        "onLoad",
        "draw:mime-type",
        raster.mediaType());
    if (!image.decorative() && image.alt() != null && !image.alt().isEmpty()) {
      x.element("svg:title", image.alt());
    }
    x.close();
  }

  private String paragraphStyle(String catalog, String fallback) {
    if (catalog == null) {
      return fallback;
    }
    if (templateStyleNames.containsKey(catalog)) {
      return templateStyleNames.get(catalog);
    }
    paragraphCatalog.add(catalog);
    return "Catalog_" + styleId(catalog);
  }

  private static String styleId(String name) {
    return name.replaceAll("[^A-Za-z0-9]", "_");
  }

  private static String cm(double value) {
    return String.format(Locale.ROOT, "%.3fcm", value);
  }

  // --- parts --------------------------------------------------------------------------------

  private static String[] namespaceAttributes() {
    String[] attributes = new String[NAMESPACES.length * 2];
    for (int i = 0; i < NAMESPACES.length; i++) {
      attributes[2 * i] = "xmlns:" + NAMESPACES[i][0];
      attributes[2 * i + 1] = NAMESPACES[i][1];
    }
    return attributes;
  }

  private static String[] root(String... extra) {
    String[] ns = namespaceAttributes();
    String[] all = new String[ns.length + extra.length];
    System.arraycopy(ns, 0, all, 0, ns.length);
    System.arraycopy(extra, 0, all, ns.length, extra.length);
    return all;
  }

  private byte[] content(Xml body) {
    Xml x = new Xml();
    x.open("office:document-content", root("office:version", "1.3"));
    x.open("office:automatic-styles");
    x.open(
        "style:style",
        "style:name",
        "P_break",
        "style:family",
        "paragraph",
        "style:parent-style-name",
        "Standard");
    x.empty("style:paragraph-properties", "fo:break-after", "page").close();
    x.open("style:style", "style:name", "Tbl", "style:family", "table");
    x.empty("style:table-properties", "style:width", cm(textWidthCm), "table:align", "margins")
        .close();
    x.open("style:style", "style:name", "Layout", "style:family", "table");
    x.empty("style:table-properties", "style:width", cm(textWidthCm), "table:align", "margins")
        .close();
    x.open("style:style", "style:name", "TblCell", "style:family", "table-cell");
    x.empty(
            "style:table-cell-properties",
            "fo:padding",
            "0.1cm",
            "fo:border",
            "0.5pt solid #000000")
        .close();
    x.open("style:style", "style:name", "LayoutCell", "style:family", "table-cell");
    x.empty("style:table-cell-properties", "fo:padding", "0.1cm", "fo:border", "none").close();
    x.open("style:style", "style:name", "BoxCell", "style:family", "table-cell");
    x.empty(
            "style:table-cell-properties",
            "fo:padding",
            "0.2cm",
            "fo:border",
            "0.5pt solid #808080",
            "fo:background-color",
            "#f2f2f2")
        .close();
    x.open("style:style", "style:name", "Frame", "style:family", "graphic");
    x.empty(
            "style:graphic-properties",
            "style:vertical-pos",
            "top",
            "style:vertical-rel",
            "baseline")
        .close();
    // LibreOffice reads the decorative flag from the frame's graphic style.
    x.open("style:style", "style:name", "FrameDecorative", "style:family", "graphic");
    x.empty(
            "style:graphic-properties",
            "style:vertical-pos",
            "top",
            "style:vertical-rel",
            "baseline",
            "loext:decorative",
            "true")
        .close();
    for (Map.Entry<TextStyleKey, String> entry : textStyles.entrySet()) {
      TextStyleKey key = entry.getKey();
      x.open(
          "style:style",
          "style:name",
          entry.getValue(),
          "style:family",
          "text",
          "style:parent-style-name",
          key.catalog() == null
              ? null
              : templateStyleNames.getOrDefault(
                  key.catalog(), "CatalogChar_" + styleId(key.catalog())));
      String[] lang = key.lang() == null ? null : key.lang().split("[-_]", 2);
      x.empty(
          "style:text-properties",
          "fo:font-weight",
          key.marks().contains(Mark.STRONG) ? "bold" : null,
          "fo:font-style",
          key.marks().contains(Mark.EMPHASIS) ? "italic" : null,
          "fo:language",
          lang == null ? null : lang[0].toLowerCase(Locale.ROOT),
          "fo:country",
          lang == null || lang.length < 2 ? null : lang[1].toUpperCase(Locale.ROOT));
      x.close();
    }
    x.close();
    x.open("office:body").raw(body.bytes()).close();
    x.close();
    return x.bytes();
  }

  private byte[] styles() throws IOException {
    byte[] own = ownStyles();
    Document merged = OfficeTemplate.parse(own);
    Element root = merged.getDocumentElement();
    if (templateStyles != null) {
      // The template's fonts and named styles win; the writer's fill in what it lacks.
      Element decls = OfficeTemplate.child(root, OFFICE, "font-face-decls").orElseThrow();
      OfficeTemplate.child(templateStyles.getDocumentElement(), OFFICE, "font-face-decls")
          .ifPresent(
              template -> {
                for (Element face : OfficeTemplate.children(template, STYLE, "font-face")) {
                  decls.appendChild(merged.importNode(face, true));
                }
              });
      Element styles = OfficeTemplate.child(root, OFFICE, "styles").orElseThrow();
      Element template =
          OfficeTemplate.child(templateStyles.getDocumentElement(), OFFICE, "styles").orElse(null);
      if (template != null) {
        for (org.w3c.dom.Node node = template.getFirstChild();
            node != null;
            node = node.getNextSibling()) {
          if (!(node instanceof Element element)) {
            continue;
          }
          Element existing = same(styles, element);
          Element imported = (Element) merged.importNode(element, true);
          if (existing != null) {
            styles.replaceChild(imported, existing);
          } else {
            styles.appendChild(imported);
          }
        }
      }
    }
    // Embed the layout's fonts the styles name.
    for (Element face : OfficeTemplate.descendants(merged, STYLE, "font-face")) {
      String family = face.getAttributeNS(SVG, "font-family").replaceAll("^['\"]|['\"]$", "");
      byte[] font = fonts.get(family);
      if (font == null || !OfficeTemplate.children(face, SVG, "font-face-src").isEmpty()) {
        continue;
      }
      String path = "Fonts/" + family.replaceAll("[^A-Za-z0-9]", "_") + ".ttf";
      embeddedFonts.put(path, font);
      Element src = merged.createElementNS(SVG, "svg:font-face-src");
      Element uri = merged.createElementNS(SVG, "svg:font-face-uri");
      uri.setAttributeNS(XLINK, "xlink:href", path);
      uri.setAttributeNS(XLINK, "xlink:type", "simple");
      Element format = merged.createElementNS(SVG, "svg:font-face-format");
      format.setAttributeNS(SVG, "svg:string", "truetype");
      uri.appendChild(format);
      src.appendChild(uri);
      face.appendChild(src);
    }
    return OfficeTemplate.serialize(merged);
  }

  /** The element in {@code styles} with the same kind, family and name, or {@code null}. */
  private static Element same(Element styles, Element element) {
    for (org.w3c.dom.Node node = styles.getFirstChild();
        node != null;
        node = node.getNextSibling()) {
      if (node instanceof Element e
          && e.getLocalName().equals(element.getLocalName())
          && java.util.Objects.equals(e.getNamespaceURI(), element.getNamespaceURI())
          && e.getAttributeNS(STYLE, "family").equals(element.getAttributeNS(STYLE, "family"))
          && e.getAttributeNS(STYLE, "name").equals(element.getAttributeNS(STYLE, "name"))) {
        return e;
      }
    }
    return null;
  }

  private byte[] ownStyles() {
    String[] lang =
        model.lang().isBlank() ? new String[] {"en", "US"} : model.lang().split("[-_]", 2);
    Xml x = new Xml();
    x.open("office:document-styles", root("office:version", "1.3"));
    x.open("office:font-face-decls")
        .empty("style:font-face", "style:name", "Arial", "svg:font-family", "Arial")
        .close();
    x.open("office:styles");
    x.open("style:default-style", "style:family", "paragraph");
    x.empty(
        "style:text-properties",
        "style:font-name",
        "Arial",
        "fo:font-size",
        "11pt",
        "fo:language",
        lang[0].toLowerCase(Locale.ROOT),
        "fo:country",
        lang.length > 1 ? lang[1].toUpperCase(Locale.ROOT) : "none");
    x.close();
    paragraphStyle(x, "Standard", "Standard", null, null);
    paragraphStyle(x, "Text_20_body", "Text body", "Standard", "0.21cm");
    x.open(
        "style:style",
        "style:name",
        "Heading",
        "style:family",
        "paragraph",
        "style:parent-style-name",
        "Standard",
        "style:next-style-name",
        "Text_20_body",
        "style:class",
        "text");
    x.empty(
        "style:paragraph-properties",
        "fo:margin-top",
        "0.42cm",
        "fo:margin-bottom",
        "0.21cm",
        "fo:keep-with-next",
        "always");
    x.empty("style:text-properties", "fo:font-weight", "bold").close();
    String[] sizes = {"16pt", "14pt", "13pt", "12pt", "11pt", "11pt"};
    for (int level = 1; level <= 6; level++) {
      x.open(
          "style:style",
          "style:name",
          "Heading_20_" + level,
          "style:display-name",
          "Heading " + level,
          "style:family",
          "paragraph",
          "style:parent-style-name",
          "Heading",
          "style:next-style-name",
          "Text_20_body",
          "style:default-outline-level",
          String.valueOf(level),
          "style:class",
          "text");
      x.empty("style:text-properties", "fo:font-size", sizes[level - 1]).close();
    }
    for (String band : List.of("Header", "Footer")) {
      x.open(
          "style:style",
          "style:name",
          band,
          "style:family",
          "paragraph",
          "style:parent-style-name",
          "Standard",
          "style:class",
          "extra");
      x.open("style:paragraph-properties").open("style:tab-stops");
      x.empty("style:tab-stop", "style:position", cm(textWidthCm / 2), "style:type", "center");
      x.empty("style:tab-stop", "style:position", cm(textWidthCm), "style:type", "right");
      x.close().close();
      x.empty("style:text-properties", "fo:font-size", "9pt");
      x.close();
    }
    paragraphStyle(x, "Caption", "Caption", "Standard", "0.21cm");
    paragraphStyle(x, "List_20_Contents", "List Contents", "Standard", "0cm");
    paragraphStyle(x, "Table_20_Contents", "Table Contents", "Standard", "0cm");
    x.open(
        "style:style",
        "style:name",
        "Table_20_Heading",
        "style:display-name",
        "Table Heading",
        "style:family",
        "paragraph",
        "style:parent-style-name",
        "Table_20_Contents");
    x.empty("style:text-properties", "fo:font-weight", "bold").close();
    x.open(
        "style:style",
        "style:name",
        "Footnote",
        "style:family",
        "paragraph",
        "style:parent-style-name",
        "Standard");
    x.empty("style:text-properties", "fo:font-size", "9pt").close();
    x.open(
        "style:style",
        "style:name",
        "Internet_20_link",
        "style:display-name",
        "Internet link",
        "style:family",
        "text");
    x.empty(
            "style:text-properties",
            "fo:color",
            "#0563c1",
            "style:text-underline-style",
            "solid",
            "style:text-underline-width",
            "auto",
            "style:text-underline-color",
            "font-color")
        .close();
    x.open(
        "style:style",
        "style:name",
        "Visited_20_Internet_20_Link",
        "style:display-name",
        "Visited Internet Link",
        "style:family",
        "text");
    x.empty(
            "style:text-properties",
            "fo:color",
            "#800080",
            "style:text-underline-style",
            "solid",
            "style:text-underline-width",
            "auto",
            "style:text-underline-color",
            "font-color")
        .close();
    for (String name : paragraphCatalog) {
      x.open(
              "style:style",
              "style:name",
              "Catalog_" + styleId(name),
              "style:display-name",
              name,
              "style:family",
              "paragraph",
              "style:parent-style-name",
              "Text_20_body")
          .close();
    }
    for (String name : characterCatalog) {
      x.open(
              "style:style",
              "style:name",
              "CatalogChar_" + styleId(name),
              "style:display-name",
              name,
              "style:family",
              "text")
          .close();
    }
    listStyle(x, "L_bullet", false);
    listStyle(x, "L_number", true);
    x.empty(
        "text:notes-configuration",
        "text:note-class",
        "footnote",
        "style:num-format",
        "1",
        "text:start-value",
        "0",
        "text:footnotes-position",
        "page",
        "text:start-numbering-at",
        "document");
    x.close();
    x.open("office:automatic-styles");
    x.open("style:page-layout", "style:name", "pm1");
    x.empty(
        "style:page-layout-properties",
        page.entrySet().stream()
            .flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue()))
            .toArray(String[]::new));
    x.open("style:header-style")
        .empty(
            "style:header-footer-properties", "fo:min-height", "0cm", "fo:margin-bottom", "0.3cm")
        .close();
    x.open("style:footer-style")
        .empty("style:header-footer-properties", "fo:min-height", "0cm", "fo:margin-top", "0.3cm")
        .close();
    x.close().close();
    x.open("office:master-styles");
    x.open("style:master-page", "style:name", "Standard", "style:page-layout-name", "pm1");
    if (!model.header().isEmpty()) {
      x.open("style:header");
      pageBand(x, "Header", model.header());
      x.close();
    }
    if (!model.footer().isEmpty()) {
      x.open("style:footer");
      pageBand(x, "Footer", model.footer());
      x.close();
    }
    x.close().close();
    x.close();
    return x.bytes();
  }

  private static void pageBand(Xml x, String style, List<DocumentModel.PageBox> boxes) {
    x.open("text:p", "text:style-name", style);
    for (DocumentModel.Align align : DocumentModel.Align.values()) {
      if (align != DocumentModel.Align.LEFT) {
        x.empty("text:tab");
      }
      for (DocumentModel.PageBox box : boxes) {
        if (box.align() != align) {
          continue;
        }
        for (DocumentModel.PagePart part : box.parts()) {
          switch (part) {
            case DocumentModel.Literal literal -> x.text(literal.text());
            case DocumentModel.PageNumber number ->
                x.element("text:page-number", "1", "text:select-page", "current");
            case DocumentModel.PageCount count -> x.element("text:page-count", "1");
          }
        }
      }
    }
    x.close();
  }

  private static void paragraphStyle(
      Xml x, String name, String display, String parent, String marginBottom) {
    x.open(
        "style:style",
        "style:name",
        name,
        "style:display-name",
        display.equals(name) ? null : display,
        "style:family",
        "paragraph",
        "style:parent-style-name",
        parent,
        "style:class",
        "text");
    if (marginBottom != null) {
      x.empty(
          "style:paragraph-properties", "fo:margin-top", "0cm", "fo:margin-bottom", marginBottom);
    }
    x.close();
  }

  private static void listStyle(Xml x, String name, boolean ordered) {
    // Characters every text font has; ◦ and ▪ are missing in many and fall back to another font.
    String[] bullets = {"•", "–", "·"};
    x.open("text:list-style", "style:name", name);
    for (int level = 1; level <= 10; level++) {
      if (ordered) {
        x.open(
            "text:list-level-style-number",
            "text:level",
            String.valueOf(level),
            "style:num-suffix",
            ".",
            "style:num-format",
            "1");
      } else {
        x.open(
            "text:list-level-style-bullet",
            "text:level",
            String.valueOf(level),
            "text:bullet-char",
            bullets[(level - 1) % bullets.length]);
      }
      x.open(
          "style:list-level-properties",
          "text:list-level-position-and-space-mode",
          "label-alignment");
      x.empty(
          "style:list-level-label-alignment",
          "text:label-followed-by",
          "listtab",
          "text:list-tab-stop-position",
          cm(0.635 * (level + 1)),
          "fo:text-indent",
          "-0.635cm",
          "fo:margin-left",
          cm(0.635 * (level + 1)));
      x.close().close();
    }
    x.close();
  }

  private byte[] meta() {
    Xml x = new Xml();
    x.open("office:document-meta", root("office:version", "1.3"));
    x.open("office:meta");
    x.element("meta:generator", DocumentRenderer.PRODUCER);
    x.element("dc:title", model.title());
    if (!model.description().isBlank()) {
      x.element("dc:description", model.description());
    }
    if (!model.lang().isBlank()) {
      x.element("dc:language", model.lang());
    }
    x.close().close();
    return x.bytes();
  }

  private byte[] manifest() {
    String ns = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0";
    Xml x = new Xml();
    x.open("manifest:manifest", "xmlns:manifest", ns, "manifest:version", "1.3");
    x.empty(
        "manifest:file-entry",
        "manifest:full-path",
        "/",
        "manifest:version",
        "1.3",
        "manifest:media-type",
        "application/vnd.oasis.opendocument.text");
    for (String part : List.of("content.xml", "styles.xml", "meta.xml")) {
      x.empty("manifest:file-entry", "manifest:full-path", part, "manifest:media-type", "text/xml");
    }
    for (String font : embeddedFonts.keySet()) {
      x.empty(
          "manifest:file-entry",
          "manifest:full-path",
          font,
          "manifest:media-type",
          "application/x-font-ttf");
    }
    for (String picture : pictures.keySet()) {
      x.empty(
          "manifest:file-entry",
          "manifest:full-path",
          picture,
          "manifest:media-type",
          picture.endsWith(".jpeg")
              ? "image/jpeg"
              : picture.endsWith(".svg") ? "image/svg+xml" : "image/png");
    }
    x.close();
    return x.bytes();
  }
}
