/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Turns rendered XHTML into a self-contained document: images, stylesheets and the resources they
 * reference become inline data. Elements specific to the PDF renderer ({@code <bookmarks>}) are
 * removed.
 */
final class SelfContainedXhtml {

  private static final Pattern CSS_URL =
      Pattern.compile("url\\(\\s*(?:\"([^\"]*)\"|'([^']*)'|([^)\\s]*))\\s*\\)");
  private static final Pattern CSS_IMPORT = Pattern.compile("@import\\b", Pattern.CASE_INSENSITIVE);

  private final ResourceResolver resolver;
  private final List<Problem> problems = new ArrayList<>();

  SelfContainedXhtml(ResourceResolver resolver) {
    this.resolver = resolver;
  }

  List<Problem> problems() {
    List<Problem> all = new ArrayList<>(resolver.problems());
    all.addAll(problems);
    return all;
  }

  static final String XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml";

  void inline(Document document) {
    removeAll(document, "bookmarks");
    toXhtmlNamespace(document);
    for (Element img : elements(document, "img")) {
      String src = img.getAttribute("src");
      if (!src.isEmpty()) {
        dataUri(ResourceResolver.BASE, src).ifPresent(uri -> img.setAttribute("src", uri));
      }
    }
    // Before the links become <style> elements, whose URLs are already rewritten.
    for (Element style : elements(document, "style")) {
      style.setTextContent(rewriteCss(style.getTextContent(), ResourceResolver.BASE));
    }
    for (Element link : elements(document, "link")) {
      if (!"stylesheet".equalsIgnoreCase(link.getAttribute("rel").strip())) {
        continue;
      }
      String href = resolver.resolveUri(ResourceResolver.BASE, link.getAttribute("href"));
      if (href == null) {
        continue;
      }
      resolver
          .load(href)
          .ifPresent(
              css -> {
                Element style = document.createElementNS(link.getNamespaceURI(), "style");
                style.setTextContent(
                    rewriteCss(new String(css.bytes(), StandardCharsets.UTF_8), href));
                link.getParentNode().replaceChild(style, link);
              });
    }
    NodeList all = document.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      Element element = (Element) all.item(i);
      if (element.hasAttribute("style")) {
        element.setAttribute(
            "style", rewriteCss(element.getAttribute("style"), ResourceResolver.BASE));
      }
    }
  }

  private String rewriteCss(String css, String base) {
    if (CSS_IMPORT.matcher(css).find()) {
      problems.add(new Problem(Problem.RESOURCE_REJECTED, "@import is not supported", base));
    }
    Matcher m = CSS_URL.matcher(css);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String url = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
      String replacement = dataUri(base, url).map(uri -> "url(\"" + uri + "\")").orElse(m.group());
      m.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);
    return out.toString();
  }

  private Optional<String> dataUri(String base, String reference) {
    String resolved = resolver.resolveUri(base, reference);
    if (resolved == null) {
      return Optional.empty();
    }
    String scheme = resolved.substring(0, Math.max(0, resolved.indexOf(':')));
    if (!scheme.equals("template") && !scheme.equals("attachment") && !scheme.equals("https")) {
      problems.add(
          new Problem(
              Problem.RESOURCE_REJECTED,
              "scheme '" + scheme + "' is not allowed for resources",
              reference.length() <= 120 ? reference : reference.substring(0, 117) + "..."));
      return Optional.empty();
    }
    return resolver
        .load(resolved)
        .map(
            r ->
                "data:"
                    + mediaType(r)
                    + ";base64,"
                    + Base64.getEncoder().encodeToString(r.bytes()));
  }

  private static String mediaType(ResourceResolver.Resource resource) {
    if (resource.kind().mediaType != null) {
      return resource.kind().mediaType;
    }
    String uri = resource.uri().toLowerCase(java.util.Locale.ROOT);
    if (uri.endsWith(".ttf")) {
      return "font/ttf";
    }
    if (uri.endsWith(".otf")) {
      return "font/otf";
    }
    if (uri.endsWith(".woff2")) {
      return "font/woff2";
    }
    if (uri.endsWith(".woff")) {
      return "font/woff";
    }
    if (uri.endsWith(".css")) {
      return "text/css";
    }
    return "application/octet-stream";
  }

  private static List<Element> elements(Document document, String localName) {
    List<Element> result = new ArrayList<>();
    NodeList all = document.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      Element element = (Element) all.item(i);
      String name = element.getLocalName() != null ? element.getLocalName() : element.getTagName();
      if (localName.equals("*") || name.equalsIgnoreCase(localName)) {
        result.add(element);
      }
    }
    return result;
  }

  /**
   * Puts elements without a namespace into the XHTML namespace; otherwise a browser reading the
   * output as {@code application/xhtml+xml} treats it as plain XML.
   */
  private static void toXhtmlNamespace(Document document) {
    for (Element element : elements(document, "*")) {
      if (element.getNamespaceURI() == null) {
        String name =
            element.getLocalName() != null ? element.getLocalName() : element.getTagName();
        document.renameNode(element, XHTML_NAMESPACE, name);
      }
    }
  }

  private static void removeAll(Document document, String localName) {
    for (Element element : elements(document, localName)) {
      element.getParentNode().removeChild(element);
    }
  }
}
