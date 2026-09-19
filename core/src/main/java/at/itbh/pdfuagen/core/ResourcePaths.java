/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/** Normalization of relative resource paths; rejects anything that escapes the root. */
public final class ResourcePaths {

  private ResourcePaths() {}

  /**
   * Normalizes a relative path ({@code a/./b.png} becomes {@code a/b.png}).
   *
   * @return empty if the path is absolute, empty, contains a backslash or a NUL character, or
   *     climbs above the root with {@code ..}
   */
  public static Optional<String> normalize(String path) {
    if (path == null
        || path.isEmpty()
        || path.startsWith("/")
        || path.indexOf('\\') >= 0
        || path.indexOf('\0') >= 0
        || path.contains(":")) {
      return Optional.empty();
    }
    Deque<String> parts = new ArrayDeque<>();
    for (String part : path.split("/")) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        if (parts.isEmpty()) {
          return Optional.empty();
        }
        parts.removeLast();
      } else {
        parts.addLast(part);
      }
    }
    return parts.isEmpty() ? Optional.empty() : Optional.of(String.join("/", parts));
  }
}
