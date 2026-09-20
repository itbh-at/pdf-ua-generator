/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import io.quarkus.qute.Engine;
import io.quarkus.qute.EngineBuilder;
import io.quarkus.qute.FragmentSectionHelper;
import io.quarkus.qute.HtmlEscaper;
import io.quarkus.qute.IfSectionHelper;
import io.quarkus.qute.IncludeSectionHelper;
import io.quarkus.qute.InsertSectionHelper;
import io.quarkus.qute.LoopSectionHelper;
import io.quarkus.qute.NamespaceResolver;
import io.quarkus.qute.Results;
import io.quarkus.qute.SetSectionHelper;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.TemplateLocator;
import io.quarkus.qute.UserTagSectionHelper;
import io.quarkus.qute.ValueResolver;
import io.quarkus.qute.ValueResolvers;
import io.quarkus.qute.Variant;
import io.quarkus.qute.WhenSectionHelper;
import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The one place where the Qute engine is configured, so the CLI and the server render identically.
 *
 * <p>Deliberately left out: the reflection resolver (templates cannot call Java methods), {@code
 * raw} (data cannot inject markup), {@code #eval} (data cannot become template code), {@code
 * #cache} (would keep output of one render for the next) and {@code #with} (its names cannot be
 * resolved without the data, so no schema could be derived; use {@code #let}).
 */
final class QuteEngines {

  static final String XHTML = "application/xhtml+xml";
  static final Variant XHTML_VARIANT = Variant.forContentType(XHTML);
  static final Variant TEXT_VARIANT = Variant.forContentType(Variant.TEXT_PLAIN);

  /** Namespace of facts about the rendered document: {@code {doc:lang}}. */
  public static final String DOCUMENT = "doc";

  /** Directory of a layout's components. */
  static final String COMPONENTS = "components/";

  private QuteEngines() {}

  static Engine create(TemplateRepository repository, Duration timeout) {
    EngineBuilder builder = Engine.builder();
    // Components of the layout: {#box title='…'}…{/box} renders layout/components/box.xhtml.
    String prefix = Layouts.prefix(repository).orElse("");
    Layouts.descriptor(repository)
        .ifPresent(
            layout ->
                layout
                    .components()
                    .keySet()
                    .forEach(
                        name ->
                            builder.addSectionHelper(
                                new UserTagSectionHelper.Factory(
                                    name, prefix + COMPONENTS + name + ".xhtml"))));
    return builder
        .addNamespaceResolver(Messages.resolver(repository, prefix))
        .addNamespaceResolver(document())
        .addSectionHelpers(
            new IfSectionHelper.Factory(),
            new LoopSectionHelper.Factory(),
            new SetSectionHelper.Factory(),
            new WhenSectionHelper.Factory(),
            new IncludeSectionHelper.Factory(),
            new InsertSectionHelper.Factory(),
            new FragmentSectionHelper.Factory())
        .addValueResolvers(
            ValueResolvers.mapperResolver(),
            ValueResolvers.mapEntryResolver(),
            ValueResolvers.collectionResolver(),
            ValueResolvers.listResolver(),
            ValueResolvers.thisResolver(),
            ValueResolvers.orResolver(),
            ValueResolvers.orEmpty(),
            ValueResolvers.trueResolver(),
            ValueResolvers.logicalAndResolver(),
            ValueResolvers.logicalOrResolver(),
            ValueResolvers.arrayResolver(),
            ValueResolvers.numberValueResolver(),
            ValueResolvers.plusResolver(),
            ValueResolvers.minusResolver(),
            ValueResolvers.modResolver(),
            ValueResolvers.equalsResolver(),
            ValueResolvers.mapResolver())
        .addValueResolvers(Formatters.resolvers().toArray(ValueResolver[]::new))
        .addResultMapper(new HtmlEscaper(List.of(XHTML, "text/html")))
        .addLocator(locator(repository))
        .strictRendering(true)
        .removeStandaloneLines(true)
        .timeout(timeout.toMillis())
        .build();
  }

  /** {@code {doc:lang}}: the BCP 47 tag of the rendered variant, e.g. for {@code <html lang>}. */
  private static NamespaceResolver document() {
    return NamespaceResolver.builder(DOCUMENT)
        .resolveAsync(
            ctx -> {
              if (!ctx.getName().equals("lang")) {
                return Results.notFound(ctx);
              }
              Object locale = ctx.getAttribute(TemplateInstance.LOCALE);
              String tag =
                  locale instanceof Locale l
                      ? l.toLanguageTag()
                      : locale == null ? "und" : locale.toString();
              return CompletableFuture.completedFuture(tag);
            })
        .build();
  }

  private static TemplateLocator locator(TemplateRepository repository) {
    // The engine is cached under the repository's content key, not under the repository, so it
    // keeps the files it was built from; the cache drops both together.
    return id ->
        repository
            .template(id)
            .map(
                source ->
                    new TemplateLocator.TemplateLocation() {
                      @Override
                      public java.io.Reader read() {
                        return new StringReader(source);
                      }

                      @Override
                      public Optional<Variant> getVariant() {
                        // Plain-text templates (<name>.txt) are not HTML-escaped.
                        return Optional.of(id.endsWith(".txt") ? TEXT_VARIANT : XHTML_VARIANT);
                      }
                    });
  }
}
