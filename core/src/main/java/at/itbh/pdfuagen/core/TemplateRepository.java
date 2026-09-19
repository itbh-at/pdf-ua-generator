/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.util.Optional;
import java.util.Set;

/**
 * Source of templates and their resources (fonts, images, stylesheets).
 *
 * <p>The CLI reads a directory tree, the server reads its database; the core only sees this
 * interface. Paths are relative, use {@code /} as separator and never contain {@code ..}; callers
 * pass them through {@link ResourcePaths#normalize}.
 */
public interface TemplateRepository {

  /** The Qute source of the template with the given id, e.g. for {@code {#include id}}. */
  Optional<String> template(String id);

  /** The bytes of a resource at the given normalized relative path. */
  Optional<byte[]> resource(String path);

  /**
   * The language tags of the variants of a template ({@code de-AT} for {@code
   * invoice.de-AT.xhtml}), without the default variant. See {@link LanguageVariants}.
   */
  default Set<String> languages(String templateId) {
    return Set.of();
  }
}
