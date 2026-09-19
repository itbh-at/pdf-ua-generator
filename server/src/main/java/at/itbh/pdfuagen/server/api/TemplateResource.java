/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.api;

import at.itbh.pdfuagen.core.JsonData;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import at.itbh.pdfuagen.core.TemplateCheck;
import at.itbh.pdfuagen.server.ServerConfig;
import at.itbh.pdfuagen.server.render.RenderService;
import at.itbh.pdfuagen.server.store.Bundle;
import at.itbh.pdfuagen.server.store.TemplateStore;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
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

  @GET
  public List<Views.TemplateView> list() {
    return store.list().stream().map(t -> Views.template(t, List.of())).toList();
  }

  @GET
  @Path("/{id}")
  public Views.TemplateView get(@PathParam("id") String id) {
    TemplateStore.Template template = targets.template(id);
    return Views.template(template, store.revisions(id));
  }

  @DELETE
  @Path("/{id}")
  public Response delete(@PathParam("id") String id) {
    if (!store.deleteTemplate(Targets.checkId(id))) {
      throw Problems.notFound("template '" + id + "' does not exist");
    }
    return Response.noContent().build();
  }

  /**
   * Stores a ZIP archive of template files as a new draft revision. The template is created with
   * its first revision. The response lists the problems found when parsing the template; a draft
   * with problems is stored anyway, so it can be corrected.
   */
  @POST
  @Path("/{id}/revisions")
  @Consumes(ZIP)
  public Response create(@PathParam("id") String id, InputStream zip, @Context UriInfo uri)
      throws IOException {
    Targets.checkId(id);
    Map<String, byte[]> files;
    try {
      files = Bundle.read(zip, config.bundle().maxSize(), config.bundle().maxFiles());
    } catch (Bundle.InvalidBundleException e) {
      throw Problems.of(Problems.INVALID_BUNDLE, 400, "Invalid bundle", e.getMessage());
    }
    TemplateStore.Revision revision = store.createRevision(id, files);
    Views.Target loaded = targets.revision(id, revision.number());
    return Response.created(
            uri.getBaseUriBuilder()
                .path("templates/{id}/revisions/{n}")
                .build(id, revision.number()))
        .entity(Views.revision(loaded, service))
        .build();
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
    Views.Target loaded = targets.revision(id, n);
    if (loaded.revision().published() || !store.deleteDraft(id, n)) {
      throw Problems.conflict("revision " + n + " is published; published revisions are kept");
    }
    return Response.noContent().build();
  }

  /**
   * Runs the publish checks with the revision's example data ({@code example.json}, attachments
   * under {@code example/}) and publishes the revision if they pass.
   */
  @POST
  @Path("/{id}/revisions/{n}/publish")
  @Blocking
  public CompletionStage<Views.RevisionView> publish(
      @PathParam("id") String id, @PathParam("n") int n) {
    Views.Target loaded = targets.revision(id, n);
    if (loaded.revision().published()) {
      throw Problems.conflict("revision " + n + " is already published");
    }
    return Problems.submit(
        service,
        () -> {
          var repository = loaded.repository();
          byte[] example =
              repository
                  .resource(Bundle.EXAMPLE)
                  .orElseThrow(
                      () ->
                          Problems.publishRejected(
                              List.of(
                                  new Problem(
                                      Problem.TEMPLATE_ERROR,
                                      "the revision has no example data; add "
                                          + Bundle.EXAMPLE
                                          + " to the bundle",
                                      Bundle.EXAMPLE))));
          Map<String, Object> data;
          try {
            data = JsonData.parse(new ByteArrayInputStream(example));
          } catch (RenderException e) {
            throw Problems.publishRejected(e.problems());
          }
          Map<String, byte[]> attachments =
              Bundle.exampleAttachments(
                  loaded.revision().files().keySet(), p -> repository.resource(p).orElseThrow());
          TemplateCheck.Report report =
              TemplateCheck.check(
                  service.renderer(), repository, Bundle.TEMPLATE, data, attachments);
          if (!report.passed()) {
            throw Problems.publishRejected(report.problems());
          }
          if (!store.publish(id, n)) {
            throw Problems.conflict("revision " + n + " is already published");
          }
          return Views.revision(targets.revision(id, n), service);
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
