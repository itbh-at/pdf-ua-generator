/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.render;

import at.itbh.pdfuagen.core.DocumentRenderer;
import at.itbh.pdfuagen.core.InMemoryTemplateRepository;
import at.itbh.pdfuagen.core.ResourceLimits;
import at.itbh.pdfuagen.core.TemplateRepository;
import at.itbh.pdfuagen.server.ServerConfig;
import at.itbh.pdfuagen.server.store.TemplateStore;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
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
 * <p>Revisions are immutable, so a loaded revision is kept as long as the memory budget allows
 * ({@code pdfuagen.render.cache-size}); the least recently used is dropped first and loaded again
 * from the database when needed. Rendering runs in a fixed pool with a bounded queue: when the
 * queue is full, {@link #submit} fails at once instead of letting requests time out.
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

  private record Entry(TemplateRepository repository, long size) {}

  @Inject ServerConfig config;
  @Inject TemplateStore store;

  private DocumentRenderer renderer;
  private ThreadPoolExecutor pool;
  private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(64, 0.75f, true);
  private long cached;

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
      throw new OverloadedException();
    }
    return result;
  }

  /**
   * The files of a revision, ready to render: from memory or loaded from the database. Keyed by the
   * revision's content hash, so a cached entry can never be stale; status and other metadata always
   * come from the store.
   */
  public TemplateRepository repository(TemplateStore.Revision revision) {
    String key = revision.sha256();
    synchronized (cache) {
      Entry entry = cache.get(key);
      if (entry != null) {
        return entry.repository();
      }
    }
    Map<String, byte[]> files = store.files(revision.templateId(), revision.number());
    long size = files.values().stream().mapToLong(b -> b.length).sum();
    TemplateRepository repository = new InMemoryTemplateRepository(files);
    synchronized (cache) {
      Entry existing = cache.putIfAbsent(key, new Entry(repository, size));
      if (existing != null) {
        return existing.repository();
      }
      cached += size;
      var it = cache.entrySet().iterator();
      while (cached > config.render().cacheSize() && cache.size() > 1 && it.hasNext()) {
        var eldest = it.next();
        if (!eldest.getKey().equals(key)) {
          cached -= eldest.getValue().size();
          it.remove();
        }
      }
    }
    return repository;
  }
}
