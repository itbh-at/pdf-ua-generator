/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Template repository on the file system: a root directory plus include paths searched in order.
 * Templates are found as {@code <id>}, {@code <id>.xhtml} or {@code <id>/template.xhtml}; resources
 * by their relative path.
 */
public final class DirectoryTemplateRepository implements TemplateRepository {

  public static final String DEFAULT_TEMPLATE_FILE = "template.xhtml";

  private final List<Path> roots;

  public DirectoryTemplateRepository(Path root, List<Path> includePaths) {
    List<Path> all = new ArrayList<>();
    all.add(root.toAbsolutePath().normalize());
    includePaths.forEach(p -> all.add(p.toAbsolutePath().normalize()));
    this.roots = List.copyOf(all);
  }

  @Override
  public Optional<String> template(String id) {
    Optional<String> path = ResourcePaths.normalize(id);
    if (path.isEmpty()) {
      return Optional.empty();
    }
    for (String candidate : candidates(path.get())) {
      Optional<byte[]> bytes = read(candidate);
      if (bytes.isPresent()) {
        return Optional.of(new String(bytes.get(), StandardCharsets.UTF_8));
      }
    }
    return Optional.empty();
  }

  /** The paths a template id may denote, in lookup order: as is, with .xhtml, as a directory. */
  static List<String> candidates(String normalizedId) {
    return List.of(
        normalizedId, normalizedId + ".xhtml", normalizedId + "/" + DEFAULT_TEMPLATE_FILE);
  }

  @Override
  public Optional<byte[]> resource(String path) {
    return ResourcePaths.normalize(path).flatMap(this::read);
  }

  /** Variants {@code <name>.<tag>.xhtml} next to {@code <name>.xhtml}, in any root. */
  @Override
  public Set<String> languages(String templateId) {
    Optional<String> path = ResourcePaths.normalize(templateId);
    if (path.isEmpty() || !path.get().endsWith(LanguageVariants.EXTENSION)) {
      return Set.of();
    }
    Set<String> tags = new TreeSet<>();
    for (Path root : roots) {
      Path file = root.resolve(path.get()).normalize();
      Path dir = file.getParent();
      if (dir == null || !Files.isDirectory(dir)) {
        continue;
      }
      try (Stream<Path> siblings = Files.list(dir)) {
        siblings.forEach(
            sibling -> {
              String id = root.relativize(sibling).toString().replace('\\', '/');
              LanguageVariants.Variant variant = LanguageVariants.parse(id);
              if (variant.tag() != null
                  && variant.baseId().equals(path.get())
                  && Files.isRegularFile(sibling)) {
                tags.add(variant.tag());
              }
            });
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return java.util.Collections.unmodifiableSet(tags);
  }

  private Optional<byte[]> read(String normalized) {
    for (Path root : roots) {
      Path file = root.resolve(normalized).normalize();
      // Symlinks must not lead out of the root either.
      try {
        if (Files.isRegularFile(file) && file.toRealPath().startsWith(root.toRealPath())) {
          return Optional.of(Files.readAllBytes(file));
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return Optional.empty();
  }
}
