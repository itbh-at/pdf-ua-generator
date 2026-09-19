/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** JSON Pointers (RFC 6901) in their URI fragment form, e.g. {@code #/items/2/price}. */
public final class JsonPointer {

  private JsonPointer() {}

  /** The fragment {@code #/a/b/0} for the given property names and array indexes. */
  public static String fragment(List<?> tokens) {
    StringBuilder pointer = new StringBuilder("#");
    for (Object token : tokens) {
      pointer.append('/').append(escape(String.valueOf(token)));
    }
    return pointer.toString();
  }

  /**
   * One reference token: {@code ~} and {@code /} escaped as {@code ~0} and {@code ~1}, then every
   * character not allowed in a URI fragment percent-encoded as UTF-8.
   */
  public static String escape(String token) {
    String escaped = token.replace("~", "~0").replace("/", "~1");
    StringBuilder out = new StringBuilder();
    for (byte b : escaped.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xff);
      if (c < 0x80 && allowedInFragment(c)) {
        out.append(c);
      } else {
        out.append('%').append(String.format("%02X", b & 0xff));
      }
    }
    return out.toString();
  }

  // RFC 3986: fragment = *( unreserved / sub-delims / ":" / "@" / "/" / "?" )
  private static boolean allowedInFragment(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || "-._~!$&'()*+,;=:@/?".indexOf(c) >= 0;
  }
}
