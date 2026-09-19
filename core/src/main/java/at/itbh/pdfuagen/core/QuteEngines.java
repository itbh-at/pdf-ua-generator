/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import io.quarkus.qute.Engine;
import io.quarkus.qute.FragmentSectionHelper;
import io.quarkus.qute.HtmlEscaper;
import io.quarkus.qute.IfSectionHelper;
import io.quarkus.qute.IncludeSectionHelper;
import io.quarkus.qute.InsertSectionHelper;
import io.quarkus.qute.LoopSectionHelper;
import io.quarkus.qute.SetSectionHelper;
import io.quarkus.qute.TemplateLocator;
import io.quarkus.qute.ValueResolver;
import io.quarkus.qute.ValueResolvers;
import io.quarkus.qute.Variant;
import io.quarkus.qute.WhenSectionHelper;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

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

  private QuteEngines() {}

  static Engine create(TemplateRepository repository, Duration timeout) {
    return Engine.builder()
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

  private static TemplateLocator locator(TemplateRepository repository) {
    // Weak: the engine is cached per repository in a WeakHashMap and must not keep it alive.
    WeakReference<TemplateRepository> reference = new WeakReference<>(repository);
    return id ->
        Optional.ofNullable(reference.get())
            .flatMap(r -> r.template(id))
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
