/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.health;

import at.itbh.pdfuagen.core.JsonData;
import at.itbh.pdfuagen.core.OutputFormat;
import at.itbh.pdfuagen.core.RenderException;
import at.itbh.pdfuagen.core.RenderRequest;
import at.itbh.pdfuagen.core.TemplateRepository;
import at.itbh.pdfuagen.server.render.RenderService;
import at.itbh.pdfuagen.server.store.Bundle;
import at.itbh.pdfuagen.server.store.TemplateStore;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

/**
 * Before the instance reports ready, it loads the latest published revision of every template and
 * renders its example data in every format it offers, so the first real request finds parsed
 * templates, font metrics and a warm JIT.
 */
@Readiness
@ApplicationScoped
public class Warmup implements HealthCheck {

  private static final Logger LOG = Logger.getLogger(Warmup.class);
  private static final URI PLACEHOLDER = URI.create("https://warmup.invalid/");

  @Inject TemplateStore store;
  @Inject RenderService service;

  private volatile boolean done;

  void start(@Observes StartupEvent event) {
    Thread.ofVirtual().name("warmup").start(this::run);
  }

  private void run() {
    long start = System.nanoTime();
    int count = 0;
    try {
      for (TemplateStore.Revision revision : store.latestPublished()) {
        warm(revision);
        count++;
      }
    } catch (RuntimeException e) {
      LOG.warnf("warm-up incomplete: %s", e.getMessage());
    } finally {
      done = true;
      LOG.infof("warm-up: %d templates in %d ms", count, (System.nanoTime() - start) / 1_000_000);
    }
  }

  private void warm(TemplateStore.Revision revision) {
    TemplateStore.Revision layout =
        revision.layoutId() == null
            ? null
            : store.revision(revision.layoutId(), revision.layoutRevision()).orElse(null);
    TemplateRepository repository = service.repository(revision, layout);
    Optional<byte[]> example = repository.resource(Bundle.EXAMPLE);
    if (example.isEmpty()) {
      return;
    }
    Map<String, byte[]> attachments =
        Bundle.exampleAttachments(
            revision.files().keySet(), p -> repository.resource(p).orElseThrow());
    var renderer = service.renderer();
    for (String variant : renderer.variants(repository, Bundle.TEMPLATE)) {
      for (OutputFormat format : renderer.formats(repository, Bundle.TEMPLATE)) {
        try {
          renderer.render(
              new RenderRequest(
                  variant,
                  repository,
                  JsonData.parse(new ByteArrayInputStream(example.get())),
                  format == OutputFormat.EMAIL_HTML ? Map.of() : attachments,
                  PLACEHOLDER),
              format);
        } catch (RenderException e) {
          // Warm-up only; the publish check has already verified the revision.
          LOG.debugf("warm-up of %s %s: %s", revision.templateId(), format.id(), e.getMessage());
        }
      }
    }
  }

  @Override
  public HealthCheckResponse call() {
    return HealthCheckResponse.named("warm-up").status(done).build();
  }
}
