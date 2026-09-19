/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** In-memory repository for tests. */
final class MapTemplateRepository implements TemplateRepository {

  private final Map<String, String> templates = new HashMap<>();
  private final Map<String, byte[]> resources = new HashMap<>();
  private final Map<String, Set<String>> languages = new HashMap<>();

  MapTemplateRepository template(String id, String source) {
    templates.put(id, source);
    return this;
  }

  MapTemplateRepository resource(String path, byte[] bytes) {
    resources.put(path, bytes);
    return this;
  }

  MapTemplateRepository resource(String path, String text) {
    return resource(path, text.getBytes(StandardCharsets.UTF_8));
  }

  /** Declares a language variant of a template; its source is added with {@link #template}. */
  MapTemplateRepository language(String templateId, String tag) {
    languages.computeIfAbsent(templateId, k -> new TreeSet<>()).add(tag);
    return this;
  }

  @Override
  public Set<String> languages(String templateId) {
    return languages.getOrDefault(templateId, Set.of());
  }

  @Override
  public Optional<String> template(String id) {
    return Optional.ofNullable(templates.get(id));
  }

  @Override
  public Optional<byte[]> resource(String path) {
    return Optional.ofNullable(resources.get(path));
  }
}
