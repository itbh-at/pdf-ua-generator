/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.net.URI;
import java.util.Optional;

/**
 * Fetches external {@code https} resources. The core contains no network code: the server supplies
 * an implementation that enforces the host allowlist, the CLI uses {@link #NONE}.
 */
@FunctionalInterface
public interface ResourceFetcher {

  /** Rejects every external resource. */
  ResourceFetcher NONE = uri -> Optional.empty();

  /**
   * @return the resource bytes, or empty if the URI is not allowed or cannot be fetched
   */
  Optional<byte[]> fetch(URI uri);
}
