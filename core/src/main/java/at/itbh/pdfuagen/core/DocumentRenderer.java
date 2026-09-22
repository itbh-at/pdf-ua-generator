/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import at.itbh.pdfuagen.core.model.AccessibilityChecker;
import at.itbh.pdfuagen.core.model.DocumentModel;
import at.itbh.pdfuagen.core.model.ModelBuilder;
import at.itbh.pdfuagen.core.model.PageBoxes;
import at.itbh.pdfuagen.core.schema.DataSchema;
import at.itbh.pdfuagen.core.schema.LayoutRules;
import at.itbh.pdfuagen.core.writer.CssRules;
import at.itbh.pdfuagen.core.writer.DocxWriter;
import at.itbh.pdfuagen.core.writer.EmailHtmlWriter;
import at.itbh.pdfuagen.core.writer.OdtWriter;
import at.itbh.pdfuagen.core.writer.OfficeTemplate;
import at.itbh.pdfuagen.core.writer.TextWriter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import org.w3c.dom.Document;

/**
 * Renders a template with data: Qute runs once, every output format is produced from the resulting
 * XHTML.
 *
 * <p>Thread-safe. Parsed templates, font metrics and schemas are cached under {@link
 * TemplateRepository#contentKey()}, so a repository rebuilt from the same files — for instance
 * after its files came back from a cache — reuses them instead of parsing again.
 */
public final class DocumentRenderer {

  public static final String PRODUCER = "itbh.at PDF UA Generator";
  static final float PDF_VERSION = 1.7f;

  /**
   * Templates kept parsed. Each entry holds one Qute engine with the parsed templates of a
   * revision, its font metrics and its schemas; the least recently used are dropped and derived
   * again on the next render.
   */
  private static final int MAX_PARSED_REPOSITORIES = 256;

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

  private record RepositoryState(
      Engine engine,
      FSCacheEx<String, FSCacheValue> fontMetrics,
      ConcurrentMap<String, TemplateInspection> inspections) {}

  /** Stylesheet a layout provides for email HTML. */
  public static final String EMAIL_CSS = "email.css";

  private final ResourceFetcher fetcher;
  private final ResourceLimits limits;
  private final Duration timeout;
  private final URI publicBaseUrl;
  private final Cache<String, RepositoryState> states =
      Caffeine.newBuilder().maximumSize(MAX_PARSED_REPOSITORIES).build();

  /** Renderer without external resources, default limits and a 30 s template timeout. */
  public DocumentRenderer() {
    this(ResourceFetcher.NONE, ResourceLimits.DEFAULT, Duration.ofSeconds(30));
  }

  public DocumentRenderer(ResourceFetcher fetcher, ResourceLimits limits, Duration timeout) {
    this(fetcher, limits, timeout, null);
  }

  /**
   * @param publicBaseUrl base of the public asset URLs ({@code <base>/assets/<sha256>}) used in
   *     email HTML, or {@code null} if template assets cannot be referenced publicly
   */
  public DocumentRenderer(
      ResourceFetcher fetcher, ResourceLimits limits, Duration timeout, URI publicBaseUrl) {
    this.fetcher = fetcher;
    this.limits = limits;
    this.timeout = timeout;
    this.publicBaseUrl = publicBaseUrl;
  }

  /**
   * Renders the template with Qute only; the result is the XHTML every format starts from.
   *
   * <p>If the template has a descriptor, the template must pass its schema checks and the data is
   * validated against the schema first. Values are formatted for the language of the variant.
   */
  public String renderSource(RenderRequest request) throws RenderException {
    TemplateInspection inspection = inspection(request.repository(), request.templateId());
    if (inspection.descriptorFound()) {
      failOnProblems(inspection.problems());
      failOnProblems(inspection.schema().validate(request.data()));
    }
    return qute(request, request.templateId(), language(inspection, request.templateId()));
  }

  private String qute(RenderRequest request, String templateId, Locale locale)
      throws RenderException {
    Engine engine = state(request.repository()).engine();
    try {
      Template template = engine.getTemplate(templateId);
      if (template == null) {
        throw new RenderException(
            List.of(new Problem(Problem.TEMPLATE_ERROR, "template not found", templateId)));
      }
      return template.data(request.data()).setLocale(locale).render();
    } catch (TemplateException e) {
      throw new RenderException(
          new Problem(
              Problem.TEMPLATE_ERROR, e.getMessage(), TemplateInspection.location(e, templateId)),
          e);
    }
  }

