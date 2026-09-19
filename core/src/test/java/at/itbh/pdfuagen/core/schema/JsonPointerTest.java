/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class JsonPointerTest {

  @Test
  void escapesTokensForUriFragments() {
    assertEquals("#", JsonPointer.fragment(List.of()));
    assertEquals("#/items/2/price", JsonPointer.fragment(List.of("items", 2, "price")));
    assertEquals("#/a~1b~0c", JsonPointer.fragment(List.of("a/b~c")));
    assertEquals("#/stra%C3%9Fe/a%20b/%25", JsonPointer.fragment(List.of("straße", "a b", "%")));
  }
}
