/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Type of a template data field, as written in the field definitions. */
public enum FieldType {
  /** A JSON string. */
  TEXT,
  /** A JSON number; formatted with {@code .number} or {@code .currency('EUR')}. */
  NUMBER,
  /** An ISO 8601 date as JSON string ({@code 2026-09-19}); formatted with {@code .date}. */
  DATE,
  /** {@code true} or {@code false}. */
  BOOLEAN,
  /**
   * An image: {@code {"src": "attachment:<name>" | "https://…", "alt": "…"}}. An empty {@code alt}
   * marks the image as decorative.
   */
  IMAGE,
  /** A JSON array; {@code items} defines its elements. */
  LIST,
  /** A JSON object; {@code fields} defines its properties. */
  OBJECT;

  /** The name used in the field definitions, e.g. {@code text}. */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  static Optional<FieldType> of(String id) {
    return Arrays.stream(values()).filter(t -> t.id().equals(id)).findFirst();
  }
}
