/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.ui;

import at.itbh.pdfuagen.server.store.TemplateStore;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The editor UI: server-rendered Qute pages, a client of the JSON API on the same service layer. It
 * never reaches for anything the JSON API does not also expose. Read-only for now: the template
 * catalogue and one template's revisions.
 */
@Path("/ui")
@Produces(MediaType.TEXT_HTML)
public class UiResource {

  @Inject TemplateStore store;

  @Inject
  @Location("ui/templates.html")
  Template templatesPage;

  @Inject
  @Location("ui/template.html")
  Template templatePage;

  @GET
  public TemplateInstance list() {
    return templatesPage.data("templates", store.list());
  }

  @GET
  @Path("/templates/{id}")
  public TemplateInstance template(@PathParam("id") String id) {
    TemplateStore.Template template =
        store.find(id).orElseThrow(() -> new NotFoundException("no template '" + id + "'"));
    return templatePage
        .data("template", template)
        .data("revisions", store.revisions(id))
        .data(
            "dependents",
            TemplateStore.LAYOUT.equals(template.kind()) ? store.dependents(id) : null);
  }
}
