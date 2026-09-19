/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class RenderSecurityTest {

  private static final String PAGE =
      """
      <html lang="en"><head><title>T</title></head><body>%s</body></html>\
      """;

  private final DocumentRenderer renderer = new DocumentRenderer();

  private static RenderRequest request(
      String body,
      Map<String, Object> data,
      Map<String, byte[]> attachments,
      MapTemplateRepository repository) {
    repository.template("t", PAGE.formatted(body));
    return new RenderRequest("t", repository, data, attachments);
  }

  private static RenderRequest request(String body) {
    return request(body, Map.of(), Map.of(), new MapTemplateRepository());
  }

  private String problemTypes(RenderRequest request, OutputFormat format) {
    RenderException e = assertThrows(RenderException.class, () -> renderer.render(request, format));
    return e.problems().stream()
        .map(Problem::type)
        .distinct()
        .sorted()
        .reduce((a, b) -> a + "," + b)
        .orElse("");
  }

  @Test
  void dataIsEscaped() throws Exception {
    String out =
        renderer.renderSource(
            request("<p>{v}</p>", Map.of("v", "<b>x</b>"), Map.of(), new MapTemplateRepository()));
    assertTrue(out.contains("&lt;b&gt;x&lt;/b&gt;"), out);
  }

  @Test
  void rawIsNotAvailable() {
    assertThrows(
        RenderException.class,
        () ->
            renderer.renderSource(
                request(
                    "<p>{v.raw}</p>",
                    Map.of("v", "<b>x</b>"),
                    Map.of(),
                    new MapTemplateRepository())));
  }

  @Test
  void evalIsNotAvailable() {
    assertThrows(
        RenderException.class,
        () ->
            renderer.renderSource(
                request(
                    "<p>{#eval v /}</p>",
                    Map.of("v", "{1}"),
                    Map.of(),
                    new MapTemplateRepository())));
  }

  @Test
  void missingValueFailsInsteadOfRenderingEmpty() {
    assertThrows(RenderException.class, () -> renderer.renderSource(request("<p>{missing}</p>")));
  }

  @Test
  void fileUrisAreRejected() {
    assertEquals(
        Problem.RESOURCE_REJECTED,
        problemTypes(request("<img src=\"file:///etc/passwd\" alt=\"x\"/>"), OutputFormat.PDF));
    assertEquals(
        Problem.RESOURCE_REJECTED,
        problemTypes(request("<img src=\"file:///etc/passwd\" alt=\"x\"/>"), OutputFormat.XHTML));
  }

  @Test
  void httpAndExternalHttpsAreRejectedWithoutFetcher() {
    assertEquals(
        Problem.RESOURCE_REJECTED,
        problemTypes(
            request("<img src=\"http://example.com/a.png\" alt=\"x\"/>"), OutputFormat.PDF));
    assertEquals(
        Problem.RESOURCE_REJECTED,
        problemTypes(
            request("<img src=\"https://example.com/a.png\" alt=\"x\"/>"), OutputFormat.PDF));
  }

  @Test
  void dataUrisAreRejected() {
    assertEquals(
        Problem.RESOURCE_REJECTED,
        problemTypes(
            request("<img src=\"data:image/png;base64,iVBORw0KGgo=\" alt=\"x\"/>"),
            OutputFormat.PDF));
  }

  @Test
  void pathTraversalIsRejected() {
    assertEquals(
        Problem.RESOURCE_REJECTED,
        problemTypes(request("<img src=\"../../secret.png\" alt=\"x\"/>"), OutputFormat.XHTML));
  }

  @Test
  void missingAttachmentIsReported() {
    assertEquals(
        Problem.ATTACHMENT_MISSING,
        problemTypes(request("<img src=\"attachment:photo\" alt=\"x\"/>"), OutputFormat.PDF));
  }

  @Test
  void attachmentsMustBeImages() {
    RenderRequest r =
        request(
            "<img src=\"attachment:photo\" alt=\"x\"/>",
            Map.of(),
            Map.of("photo", "body { color: red }".getBytes(StandardCharsets.UTF_8)),
            new MapTemplateRepository());
    assertEquals(Problem.IMAGE_REJECTED, problemTypes(r, OutputFormat.XHTML));
  }

  @Test
  void gifIsRejected() {
    MapTemplateRepository repo =
        new MapTemplateRepository()
            .resource("a.gif", "GIF89a....".getBytes(StandardCharsets.ISO_8859_1));
    assertEquals(
        Problem.IMAGE_REJECTED,
        problemTypes(
            request("<img src=\"a.gif\" alt=\"x\"/>", Map.of(), Map.of(), repo), OutputFormat.PDF));
  }

  @Test
  void oversizedImageIsRejectedBeforeDecoding() throws Exception {
    MapTemplateRepository repo = new MapTemplateRepository().resource("big.png", png(300, 10));
    DocumentRenderer strict =
        new DocumentRenderer(
            ResourceFetcher.NONE,
            new ResourceLimits(1_000_000, 200, 1_000_000),
            java.time.Duration.ofSeconds(10));
    RenderException e =
        assertThrows(
            RenderException.class,
            () ->
                strict.render(
                    request("<img src=\"big.png\" alt=\"x\"/>", Map.of(), Map.of(), repo),
                    OutputFormat.PDF));
    assertEquals(Problem.IMAGE_REJECTED, e.problems().getFirst().type());
  }

  @Test
  void attachedPngIsEmbedded() throws Exception {
    RenderRequest r =
        request(
            "<img src=\"attachment:photo\" alt=\"A photo\"/>",
            Map.of(),
            Map.of("photo", png(4, 4)),
            new MapTemplateRepository());
    String xhtml =
        new String(renderer.render(r, OutputFormat.XHTML).content(), StandardCharsets.UTF_8);
    assertTrue(xhtml.contains("src=\"data:image/png;base64,"), xhtml);
  }

  @Test
  void rendererWarningsAreReturned() throws Exception {
    Rendered pdf = renderer.render(request("<p>x</p>"), OutputFormat.PDF);
    assertTrue(
        pdf.warnings().stream().anyMatch(w -> w.contains("description")),
        pdf.warnings().toString());
  }

  @Test
  void externalEntitiesAreNotResolved() throws Exception {
    RenderRequest r =
        new RenderRequest(
            "t",
            new MapTemplateRepository()
                .template(
                    "t",
                    "<!DOCTYPE html [<!ENTITY x SYSTEM"
                        + " \"file:///etc/passwd\">]><html><body><p>&x;</p></body></html>"),
            Map.of(),
            Map.of());
    String xhtml =
        new String(renderer.render(r, OutputFormat.XHTML).content(), StandardCharsets.UTF_8);
    assertFalse(xhtml.contains("root:"), xhtml);
  }

  private static byte[] png(int width, int height) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
    return out.toByteArray();
  }
}
