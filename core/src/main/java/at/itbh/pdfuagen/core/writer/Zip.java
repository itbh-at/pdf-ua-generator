/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.writer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** ZIP container with fixed timestamps, so equal input gives byte-identical output. */
final class Zip {

  private static final long FIXED_TIME = 315532800000L; // 1980-01-01, the ZIP epoch

  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final ZipOutputStream zip = new ZipOutputStream(bytes);

  Zip add(String name, byte[] content) throws IOException {
    ZipEntry entry = new ZipEntry(name);
    entry.setTime(FIXED_TIME);
    zip.putNextEntry(entry);
    zip.write(content);
    zip.closeEntry();
    return this;
  }

  /** Uncompressed entry, as ODF requires for {@code mimetype}. */
  Zip addStored(String name, byte[] content) throws IOException {
    ZipEntry entry = new ZipEntry(name);
    entry.setTime(FIXED_TIME);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(content.length);
    CRC32 crc = new CRC32();
    crc.update(content);
    entry.setCrc(crc.getValue());
    zip.putNextEntry(entry);
    zip.write(content);
    zip.closeEntry();
    return this;
  }

  byte[] finish() throws IOException {
    zip.close();
    return bytes.toByteArray();
  }
}
