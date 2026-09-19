/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.writer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The simple CSS the style catalog uses, for inlining into email HTML: rules with element ({@code
 * p}), class ({@code .lead}) and element-with-class ({@code p.lead}) selectors. At-rules and other
 * selectors are ignored.
 */
public final class CssRules {

  private record Rule(String element, String cssClass, String declarations) {
    int specificity() {
      return (element == null ? 0 : 1) + (cssClass == null ? 0 : 10);
    }
  }

  private static final Pattern SIMPLE_SELECTOR =
      Pattern.compile("([a-zA-Z][a-zA-Z0-9]*)?(?:\\.([\\w-]+))?");

  private final List<Rule> rules;

  private CssRules(List<Rule> rules) {
    this.rules = rules;
  }

  public static CssRules parse(String css) {
    String text = css.replaceAll("(?s)/\\*.*?\\*/", "");
    List<Rule> rules = new ArrayList<>();
    int i = 0;
    while (i < text.length()) {
      int open = text.indexOf('{', i);
      if (open < 0) {
        break;
      }
      String prelude = text.substring(i, open).strip();
      int close = matchingBrace(text, open);
      String block = text.substring(open + 1, Math.max(open + 1, close));
      i = close < 0 ? text.length() : close + 1;
      if (prelude.startsWith("@")) {
        continue;
      }
      String declarations = block.strip().replaceAll("\\s+", " ");
      if (!declarations.isEmpty() && !declarations.endsWith(";")) {
        declarations += ";";
      }
      for (String selector : prelude.split(",")) {
        Matcher m = SIMPLE_SELECTOR.matcher(selector.strip());
        if (m.matches() && (m.group(1) != null || m.group(2) != null)) {
          String element = m.group(1) == null ? null : m.group(1).toLowerCase(Locale.ROOT);
          rules.add(new Rule(element, m.group(2), declarations));
        }
      }
    }
    return new CssRules(rules);
  }

  /** Declarations for an element with the given classes, in cascade order (specificity, source). */
  public String style(String element, String... classes) {
    StringBuilder out = new StringBuilder();
    rules.stream()
        .filter(r -> r.element() == null || r.element().equals(element))
        .filter(r -> r.cssClass() == null || contains(classes, r.cssClass()))
        .sorted((a, b) -> Integer.compare(a.specificity(), b.specificity()))
        .forEach(r -> out.append(out.isEmpty() ? "" : " ").append(r.declarations()));
    return out.toString();
  }

  private static boolean contains(String[] classes, String cssClass) {
    for (String c : classes) {
      if (cssClass.equals(c)) {
        return true;
      }
    }
    return false;
  }

  private static int matchingBrace(String text, int open) {
    int depth = 0;
    for (int i = open; i < text.length(); i++) {
      if (text.charAt(i) == '{') {
        depth++;
      } else if (text.charAt(i) == '}' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }
}
