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
    for (String candidate :
        List.of(path.get(), path.get() + ".xhtml", path.get() + "/" + DEFAULT_TEMPLATE_FILE)) {
      Optional<byte[]> bytes = read(candidate);
      if (bytes.isPresent()) {
        return Optional.of(new String(bytes.get(), StandardCharsets.UTF_8));
      }
    }
    return Optional.empty();
  }

  @Override
  public Optional<byte[]> resource(String path) {
    return ResourcePaths.normalize(path).flatMap(this::read);
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
