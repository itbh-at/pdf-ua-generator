/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.api;

import at.itbh.pdfuagen.core.DocumentRenderer;
import at.itbh.pdfuagen.core.JsonData;
import at.itbh.pdfuagen.core.LanguageVariants;
import at.itbh.pdfuagen.core.OutputFormat;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import at.itbh.pdfuagen.core.RenderRequest;
import at.itbh.pdfuagen.core.Rendered;
import at.itbh.pdfuagen.server.ServerConfig;
import at.itbh.pdfuagen.server.render.RenderService;
import at.itbh.pdfuagen.server.store.Bundle;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

/**
 * Renders a template with JSON data. The format follows the {@code format} query parameter or the
 * {@code Accept} header; the language variant follows the {@code lang} query parameter or {@code
 * Accept-Language}. Data comes as {@code application/json}, or as {@code multipart/form-data} with
 * the JSON in the part {@code data} and every other part an attachment named after its part.
 */
@Path("/templates/{id}")
public class RenderResource {

  static final String DATA_PART = "data";

  @Inject RenderService service;
  @Inject ServerConfig config;
  @Inject Targets targets;

  /** Renders the latest published revision. */
  @POST
  @Path("/render")
  @Consumes(MediaType.APPLICATION_JSON)
  public CompletionStage<Response> render(
      @PathParam("id") String id,
      @QueryParam("format") String format,
      @QueryParam("lang") String lang,
      @Context HttpHeaders headers,
      @Context UriInfo uri,
      byte[] data) {
    return render(() -> targets.published(id), format, lang, headers, uri, data, Map.of());
  }

  @POST
  @Path("/render")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Blocking
  public CompletionStage<Response> renderMultipart(
      @PathParam("id") String id,
      @QueryParam("format") String format,
      @QueryParam("lang") String lang,
      @Context HttpHeaders headers,
      @Context UriInfo uri,
      @RestForm(DATA_PART) String data,
      @RestForm(FileUpload.ALL) List<FileUpload> parts)
      throws IOException {
    return renderParts(() -> targets.published(id), format, lang, headers, uri, data, parts);
  }

  /** Renders a specific revision, also a draft, e.g. as a preview. */
  @POST
  @Path("/revisions/{n}/render")
  @Consumes(MediaType.APPLICATION_JSON)
  public CompletionStage<Response> renderRevision(
      @PathParam("id") String id,
      @PathParam("n") int n,
      @QueryParam("format") String format,
      @QueryParam("lang") String lang,
      @Context HttpHeaders headers,
      @Context UriInfo uri,
      byte[] data) {
    return render(() -> targets.revision(id, n), format, lang, headers, uri, data, Map.of());
  }

  @POST
  @Path("/revisions/{n}/render")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Blocking
  public CompletionStage<Response> renderRevisionMultipart(
      @PathParam("id") String id,
      @PathParam("n") int n,
      @QueryParam("format") String format,
      @QueryParam("lang") String lang,
      @Context HttpHeaders headers,
      @Context UriInfo uri,
      @RestForm(DATA_PART) String data,
      @RestForm(FileUpload.ALL) List<FileUpload> parts)
      throws IOException {
    return renderParts(() -> targets.revision(id, n), format, lang, headers, uri, data, parts);
  }

  private CompletionStage<Response> renderParts(
      Supplier<Views.Target> target,
      String format,
      String lang,
      HttpHeaders headers,
      UriInfo uri,
      String dataField,
      List<FileUpload> parts)
      throws IOException {
    // The JSON may come as a plain form field or as a file part.
    byte[] data = dataField == null ? null : dataField.getBytes(StandardCharsets.UTF_8);
    Map<String, byte[]> attachments = new LinkedHashMap<>();
    for (FileUpload part : parts) {
      byte[] bytes = Files.readAllBytes(part.uploadedFile());
      if (part.name().equals(DATA_PART)) {
        data = bytes;
      } else {
        attachments.put(part.name(), bytes);
      }
    }
    if (data == null) {
      throw Problems.invalidRequest("the multipart request has no part '" + DATA_PART + "'");
    }
    return render(target, format, lang, headers, uri, data, attachments);
  }

  /**
   * Everything that touches the database or the renderer happens in the worker pool, so an
   * overloaded service answers 503 before it does any work; only the request headers are read here,
   * because they belong to the request thread.
   */
  private CompletionStage<Response> render(
      Supplier<Views.Target> target,
      String format,
      String lang,
      HttpHeaders headers,
      UriInfo uri,
      byte[] data,
      Map<String, byte[]> attachments) {
    DocumentRenderer renderer = service.renderer();
    List<MediaType> acceptable = headers.getAcceptableMediaTypes();
    List<Locale.LanguageRange> ranges = ranges(lang, headers);
    URI publicBase = config.publicBaseUrl().orElse(uri.getBaseUri());
    return Problems.submit(
        service,
        () -> {
          // Inside the pool: the revision is looked up, and its first use parses its templates.
          Views.Target resolved = target.get();
          var repository = resolved.repository();
          Set<OutputFormat> offered = renderer.formats(repository, Bundle.TEMPLATE);
          Negotiated negotiated = negotiate(format, acceptable, offered);
          String variant = renderer.selectVariant(repository, Bundle.TEMPLATE, ranges);
          RenderRequest request =
              new RenderRequest(
                  variant,
                  repository,
                  JsonData.parse(new ByteArrayInputStream(data)),
                  attachments,
                  publicBase);
          Response.ResponseBuilder response;
          try {
            response =
                negotiated.multipart()
                    ? multipartAlternative(renderer, request)
                    : single(renderer, request, negotiated.format(), resolved);
          } catch (RenderException e) {
            throw Problems.from(e.problems());
          }
          response
              .header("Vary", "Accept, Accept-Language")
              .header("Template-Revision", resolved.revision().number());
          Locale language = renderer.language(repository, variant);
          if (!language.equals(Locale.ROOT)) {
            response.header("Content-Language", language.toLanguageTag());
          }
          return response.build();
        });
  }

