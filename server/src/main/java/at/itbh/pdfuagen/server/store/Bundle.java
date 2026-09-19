/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.store;

import at.itbh.pdfuagen.core.ResourcePaths;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The files of a template revision as a ZIP archive, laid out like a template directory of the CLI:
 * {@code template.xhtml}, its descriptor {@code template.json}, language variants, assets and the
 * example data {@code example.json} with its attachments under {@code example/}.
 */
public final class Bundle {

  /** The main template of every revision. */
  public static final String TEMPLATE = "template.xhtml";

  /** Example data rendered by the publish check. */
  public static final String EXAMPLE = "example.json";

  /**
   * Directory of the attachments the example data refers to: {@code example/photo.png} is {@code
   * attachment:photo}.
   */
  public static final String EXAMPLE_ATTACHMENTS = "example/";

  private static final FileTime EPOCH = FileTime.fromMillis(0);

  private Bundle() {}

  /** The archive cannot be accepted; the message says why. */
  public static final class InvalidBundleException extends Exception {
    private static final long serialVersionUID = 1L;

    InvalidBundleException(String message) {
      super(message);
    }
  }

  /**
   * Reads an archive, enforcing the limits while decompressing.
   *
   * @return file contents by normalized path, sorted
   */
  public static Map<String, byte[]> read(InputStream zip, long maxSize, int maxFiles)
      throws InvalidBundleException, IOException {
    Map<String, byte[]> files = new TreeMap<>();
    long total = 0;
    try (ZipInputStream in = new ZipInputStream(zip)) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName();
        String path =
            ResourcePaths.normalize(name)
                .orElseThrow(() -> new InvalidBundleException("invalid path in archive: " + name));
        if (files.containsKey(path)) {
          throw new InvalidBundleException("duplicate path in archive: " + path);
        }
        if (files.size() >= maxFiles) {
          throw new InvalidBundleException("more than " + maxFiles + " files");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) {
          total += n;
          if (total > maxSize) {
            throw new InvalidBundleException("files larger than " + maxSize + " bytes in total");
          }
          out.write(buffer, 0, n);
        }
        files.put(path, out.toByteArray());
      }
    } catch (ZipException e) {
      throw new InvalidBundleException("not a valid ZIP archive: " + e.getMessage());
    }
    if (files.isEmpty()) {
      throw new InvalidBundleException("not a ZIP archive, or an empty one");
    }
    if (!files.containsKey(TEMPLATE)) {
      throw new InvalidBundleException("the archive has no " + TEMPLATE);
    }
    return files;
  }

  /** Writes files as a deterministic archive: sorted, fixed timestamps. */
  public static byte[] write(Map<String, byte[]> files) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
        ZipEntry entry = new ZipEntry(file.getKey());
        entry.setLastModifiedTime(EPOCH);
        out.putNextEntry(entry);
        out.write(file.getValue());
        out.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  /** Reads an archive from bytes. */
  public static Map<String, byte[]> read(byte[] zip, long maxSize, int maxFiles)
      throws InvalidBundleException, IOException {
    return read(new ByteArrayInputStream(zip), maxSize, maxFiles);
  }

  /** The example attachments among the files of a revision, by attachment name. */
  public static Map<String, byte[]> exampleAttachments(
      java.util.Set<String> paths, java.util.function.Function<String, byte[]> content) {
    Map<String, byte[]> attachments = new TreeMap<>();
    for (String path : paths) {
      if (path.startsWith(EXAMPLE_ATTACHMENTS)
          && path.indexOf('/', EXAMPLE_ATTACHMENTS.length()) < 0) {
        String name = path.substring(EXAMPLE_ATTACHMENTS.length());
        int dot = name.lastIndexOf('.');
        attachments.put(dot > 0 ? name.substring(0, dot) : name, content.apply(path));
      }
    }
    return attachments;
  }

  /** The media type of a file, from its extension. */
  public static String mediaType(String path) {
    String name = path.toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    return switch (dot < 0 ? "" : name.substring(dot + 1)) {
      case "xhtml" -> "application/xhtml+xml";
      case "json" -> "application/json";
      case "txt" -> "text/plain";
      case "css" -> "text/css";
      case "png" -> "image/png";
      case "jpg", "jpeg" -> "image/jpeg";
      case "svg" -> "image/svg+xml";
      case "ttf" -> "font/ttf";
      case "otf" -> "font/otf";
      case "woff" -> "font/woff";
      case "woff2" -> "font/woff2";
      default -> "application/octet-stream";
    };
  }
}
