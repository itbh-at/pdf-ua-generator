/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.api;

import at.itbh.pdfuagen.core.JsonData;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import at.itbh.pdfuagen.server.ServerConfig;
import at.itbh.pdfuagen.server.TemplateActions;
import at.itbh.pdfuagen.server.render.RenderService;
import at.itbh.pdfuagen.server.store.Bundle;
import at.itbh.pdfuagen.server.store.TemplateStore;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Templates and their revisions: upload (validate and release), inspect, archive, delete; schema
 * and validation.
 */
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

  /**
   * The template with its revisions. The {@code ETag} is its latest revision; sent back in {@code
   * If-Match} when uploading or saving, it makes sure nobody saved meanwhile.
   */
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") String id) {
    TemplateStore.Template template = targets.template(id);
    return Response.ok(
            Views.template(
                template,
                store.revisions(id),
                TemplateStore.LAYOUT.equals(template.kind()) ? store.dependents(id) : null))
        .tag(etag(template.latest()))
        .build();
  }

  static jakarta.ws.rs.core.EntityTag etag(int latest) {
    return new jakarta.ws.rs.core.EntityTag(String.valueOf(latest));
  }

  /**
   * The latest revision an {@code If-Match} header expects; {@code null} without one or for {@code
   * *}. A tag that is no revision number never matches.
   */
  static Integer ifMatch(String header) {
    if (header == null || header.isBlank() || header.trim().equals("*")) {
      return null;
    }
    String tag = header.split(",")[0].trim();
    if (tag.startsWith("W/")) {
      tag = tag.substring(2);
    }
    try {
      return Integer.valueOf(tag.replace("\"", "").trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  @DELETE
  @Path("/{id}")
  public Response delete(@PathParam("id") String id) {
    Targets.checkId(id);
    return switch (actions.deleteTemplate(id)) {
      case TemplateActions.Deleted d -> Response.noContent().build();
      case TemplateActions.CannotDelete c -> throw Problems.conflict(c.detail());
      case TemplateActions.Missing m -> throw Problems.notFound(m.detail());
    };
  }

  /**
   * Uploads a ZIP archive of template files, validates it and releases it for use in one step. The
   * template is created with its first revision; a further upload adds a revision. A bundle that
   * fails the publish checks is answered with 422 and nothing is stored.
   *
   * <p>Files already stored as a revision of this template yield that revision instead of a new
   * one, so an import can be repeated without changing anything. A layout carries no example data;
   * a content template is checked with its {@code example.json} and attachments under {@code
   * example/}.
   */
  @POST
  @Path("/{id}/revisions")
  @Consumes(ZIP)
  @Blocking
  public CompletionStage<Response> create(
      @PathParam("id") String id,
      InputStream zip,
      @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
      @Context UriInfo uri)
      throws IOException {
    Targets.checkId(id);
    return upload(id, zip.readAllBytes(), uri, ifMatch(ifMatch));
  }

  /**
   * Saves edited files as a new revision: revision {@code base} with {@code files} written over it
   * and {@code delete} removed, then validated and released like an uploaded bundle. {@code base}
   * must still be the latest revision (or the one {@code If-Match} names), otherwise 412 {@code
   * revision-conflict}; {@code "force": true} saves anyway.
   */
  @POST
  @Path("/{id}/revisions")
  @Consumes(MediaType.APPLICATION_JSON)
  @Blocking
  public CompletionStage<Response> save(
      @PathParam("id") String id,
      Views.DraftRequest draft,
      @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
      @Context UriInfo uri)
      throws IOException {
    Targets.checkId(id);
    if (draft == null || draft.base() == null) {
      throw Problems.invalidRequest("'base' is required: the revision the files are based on");
    }
    Integer expected = ifMatch(ifMatch);
    if (expected == null && !Boolean.TRUE.equals(draft.force())) {
      expected = draft.base();
    }
    return upload(
        id, Bundle.write(targets.draftFiles(id, draft.base(), draft).files()), uri, expected);
  }

  private CompletionStage<Response> upload(
      String id, byte[] bytes, UriInfo uri, Integer expectedLatest) {
    java.net.URI base = uri.getBaseUri();
    java.net.URI publicBase = config.publicBaseUrl().orElse(base);
    return Problems.submit(
        service,
        () -> {
          TemplateStore.Stored stored =
              switch (actions.create(
                  id, new ByteArrayInputStream(bytes), publicBase, null, expectedLatest)) {
                case TemplateActions.Created c -> c.stored();
                case TemplateActions.InvalidBundle b ->
                    throw Problems.of(Problems.INVALID_BUNDLE, 400, "Invalid bundle", b.detail());
                case TemplateActions.KindMismatch k -> throw Problems.conflict(k.detail());
                case TemplateActions.Rejected r -> throw Problems.publishRejected(r.problems());
                case TemplateActions.Stale st ->
                    throw Problems.revisionConflict(st.latest(), expectedLatest);
              };
          int number = stored.revision().number();
          java.net.URI location =
              jakarta.ws.rs.core.UriBuilder.fromUri(base)
                  .path("templates/{id}/revisions/{n}")
                  .build(id, number);
          Views.Target loaded = targets.revision(id, number);
          Response.ResponseBuilder response =
              stored.created()
                  ? Response.created(location)
                  : Response.ok().header("Content-Location", location);
          return response
              .entity(Views.revision(loaded, service))
              .tag(etag(targets.template(id).latest()))
              .build();
        });
  }

  @GET
  @Path("/{id}/revisions/{n}")
  public Views.RevisionView revision(@PathParam("id") String id, @PathParam("n") int n) {
    return Views.revision(targets.revision(id, n), service);
  }

  /** One file of a revision, as stored. */
  @GET
  @Path("/{id}/revisions/{n}/files/{path: .+}")
  @Produces(MediaType.WILDCARD)
  public Response file(
      @PathParam("id") String id, @PathParam("n") int n, @PathParam("path") String path) {
    Targets.checkId(id);
    if (store.revision(id, n).isEmpty()) {
      throw Problems.notFound("template '" + id + "' has no revision " + n);
    }
    byte[] content = store.files(id, n).get(path);
    if (content == null) {
      throw Problems.notFound("revision " + n + " of '" + id + "' has no file " + path);
    }
    return Response.ok(content).type(Bundle.mediaType(path)).build();
  }

  /**
   * The quick checks of edited files, without saving them: every language variant parses, the
   * descriptor and data model are valid, the layout rules and texts hold. The full publish checks
   * run when the files are saved.
   */
  @POST
  @Path("/{id}/revisions/{n}/check")
  @Consumes(MediaType.APPLICATION_JSON)
  public CompletionStage<Views.CheckView> check(
      @PathParam("id") String id, @PathParam("n") int n, Views.DraftRequest draft) {
    Targets.checkId(id);
    return Problems.submit(
        service,
        () -> {
          TemplateActions.Findings findings =
              actions.check(targets.draftFiles(id, n, draft).files());
          return new Views.CheckView(
              findings.problems().stream().map(Views.ProblemView::of).toList(),
              findings.warnings());
        });
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

  /**
   * Retires (archives) a released revision. A layout revision cannot be archived while a released
   * content revision pins it.
   */
  @DELETE
  @Path("/{id}/revisions/{n}")
  public Views.RevisionView archive(@PathParam("id") String id, @PathParam("n") int n) {
    Targets.checkId(id);
    return switch (actions.archive(id, n)) {
      case TemplateActions.Archived a -> Views.revision(targets.revision(id, n), service);
      case TemplateActions.CannotArchive c -> throw Problems.conflict(c.detail());
      case TemplateActions.NotArchived m -> throw Problems.notFound(m.detail());
    };
  }

  /** The data model; a layout may add fields, so it follows {@code ?layout=} like rendering. */
  @GET
  @Path("/{id}/schema")
  @Produces(SCHEMA_JSON)
  public String schema(@PathParam("id") String id, @QueryParam("layout") String layout) {
    return schema(targets.published(id, layout));
  }

  @GET
  @Path("/{id}/revisions/{n}/schema")
  @Produces(SCHEMA_JSON)
  public String revisionSchema(
      @PathParam("id") String id, @PathParam("n") int n, @QueryParam("layout") String layout) {
    return schema(targets.revision(id, n, layout));
  }

  /** Validates data against the schema of the latest published revision; 204 if valid. */
  @POST
  @Path("/{id}/validate")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response validate(
      @PathParam("id") String id, @QueryParam("layout") String layout, byte[] data) {
    return validate(targets.published(id, layout), data);
  }

  @POST
  @Path("/{id}/revisions/{n}/validate")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response validateRevision(
      @PathParam("id") String id,
      @PathParam("n") int n,
      @QueryParam("layout") String layout,
      byte[] data) {
    return validate(targets.revision(id, n, layout), data);
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
