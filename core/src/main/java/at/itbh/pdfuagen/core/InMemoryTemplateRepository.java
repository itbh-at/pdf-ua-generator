/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Template repository over files held in memory, keyed by relative path — e.g. a stored template
 * revision. Lookup rules are those of {@link DirectoryTemplateRepository}.
 */
public final class InMemoryTemplateRepository implements TemplateRepository {

  private final Map<String, byte[]> files;
  private final String contentKey;

  /**
   * Files without a content key of their own: the renderer treats every instance as different
   * content and parses its templates again.
   *
   * @param files file contents by normalized relative path ({@link ResourcePaths#normalize})
   */
  public InMemoryTemplateRepository(Map<String, byte[]> files) {
    this(files, UUID.randomUUID().toString());
  }

  /**
   * @param files file contents by normalized relative path ({@link ResourcePaths#normalize})
   * @param contentKey identifies these files, e.g. the content hash of a stored revision; see
   *     {@link TemplateRepository#contentKey()}
   */
  public InMemoryTemplateRepository(Map<String, byte[]> files, String contentKey) {
    this.files = Map.copyOf(files);
    this.contentKey = contentKey;
  }

  @Override
  public String contentKey() {
    return contentKey;
  }

  @Override
  public Optional<String> template(String id) {
    Optional<String> path = ResourcePaths.normalize(id);
    if (path.isEmpty()) {
      return Optional.empty();
    }
    for (String candidate : DirectoryTemplateRepository.candidates(path.get())) {
      byte[] bytes = files.get(candidate);
      if (bytes != null) {
        return Optional.of(new String(bytes, StandardCharsets.UTF_8));
      }
    }
    return Optional.empty();
  }

  @Override
  public Optional<byte[]> resource(String path) {
    return ResourcePaths.normalize(path).map(files::get);
  }

  @Override
  public Set<String> languages(String templateId) {
    Set<String> tags = new TreeSet<>();
    ResourcePaths.normalize(templateId)
        .ifPresent(
            base ->
                files.keySet().stream()
                    .map(LanguageVariants::parse)
                    .filter(v -> v.tag() != null && v.baseId().equals(base))
                    .forEach(v -> tags.add(v.tag())));
    return java.util.Collections.unmodifiableSet(tags);
  }
}
