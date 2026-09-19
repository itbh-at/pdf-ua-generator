/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.api;

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

  Views.Target revision(String id, int n) {
    TemplateStore.Revision revision =
        store
            .revision(checkId(id), n)
            .orElseThrow(() -> Problems.notFound("template '" + id + "' has no revision " + n));
    return new Views.Target(revision, service.repository(revision));
  }

  /** The latest published revision. */
  Views.Target published(String id) {
    Integer n = template(id).latestPublished();
    if (n == null) {
      throw Problems.notFound("template '" + id + "' has no published revision");
    }
    return revision(id, n);
  }

  static String checkId(String id) {
    if (!TemplateStore.ID.matcher(id).matches()) {
      throw Problems.invalidRequest(
          "template ids consist of lower-case letters, digits and '-', at most 64 characters");
    }
    return id;
  }
}
