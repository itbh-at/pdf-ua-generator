/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.util.Optional;
import java.util.Set;

/**
 * A content template together with its layout: the layout's files appear under {@code layout/}, so
 * {@code {#include layout}} finds the layout's {@code template.xhtml} and the layout refers to its
 * files as {@code layout/layout.css}, {@code layout/fonts/…}. The content cannot replace a layout
 * file.
 */
public final class ComposedTemplateRepository implements TemplateRepository {

  /** Where the layout's files appear. */
  public static final String MOUNT = "layout";

  private final TemplateRepository content;
  private final TemplateRepository layout;

  public ComposedTemplateRepository(TemplateRepository content, TemplateRepository layout) {
    this.content = content;
    this.layout = layout;
  }

  @Override
  public String contentKey() {
    return content.contentKey() + '+' + layout.contentKey();
  }

  @Override
  public Optional<String> template(String id) {
    Optional<String> path = ResourcePaths.normalize(id);
    if (path.isEmpty()) {
      return Optional.empty();
    }
    if (path.get().equals(MOUNT)) {
      return layout.template(DirectoryTemplateRepository.DEFAULT_TEMPLATE_FILE);
    }
    return inLayout(path.get()).map(layout::template).orElseGet(() -> content.template(id));
  }

  @Override
  public Optional<byte[]> resource(String path) {
    Optional<String> normalized = ResourcePaths.normalize(path);
    if (normalized.isEmpty()) {
      return Optional.empty();
    }
    return inLayout(normalized.get()).map(layout::resource).orElseGet(() -> content.resource(path));
  }

  @Override
  public Set<String> languages(String templateId) {
    return inLayout(templateId)
        .map(layout::languages)
        .orElseGet(() -> content.languages(templateId));
  }

  private static Optional<String> inLayout(String path) {
    return path.startsWith(MOUNT + "/")
        ? Optional.of(path.substring(MOUNT.length() + 1))
        : Optional.empty();
  }
}