  /**
   * The data schema of a template, shared by all its language variants.
   *
   * @param templateId the template or one of its variants
   * @throws RenderException if the template has no descriptor, or the template, a variant or the
   *     descriptor has problems
   */
  public DataSchema schema(TemplateRepository repository, String templateId)
      throws RenderException {
    TemplateInspection inspection = inspection(repository, templateId);
    if (!inspection.descriptorFound()) {
      throw new RenderException(
          List.of(
              new Problem(
                  Problem.TEMPLATE_ERROR,
                  "the template has no field definitions; add "
                      + LanguageVariants.descriptorPath(inspection.baseId()),
                  inspection.baseId())));
    }
    failOnProblems(inspection.problems());
    return inspection.schema();
  }

  /** The formats a template offers: those of its descriptor, or all without a descriptor. */
  public Set<OutputFormat> formats(TemplateRepository repository, String templateId) {
    TemplateInspection inspection = inspection(repository, templateId);
    return inspection.descriptor() == null
        ? EnumSet.allOf(OutputFormat.class)
        : inspection.descriptor().formats();
  }

  /** Fields the template defines but never reads; empty if the template cannot be inspected. */
  public List<String> schemaWarnings(TemplateRepository repository, String templateId) {
    return inspection(repository, templateId).warnings();
  }

  /**
   * Picks the language variant of a template for the accepted languages (RFC 4647 lookup). Without
   * a match, the default variant is returned.
   *
   * @param templateId id of the default variant
   * @param ranges accepted languages, e.g. {@link LanguageVariants#ranges} of {@code
   *     Accept-Language}
   * @return the id of the variant to render
   */
  public String selectVariant(
      TemplateRepository repository, String templateId, List<Locale.LanguageRange> ranges) {
    TemplateInspection inspection = inspection(repository, templateId);
    Locale defaultLanguage =
        inspection.descriptor() == null ? null : inspection.descriptor().language();
    return LanguageVariants.select(defaultLanguage, inspection.variants().keySet(), ranges)
        .map(inspection.variants()::get)
        .orElse(inspection.baseId());
  }

  /** Ids of all variants of a template, the default variant first. */
  public List<String> variants(TemplateRepository repository, String templateId) {
    TemplateInspection inspection = inspection(repository, templateId);
    List<String> ids = new ArrayList<>();
    ids.add(inspection.baseId());
    inspection.variants().keySet().stream()
        .sorted()
        .map(inspection.variants()::get)
        .forEach(ids::add);
    return ids;
  }

  /**
   * The language a template variant is written in: the tag of a variant, the descriptor's language
   * for the default variant, or {@link Locale#ROOT} if the template declares none.
   */
  public Locale language(TemplateRepository repository, String templateId) {
    return language(inspection(repository, templateId), templateId);
  }

  private static Locale language(TemplateInspection inspection, String templateId) {
    String tag = LanguageVariants.parse(templateId).tag();
    if (tag != null) {
      return Locale.forLanguageTag(tag);
    }
    return inspection.descriptor() != null && inspection.descriptor().language() != null
        ? inspection.descriptor().language()
        : Locale.ROOT;
  }

  private TemplateInspection inspection(TemplateRepository repository, String templateId) {
    RepositoryState state = state(repository);
    String baseId = LanguageVariants.parse(templateId).baseId();
    return state
        .inspections()
        .computeIfAbsent(baseId, id -> TemplateInspection.inspect(state.engine(), repository, id));
  }

  /**
   * Renders a template into one format.
   *
   * @throws RenderException with {@link Problem#FORMAT_NOT_SUPPORTED} if the template's descriptor
   *     does not offer the format, or any problem of {@link #renderSource}
   */
  public Rendered render(RenderRequest request, OutputFormat format) throws RenderException {
    Set<OutputFormat> offered = formats(request.repository(), request.templateId());
    if (!offered.contains(format)) {
      throw new RenderException(
          List.of(
              new Problem(
                  Problem.FORMAT_NOT_SUPPORTED,
                  "the template does not offer "
                      + format.id()
                      + "; it offers "
                      + String.join(", ", offered.stream().map(OutputFormat::id).toList()),
                  request.templateId())));
    }
    String source = renderSource(request);
    Document document = SecureXml.parse(source, request.templateId());
    ResourceResolver resolver = new ResourceResolver(request, fetcher, limits);
    return switch (format) {
      case PDF -> renderPdf(request, document, resolver);
      case XHTML -> renderXhtml(document, resolver);
      case TEXT -> renderText(request, document, resolver);
      case EMAIL_HTML -> renderEmail(request, document, resolver);
      case DOCX, ODT -> renderOffice(request, format, document, resolver);
    };
  }

