/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The checks a template must pass before it is published, the runtime replacement for Qute's
 * build-time checks:
 *
 * <ol>
 *   <li>every language variant parses; the descriptor is valid; the schema derives without errors
 *       and is the same for every variant
 *   <li>the example data is valid
 *   <li>every variant renders to well-formed XHTML whose {@code lang} matches the variant
 *   <li>every variant renders in every format the template offers; veraPDF confirms the PDF as
 *       PDF/UA-1. Email HTML is skipped when the example uses attachments, which it cannot show.
 * </ol>
 */
public final class TemplateCheck {

  /**
   * @param problems everything that prevents publishing
   * @param warnings findings that do not, e.g. defined fields the template never reads
   */
  public record Report(List<Problem> problems, List<String> warnings) {
    public boolean passed() {
      return problems.isEmpty();
    }
  }

  private TemplateCheck() {}

  /**
   * @param templateId id of the default variant
   * @param example example data, rendered with every variant
   * @param attachments attachments the example data refers to
   */
  public static Report check(
      DocumentRenderer renderer,
      TemplateRepository repository,
      String templateId,
      Map<String, Object> example,
      Map<String, byte[]> attachments) {
    return check(renderer, repository, templateId, example, attachments, null);
  }

  /**
   * @param publicBaseUrl base of asset URLs in email HTML, or {@code null} for the renderer's
   */
  public static Report check(
      DocumentRenderer renderer,
      TemplateRepository repository,
      String templateId,
      Map<String, Object> example,
      Map<String, byte[]> attachments,
      java.net.URI publicBaseUrl) {
    List<Problem> problems = new ArrayList<>();
    try {
      renderer.schema(repository, templateId);
    } catch (RenderException e) {
      return new Report(e.problems(), List.of());
    }
    List<String> warnings = new ArrayList<>(renderer.schemaWarnings(repository, templateId));
    Set<OutputFormat> formats = renderer.formats(repository, templateId);
    if (formats.contains(OutputFormat.EMAIL_HTML) && !attachments.isEmpty()) {
      warnings.add(
          "email-html not checked: the example data uses attachments, which email HTML"
              + " cannot show");
    }
    for (String variantId : renderer.variants(repository, templateId)) {
      RenderRequest request =
          new RenderRequest(variantId, repository, example, attachments, publicBaseUrl);
      try {
        Document document = SecureXml.parse(renderer.renderSource(request), variantId);
        checkLanguage(document, renderer.language(repository, variantId), variantId, problems);
      } catch (RenderException e) {
        problems.addAll(e.problems());
        continue;
      }
      for (OutputFormat format : formats) {
        if (format == OutputFormat.EMAIL_HTML && !attachments.isEmpty()) {
          continue;
        }
        try {
          Rendered rendered = renderer.render(request, format);
          if (format == OutputFormat.PDF) {
            PdfUaValidator.validate(rendered.content())
                .failures()
                .forEach(
                    f ->
                        problems.add(
                            new Problem(Problem.ACCESSIBILITY, "PDF/UA: " + f, variantId)));
          }
        } catch (RenderException e) {
          problems.addAll(e.problems());
        }
      }
    }
    return new Report(problems.stream().distinct().toList(), warnings);
  }

  private static void checkLanguage(
      Document document, Locale language, String variantId, List<Problem> problems) {
    Element html = document.getDocumentElement();
    String lang = html.getAttribute("lang");
    if (lang.isEmpty()) {
      lang = html.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang");
    }
    if (lang.isEmpty()) {
      problems.add(
          new Problem(Problem.ACCESSIBILITY, "the html element has no lang attribute", variantId));
    } else if (!language.getLanguage().isEmpty()
        && !Locale.forLanguageTag(lang).getLanguage().equals(language.getLanguage())) {
      problems.add(
          new Problem(
              Problem.ACCESSIBILITY,
              "the html element declares lang=\""
                  + lang
                  + "\", but the variant is written in "
                  + language.toLanguageTag(),
              variantId));
    }
  }
}
