/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ResourcePathsTest {

  @ParameterizedTest
  @CsvSource({
    "logo.svg,logo.svg",
    "fonts/./a.ttf,fonts/a.ttf",
    "a/b/../c.png,a/c.png",
    "a//b.png,a/b.png"
  })
  void normalizes(String input, String expected) {
    assertEquals(Optional.of(expected), ResourcePaths.normalize(input));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/etc/passwd",
        "../a.png",
        "a/../../b.png",
        "c:\\x",
        "a\\b",
        "file:a",
        "",
        ".",
        "a/.."
      })
  void rejects(String input) {
    assertTrue(ResourcePaths.normalize(input).isEmpty());
  }
}