  /** Builds and checks the format-neutral model that DOCX, ODT, text and email are written from. */
  private static DocumentModel model(Document document, ResourceResolver resolver)
      throws RenderException {
    List<Problem> problems = new ArrayList<>();
    CssRules catalog = CssRules.parse(stylesheets(document, resolver));
    ModelBuilder builder =
        new ModelBuilder(
            reference -> {
              String uri = resolver.resolveUri(ResourceResolver.BASE, reference);
              if (uri == null
                  || !resolver.allow(
                      uri,
                      com.openhtmltopdf.outputdevice.helper.ExternalResourceType.IMAGE_RASTER)) {
                return Optional.empty();
              }
              return resolver
                  .load(uri)
                  .flatMap(
                      r -> {
                        if (r.kind().mediaType == null) {
                          problems.add(
                              new Problem(
                                  Problem.IMAGE_REJECTED,
                                  "only PNG, JPEG and SVG images are allowed",
                                  uri));
                          return Optional.empty();
                        }
                        return Optional.of(
                            new ModelBuilder.LoadedImage(uri, r.bytes(), r.kind().mediaType));
                      });
            },
            (element, classes) -> catalog.style(element, classes.toArray(new String[0])));
    DocumentModel model = builder.build(document);
    PageBoxes.Result pageBoxes = PageBoxes.parse(stylesheets(document, resolver));
    model = model.withPageBoxes(pageBoxes.header(), pageBoxes.footer());
    problems.addAll(pageBoxes.problems());
    problems.addAll(resolver.problems());
    problems.addAll(builder.problems());
    problems.addAll(AccessibilityChecker.check(model));
    failOnProblems(problems);
    return model;
  }

  private static final java.util.regex.Pattern FONT_FACE =
      java.util.regex.Pattern.compile("@font-face\\s*\\{([^}]*)\\}");
  private static final java.util.regex.Pattern FONT_FAMILY =
      java.util.regex.Pattern.compile("font-family\\s*:\\s*['\"]?([^;'\"}]+)['\"]?");
  private static final java.util.regex.Pattern FONT_SRC =
      java.util.regex.Pattern.compile("url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)");

