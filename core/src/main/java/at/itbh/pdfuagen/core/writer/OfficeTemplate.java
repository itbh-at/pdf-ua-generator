/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.writer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * What a layout gives the DOCX and ODT writers: its Word template ({@code layout.dotx}) and ODF
 * template ({@code layout.ott}) as the base of styles, default font and page setup, and its font
 * files to embed.
 *
 * @param dotx the Word template, or {@code null}
 * @param ott the ODF template, or {@code null}
 * @param fonts font files by family name, from the layout's {@code @font-face} rules
 */
public record OfficeTemplate(byte[] dotx, byte[] ott, Map<String, byte[]> fonts) {

  /** No layout: the writers' own styles. */
  public static final OfficeTemplate NONE = new OfficeTemplate(null, null, Map.of());

  public OfficeTemplate {
    fonts = Map.copyOf(fonts);
  }

  /** A part of a ZIP package, e.g. {@code word/styles.xml}. */
  static Optional<byte[]> part(byte[] zip, String name) throws IOException {
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
        if (entry.getName().equals(name)) {
          return Optional.of(in.readNBytes(64 * 1024 * 1024));
        }
      }
    }
    return Optional.empty();
  }

  /** Parses XML namespace-aware, without DTDs or external entities. */
  static Document parse(byte[] xml) throws IOException {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(null);
      return builder.parse(new InputSource(new ByteArrayInputStream(xml)));
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException("invalid XML in the office template: " + e.getMessage(), e);
    }
  }

  static byte[] serialize(Document document) throws IOException {
    try {
      TransformerFactory factory = TransformerFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      Transformer transformer = factory.newTransformer();
      transformer.setOutputProperty(OutputKeys.METHOD, "xml");
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      transformer.transform(new DOMSource(document), new StreamResult(out));
      return out.toByteArray();
    } catch (TransformerException e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  /** The child elements of a node with the given namespace and local name. */
  static List<Element> children(Node parent, String namespace, String localName) {
    List<Element> result = new ArrayList<>();
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      if (nodes.item(i) instanceof Element e
          && namespace.equals(e.getNamespaceURI())
          && localName.equals(e.getLocalName())) {
        result.add(e);
      }
    }
    return result;
  }

  static Optional<Element> child(Node parent, String namespace, String localName) {
    return children(parent, namespace, localName).stream().findFirst();
  }

  /** All descendant elements with the given namespace and local name. */
  static List<Element> descendants(Node parent, String namespace, String localName) {
    List<Element> result = new ArrayList<>();
    NodeList nodes =
        parent instanceof Document d
            ? d.getElementsByTagNameNS(namespace, localName)
            : ((Element) parent).getElementsByTagNameNS(namespace, localName);
    for (int i = 0; i < nodes.getLength(); i++) {
      result.add((Element) nodes.item(i));
    }
    return result;
  }

  /** A length in CSS/ODF units ({@code 2cm}, {@code 20mm}, {@code 1in}, {@code 72pt}) in cm. */
  static double centimetres(String length) {
    String value = length.strip();
    int unit = 0;
    while (unit < value.length()
        && (Character.isDigit(value.charAt(unit))
            || value.charAt(unit) == '.'
            || value.charAt(unit) == '-')) {
      unit++;
    }
    double number = Double.parseDouble(value.substring(0, unit));
    return switch (value.substring(unit)) {
      case "mm" -> number / 10;
      case "in" -> number * 2.54;
      case "pt" -> number * 2.54 / 72;
      case "pc" -> number * 2.54 / 6;
      default -> number;
    };
  }

  /**
   * Obfuscates a font for embedding in DOCX (ECMA-376 Part 1, 17.8.1): the first 32 bytes are XORed
   * with the font key, a GUID, read as bytes from the end of its hexadecimal form.
   */
  static byte[] obfuscate(byte[] font, String fontKey) {
    String hex = fontKey.replaceAll("[{}-]", "");
    byte[] key = new byte[16];
    for (int i = 0; i < 16; i++) {
      key[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    byte[] result = font.clone();
    for (int i = 0; i < Math.min(32, result.length); i++) {
      result[i] ^= key[15 - (i % 16)];
    }
    return result;
  }

  /** A font key derived from the font itself, so equal input gives equal output. */
  static String fontKey(byte[] font) {
    try {
      byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(font);
      String hex = java.util.HexFormat.of().withUpperCase().formatHex(hash, 0, 16);
      return "{"
          + hex.substring(0, 8)
          + "-"
          + hex.substring(8, 12)
          + "-"
          + hex.substring(12, 16)
          + "-"
          + hex.substring(16, 20)
          + "-"
          + hex.substring(20, 32)
          + "}";
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
