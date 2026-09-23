/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.api;

import at.itbh.pdfuagen.core.JsonData;
import at.itbh.pdfuagen.core.LanguageVariants;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import at.itbh.pdfuagen.core.schema.LayoutDescriptor;
import at.itbh.pdfuagen.core.schema.TemplateDescriptor;
import at.itbh.pdfuagen.server.ServerConfig;
import at.itbh.pdfuagen.server.TemplateActions;
import at.itbh.pdfuagen.server.render.RenderService;
import at.itbh.pdfuagen.server.store.Bundle;
import at.itbh.pdfuagen.server.store.TemplateStore;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Templates and their revisions: create, inspect, publish, delete; schema and validation. */
@Path("/templates")
@Produces(MediaType.APPLICATION_JSON)
public class TemplateResource {

  static final String ZIP = "application/zip";
  static final String SCHEMA_JSON = "application/schema+json";

  @Inject TemplateStore store;
  @Inject RenderService service;
  @Inject ServerConfig config;
  @Inject Targets targets;
  @Inject TemplateActions actions;

  @GET
  public List<Views.TemplateView> list() {
    return store.list().stream().map(t -> Views.template(t, null, null)).toList();
  }

  @GET
  @Path("/{id}")
  public Views.TemplateView get(@PathParam("id") String id) {
    TemplateStore.Template template = targets.template(id);
    return Views.template(
        template,
        store.revisions(id),
        TemplateStore.LAYOUT.equals(template.kind()) ? store.dependents(id) : null);
  }

  @DELETE
  @Path("/{id}")
  public Response delete(@PathParam("id") String id) {
    List<String> users =
        store.dependents(Targets.checkId(id)).stream()
            .filter(d -> !d.templateId().equals(id))
            .map(d -> d.templateId() + "@" + d.number())
            .distinct()
            .toList();
    if (!users.isEmpty()) {
      throw Problems.conflict(
          "layout '" + id + "' is used by " + String.join(", ", users) + "; delete those first");
    }
    if (!store.deleteTemplate(id)) {
      throw Problems.notFound("template '" + id + "' does not exist");
    }
    return Response.noContent().build();
  }

  /**
   * Stores a ZIP archive of template files as a new draft revision. The template is created with
   * its first revision. The response lists the problems found when parsing the template; a draft
   * with problems is stored anyway, so it can be corrected.
   *
   * <p>Files already stored as a revision of this template yield that revision instead of a new
   * one. With {@code ?publish=true} the revision is published right away, unless it already is; an
   * import can therefore be repeated without changing anything.
   */
  @POST
  @Path("/{id}/revisions")
  @Consumes(ZIP)
  @Blocking
  public CompletionStage<Response> create(
      @PathParam("id") String id,
      InputStream zip,
      @QueryParam("publish") @DefaultValue("false") boolean publish,
      @Context UriInfo uri)
      throws IOException {
    Targets.checkId(id);
    Map<String, byte[]> files;
    try {
      files = Bundle.read(zip, config.bundle().maxSize(), config.bundle().maxFiles());
    } catch (Bundle.InvalidBundleException e) {
      throw Problems.of(Problems.INVALID_BUNDLE, 400, "Invalid bundle", e.getMessage());
    }
    String kind =
        files.containsKey(LayoutDescriptor.FILE) ? TemplateStore.LAYOUT : TemplateStore.CONTENT;
    TemplateDescriptor.LayoutRef layout = null;
    byte[] descriptor = files.get(LanguageVariants.descriptorPath(Bundle.TEMPLATE));
    if (kind.equals(TemplateStore.CONTENT) && descriptor != null) {
      try {
        layout = TemplateDescriptor.parse(descriptor, Bundle.TEMPLATE).layout();
      } catch (RenderException e) {
        // Reported with the revision's problems.
      }
    }
    TemplateStore.Stored stored;
    try {
      stored =
          store.createRevision(
              id,
              files,
              kind,
              layout == null ? null : layout.id(),
              layout == null ? null : layout.revision());
    } catch (TemplateStore.KindMismatchException e) {
      throw Problems.conflict(e.getMessage());
    }
    int number = stored.revision().number();
    java.net.URI location =
        uri.getBaseUriBuilder().path("templates/{id}/revisions/{n}").build(id, number);
    Views.Target loaded = targets.revision(id, number);
    // Same files as a revision already stored: that revision, not a new one.
    Response.ResponseBuilder response =
        stored.created()
            ? Response.created(location)
            : Response.ok().header("Content-Location", location);
    if (!publish || loaded.revision().published()) {
      return CompletableFuture.completedStage(
          response.entity(Views.revision(loaded, service)).build());
    }
    return publishRevision(id, number, uri).thenApply(view -> response.entity(view).build());
  }

