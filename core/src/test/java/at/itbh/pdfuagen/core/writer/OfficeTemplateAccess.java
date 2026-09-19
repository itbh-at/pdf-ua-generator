/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.writer;

/** Test access to the package-private font obfuscation; it is its own inverse. */
public final class OfficeTemplateAccess {

  private OfficeTemplateAccess() {}

  public static byte[] obfuscate(byte[] font, String fontKey) {
    return OfficeTemplate.obfuscate(font, fontKey);
  }
}
