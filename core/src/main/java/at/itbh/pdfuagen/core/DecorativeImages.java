/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * PDF/UA requires decorative images to be artifacts, but openhtmltopdf tags every {@code <img>} as
 * a figure. Backgrounds, however, become artifacts. Before PDF rendering, every decorative image
 * ({@code alt=""} or {@code role="presentation"/"none"}) is therefore replaced by a {@code <span>}
 * showing the image as its background.
 *
 * <p>The span keeps {@code id}, {@code class} and {@code style}. Its intrinsic size and display
 * come from a generated style rule placed before the template's own styles, so class rules of the
 * template (e.g. {@code .divider { width: 100% }}) still apply. Selectors on the element name
 * ({@code img.divider}) no longer match.
 */
final class DecorativeImages {

  static final String MARKER = "data-decorative-image";

  private DecorativeImages() {}

  static void toBackgrounds(Document document, ResourceResolver resolver) {
    List<Element> images = new ArrayList<>();
    NodeList all = document.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      Element element = (Element) all.item(i);
      if (name(element).equals("img") && isDecorative(element)) {
        images.add(element);
      }
    }
    if (images.isEmpty()) {
      return;
    }
    StringBuilder rules = new StringBuilder();
    int number = 0;
    for (Element img : images) {
      String src = img.getAttribute("src").strip();
      String uri = resolver.resolveUri(ResourceResolver.BASE, src);
      int width = 0;
      int height = 0;
      if (uri != null) {
        var resource = resolver.load(uri);
        if (resource.isPresent() && resource.get().kind().mediaType != null) {
          int[] size =
              ImageSize.of(resource.get().bytes(), resource.get().kind().mediaType)
                  .orElse(new int[2]);
          width = size[0];
          height = size[1];
        }
      }
      String key = String.valueOf(++number);
      Element span = document.createElementNS(img.getNamespaceURI(), "span");
      for (String attribute : List.of("id", "class", "style")) {
        if (img.hasAttribute(attribute)) {
          span.setAttribute(attribute, img.getAttribute(attribute));
        }
      }
      span.setAttribute(MARKER, key);
      img.getParentNode().replaceChild(span, img);
      rules
          .append('[')
          .append(MARKER)
          .append("=\"")
          .append(key)
          .append("\"] { display: inline-block; max-width: 100%; width: ")
          .append(width)
          .append("px; height: ")
          .append(height)
          .append("px; background-image: url('")
          .append(src.replace("'", "%27"))
          .append("'); background-size: contain; background-repeat: no-repeat;")
          .append(" background-position: left center; }\n");
    }
    Element head = head(document);
    if (head != null) {
      Element style = document.createElementNS(head.getNamespaceURI(), "style");
      style.setTextContent(rules.toString());
      head.insertBefore(style, head.getFirstChild());
    }
  }

  static boolean isDecorative(Element img) {
    String role = img.getAttribute("role").strip().toLowerCase(Locale.ROOT);
    return role.equals("presentation")
        || role.equals("none")
        || (img.hasAttribute("alt") && img.getAttribute("alt").isBlank());
  }

  private static Element head(Document document) {
    for (Node node = document.getDocumentElement().getFirstChild();
        node != null;
        node = node.getNextSibling()) {
      if (node instanceof Element element && name(element).equals("head")) {
        return element;
      }
    }
    return null;
  }

  private static String name(Element element) {
    String name = element.getLocalName() != null ? element.getLocalName() : element.getTagName();
    return name.toLowerCase(Locale.ROOT);
  }
}
