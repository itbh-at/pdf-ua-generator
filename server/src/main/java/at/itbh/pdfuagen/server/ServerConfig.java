/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Settings of the service, all overridable by environment variables ({@code PDFUAGEN_…}). */
@ConfigMapping(prefix = "pdfuagen")
public interface ServerConfig {

  /**
   * Base of the public asset URLs in email HTML ({@code <base>/assets/<sha256>}). Default: the base
   * URL of the request.
   */
  Optional<URI> publicBaseUrl();

  Render render();

  Fetch fetch();

  Bundle bundle();

  interface Render {
    /** Rendering threads. Default: the number of CPU cores. */
    OptionalInt threads();

    /** Requests waiting for a thread before the service answers 503. Default: 2 × threads. */
    OptionalInt queue();

    /** Maximum time Qute may spend on one template. */
    @WithDefault("30s")
    Duration timeout();

    /**
     * Memory for template revisions kept parsed, in bytes of their files; the least recently used
     * are dropped first and reloaded from the database when needed again.
     */
    @WithDefault("268435456")
    long cacheSize();
  }

  interface Fetch {
    /**
     * Hosts external images may be fetched from, e.g. {@code images.example.org}; {@code
     * *.example.org} matches every subdomain. Empty: no external images.
     */
    Optional<List<String>> allowedHosts();

    @WithDefault("10s")
    Duration timeout();
  }

  interface Bundle {
    /** Maximum total size of the files of one revision, uncompressed. */
    @WithDefault("52428800")
    long maxSize();

    /** Maximum number of files in one revision. */
    @WithDefault("1000")
    int maxFiles();
  }
}