  /**
   * The font files of the document's {@code @font-face} rules, by family: the first source of the
   * first rule of each family, resolved relative to its stylesheet.
   */
  private static Map<String, byte[]> fonts(Document document, ResourceResolver resolver) {
    Map<String, byte[]> fonts = new java.util.LinkedHashMap<>();
    org.w3c.dom.NodeList all = document.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      org.w3c.dom.Element element = (org.w3c.dom.Element) all.item(i);
      String name =
          (element.getLocalName() != null ? element.getLocalName() : element.getTagName())
              .toLowerCase(java.util.Locale.ROOT);
      if (name.equals("style")) {
        fontFaces(element.getTextContent(), ResourceResolver.BASE, resolver, fonts);
      } else if (name.equals("link")
          && "stylesheet".equalsIgnoreCase(element.getAttribute("rel").strip())) {
        String uri = resolver.resolveUri(ResourceResolver.BASE, element.getAttribute("href"));
        if (uri != null) {
          resolver
              .load(uri)
              .ifPresent(
                  r ->
                      fontFaces(
                          new String(r.bytes(), StandardCharsets.UTF_8), uri, resolver, fonts));
        }
      }
    }
    return fonts;
  }

  private static void fontFaces(
      String css, String base, ResourceResolver resolver, Map<String, byte[]> fonts) {
    java.util.regex.Matcher face = FONT_FACE.matcher(css);
    while (face.find()) {
      java.util.regex.Matcher family = FONT_FAMILY.matcher(face.group(1));
      java.util.regex.Matcher src = FONT_SRC.matcher(face.group(1));
      if (family.find() && src.find() && !fonts.containsKey(family.group(1).strip())) {
        String uri = resolver.resolveUri(base, src.group(1));
        if (uri != null) {
          resolver.load(uri).ifPresent(r -> fonts.put(family.group(1).strip(), r.bytes()));
        }
      }
    }
  }

  /** The text of all stylesheets of the document: {@code <style>} and linked template CSS. */
  private static String stylesheets(Document document, ResourceResolver resolver) {
    StringBuilder css = new StringBuilder();
    org.w3c.dom.NodeList all = document.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      org.w3c.dom.Element element = (org.w3c.dom.Element) all.item(i);
      String name =
          (element.getLocalName() != null ? element.getLocalName() : element.getTagName())
              .toLowerCase(java.util.Locale.ROOT);
      if (name.equals("style")) {
        css.append(element.getTextContent()).append('\n');
      } else if (name.equals("link")
          && "stylesheet".equalsIgnoreCase(element.getAttribute("rel").strip())) {
        String uri = resolver.resolveUri(ResourceResolver.BASE, element.getAttribute("href"));
        if (uri != null) {
          resolver
              .load(uri)
              .ifPresent(
                  r -> css.append(new String(r.bytes(), StandardCharsets.UTF_8)).append('\n'));
        }
      }
    }
    return css.toString();
  }

  private Rendered renderOffice(
      RenderRequest request, OutputFormat format, Document document, ResourceResolver resolver)
      throws RenderException {
    DocumentModel model = model(document, resolver);
    // A layout's Word and ODF templates give styles and page setup; its fonts are embedded.
    String prefix = Layouts.prefix(request.repository()).orElse(null);
    OfficeTemplate template =
        prefix == null
            ? OfficeTemplate.NONE
            : new OfficeTemplate(
                request.repository().resource(prefix + LayoutRules.DOTX).orElse(null),
                request.repository().resource(prefix + LayoutRules.OTT).orElse(null),
                fonts(document, resolver));
    try {
      byte[] bytes =
          format == OutputFormat.DOCX
              ? DocxWriter.write(model, template)
              : OdtWriter.write(model, template);
      return new Rendered(format, bytes, List.of());
    } catch (IOException e) {
      throw new RenderException(
          new Problem(
              Problem.TEMPLATE_ERROR, format + " cannot be written: " + e.getMessage(), null),
          e);
    }
  }

  private Rendered renderText(RenderRequest request, Document document, ResourceResolver resolver)
      throws RenderException {
    // A template may provide its own plain-text version: <name>.txt next to <name>.xhtml.
    String textId = textTemplateId(request.templateId());
    if (request.repository().template(textId).isPresent()) {
      String text = qute(request, textId, language(request.repository(), request.templateId()));
      return new Rendered(OutputFormat.TEXT, text.getBytes(StandardCharsets.UTF_8), List.of());
    }
    DocumentModel model = model(document, resolver);
    return new Rendered(
        OutputFormat.TEXT, TextWriter.write(model).getBytes(StandardCharsets.UTF_8), List.of());
  }

  private Rendered renderEmail(RenderRequest request, Document document, ResourceResolver resolver)
      throws RenderException {
    // With a layout, email.css is the layout's; the content cannot bring its own.
    String cssPath = Layouts.prefix(request.repository()).orElse("") + EMAIL_CSS;
    Optional<byte[]> cssBytes = request.repository().resource(cssPath);
    if (cssBytes.isEmpty()) {
      throw new RenderException(
          List.of(
              new Problem(
                  Problem.TEMPLATE_ERROR,
                  "the template provides no " + cssPath + " for email HTML",
                  request.templateId())));
    }
    DocumentModel model = model(document, resolver);
    List<Problem> problems = new ArrayList<>();
    byte[] html;
    try {
      html =
          EmailHtmlWriter.write(
              model,
              CssRules.parse(new String(cssBytes.get(), StandardCharsets.UTF_8)),
              image -> publicUrl(image, request.publicBaseUrl(), problems));
    } catch (IOException e) {
      throw new RenderException(
          new Problem(
              Problem.TEMPLATE_ERROR, "email HTML cannot be written: " + e.getMessage(), null),
          e);
    }
    failOnProblems(problems);
    return new Rendered(OutputFormat.EMAIL_HTML, html, List.of());
  }

  /**
   * Public URL of an image in email HTML: template assets at {@code <base>/assets/<sha256>} (SVG as
   * a PNG rendition, {@code .png}, since many mail clients do not show SVG), external URLs
   * unchanged. Request attachments have no public URL.
   */
  private String publicUrl(DocumentModel.Image image, URI requestBase, List<Problem> problems) {
    URI base = requestBase != null ? requestBase : publicBaseUrl;
    String source = image.source();
    if (source.startsWith("https:")) {
      return source;
    }
    if (source.startsWith("attachment:")) {
      problems.add(
          new Problem(
              Problem.RESOURCE_REJECTED,
              "attachments cannot be used in email HTML; pass an external URL instead",
              source));
      return "";
    }
    if (base == null) {
      problems.add(
          new Problem(
              Problem.RESOURCE_REJECTED,
              "template assets in email HTML need a public base URL",
              source));
      return "";
    }
    String prefix = base.toString().replaceAll("/+$", "");
    String suffix = "image/svg+xml".equals(image.mediaType()) ? ".png" : "";
    return prefix + "/assets/" + sha256(image.bytes()) + suffix;
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String textTemplateId(String templateId) {
    int dot = templateId.lastIndexOf('.');
    int slash = templateId.lastIndexOf('/');
    return (dot > slash ? templateId.substring(0, dot) : templateId) + ".txt";
  }

  private Rendered renderPdf(RenderRequest request, Document document, ResourceResolver resolver)
      throws RenderException {
    DecorativeImages.toBackgrounds(document, resolver);
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
    return states.get(
        repository.contentKey(),
        key ->
            new RepositoryState(
                QuteEngines.create(repository, timeout),
                new FSDefaultCacheStore(),
                new ConcurrentHashMap<>()));
  }
}
