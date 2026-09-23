/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.api;

import at.itbh.pdfuagen.core.schema.LayoutDescriptor;
import at.itbh.pdfuagen.server.render.RenderService;
import at.itbh.pdfuagen.server.store.TemplateStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Looks up templates and revisions for the resources; unknown ones are answered with 404. */
@ApplicationScoped
class Targets {

  @Inject TemplateStore store;
  @Inject RenderService service;

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
    TemplateStore.LayoutPin pin = pin(revision, layout);
    TemplateStore.Revision composed =
        pin == null
            ? null
            : store
                .revision(pin.id(), pin.revision())
                .filter(l -> l.files().containsKey(LayoutDescriptor.FILE))
                .orElse(null);
    return new Views.Target(revision, composed, service.repository(revision, composed));
  }

  /** The requested layout among those a revision lists, or its default. */
  private static TemplateStore.LayoutPin pin(TemplateStore.Revision revision, String requested) {
    if (requested == null || requested.isBlank()) {
      return revision.defaultLayout();
    }
    for (TemplateStore.LayoutPin pin : revision.layouts()) {
      if (pin.id().equals(requested) || pin.toString().equals(requested)) {
        return pin;
      }
    }
    throw Problems.invalidRequest(
        revision.layouts().isEmpty()
            ? "template '" + revision.templateId() + "' is not rendered with a layout"
            : "template '"
                + revision.templateId()
                + "' does not list the layout '"
                + requested
                + "'; it lists "
                + String.join(", ", revision.layouts().stream().map(Object::toString).toList()));
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

  static String checkId(String id) {
    if (!TemplateStore.ID.matcher(id).matches()) {
      throw Problems.invalidRequest(
          "template ids consist of lower-case letters, digits and '-', at most 64 characters");
    }
    return id;
  }
}
