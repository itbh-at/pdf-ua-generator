/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.render;

import at.itbh.pdfuagen.core.ComposedTemplateRepository;
import at.itbh.pdfuagen.core.DocumentRenderer;
import at.itbh.pdfuagen.core.InMemoryTemplateRepository;
import at.itbh.pdfuagen.core.ResourceLimits;
import at.itbh.pdfuagen.core.TemplateRepository;
import at.itbh.pdfuagen.core.schema.LayoutDescriptor;
import at.itbh.pdfuagen.server.ServerConfig;
import at.itbh.pdfuagen.server.store.TemplateStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The renderer, the revisions it works on, and the worker pool it runs in.
 *
 * <p>Revisions are immutable and addressed by content hash, so their files are cached under it
 * ({@code template-revisions}, sized by {@code quarkus.cache.caffeine}) and read from the database
 * again only after eviction. What the renderer derives from those files — parsed templates, font
 * metrics, schemas — it caches itself under {@link TemplateRepository#contentKey()}, so a
 * repository rebuilt around cached files costs nothing.
 *
 * <p>Rendering runs in a fixed pool with a bounded queue: when the queue is full, {@link #submit}
 * fails at once instead of letting requests time out.
 */
@ApplicationScoped
public class RenderService {

  /** The worker pool is full. */
  public static final class OverloadedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    OverloadedException() {
      super("all rendering threads are busy");
    }
  }

  @Inject ServerConfig config;
  @Inject TemplateStore store;
  @Inject MeterRegistry meters;

  /** Files of a revision by content hash. */
  @Inject
  @CacheName("template-revisions")
  Cache revisions;

  private DocumentRenderer renderer;
  private ThreadPoolExecutor pool;
  private Counter rejected;

  @PostConstruct
  void init() {
    ResourceLimits limits = ResourceLimits.DEFAULT;
    renderer =
        new DocumentRenderer(
            new HttpsFetcher(
                config.fetch().allowedHosts().orElse(List.of()),
                config.fetch().timeout(),
                limits.maxBytes()),
            limits,
            config.render().timeout(),
            config.publicBaseUrl().orElse(null));
    int threads = config.render().threads().orElse(Runtime.getRuntime().availableProcessors());
    int queue = config.render().queue().orElse(2 * threads);
    AtomicInteger counter = new AtomicInteger();
    pool =
        new ThreadPoolExecutor(
            threads,
            threads,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(Math.max(queue, 1)),
            r -> {
              Thread thread = new Thread(r, "render-" + counter.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    // executor.* gauges: busy threads, queue length, remaining capacity.
    ExecutorServiceMetrics.monitor(meters, pool, "render");
    rejected =
        Counter.builder("pdfuagen.render.rejected")
            .description("Render requests answered with 503 because the queue was full")
            .register(meters);
  }

  void shutdown(@Observes ShutdownEvent event) {
    pool.shutdown();
    try {
      // Graceful: running renders finish.
      pool.awaitTermination(config.render().timeout().toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public DocumentRenderer renderer() {
    return renderer;
  }

  /**
   * Runs a task in the worker pool.
   *
   * @throws OverloadedException if every thread is busy and the queue is full
   */
  public <T> CompletableFuture<T> submit(Callable<T> task) {
    CompletableFuture<T> result = new CompletableFuture<>();
    try {
      pool.execute(
          () -> {
            try {
              result.complete(task.call());
            } catch (Throwable e) {
              result.completeExceptionally(e);
            }
          });
    } catch (RejectedExecutionException e) {
      rejected.increment();
      throw new OverloadedException();
    }
    return result;
  }

  /**
   * A revision ready to render: its files from the cache or the database, wrapped in a repository
   * keyed by their content hash. A layout appears under {@code layout/} as well, as for the content
   * that fills it; content that pins a layout revision is composed with it. Status and other
   * metadata always come from the store, never from a cached entry.
   *
   * @param layout the layout revision the content pins, or {@code null}
   */
  public TemplateRepository repository(
      TemplateStore.Revision revision, TemplateStore.Revision layout) {
    TemplateRepository content = files(revision);
    if (revision.files().containsKey(LayoutDescriptor.FILE)) {
      // A layout fills its own areas, so it is composed with itself.
      return new ComposedTemplateRepository(content, content);
    }
    return layout == null ? content : new ComposedTemplateRepository(content, files(layout));
  }

  /**
   * The files of a revision, cached under its content hash. Composing a repository around them is
   * cheap: the renderer keys what it derives on {@link TemplateRepository#contentKey()}, not on the
   * repository object.
   */
  private TemplateRepository files(TemplateStore.Revision revision) {
    // The cache loads once even if several requests miss the same revision at the same time.
    Map<String, byte[]> content =
        revisions
            .<String, Map<String, byte[]>>get(
                revision.sha256(),
                sha -> Map.copyOf(store.files(revision.templateId(), revision.number())))
            .await()
            .indefinitely();
    return new InMemoryTemplateRepository(content, revision.sha256());
  }
}
