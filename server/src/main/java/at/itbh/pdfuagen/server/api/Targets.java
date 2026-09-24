/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.api;

import at.itbh.pdfuagen.core.schema.LayoutDescriptor;
import at.itbh.pdfuagen.server.render.RenderService;
import at.itbh.pdfuagen.server.store.Bundle;
import at.itbh.pdfuagen.server.store.TemplateStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Looks up templates and revisions for the resources; unknown ones are answered with 404. */
@ApplicationScoped
class Targets {

  @Inject TemplateStore store;
  @Inject RenderService service;
  @Inject at.itbh.pdfuagen.server.TemplateActions actions;

  TemplateStore.Template template(String id) {
    return store
        .find(checkId(id))
        .orElseThrow(() -> Problems.notFound("template '" + id + "' does not exist"));
  }

  /** A revision ready to render with its default layout. */
  Views.Target revision(String id, int n) {
    return revision(id, n, null);
  }

  /**
   * A revision ready to render; content is composed with one of the layout revisions it lists — the
   * requested one, or the default. If that does not exist, the content stands alone and its checks
   * report the missing layout.
   *
   * @param layout a listed layout, as {@code memo} or {@code memo@1}; {@code null} for the default
   */
  Views.Target revision(String id, int n, String layout) {
    TemplateStore.Revision revision =
        store
            .revision(checkId(id), n)
            .orElseThrow(() -> Problems.notFound("template '" + id + "' has no revision " + n));
    TemplateStore.Revision composed = layout(pin(id, revision.layouts(), layout));
    return new Views.Target(revision, composed, service.repository(revision, composed));
  }

  /**
   * Unsaved files based on a revision, ready to render with one of the layouts their descriptor
   * lists — the requested one, or the default.
   */
  Views.Target draft(
      TemplateStore.Revision base, java.util.Map<String, byte[]> files, String layout) {
    java.util.List<TemplateStore.LayoutPin> pins = java.util.List.of();
    byte[] descriptor =
        files.get(at.itbh.pdfuagen.core.LanguageVariants.descriptorPath(Bundle.TEMPLATE));
    if (!files.containsKey(LayoutDescriptor.FILE) && descriptor != null) {
      try {
        pins =
            at.itbh.pdfuagen.core.schema.TemplateDescriptor.parse(descriptor, Bundle.TEMPLATE)
                .layouts()
                .stream()
                .map(l -> new TemplateStore.LayoutPin(l.id(), l.revision()))
                .toList();
      } catch (at.itbh.pdfuagen.core.RenderException e) {
        throw Problems.from(e.problems());
      }
    }
    TemplateStore.Revision composed = layout(pin(base.templateId(), pins, layout));
    return new Views.Target(base, composed, service.draft(files, composed));
  }

  private TemplateStore.Revision layout(TemplateStore.LayoutPin pin) {
    return pin == null
        ? null
        : store
            .revision(pin.id(), pin.revision())
            .filter(l -> l.files().containsKey(LayoutDescriptor.FILE))
            .orElse(null);
  }

  /** The requested layout among those listed, or the default (the first). */
  private static TemplateStore.LayoutPin pin(
      String id, java.util.List<TemplateStore.LayoutPin> listed, String requested) {
    if (requested == null || requested.isBlank()) {
      return listed.isEmpty() ? null : listed.getFirst();
    }
    for (TemplateStore.LayoutPin pin : listed) {
      if (pin.id().equals(requested) || pin.toString().equals(requested)) {
        return pin;
      }
    }
    throw Problems.invalidRequest(
        listed.isEmpty()
            ? "template '" + id + "' is not rendered with a layout"
            : "template '"
                + id
                + "' does not list the layout '"
                + requested
                + "'; it lists "
                + String.join(", ", listed.stream().map(Object::toString).toList()));
  }

  /** The latest published revision with its default layout. */
  Views.Target published(String id) {
    return published(id, null);
  }

  /** The latest published revision with one of the layouts it lists. */
  Views.Target published(String id, String layout) {
    Integer n = template(id).latestPublished();
    if (n == null) {
      throw Problems.notFound("template '" + id + "' has no published revision");
    }
    return revision(id, n, layout);
  }

  /** The files of a draft; an unknown base revision is 404, a bad path 400. */
  at.itbh.pdfuagen.server.TemplateActions.DraftFiles draftFiles(
      String id, int base, Views.DraftRequest draft) {
    return draftFiles(id, base, draft, java.util.Map.of());
  }

  /** The files of a draft with uploaded binary files, by path. */
  at.itbh.pdfuagen.server.TemplateActions.DraftFiles draftFiles(
      String id, int base, Views.DraftRequest draft, java.util.Map<String, byte[]> uploads) {
    return switch (actions.draft(
        id,
        base,
        draft == null ? null : draft.files(),
        draft == null ? null : draft.delete(),
        uploads)) {
      case at.itbh.pdfuagen.server.TemplateActions.DraftFiles f -> f;
      case at.itbh.pdfuagen.server.TemplateActions.NoSuchRevision m ->
          throw Problems.notFound(m.detail());
      case at.itbh.pdfuagen.server.TemplateActions.InvalidDraft b ->
          throw Problems.invalidRequest(b.detail());
    };
  }

  /**
   * A multipart draft: the part {@code draft} is the JSON of a {@link Views.DraftRequest}, every
   * other part a file written over the base under the part's name, its path.
   */
  static Draft multipart(
      String draft, java.util.List<org.jboss.resteasy.reactive.multipart.FileUpload> parts) {
    Views.DraftRequest request = null;
    if (draft == null) {
      // Sent as a file part (e.g. a JSON blob) rather than a text field.
      for (org.jboss.resteasy.reactive.multipart.FileUpload part : parts) {
        if (part.name().equals("draft")) {
          try {
            draft = java.nio.file.Files.readString(part.uploadedFile());
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        }
      }
    }
    if (draft != null && !draft.isBlank()) {
      try {
        request =
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(draft, Views.DraftRequest.class);
      } catch (java.io.IOException e) {
        throw Problems.invalidRequest("the part 'draft' is not a draft: " + e.getMessage());
      }
    }
    java.util.Map<String, byte[]> uploads = new java.util.TreeMap<>();
    for (org.jboss.resteasy.reactive.multipart.FileUpload part : parts) {
      if (part.name().equals("draft")) {
        continue;
      }
      try {
        uploads.put(part.name(), java.nio.file.Files.readAllBytes(part.uploadedFile()));
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }
    return new Draft(request, uploads);
  }

  /** A draft with its uploaded files. */
  record Draft(Views.DraftRequest request, java.util.Map<String, byte[]> uploads) {}

  static String checkId(String id) {
    if (!TemplateStore.ID.matcher(id).matches()) {
      throw Problems.invalidRequest(
          "template ids consist of lower-case letters, digits and '-', at most 64 characters");
    }
    return id;
  }
}
