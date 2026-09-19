/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.util.List;

/**
 * Result of a render.
 *
 * @param format the output format
 * @param content the document bytes
 * @param warnings non-fatal renderer messages (e.g. unsupported CSS)
 */
public record Rendered(OutputFormat format, byte[] content, List<String> warnings) {

  public Rendered {
    warnings = List.copyOf(warnings);
  }
}
