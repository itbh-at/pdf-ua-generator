/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** Reading a bundle: unwrapping a zipped folder and ignoring archiver metadata. */
class BundleTest {

  private static final long MAX_SIZE = 1_000_000;
  private static final int MAX_FILES = 100;

  private static byte[] zip(Map<String, byte[]> entries) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, byte[]> e : entries.entrySet()) {
        out.putNextEntry(new ZipEntry(e.getKey()));
        out.write(e.getValue());
        out.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void readsFilesAtTheRoot() throws Exception {
    byte[] archive = zip(Map.of("template.xhtml", utf8("<html/>"), "template.json", utf8("{}")));
    Map<String, byte[]> files = Bundle.read(archive, MAX_SIZE, MAX_FILES);
    assertEquals(
        Map.of("template.xhtml", "<html/>", "template.json", "{}").keySet(), files.keySet());
  }

  @Test
  void unwrapsAZippedFolder() throws Exception {
    // What macOS Finder produces when zipping the "layout" folder itself.
    byte[] archive =
        zip(
            Map.of(
                "layout/template.xhtml", utf8("<html/>"),
                "layout/layout.json", utf8("{}"),
                "layout/components/box.xhtml", utf8("box")));
    Map<String, byte[]> files = Bundle.read(archive, MAX_SIZE, MAX_FILES);
    assertTrue(
        files.containsKey("template.xhtml"), "template.xhtml is at the root after unwrapping");
    assertTrue(files.containsKey("components/box.xhtml"), "subdirectories keep their structure");
    assertTrue(files.keySet().stream().noneMatch(p -> p.startsWith("layout/")), "no wrapper left");
  }

  @Test
  void ignoresArchiverJunk() throws Exception {
    byte[] archive =
        zip(
            new java.util.TreeMap<>(
                Map.of(
                    "layout/template.xhtml", utf8("<html/>"),
                    "layout/.DS_Store", utf8("junk"),
                    "__MACOSX/layout/._template.xhtml", utf8("junk"))));
    Map<String, byte[]> files = Bundle.read(archive, MAX_SIZE, MAX_FILES);
    assertEquals(java.util.Set.of("template.xhtml"), files.keySet());
  }

  @Test
  void rejectsAnArchiveWithoutTheTemplate() throws Exception {
    byte[] archive = zip(Map.of("layout/layout.json", utf8("{}")));
    Bundle.InvalidBundleException e =
        assertThrows(
            Bundle.InvalidBundleException.class, () -> Bundle.read(archive, MAX_SIZE, MAX_FILES));
    assertTrue(e.getMessage().contains("template.xhtml"));
  }
}