  @GET
  @Path("/{id}/revisions/{n}")
  public Views.RevisionView revision(@PathParam("id") String id, @PathParam("n") int n) {
    return Views.revision(targets.revision(id, n), service);
  }

  @GET
  @Path("/{id}/revisions/{n}/bundle")
  @Produces(ZIP)
  public Response bundle(@PathParam("id") String id, @PathParam("n") int n) throws IOException {
    targets.revision(id, n);
    return Response.ok(Bundle.write(store.files(id, n)))
        .header("Content-Disposition", "attachment; filename=\"" + id + "-" + n + ".zip\"")
        .build();
  }

  @DELETE
  @Path("/{id}/revisions/{n}")
  public Response deleteRevision(@PathParam("id") String id, @PathParam("n") int n) {
    Targets.checkId(id);
    return switch (actions.deleteDraft(id, n)) {
      case TemplateActions.Deleted d -> Response.noContent().build();
      case TemplateActions.CannotDelete c -> throw Problems.conflict(c.detail());
      case TemplateActions.Missing m -> throw Problems.notFound(m.detail());
    };
  }

  /**
   * Runs the publish checks with the revision's example data ({@code example.json}, attachments
   * under {@code example/}) and publishes the revision if they pass.
   */
  @POST
  @Path("/{id}/revisions/{n}/publish")
  @Blocking
  public CompletionStage<Views.RevisionView> publish(
      @PathParam("id") String id, @PathParam("n") int n, @Context UriInfo uri) {
    return publishRevision(id, n, uri);
  }

  private CompletionStage<Views.RevisionView> publishRevision(String id, int n, UriInfo uri) {
    Targets.checkId(id);
    java.net.URI publicBase = config.publicBaseUrl().orElse(uri.getBaseUri());
    return Problems.submit(
        service,
        () ->
            switch (actions.publish(id, n, publicBase)) {
              case TemplateActions.Published p -> Views.revision(targets.revision(id, n), service);
              case TemplateActions.Rejected r -> throw Problems.publishRejected(r.problems());
              case TemplateActions.Conflict c -> throw Problems.conflict(c.detail());
              case TemplateActions.NotFound nf -> throw Problems.notFound(nf.detail());
            });
  }

  @GET
  @Path("/{id}/schema")
  @Produces(SCHEMA_JSON)
  public String schema(@PathParam("id") String id) {
    return schema(targets.published(id));
  }

  @GET
  @Path("/{id}/revisions/{n}/schema")
  @Produces(SCHEMA_JSON)
  public String revisionSchema(@PathParam("id") String id, @PathParam("n") int n) {
    return schema(targets.revision(id, n));
  }

  /** Validates data against the schema of the latest published revision; 204 if valid. */
  @POST
  @Path("/{id}/validate")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response validate(@PathParam("id") String id, byte[] data) {
    return validate(targets.published(id), data);
  }

  @POST
  @Path("/{id}/revisions/{n}/validate")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response validateRevision(@PathParam("id") String id, @PathParam("n") int n, byte[] data) {
    return validate(targets.revision(id, n), data);
  }

  private Response validate(Views.Target loaded, byte[] data) {
    try {
      List<Problem> problems =
          service
              .renderer()
              .schema(loaded.repository(), Bundle.TEMPLATE)
              .validate(JsonData.parse(new ByteArrayInputStream(data)));
      if (!problems.isEmpty()) {
        throw Problems.from(problems);
      }
      return Response.noContent().build();
    } catch (RenderException e) {
      throw Problems.from(e.problems());
    }
  }

  private String schema(Views.Target loaded) {
    try {
      return service.renderer().schema(loaded.repository(), Bundle.TEMPLATE).toJson();
    } catch (RenderException e) {
      throw Problems.from(e.problems());
    }
  }
}
