/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server;

import at.itbh.pdfuagen.core.JsonData;
import at.itbh.pdfuagen.core.LanguageVariants;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import at.itbh.pdfuagen.core.TemplateCheck;
import at.itbh.pdfuagen.core.TemplateRepository;
import at.itbh.pdfuagen.core.schema.LayoutDescriptor;
import at.itbh.pdfuagen.core.schema.TemplateDescriptor;
import at.itbh.pdfuagen.server.render.RenderService;
import at.itbh.pdfuagen.server.store.Bundle;
import at.itbh.pdfuagen.server.store.TemplateStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The write logic behind both the JSON API and the editor UI, so it lives in one place. A bundle is
 * validated and released for use on upload (no separate publish step); a released revision is
 * retired by archiving it, or a whole template is deleted while nothing references it. Methods run
 * on the calling thread; callers put them on the render pool where they must not block a request
 * thread. Results are values, not HTTP or HTML, so each caller renders them its own way.
 */
@ApplicationScoped
public class TemplateActions {

  @Inject TemplateStore store;
  @Inject RenderService service;
  @Inject ServerConfig config;

  /** The outcome of uploading a bundle. */
  public sealed interface CreateResult {}

  /** Stored and released for use. */
  public record Created(TemplateStore.Stored stored) implements CreateResult {}

  /** The archive itself cannot be read (not a ZIP, no {@code template.xhtml}, too big, …). */
  public record InvalidBundle(String detail) implements CreateResult {}

  /** The bundle is of another kind than the template's earlier revisions. */
  public record KindMismatch(String detail) implements CreateResult {}

  /** The bundle read, but the publish checks rejected it; nothing was stored. */
  public record Rejected(List<Problem> problems) implements CreateResult {}

  /**
   * Stores a bundle, validates it and releases it for use in one step. A rejected bundle leaves
   * nothing behind. Uploading the same files again yields the existing revision (and re-releases it
   * if it was archived).
   *
   * @param publicBase base of asset URLs for the email-HTML publish check
   */
  public CreateResult create(String id, InputStream zip, URI publicBase)
      throws java.io.IOException {
    Map<String, byte[]> files;
    try {
      files = Bundle.read(zip, config.bundle().maxSize(), config.bundle().maxFiles());
    } catch (Bundle.InvalidBundleException e) {
      return new InvalidBundle(e.getMessage());
    }
    String kind =
        files.containsKey(LayoutDescriptor.FILE) ? TemplateStore.LAYOUT : TemplateStore.CONTENT;
    List<TemplateStore.LayoutPin> layouts = List.of();
    byte[] descriptor = files.get(LanguageVariants.descriptorPath(Bundle.TEMPLATE));
    if (kind.equals(TemplateStore.CONTENT) && descriptor != null) {
      try {
        layouts =
            TemplateDescriptor.parse(descriptor, Bundle.TEMPLATE).layouts().stream()
                .map(l -> new TemplateStore.LayoutPin(l.id(), l.revision()))
                .toList();
      } catch (RenderException e) {
        // A malformed descriptor is reported by the publish check below.
      }
    }
    TemplateStore.Stored stored;
    try {
      stored = store.createRevision(id, files, kind, layouts);
    } catch (TemplateStore.KindMismatchException e) {
      return new KindMismatch(e.getMessage());
    }
    // The same files were already stored and released: nothing to validate.
    if (stored.revision().published()) {
      return new Created(stored);
    }
    return switch (release(stored.revision(), publicBase)) {
      case Published p -> new Created(new TemplateStore.Stored(p.revision(), stored.created()));
      case Rejected2 r -> {
        // A rejected upload is not kept; a fresh draft is removed, an existing archived one stays.
        if (stored.created()) {
          store.deleteDraft(id, stored.revision().number());
          // Its first revision created the template: leave nothing behind.
          if (store.revisions(id).isEmpty()) {
            store.deleteTemplate(id);
          }
        }
        yield new Rejected(r.problems());
      }
      // release() is called only on a draft or archived revision that exists, so these cannot
      // occur.
      case Conflict c -> new Rejected(List.of(problem(c.detail())));
      case NotFound nf -> new Rejected(List.of(problem(nf.detail())));
    };
  }

  /** The outcome of a release (the validation run on upload). */
  private sealed interface ReleaseResult {}

  private record Published(TemplateStore.Revision revision) implements ReleaseResult {}

  private record Rejected2(List<Problem> problems) implements ReleaseResult {}

  private record Conflict(String detail) implements ReleaseResult {}

  private record NotFound(String detail) implements ReleaseResult {}

