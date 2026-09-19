/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.api;

import at.itbh.pdfuagen.core.ImageSize;
import at.itbh.pdfuagen.core.writer.Images;
import at.itbh.pdfuagen.server.store.TemplateStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template images by content hash, public and immutable, for email HTML: {@code /assets/<sha256>},
 * and {@code /assets/<sha256>.png} as a PNG rendition of an SVG. Only images are served; templates,
 * descriptors, fonts and example data are not.
 */
@Path("/assets")
public class AssetResource {

  private static final Pattern NAME = Pattern.compile("([0-9a-f]{64})(\\.png)?");
  private static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/svg+xml");

  @Inject TemplateStore store;

  @GET
  @Path("/{name}")
  public Response asset(@PathParam("name") String name) throws IOException {
    Matcher m = NAME.matcher(name);
    if (!m.matches()) {
      throw Problems.notFound("no such asset");
    }
    TemplateStore.Asset asset =
        store
            .asset(m.group(1))
            .filter(a -> IMAGES.contains(a.mediaType()))
            .filter(a -> ImageSize.of(a.content(), a.mediaType()).isPresent())
            .orElseThrow(() -> Problems.notFound("no such asset"));
    byte[] content = asset.content();
    String type = asset.mediaType();
    if (m.group(2) != null) {
      if (!type.equals("image/svg+xml")) {
        throw Problems.notFound("PNG renditions exist only for SVG assets");
      }
      content = Images.svgToPng(content);
      type = "image/png";
    }
    return Response.ok(content)
        .type(type)
        .header("Cache-Control", "public, max-age=31536000, immutable")
        .header("X-Content-Type-Options", "nosniff")
        // An SVG opened directly must not run scripts on this origin.
        .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
        .build();
  }
}
