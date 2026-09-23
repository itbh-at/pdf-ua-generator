/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server;

import at.itbh.pdfuagen.core.JsonData;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import at.itbh.pdfuagen.core.TemplateCheck;
import at.itbh.pdfuagen.core.TemplateRepository;
import at.itbh.pdfuagen.core.schema.LayoutDescriptor;
import at.itbh.pdfuagen.server.render.RenderService;
import at.itbh.pdfuagen.server.store.Bundle;
import at.itbh.pdfuagen.server.store.TemplateStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Publishing and deleting a revision — the write logic behind both the JSON API and the editor UI,
 * so it lives in one place. Methods run on the calling thread; callers put them on the render pool
 * where they must not block a request thread. Results are values, not HTTP or HTML, so each caller
 * renders them its own way.
 */
@ApplicationScoped
public class TemplateActions {

  @Inject TemplateStore store;
  @Inject RenderService service;

  /** The outcome of a publish attempt. */
  public sealed interface PublishResult {}

  public record Published(TemplateStore.Revision revision) implements PublishResult {}

  /** The publish checks (or the pinned layout) rejected the revision. */
  public record Rejected(List<Problem> problems) implements PublishResult {}

  /** Already published, or removed meanwhile. */
  public record Conflict(String detail) implements PublishResult {}

  public record NotFound(String detail) implements PublishResult {}

  /** The outcome of a delete attempt. */
  public sealed interface DeleteResult {}

  public record Deleted() implements DeleteResult {}

  public record CannotDelete(String detail) implements DeleteResult {}

  public record Missing(String detail) implements DeleteResult {}

  /** Runs the publish checks with the revision's example data and publishes it if they pass. */
  public PublishResult publish(String id, int number, URI publicBase) {
    Optional<TemplateStore.Revision> found = store.revision(id, number);
    if (found.isEmpty()) {
      return new NotFound("template '" + id + "' has no revision " + number);
    }
    TemplateStore.Revision revision = found.get();
    if (revision.published()) {
      return new Conflict("revision " + number + " is already published");
    }
    TemplateStore.Revision layout = layout(revision).orElse(null);
    if (revision.layoutId() != null) {
      String pinned = revision.layoutId() + "@" + revision.layoutRevision();
      String problem =
          layout == null
              ? "the layout " + pinned + " does not exist"
              : layout.published() ? null : "the layout " + pinned + " is not published";
      if (problem != null) {
        return new Rejected(
            List.of(
                new Problem(
                    Problem.TEMPLATE_ERROR,
                    problem + "; publish it first, or pin a published one",
                    "template.json#/layout")));
      }
    }
    TemplateRepository repository = service.repository(revision, layout);
    Optional<byte[]> example = repository.resource(Bundle.EXAMPLE);
    if (example.isEmpty()) {
      return new Rejected(
          List.of(
              new Problem(
                  Problem.TEMPLATE_ERROR,
                  "the revision has no example data; add " + Bundle.EXAMPLE + " to the bundle",
                  Bundle.EXAMPLE)));
    }
    Map<String, Object> data;
    try {
      data = JsonData.parse(new ByteArrayInputStream(example.get()));
    } catch (RenderException e) {
      return new Rejected(e.problems());
    }
    Map<String, byte[]> attachments =
        Bundle.exampleAttachments(
            revision.files().keySet(), p -> repository.resource(p).orElseThrow());
    TemplateCheck.Report report =
        TemplateCheck.check(
            service.renderer(), repository, Bundle.TEMPLATE, data, attachments, publicBase);
    if (!report.passed()) {
      return new Rejected(report.problems());
    }
    if (!store.publish(id, number)) {
      return new Conflict("revision " + number + " is already published");
    }
    return new Published(store.revision(id, number).orElseThrow());
  }

  /** Deletes a draft revision, unless it is published or a published content pins it. */
  public DeleteResult deleteDraft(String id, int number) {
    Optional<TemplateStore.Revision> found = store.revision(id, number);
    if (found.isEmpty()) {
      return new Missing("template '" + id + "' has no revision " + number);
    }
    List<String> users =
        store.dependents(id).stream()
            .filter(d -> d.layoutRevision() == number)
            .map(d -> d.templateId() + "@" + d.number())
            .toList();
    if (!users.isEmpty()) {
      return new CannotDelete("revision " + number + " is used by " + String.join(", ", users));
    }
    if (found.get().published() || !store.deleteDraft(id, number)) {
      return new CannotDelete("revision " + number + " is published; published revisions are kept");
    }
    return new Deleted();
  }

  private Optional<TemplateStore.Revision> layout(TemplateStore.Revision revision) {
    if (revision.layoutId() == null) {
      return Optional.empty();
    }
    return store
        .revision(revision.layoutId(), revision.layoutRevision())
        .filter(l -> l.files().containsKey(LayoutDescriptor.FILE));
  }
}