  /** A single format response, with the inline filename and text charset. */
  private static Response.ResponseBuilder single(
      DocumentRenderer renderer, RenderRequest request, OutputFormat format, Views.Target resolved)
      throws RenderException {
    Rendered rendered = renderer.render(request, format);
    return Response.ok(rendered.content())
        .type(
            format.mediaType() + (format.mediaType().startsWith("text/") ? "; charset=utf-8" : ""))
        .header(
            "Content-Disposition",
            "inline; filename=\""
                + resolved.revision().templateId()
                + "."
                + format.extension()
                + "\"");
  }

  /**
   * A {@code multipart/alternative} body of the same document as plain text and as email HTML. Per
   * RFC 2046 the least faithful alternative comes first, so text precedes HTML. The service builds
   * the body; it never sends mail.
   */
  private static Response.ResponseBuilder multipartAlternative(
      DocumentRenderer renderer, RenderRequest request) throws RenderException {
    byte[] text = renderer.render(request, OutputFormat.TEXT).content();
    byte[] html = renderer.render(request, OutputFormat.EMAIL_HTML).content();
    String boundary = "itbh-" + java.util.UUID.randomUUID();
    return Response.ok(multipartBody(boundary, text, html))
        .type("multipart/alternative; boundary=\"" + boundary + "\"");
  }

  private static byte[] multipartBody(String boundary, byte[] text, byte[] html) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writePart(out, boundary, "text/plain; charset=utf-8", text);
    writePart(out, boundary, "text/html; charset=utf-8", html);
    out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  private static void writePart(
      ByteArrayOutputStream out, String boundary, String contentType, byte[] content) {
    out.writeBytes(
        ("--" + boundary + "\r\nContent-Type: " + contentType + "\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    out.writeBytes(content);
    out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
  }

  /** The {@code lang} parameter if given, otherwise {@code Accept-Language}; malformed: none. */
  private static List<Locale.LanguageRange> ranges(String lang, HttpHeaders headers) {
    if (lang != null) {
      try {
        return LanguageVariants.ranges(lang);
      } catch (IllegalArgumentException e) {
        throw Problems.invalidRequest("'lang' is not a language range list: " + lang);
      }
    }
    String accept = headers.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
    if (accept == null || accept.isBlank()) {
      return List.of();
    }
    try {
      return LanguageVariants.ranges(accept);
    } catch (IllegalArgumentException e) {
      return List.of();
    }
  }

  static final String MULTIPART_ALTERNATIVE = "multipart/alternative";

  /**
   * The chosen rendition: either one {@link OutputFormat}, or the {@code multipart/alternative}
   * pair.
   */
  record Negotiated(OutputFormat format, boolean multipart) {
    static final Negotiated MULTIPART = new Negotiated(null, true);

    static Negotiated of(OutputFormat format) {
      return new Negotiated(format, false);
    }
  }

  /**
   * The chosen rendition: the {@code format} parameter, otherwise the {@code Accept} header in
   * order ({@code *}{@code /*} gives the first offered, PDF if offered). {@code
   * multipart/alternative} pairs plain text and email HTML, which the template must both offer.
   */
  static Negotiated negotiate(
      String format, List<MediaType> acceptable, Set<OutputFormat> offered) {
    if (format != null) {
      if (format.equalsIgnoreCase(MULTIPART_ALTERNATIVE)) {
        return multipart(offered);
      }
      OutputFormat requested =
          OutputFormat.of(format)
              .orElseThrow(() -> notSupported("unknown format " + format, offered));
      if (!offered.contains(requested)) {
        throw notSupported("the template does not offer " + format, offered);
      }
      return Negotiated.of(requested);
    }
    if (acceptable.isEmpty()) {
      return Negotiated.of(offered.iterator().next());
    }
    for (MediaType type : acceptable) {
      if (isMultipartAlternative(type)) {
        return multipart(offered);
      }
      for (OutputFormat candidate : offered) {
        if (type.isCompatible(MediaType.valueOf(candidate.mediaType()))) {
          return Negotiated.of(candidate);
        }
      }
    }
    throw notSupported("none of the accepted media types is offered", offered);
  }

  private static Negotiated multipart(Set<OutputFormat> offered) {
    if (!offered.contains(OutputFormat.TEXT) || !offered.contains(OutputFormat.EMAIL_HTML)) {
      throw notSupported("multipart/alternative needs both text and email-html", offered);
    }
    return Negotiated.MULTIPART;
  }

  /** An explicit {@code multipart/alternative} or {@code multipart} type, not the wildcard type. */
  private static boolean isMultipartAlternative(MediaType type) {
    return "multipart".equalsIgnoreCase(type.getType())
        && (type.isWildcardSubtype() || "alternative".equalsIgnoreCase(type.getSubtype()));
  }

  private static RuntimeException notSupported(String detail, Set<OutputFormat> offered) {
    return Problems.from(
        List.of(
            new Problem(
                Problem.FORMAT_NOT_SUPPORTED,
                detail
                    + "; the template offers "
                    + String.join(
                        ", ",
                        offered.stream().map(f -> f.id() + " (" + f.mediaType() + ")").toList()),
                null)));
  }
}
