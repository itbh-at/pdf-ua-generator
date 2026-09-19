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
  @Blocking
  public CompletionStage<Response> render(
      @PathParam("id") String id,
      @QueryParam("format") String format,
      @QueryParam("lang") String lang,
      @Context HttpHeaders headers,
      @Context UriInfo uri,
      byte[] data) {
    return render(targets.published(id), format, lang, headers, uri, data, Map.of());
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
    return renderParts(targets.published(id), format, lang, headers, uri, data, parts);
  }

  /** Renders a specific revision, also a draft, e.g. as a preview. */
  @POST
  @Path("/revisions/{n}/render")
  @Consumes(MediaType.APPLICATION_JSON)
  @Blocking
  public CompletionStage<Response> renderRevision(
      @PathParam("id") String id,
      @PathParam("n") int n,
      @QueryParam("format") String format,
      @QueryParam("lang") String lang,
      @Context HttpHeaders headers,
      @Context UriInfo uri,
      byte[] data) {
    return render(targets.revision(id, n), format, lang, headers, uri, data, Map.of());
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
    return renderParts(targets.revision(id, n), format, lang, headers, uri, data, parts);
  }

  private CompletionStage<Response> renderParts(
      Views.Target target,
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

  private CompletionStage<Response> render(
      Views.Target target,
      String format,
      String lang,
      HttpHeaders headers,
      UriInfo uri,
      byte[] data,
      Map<String, byte[]> attachments) {
    DocumentRenderer renderer = service.renderer();
    var repository = target.repository();
    List<MediaType> acceptable = headers.getAcceptableMediaTypes();
    List<Locale.LanguageRange> ranges = ranges(lang, headers);
    URI publicBase = config.publicBaseUrl().orElse(uri.getBaseUri());
    return Problems.submit(
        service,
        () -> {
          // Inside the pool: the first use of a revision parses its templates.
          OutputFormat outputFormat =
              select(format, acceptable, renderer.formats(repository, Bundle.TEMPLATE));
          String variant = renderer.selectVariant(repository, Bundle.TEMPLATE, ranges);
          Rendered rendered;
          try {
            rendered =
                renderer.render(
                    new RenderRequest(
                        variant,
                        repository,
                        JsonData.parse(new ByteArrayInputStream(data)),
                        attachments,
                        publicBase),
                    outputFormat);
          } catch (RenderException e) {
            throw Problems.from(e.problems());
          }
          Response.ResponseBuilder response =
              Response.ok(rendered.content())
                  .type(
                      outputFormat.mediaType()
                          + (outputFormat.mediaType().startsWith("text/") ? "; charset=utf-8" : ""))
                  .header("Vary", "Accept, Accept-Language")
                  .header(
                      "Content-Disposition",
                      "inline; filename=\""
                          + target.revision().templateId()
                          + "."
                          + outputFormat.extension()
                          + "\"")
                  .header("Template-Revision", target.revision().number());
          Locale language = renderer.language(repository, variant);
          if (!language.equals(Locale.ROOT)) {
            response.header("Content-Language", language.toLanguageTag());
          }
          return response.build();
        });
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

  /**
   * The output format: the {@code format} parameter, otherwise the first acceptable media type the
   * template offers ({@code *}{@code /*} gives the first offered, PDF if offered).
   */
  static OutputFormat select(String format, List<MediaType> acceptable, Set<OutputFormat> offered) {
    if (format != null) {
      OutputFormat requested =
          OutputFormat.of(format)
              .orElseThrow(() -> notSupported("unknown format " + format, offered));
      if (!offered.contains(requested)) {
        throw notSupported("the template does not offer " + format, offered);
      }
      return requested;
    }
    if (acceptable.isEmpty()) {
      return offered.iterator().next();
    }
    for (MediaType type : acceptable) {
      for (OutputFormat candidate : offered) {
        if (type.isCompatible(MediaType.valueOf(candidate.mediaType()))) {
          return candidate;
        }
      }
    }
    throw notSupported("none of the accepted media types is offered", offered);
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
