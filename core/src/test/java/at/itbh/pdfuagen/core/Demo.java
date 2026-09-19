/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.nio.file.Path;
import java.util.List;

/** The demo shipped in {@code demo/}: the content template with its layout. */
public final class Demo {

  public static final Path DIR = Path.of("..", "demo");

  private Demo() {}

  /** The demo layouts: {@code layout/} and {@code layout-memo/}. */
  public static final List<String> LAYOUTS = List.of("layout", "layout-memo");

  public static TemplateRepository repository() {
    return repository("layout");
  }

  /** The demo content with one of the demo layouts. */
  public static TemplateRepository repository(String layout) {
    return new ComposedTemplateRepository(
        new DirectoryTemplateRepository(DIR, List.of()),
        new DirectoryTemplateRepository(DIR.resolve(layout), List.of()));
  }
}
