/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import at.itbh.pdfuagen.core.schema.LayoutDescriptor;
import java.util.Optional;

/** Where a repository's layout is: mounted for a content template, or the repository itself. */
final class Layouts {

  private Layouts() {}

  /**
   * The path prefix of the layout's files: {@code layout/} for a content template composed with its
   * layout, the empty string for a layout on its own, empty if there is no layout.
   */
  static Optional<String> prefix(TemplateRepository repository) {
    String mounted = ComposedTemplateRepository.MOUNT + "/";
    if (repository.resource(mounted + LayoutDescriptor.FILE).isPresent()) {
      return Optional.of(mounted);
    }
    if (repository.resource(LayoutDescriptor.FILE).isPresent()) {
      return Optional.of("");
    }
    return Optional.empty();
  }

  /** The layout's descriptor; empty without a layout or if it is invalid (the checks report it). */
  static Optional<LayoutDescriptor> descriptor(TemplateRepository repository) {
    return prefix(repository)
        .flatMap(
            prefix ->
                repository
                    .resource(prefix + LayoutDescriptor.FILE)
                    .flatMap(
                        bytes -> {
                          try {
                            return Optional.of(
                                LayoutDescriptor.parse(bytes, prefix + LayoutDescriptor.FILE));
                          } catch (RenderException e) {
                            return Optional.empty();
                          }
                        }));
  }
}
