/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.ui;

import at.itbh.pdfuagen.server.ServerConfig;
import at.itbh.pdfuagen.server.TemplateActions;
import at.itbh.pdfuagen.server.store.TemplateStore;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;

/**
 * The editor UI: server-rendered Qute pages, a client of the JSON API on the same service layer. It
 * never reaches for anything the JSON API does not also expose — the write actions go through the
 * same {@link TemplateActions} the JSON API uses. htmx swaps the revisions panel after an action.
 */
@Path("/ui")
@Produces(MediaType.TEXT_HTML)
public class UiResource {

  @Inject TemplateStore store;
  @Inject TemplateActions actions;
  @Inject ServerConfig config;

  @Inject
  @Location("ui/templates.html")
  Template templatesPage;

  @Inject
  @Location("ui/template.html")
  Template templatePage;

  @Inject
  @Location("ui/revision.html")
  Template revisionPage;

  @Inject
  @Location("ui/revisions.html")
  Template revisionsFragment;

  @GET
  public TemplateInstance list() {
    return templatesPage.data("templates", store.list());
  }

  @GET
  @Path("/templates/{id}")
  public TemplateInstance template(@PathParam("id") String id) {
    return templatePage
        .data("template", templateOf(id))
        .data("revisions", store.revisions(id))
        .data(
            "dependents",
            TemplateStore.LAYOUT.equals(templateOf(id).kind()) ? store.dependents(id) : null)
        .data("messageKind", null)
        .data("messageText", null)
        .data("problems", List.of());
  }

  @GET
  @Path("/templates/{id}/revisions/{n}")
  public TemplateInstance revision(@PathParam("id") String id, @PathParam("n") int n) {
    TemplateStore.Revision revision =
        store
            .revision(id, n)
            .orElseThrow(() -> new NotFoundException("no revision " + n + " of '" + id + "'"));
    return revisionPage.data("template", templateOf(id)).data("revision", revision);
  }

  @POST
  @Path("/templates/{id}/revisions/{n}/publish")
  @Blocking
  public TemplateInstance publish(
      @PathParam("id") String id, @PathParam("n") int n, @Context UriInfo uri) {
    TemplateActions.PublishResult result =
        actions.publish(id, n, config.publicBaseUrl().orElse(uri.getBaseUri()));
    return switch (result) {
      case TemplateActions.Published p ->
          revisions(id, "ok", "Revision " + n + " published.", List.of());
      case TemplateActions.Rejected r -> revisions(id, "error", "Publish rejected.", r.problems());
      case TemplateActions.Conflict c -> revisions(id, "error", c.detail(), List.of());
      case TemplateActions.NotFound nf -> throw new NotFoundException(nf.detail());
    };
  }

  @POST
  @Path("/templates/{id}/revisions/{n}/delete")
  @Blocking
  public TemplateInstance delete(@PathParam("id") String id, @PathParam("n") int n) {
    TemplateActions.DeleteResult result = actions.deleteDraft(id, n);
    return switch (result) {
      case TemplateActions.Deleted d -> revisions(id, "ok", "Draft " + n + " deleted.", List.of());
      case TemplateActions.CannotDelete c -> revisions(id, "error", c.detail(), List.of());
      case TemplateActions.Missing m -> throw new NotFoundException(m.detail());
    };
  }

  private TemplateInstance revisions(
      String id, String kind, String text, List<at.itbh.pdfuagen.core.Problem> problems) {
    return revisionsFragment
        .data("template", templateOf(id))
        .data("revisions", store.revisions(id))
        .data("messageKind", kind)
        .data("messageText", text)
        .data("problems", problems);
  }

  private TemplateStore.Template templateOf(String id) {
    return store.find(id).orElseThrow(() -> new NotFoundException("no template '" + id + "'"));
  }
}
