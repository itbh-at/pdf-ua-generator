/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import java.util.Map;

/**
 * Definition of one data field.
 *
 * @param type the field type
 * @param label short name shown to editors and in the schema as {@code title}, or {@code null}
 * @param description longer explanation, or {@code null}
 * @param fields the properties of an {@link FieldType#OBJECT}, in definition order; empty otherwise
 * @param items the element definition of a {@link FieldType#LIST}; {@code null} otherwise
 */
public record Field(
    FieldType type, String label, String description, Map<String, Field> fields, Field items) {

  public Field {
    fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(fields);
  }
}
