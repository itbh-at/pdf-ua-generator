/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
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
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

/** XML parsing and serialization without DTD loading or external entities. */
final class SecureXml {

  private SecureXml() {}

  /**
   * Parses rendered XHTML. A DOCTYPE is accepted but never loaded; named entities other than the
   * five XML ones are therefore not available.
   */
  static Document parse(String xhtml, String templateId) throws RenderException {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(null);
      builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
      return builder.parse(new InputSource(new StringReader(xhtml)));
    } catch (SAXParseException e) {
      throw new RenderException(
          new Problem(
              Problem.TEMPLATE_ERROR,
              "Rendered output is not well-formed XHTML: " + e.getMessage(),
              templateId
                  + " (rendered output, line "
                  + e.getLineNumber()
                  + ", column "
                  + e.getColumnNumber()
                  + ")"),
          e);
    } catch (ParserConfigurationException | IOException | org.xml.sax.SAXException e) {
      throw new RenderException(
          new Problem(
              Problem.TEMPLATE_ERROR, "Cannot parse rendered XHTML: " + e.getMessage(), templateId),
          e);
    }
  }

  /** Serializes a document as XHTML with an HTML5 doctype and no XML declaration. */
  static String serialize(Document document) {
    try {
      TransformerFactory factory = TransformerFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      Transformer transformer = factory.newTransformer();
      transformer.setOutputProperty(OutputKeys.METHOD, "xml");
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      StringWriter out = new StringWriter();
      out.write("<!DOCTYPE html>\n");
      transformer.transform(new DOMSource(document.getDocumentElement()), new StreamResult(out));
      return out.toString();
    } catch (TransformerException e) {
      throw new IllegalStateException("Cannot serialize XHTML", e);
    }
  }
}