  /**
   * Runs the publish checks and, if they pass, releases the revision. A layout is checked with
   * empty data ({@code {}}) — it carries no example data of its own; a content template is checked
   * with its {@code example.json}, once with every layout it lists.
   */
  private ReleaseResult release(TemplateStore.Revision draft, URI publicBase) {
    Optional<TemplateStore.Revision> found = store.revision(draft.templateId(), draft.number());
    if (found.isEmpty()) {
      return new NotFound(
          "template '" + draft.templateId() + "' has no revision " + draft.number());
    }
    TemplateStore.Revision revision = found.get();
    if (revision.published()) {
      return new Conflict("revision " + revision.number() + " is already published");
    }
    boolean isLayout = revision.files().containsKey(LayoutDescriptor.FILE);
    // Every listed layout must exist and be released.
    List<TemplateStore.Revision> layouts = new ArrayList<>();
    List<Problem> unavailable = new ArrayList<>();
    for (int i = 0; i < revision.layouts().size(); i++) {
      TemplateStore.LayoutPin pin = revision.layouts().get(i);
      Optional<TemplateStore.Revision> layout = layout(pin);
      String problem =
          layout.isEmpty()
              ? "the layout " + pin + " does not exist"
              : layout.get().published() ? null : "the layout " + pin + " is not published";
      if (problem == null) {
        layouts.add(layout.get());
      } else {
        unavailable.add(
            new Problem(
                Problem.TEMPLATE_ERROR,
                problem + "; upload it first, or list a released one",
                "template.json#/layouts/" + i));
      }
    }
    if (!unavailable.isEmpty()) {
      return new Rejected2(unavailable);
    }
    TemplateRepository own = service.repository(revision, null);
    Map<String, Object> data;
    Map<String, byte[]> attachments;
    if (isLayout) {
      // A layout is only styles; it renders on its own with no data and no attachments.
      data = Map.of();
      attachments = Map.of();
    } else {
      Optional<byte[]> example = own.resource(Bundle.EXAMPLE);
      if (example.isEmpty()) {
        return new Rejected2(
            List.of(
                new Problem(
                    Problem.TEMPLATE_ERROR,
                    "the bundle has no example data; add " + Bundle.EXAMPLE,
                    Bundle.EXAMPLE)));
      }
      try {
        data = JsonData.parse(new ByteArrayInputStream(example.get()));
      } catch (RenderException e) {
        return new Rejected2(e.problems());
      }
      attachments =
          Bundle.exampleAttachments(revision.files().keySet(), p -> own.resource(p).orElseThrow());
    }
    // A template without layouts is checked on its own; one with layouts with each of them.
    List<TemplateStore.Revision> checked =
        layouts.isEmpty() ? java.util.Arrays.asList((TemplateStore.Revision) null) : layouts;
    List<Problem> problems = new ArrayList<>();
    for (TemplateStore.Revision layout : checked) {
      TemplateCheck.Report report =
          TemplateCheck.check(
              service.renderer(),
              service.repository(revision, layout),
              Bundle.TEMPLATE,
              data,
              attachments,
              publicBase);
      for (Problem p : report.problems()) {
        problems.add(
            checked.size() == 1
                ? p
                : new Problem(
                    p.type(),
                    "with layout "
                        + layout.templateId()
                        + "@"
                        + layout.number()
                        + ": "
                        + p.detail(),
                    p.location()));
      }
    }
    if (!problems.isEmpty()) {
      return new Rejected2(problems.stream().distinct().toList());
    }
    if (!store.release(revision.templateId(), revision.number())) {
      return new Conflict("revision " + revision.number() + " is already published");
    }
    return new Published(store.revision(revision.templateId(), revision.number()).orElseThrow());
  }

  /** The outcome of archiving a revision. */
  public sealed interface ArchiveResult {}

  public record Archived(TemplateStore.Revision revision) implements ArchiveResult {}

  /** A released content revision still pins this layout revision. */
  public record CannotArchive(String detail) implements ArchiveResult {}

  public record NotArchived(String detail) implements ArchiveResult {}

  /** Retires a released revision. A layout revision cannot be archived while content pins it. */
  public ArchiveResult archive(String id, int number) {
    Optional<TemplateStore.Revision> found = store.revision(id, number);
    if (found.isEmpty() || !found.get().published()) {
      return new NotArchived("template '" + id + "' has no released revision " + number);
    }
    List<String> users =
        store.dependents(id).stream()
            .filter(d -> d.layoutRevision() == number && TemplateStore.PUBLISHED.equals(d.status()))
            .map(d -> d.templateId() + "@" + d.number())
            .toList();
    if (!users.isEmpty()) {
      return new CannotArchive(
          "revision " + number + " is still used by " + String.join(", ", users));
    }
    store.archive(id, number);
    return new Archived(store.revision(id, number).orElseThrow());
  }

  /** The outcome of deleting a whole template. */
  public sealed interface DeleteResult {}

  public record Deleted() implements DeleteResult {}

  public record CannotDelete(String detail) implements DeleteResult {}

  public record Missing(String detail) implements DeleteResult {}

  /**
   * Deletes a template with all its revisions. A layout cannot be deleted while content pins it.
   */
  public DeleteResult deleteTemplate(String id) {
    if (store.find(id).isEmpty()) {
      return new Missing("no template '" + id + "'");
    }
    List<String> users =
        store.dependents(id).stream()
            .map(d -> d.templateId() + "@" + d.number())
            .distinct()
            .toList();
    if (!users.isEmpty()) {
      return new CannotDelete(
          "the layout is still referenced by " + String.join(", ", users) + "; delete those first");
    }
    return store.deleteTemplate(id) ? new Deleted() : new Missing("no template '" + id + "'");
  }

  private Optional<TemplateStore.Revision> layout(TemplateStore.LayoutPin pin) {
    return store
        .revision(pin.id(), pin.revision())
        .filter(l -> l.files().containsKey(LayoutDescriptor.FILE));
  }

  private static Problem problem(String detail) {
    return new Problem(Problem.TEMPLATE_ERROR, detail, null);
  }
}
