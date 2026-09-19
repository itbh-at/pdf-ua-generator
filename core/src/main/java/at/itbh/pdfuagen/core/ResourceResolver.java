/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceType;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves and loads every resource a template references, for one render.
 *
 * <p>Allowed are template resources ({@code template:/…}, what relative URIs resolve to), request
 * attachments ({@code attachment:<name>}, images only) and {@code https} URLs the {@link
 * ResourceFetcher} accepts. Everything else — {@code file:}, {@code http:}, {@code jar:}, {@code
 * data:}, … — is rejected and recorded as a {@link Problem}.
 */
final class ResourceResolver {

  static final String BASE = "template:/";
  private static final Set<ExternalResourceType> ALLOWED_TYPES =
      Set.of(
          ExternalResourceType.FONT,
          ExternalResourceType.CSS,
          ExternalResourceType.IMAGE_RASTER,
          ExternalResourceType.XML_SVG,
          ExternalResourceType.SVG_BINARY);

  /** A loaded resource and its detected kind. */
  record Resource(String uri, byte[] bytes, ImageGuard.Kind kind) {}

  private final RenderRequest request;
  private final ResourceFetcher fetcher;
  private final ResourceLimits limits;
  private final List<Problem> problems = Collections.synchronizedList(new ArrayList<>());

  ResourceResolver(RenderRequest request, ResourceFetcher fetcher, ResourceLimits limits) {
    this.request = request;
    this.fetcher = fetcher;
    this.limits = limits;
  }

  List<Problem> problems() {
    return List.copyOf(problems);
  }

  /**
   * Resolves {@code uri} against {@code base}. Also used for link targets ({@code mailto:}, {@code
   * http:}), so it records no problems; whether a resource may be loaded is decided by {@link
   * #allow} and {@link #load}.
   */
  String resolveUri(String base, String uri) {
    if (uri == null || uri.isBlank()) {
      return null;
    }
    String trimmed = uri.strip();
    try {
      URI parsed = new URI(trimmed);
      if (parsed.isAbsolute()) {
        return trimmed;
      }
      return new URI(base == null || base.isBlank() ? BASE : base).resolve(parsed).toString();
    } catch (URISyntaxException e) {
      return null;
    }
  }

  /** Access control for openhtmltopdf: only fonts, stylesheets and images from allowed sources. */
  boolean allow(String uri, ExternalResourceType type) {
    if (!ALLOWED_TYPES.contains(type)) {
      problems.add(
          new Problem(
              Problem.RESOURCE_REJECTED,
              "resource type " + type + " is not allowed",
              abbreviate(uri)));
      return false;
    }
    String scheme = scheme(uri);
    if (scheme.equals("template") || scheme.equals("attachment") || scheme.equals("https")) {
      return true;
    }
    String detail =
        scheme.equals("data")
            ? "data URIs are not allowed; use a template asset or an attachment"
            : "scheme '" + scheme + "' is not allowed for resources";
    problems.add(new Problem(Problem.RESOURCE_REJECTED, detail, abbreviate(uri)));
    return false;
  }

  private static String scheme(String uri) {
    int colon = uri == null ? -1 : uri.indexOf(':');
    return colon <= 0 ? "" : uri.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
  }

  /** Stream factory for openhtmltopdf. */
  FSStream stream(String uri) {
    Optional<Resource> resource = load(uri);
    return new FSStream() {
      @Override
      public InputStream getStream() {
        return resource.<InputStream>map(r -> new ByteArrayInputStream(r.bytes())).orElse(null);
      }

      @Override
      public Reader getReader() {
        return resource
            .<Reader>map(
                r ->
                    new InputStreamReader(
                        new ByteArrayInputStream(r.bytes()), StandardCharsets.UTF_8))
            .orElse(null);
      }
    };
  }

  /** Loads and checks a resolved resource; records a problem and returns empty on failure. */
  Optional<Resource> load(String uri) {
    URI parsed;
    try {
      parsed = new URI(uri);
    } catch (URISyntaxException e) {
      problems.add(new Problem(Problem.RESOURCE_REJECTED, "invalid URI", abbreviate(uri)));
      return Optional.empty();
    }
    String scheme =
        parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(java.util.Locale.ROOT);
    Optional<byte[]> bytes =
        switch (scheme) {
          case "template" -> loadTemplateResource(parsed, uri);
          case "attachment" -> loadAttachment(parsed, uri);
          case "https" -> loadExternal(parsed, uri);
          default -> {
            problems.add(
                new Problem(
                    Problem.RESOURCE_REJECTED,
                    "scheme '" + scheme + "' is not allowed",
                    abbreviate(uri)));
            yield Optional.empty();
          }
        };
    return bytes.flatMap(b -> check(uri, b, scheme.equals("attachment")));
  }

  private Optional<byte[]> loadTemplateResource(URI uri, String raw) {
    String path = uri.getPath() == null ? "" : uri.getPath();
    Optional<byte[]> bytes =
        ResourcePaths.normalize(path.startsWith("/") ? path.substring(1) : path)
            .flatMap(request.repository()::resource);
    if (bytes.isEmpty()) {
      problems.add(new Problem(Problem.RESOURCE_REJECTED, "template resource not found", raw));
    }
    return bytes;
  }

  private Optional<byte[]> loadAttachment(URI uri, String raw) {
    byte[] bytes = request.attachments().get(uri.getSchemeSpecificPart());
    if (bytes == null) {
      problems.add(
          new Problem(
              Problem.ATTACHMENT_MISSING,
              "attachment '" + uri.getSchemeSpecificPart() + "' was not provided",
              raw));
    }
    return Optional.ofNullable(bytes);
  }

  private Optional<byte[]> loadExternal(URI uri, String raw) {
    Optional<byte[]> bytes = fetcher.fetch(uri);
    if (bytes.isEmpty()) {
      problems.add(
          new Problem(
              Problem.RESOURCE_REJECTED, "external resource not allowed or not available", raw));
    }
    return bytes;
  }

  private Optional<Resource> check(String uri, byte[] bytes, boolean imageOnly) {
    if (bytes.length > limits.maxBytes()) {
      problems.add(
          new Problem(
              Problem.RESOURCE_REJECTED,
              "resource of " + bytes.length + " bytes exceeds the limit of " + limits.maxBytes(),
              uri));
      return Optional.empty();
    }
    ImageGuard.Kind kind = ImageGuard.detect(bytes);
    if (kind == ImageGuard.Kind.FORBIDDEN_IMAGE) {
      problems.add(
          new Problem(Problem.IMAGE_REJECTED, "only PNG, JPEG and SVG images are allowed", uri));
      return Optional.empty();
    }
    if (imageOnly && kind == ImageGuard.Kind.OTHER) {
      problems.add(
          new Problem(Problem.IMAGE_REJECTED, "attachments must be PNG, JPEG or SVG images", uri));
      return Optional.empty();
    }
    if (kind == ImageGuard.Kind.PNG || kind == ImageGuard.Kind.JPEG) {
      Optional<String> violation = ImageGuard.checkDimensions(bytes, limits);
      if (violation.isPresent()) {
        problems.add(new Problem(Problem.IMAGE_REJECTED, violation.get(), uri));
        return Optional.empty();
      }
    }
    return Optional.of(new Resource(uri, bytes, kind));
  }

  private static String abbreviate(String uri) {
    return uri.length() <= 120 ? uri : uri.substring(0, 117) + "...";
  }
}
