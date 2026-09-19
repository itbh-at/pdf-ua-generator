/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.api;

import at.itbh.pdfuagen.core.OutputFormat;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import at.itbh.pdfuagen.core.TemplateRepository;
import at.itbh.pdfuagen.server.render.RenderService;
import at.itbh.pdfuagen.server.store.Bundle;
import at.itbh.pdfuagen.server.store.TemplateStore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON representations of the API. */
final class Views {

  private Views() {}

  /** A stored revision with its files ready to render. */
  record Target(TemplateStore.Revision revision, TemplateRepository repository) {}

  record TemplateView(
      String id,
      OffsetDateTime createdAt,
      Integer publishedRevision,
      int latestRevision,
      List<RevisionSummary> revisions) {}

  record RevisionSummary(
      int revision, String status, OffsetDateTime createdAt, OffsetDateTime publishedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ProblemView(String type, String detail, String pointer, String location) {
    static ProblemView of(Problem p) {
      boolean pointer = p.location() != null && p.location().startsWith("#");
      return new ProblemView(
          Problems.URN + p.type(),
          p.detail(),
          pointer ? p.location() : null,
          pointer ? null : p.location());
    }
  }

  /**
   * A revision with the result of the checks that run when it is saved.
   *
   * @param problems template errors that prevent rendering; empty for a usable revision
   * @param warnings findings that do not
   */
  record RevisionView(
      String template,
      int revision,
      String status,
      String sha256,
      OffsetDateTime createdAt,
      OffsetDateTime publishedAt,
      String language,
      List<String> languages,
      List<String> formats,
      Map<String, String> files,
      List<ProblemView> problems,
      List<String> warnings) {}

  static TemplateView template(TemplateStore.Template t, List<TemplateStore.Revision> revisions) {
    return new TemplateView(
        t.id(),
        t.createdAt(),
        t.latestPublished(),
        t.latest(),
        revisions.stream()
            .map(r -> new RevisionSummary(r.number(), r.status(), r.createdAt(), r.publishedAt()))
            .toList());
  }

  static RevisionView revision(Target target, RenderService service) {
    var renderer = service.renderer();
    var repository = target.repository();
    var revision = target.revision();
    List<ProblemView> problems = new ArrayList<>();
    try {
      renderer.schema(repository, Bundle.TEMPLATE);
    } catch (RenderException e) {
      e.problems().stream().map(ProblemView::of).forEach(problems::add);
    }
    var language = renderer.language(repository, Bundle.TEMPLATE);
    return new RevisionView(
        revision.templateId(),
        revision.number(),
        revision.status(),
        revision.sha256(),
        revision.createdAt(),
        revision.publishedAt(),
        language.toLanguageTag().equals("und") ? null : language.toLanguageTag(),
        List.copyOf(repository.languages(Bundle.TEMPLATE)),
        renderer.formats(repository, Bundle.TEMPLATE).stream().map(OutputFormat::id).toList(),
        new LinkedHashMap<>(revision.files()),
        problems,
        renderer.schemaWarnings(repository, Bundle.TEMPLATE));
  }
}
