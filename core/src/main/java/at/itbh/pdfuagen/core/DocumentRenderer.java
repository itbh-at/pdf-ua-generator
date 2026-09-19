/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import com.openhtmltopdf.extend.FSCacheEx;
import com.openhtmltopdf.extend.FSCacheValue;
import com.openhtmltopdf.extend.impl.FSDefaultCacheStore;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer.SvgExternalResourceMode;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer.SvgScriptMode;
import com.openhtmltopdf.util.Diagnostic;
import com.openhtmltopdf.util.XRLog;
import com.openhtmltopdf.util.XRLogger;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;
import org.w3c.dom.Document;

/**
 * Renders a template with data: Qute runs once, every output format is produced from the resulting
 * XHTML.
 *
 * <p>Thread-safe. Parsed templates and font metrics are cached per {@link TemplateRepository}
 * instance, so pass the same instance (e.g. a {@link CachingTemplateRepository} of a published
 * revision) to benefit, and a new one when the templates change.
 */
public final class DocumentRenderer {

  public static final String PRODUCER = "itbh.at PDF UA Generator";
  static final float PDF_VERSION = 1.7f;

  static {
    // Renderer messages reach the caller as Rendered.warnings(), not the console:
    // warnings and errors are passed on to the per-render diagnostic consumer,
    // nothing is printed.
    XRLog.setLoggerImpl(
        new XRLogger() {
          @Override
          public void log(String where, Level level, String msg) {}

          @Override
          public void log(String where, Level level, String msg, Throwable th) {}

          @Override
          public void setLevel(String logger, Level level) {}

          @Override
          public boolean isLogLevelEnabled(Diagnostic diagnostic) {
            return diagnostic.getLevel().intValue() >= Level.WARNING.intValue();
          }
        });
  }

  private record RepositoryState(Engine engine, FSCacheEx<String, FSCacheValue> fontMetrics) {}

  private final ResourceFetcher fetcher;
  private final ResourceLimits limits;
  private final Duration timeout;
  private final Map<TemplateRepository, RepositoryState> states =
      Collections.synchronizedMap(new WeakHashMap<>());

  /** Renderer without external resources, default limits and a 30 s template timeout. */
  public DocumentRenderer() {
    this(ResourceFetcher.NONE, ResourceLimits.DEFAULT, Duration.ofSeconds(30));
  }

  public DocumentRenderer(ResourceFetcher fetcher, ResourceLimits limits, Duration timeout) {
    this.fetcher = fetcher;
    this.limits = limits;
    this.timeout = timeout;
  }

  /** Renders the template with Qute only; the result is the XHTML every format starts from. */
  public String renderSource(RenderRequest request) throws RenderException {
    Engine engine = state(request.repository()).engine();
    try {
      Template template = engine.getTemplate(request.templateId());
      if (template == null) {
        throw new RenderException(
            List.of(
                new Problem(Problem.TEMPLATE_ERROR, "template not found", request.templateId())));
      }
      return template.data(request.data()).render();
    } catch (TemplateException e) {
      throw new RenderException(
          new Problem(Problem.TEMPLATE_ERROR, e.getMessage(), location(e, request.templateId())),
          e);
    }
  }

  public Rendered render(RenderRequest request, OutputFormat format) throws RenderException {
    String source = renderSource(request);
    Document document = SecureXml.parse(source, request.templateId());
    ResourceResolver resolver = new ResourceResolver(request, fetcher, limits);
    return switch (format) {
      case PDF -> renderPdf(request, document, resolver);
      case XHTML -> renderXhtml(document, resolver);
    };
  }

  private Rendered renderPdf(RenderRequest request, Document document, ResourceResolver resolver)
      throws RenderException {
    List<String> warnings = new ArrayList<>();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PdfRendererBuilder builder =
        new PdfRendererBuilder()
            .usePdfUaAccessibility(true)
            .usePdfVersion(PDF_VERSION)
            .withProducer(PRODUCER)
            .useSVGDrawer(new BatikSVGDrawer(SvgScriptMode.SECURE, SvgExternalResourceMode.SECURE))
            .useUriResolver(resolver::resolveUri)
            .useHttpStreamImplementation(resolver::stream)
            .useProtocolsStreamImplementation(
                resolver::stream, "template", "attachment", "https", "http", "file", "jar")
            .useExternalResourceAccessControl(
                resolver::allow, ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI)
            .useCacheStore(
                PdfRendererBuilder.CacheStore.PDF_FONT_METRICS,
                state(request.repository()).fontMetrics())
            .withDiagnosticConsumer(
                d -> {
                  if (d.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(d.getFormattedMessage());
                  }
                })
            .withW3cDocument(document, ResourceResolver.BASE)
            .toStream(out);
    try {
      builder.run();
    } catch (IOException | RuntimeException e) {
      List<Problem> problems = new ArrayList<>(resolver.problems());
      problems.add(
          new Problem(
              Problem.TEMPLATE_ERROR,
              "PDF rendering failed: " + e.getMessage(),
              request.templateId()));
      throw new RenderException(problems, e);
    }
    failOnProblems(resolver.problems());
    return new Rendered(OutputFormat.PDF, out.toByteArray(), warnings);
  }

  private static Rendered renderXhtml(Document document, ResourceResolver resolver)
      throws RenderException {
    SelfContainedXhtml inliner = new SelfContainedXhtml(resolver);
    inliner.inline(document);
    failOnProblems(inliner.problems());
    return new Rendered(
        OutputFormat.XHTML,
        SecureXml.serialize(document).getBytes(StandardCharsets.UTF_8),
        List.of());
  }

  private static void failOnProblems(List<Problem> problems) throws RenderException {
    if (!problems.isEmpty()) {
      throw new RenderException(problems.stream().distinct().toList());
    }
  }

  private RepositoryState state(TemplateRepository repository) {
    return states.computeIfAbsent(
        repository,
        r -> new RepositoryState(QuteEngines.create(r, timeout), new FSDefaultCacheStore()));
  }

  private static String location(TemplateException e, String templateId) {
    if (e.getOrigin() == null) {
      return templateId;
    }
    String id =
        e.getOrigin().hasNonGeneratedTemplateId() ? e.getOrigin().getTemplateId() : templateId;
    return id + ", line " + e.getOrigin().getLine();
  }
}
