/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.model;

import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.model.DocumentModel.Align;
import at.itbh.pdfuagen.core.model.DocumentModel.Literal;
import at.itbh.pdfuagen.core.model.DocumentModel.PageBox;
import at.itbh.pdfuagen.core.model.DocumentModel.PageCount;
import at.itbh.pdfuagen.core.model.DocumentModel.PageNumber;
import at.itbh.pdfuagen.core.model.DocumentModel.PagePart;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads running headers and footers from the {@code @page} rule of the template CSS, so the page
 * numbers the PDF shows appear in DOCX and ODT too. Supported: the margin boxes {@code
 * top-left|center|right} and {@code bottom-left|center|right} with a {@code content} of strings,
 * {@code counter(page)} and {@code counter(pages)}. Page selectors ({@code @page :first}) are
 * ignored; anything else in {@code content} is reported.
 */
public final class PageBoxes {

  /** Parsed header and footer boxes. */
  public record Result(List<PageBox> header, List<PageBox> footer, List<Problem> problems) {}

  private static final Pattern MARGIN_BOX =
      Pattern.compile("@(top|bottom)-(left|center|right)\\s*\\{([^{}]*)}");
  private static final Pattern CONTENT = Pattern.compile("(?:^|[;{\\s])content\\s*:\\s*([^;}]*)");
  private static final Pattern TOKEN =
      Pattern.compile(
          "\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)'|counter\\(\\s*(page|pages)\\s*\\)|\\S+)");

  private PageBoxes() {}

  public static Result parse(String css) {
    String text = css.replaceAll("(?s)/\\*.*?\\*/", "");
    List<PageBox> header = new ArrayList<>();
    List<PageBox> footer = new ArrayList<>();
    List<Problem> problems = new ArrayList<>();
    for (String page : pageRules(text)) {
      Matcher box = MARGIN_BOX.matcher(page);
      while (box.find()) {
        Matcher content = CONTENT.matcher(box.group(3));
        if (!content.find()) {
          continue;
        }
        List<PagePart> parts = parts(content.group(1).strip(), box.group(0), problems);
        if (parts.isEmpty()) {
          continue;
        }
        Align align = Align.valueOf(box.group(2).toUpperCase(Locale.ROOT));
        (box.group(1).equals("top") ? header : footer).add(new PageBox(align, parts));
      }
    }
    return new Result(List.copyOf(header), List.copyOf(footer), List.copyOf(problems));
  }

  /** Bodies of plain {@code @page} rules (no page selector). */
  private static List<String> pageRules(String css) {
    List<String> bodies = new ArrayList<>();
    Matcher m = Pattern.compile("@page\\s*\\{").matcher(css);
    while (m.find()) {
      int depth = 1;
      int i = m.end();
      while (i < css.length() && depth > 0) {
        char c = css.charAt(i++);
        if (c == '{') {
          depth++;
        } else if (c == '}') {
          depth--;
        }
      }
      bodies.add(css.substring(m.end(), Math.max(m.end(), i - 1)));
    }
    return bodies;
  }

  private static List<PagePart> parts(String value, String box, List<Problem> problems) {
    List<PagePart> parts = new ArrayList<>();
    if (value.equals("none") || value.equals("normal")) {
      return parts;
    }
    Matcher token = TOKEN.matcher(value);
    while (token.find()) {
      if (token.group(2) != null || token.group(3) != null) {
        String literal = token.group(2) != null ? token.group(2) : token.group(3);
        parts.add(new Literal(literal.replaceAll("\\\\(.)", "$1")));
      } else if ("page".equals(token.group(4))) {
        parts.add(new PageNumber());
      } else if ("pages".equals(token.group(4))) {
        parts.add(new PageCount());
      } else {
        problems.add(
            new Problem(
                Problem.TEMPLATE_ERROR,
                "unsupported page box content \""
                    + token.group(1).strip()
                    + "\"; use strings, counter(page) and counter(pages)",
                box.substring(0, box.indexOf('{')).strip()));
      }
    }
    return parts;
  }
}
