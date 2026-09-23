/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.ui;

import at.itbh.pdfuagen.core.JsonData;
import at.itbh.pdfuagen.core.OutputFormat;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import at.itbh.pdfuagen.core.RenderRequest;
import at.itbh.pdfuagen.core.Rendered;
import at.itbh.pdfuagen.core.TemplateRepository;
import at.itbh.pdfuagen.core.schema.LayoutDescriptor;
import at.itbh.pdfuagen.server.ServerConfig;
import at.itbh.pdfuagen.server.TemplateActions;
import at.itbh.pdfuagen.server.render.RenderService;
import at.itbh.pdfuagen.server.store.Bundle;
import at.itbh.pdfuagen.server.store.TemplateStore;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

/**
 * The editor UI: server-rendered Qute pages, a client of the JSON API on the same service layer. It
 * never reaches for anything the JSON API does not also expose — creating, publishing and deleting
 * go through the same {@link TemplateActions} the JSON API uses, and rendering through the same
 * renderer.
 */
@Path("/ui")
@Produces(MediaType.TEXT_HTML)
public class UiResource {

  /** Formats a browser shows inline; the rest are offered as a download. */
  private static final Set<OutputFormat> INLINE =
      EnumSet.of(OutputFormat.PDF, OutputFormat.XHTML, OutputFormat.EMAIL_HTML, OutputFormat.TEXT);

  @Inject TemplateStore store;
  @Inject TemplateActions actions;
  @Inject RenderService renderers;
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

  @Inject
  @Location("ui/render.html")
  Template renderPage;

  @Inject
  @Location("ui/problems.html")
  Template problemsFragment;

  @GET
  public TemplateInstance list() {
    return templatesPage.data("templates", store.list());
  }

  @POST
  @Path("/templates")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Blocking
  public Response create(
      @RestForm String id, @RestForm("bundle") FileUpload bundle, @Context UriInfo uri)
      throws IOException {
    if (id == null || !TemplateStore.ID.matcher(id).matches()) {
      return html(problemsFragment.data("problems", problem("invalid template id")));
    }
    if (bundle == null) {
      return html(problemsFragment.data("problems", problem("no bundle uploaded")));
    }
    try (InputStream in = Files.newInputStream(bundle.uploadedFile())) {
      java.net.URI publicBase = config.publicBaseUrl().orElse(uri.getBaseUri());
      return switch (actions.create(id, in, publicBase)) {
        // Validated and released: let htmx navigate to the new template's page.
        case TemplateActions.Created c ->
            Response.ok().header("HX-Redirect", "/ui/templates/" + id).build();
        case TemplateActions.Rejected r -> html(problemsFragment.data("problems", r.problems()));
        case TemplateActions.InvalidBundle b ->
            html(problemsFragment.data("problems", problem(b.detail())));
        case TemplateActions.KindMismatch k ->
            html(problemsFragment.data("problems", problem(k.detail())));
      };
    }
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
  @Path("/templates/{id}/revisions/{n}/archive")
  public TemplateInstance archive(@PathParam("id") String id, @PathParam("n") int n) {
    return switch (actions.archive(id, n)) {
      case TemplateActions.Archived a ->
          revisions(id, "ok", "Revision " + n + " archived.", List.of());
      case TemplateActions.CannotArchive c -> revisions(id, "error", c.detail(), List.of());
      case TemplateActions.NotArchived m -> throw new NotFoundException(m.detail());
    };
  }

  @POST
  @Path("/templates/{id}/delete")
  public Response delete(@PathParam("id") String id) {
    return switch (actions.deleteTemplate(id)) {
      // Deleted: leave the template page for the catalogue.
      case TemplateActions.Deleted d -> Response.ok().header("HX-Redirect", "/ui").build();
      case TemplateActions.CannotDelete c ->
          html(problemsFragment.data("problems", problem(c.detail())));
      case TemplateActions.Missing m -> throw new NotFoundException(m.detail());
    };
  }

  @GET
  @Path("/templates/{id}/render")
  @Blocking
  public TemplateInstance renderForm(@PathParam("id") String id) {
    TemplateStore.Template template = templateOf(id);
    Integer published = template.latestPublished();
    List<String> formats = List.of();
    List<String> attachments = List.of();
    String example = "";
    String schema = "";
    if (published != null) {
      TemplateStore.Revision revision = store.revision(id, published).orElseThrow();
      TemplateRepository repository = renderers.repository(revision, layoutOf(revision));
      formats =
          renderers.renderer().formats(repository, Bundle.TEMPLATE).stream()
              .map(OutputFormat::id)
              .toList();
      example =
          repository
              .resource(Bundle.EXAMPLE)
              .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
              .orElse("");
      attachments =
          Bundle.exampleAttachments(
                  revision.files().keySet(), p -> repository.resource(p).orElseThrow())
              .keySet()
              .stream()
              .toList();
      schema = schema(repository);
    }
    return renderPage
        .data("template", template)
        .data("published", published)
        .data("formats", formats)
        .data("attachments", attachments)
        .data("example", example)
        .data("schema", schema);
  }

  /** The data model as a pretty-printed JSON Schema, or empty if it cannot be derived. */
  private String schema(TemplateRepository repository) {
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      return mapper
          .writerWithDefaultPrettyPrinter()
          .writeValueAsString(
              mapper.readTree(renderers.renderer().schema(repository, Bundle.TEMPLATE).toJson()));
    } catch (Exception e) {
      return "";
    }
  }

