/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.writer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Thin StAX wrapper for the office formats. Element and attribute names are written as qualified
 * names ({@code w:p}); namespaces are declared explicitly on the root element.
 */
final class Xml {

  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final XMLStreamWriter writer;

  /** A complete document with XML declaration. */
  Xml() {
    this(true);
  }

  /** A document, or a fragment (no XML declaration) to be inserted with {@link #raw}. */
  Xml(boolean document) {
    try {
      writer = XMLOutputFactory.newFactory().createXMLStreamWriter(bytes, "UTF-8");
      if (document) {
        writer.writeStartDocument("UTF-8", "1.0");
      }
    } catch (XMLStreamException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Writes a document type declaration, e.g. {@code <!DOCTYPE html>}, before the root element. */
  Xml doctype(String dtd) {
    try {
      writer.writeDTD(dtd);
      return this;
    } catch (XMLStreamException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Inserts a balanced fragment written by another {@code Xml} as the next content. */
  Xml raw(byte[] fragment) {
    try {
      writer.writeCharacters(""); // closes a pending start tag
      writer.flush();
      bytes.writeBytes(fragment);
      return this;
    } catch (XMLStreamException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Opens an element; {@code attributes} are name/value pairs, {@code null} values are skipped. */
  Xml open(String name, String... attributes) {
    try {
      writer.writeStartElement(name);
      attributes(attributes);
      return this;
    } catch (XMLStreamException e) {
      throw new IllegalStateException(e);
    }
  }

  Xml empty(String name, String... attributes) {
    try {
      writer.writeEmptyElement(name);
      attributes(attributes);
      return this;
    } catch (XMLStreamException e) {
      throw new IllegalStateException(e);
    }
  }

  Xml text(String text) {
    try {
      writer.writeCharacters(text);
      return this;
    } catch (XMLStreamException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Element with text content. */
  Xml element(String name, String text, String... attributes) {
    return open(name, attributes).text(text).close();
  }

  Xml close() {
    try {
      writer.writeEndElement();
      return this;
    } catch (XMLStreamException e) {
      throw new IllegalStateException(e);
    }
  }

  byte[] bytes() {
    try {
      writer.writeEndDocument();
      writer.flush();
      return bytes.toByteArray();
    } catch (XMLStreamException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public String toString() {
    return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
  }

  private void attributes(String... attributes) throws XMLStreamException {
    for (int i = 0; i + 1 < attributes.length; i += 2) {
      if (attributes[i + 1] != null) {
        writer.writeAttribute(attributes[i], attributes[i + 1]);
      }
    }
  }
}