  @POST
  @Path("/templates/{id}/render")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Blocking
  public Response render(
      @PathParam("id") String id,
      @RestForm String data,
      @RestForm String format,
      @RestForm(FileUpload.ALL) List<FileUpload> uploads,
      @Context UriInfo uri)
      throws IOException {
    TemplateStore.Template template = templateOf(id);
    Integer published = template.latestPublished();
    if (published == null) {
      return html(
          problemsFragment.data("problems", problem("the template has no published revision")));
    }
    TemplateStore.Revision revision = store.revision(id, published).orElseThrow();
    TemplateRepository repository = renderers.repository(revision, layoutOf(revision));
    OutputFormat out = OutputFormat.of(format).orElse(OutputFormat.PDF);
    // The example attachments are the default; an uploaded file for an attachment overrides it.
    Map<String, byte[]> attachments =
        new java.util.LinkedHashMap<>(
            Bundle.exampleAttachments(
                revision.files().keySet(), p -> repository.resource(p).orElseThrow()));
    for (FileUpload upload : uploads) {
      if (upload.size() > 0) {
        attachments.put(upload.name(), Files.readAllBytes(upload.uploadedFile()));
      }
    }
    try {
      Map<String, Object> json =
          JsonData.parse(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
      String variant = renderers.renderer().selectVariant(repository, Bundle.TEMPLATE, List.of());
      Rendered rendered =
          renderers
              .renderer()
              .render(
                  new RenderRequest(
                      variant,
                      repository,
                      json,
                      attachments,
                      config.publicBaseUrl().orElse(uri.getBaseUri())),
                  out);
      String type =
          out.mediaType() + (out.mediaType().startsWith("text/") ? "; charset=utf-8" : "");
      return Response.ok(rendered.content())
          .type(type)
          .header(
              "Content-Disposition",
              (INLINE.contains(out) ? "inline" : "attachment")
                  + "; filename=\""
                  + id
                  + "."
                  + out.extension()
                  + "\"")
          .build();
    } catch (RenderException e) {
      return html(problemsFragment.data("problems", e.problems()));
    }
  }

  private TemplateInstance revisions(String id, String kind, String text, List<Problem> problems) {
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

  private TemplateStore.Revision layoutOf(TemplateStore.Revision revision) {
    if (revision.layoutId() == null) {
      return null;
    }
    return store
        .revision(revision.layoutId(), revision.layoutRevision())
        .filter(l -> l.files().containsKey(LayoutDescriptor.FILE))
        .orElse(null);
  }

  private static List<Problem> problem(String detail) {
    return List.of(new Problem(Problem.TEMPLATE_ERROR, detail, null));
  }

  private static Response html(TemplateInstance instance) {
    return Response.ok(instance).type(MediaType.TEXT_HTML).build();
  }
}
