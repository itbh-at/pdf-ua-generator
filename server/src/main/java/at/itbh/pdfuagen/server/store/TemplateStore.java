/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.store;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Templates, their immutable revisions and the files of each revision, in PostgreSQL. Files are
 * stored once per content hash ({@code asset}); a revision lists its files by path.
 */
@ApplicationScoped
public class TemplateStore {

  /** Template ids: lower-case letters, digits and hyphens, starting with a letter or digit. */
  public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

  // 'draft' is transient: a revision holds it only while its upload is validated, then it is
  // promoted to 'published' (released for use) or removed. A released revision is retired by
  // moving it to 'archived'.
  public static final String DRAFT = "draft";
  public static final String PUBLISHED = "published";
  public static final String ARCHIVED = "archived";

  public static final String CONTENT = "content";
  public static final String LAYOUT = "layout";

  /** A new revision is of another kind than the template's earlier revisions. */
  public static final class KindMismatchException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    KindMismatchException(String message) {
      super(message);
    }
  }

  /** The template has another latest revision than the one the change was based on. */
  public static final class StaleException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int latest;

    StaleException(int latest) {
      super("the latest revision is " + latest);
      this.latest = latest;
    }

    /** The latest revision now; 0 if the template has none. */
    public int latest() {
      return latest;
    }
  }

  /** A layout revision a content revision may be rendered with, written {@code corporate@3}. */
  public record LayoutPin(String id, int revision) {
    @Override
    public String toString() {
      return id + "@" + revision;
    }
  }

  /**
   * @param layouts the layout revisions this content revision may be rendered with, the default
   *     first; empty for a layout or a template without one
   * @param files content hash by path
   */
  public record Revision(
      String templateId,
      int number,
      String status,
      String sha256,
      OffsetDateTime createdAt,
      OffsetDateTime publishedAt,
      OffsetDateTime archivedAt,
      List<LayoutPin> layouts,
      Map<String, String> files) {

    /** Released for use. */
    public boolean published() {
      return PUBLISHED.equals(status);
    }

    /** Retired: it existed and is kept as history, but is no longer current. */
    public boolean archived() {
      return ARCHIVED.equals(status);
    }

    /** The default layout, or {@code null}. */
    public LayoutPin defaultLayout() {
      return layouts.isEmpty() ? null : layouts.getFirst();
    }
  }

  /**
   * @param latestPublished number of the latest published revision, or {@code null}
   * @param latest number of the latest revision
   */
  public record Template(
      String id, String kind, OffsetDateTime createdAt, Integer latestPublished, int latest) {}

  /**
   * The result of storing a bundle.
   *
   * @param created {@code false} if the same files were already stored as this revision
   */
  public record Stored(Revision revision, boolean created) {}

  /** A content revision that lists a layout revision. */
  public record Dependent(String templateId, int number, String status, int layoutRevision) {}

  /** A stored file, served publicly if it is an image. */
  public record Asset(String sha256, String mediaType, byte[] content) {}

  @Inject DataSource dataSource;

  public List<Template> list() {
    return query(
        "select t.id, t.kind, t.created_at,"
            + " (select max(number) from revision r where r.template_id = t.id"
            + "  and r.status = 'published') as latest_published,"
            + " (select coalesce(max(number), 0) from revision r where r.template_id = t.id)"
            + "  as latest"
            + " from template t order by t.id",
        List.of(),
        TemplateStore::template);
  }

  public Optional<Template> find(String id) {
    return query(
            "select t.id, t.kind, t.created_at,"
                + " (select max(number) from revision r where r.template_id = t.id"
                + "  and r.status = 'published') as latest_published,"
                + " (select coalesce(max(number), 0) from revision r where r.template_id = t.id)"
                + "  as latest"
                + " from template t where t.id = ?",
            List.of(id),
            TemplateStore::template)
        .stream()
        .findFirst();
  }

  /**
   * Stores the files as a new draft revision, creating the template if needed. Files already stored
   * as a revision of this template yield that revision instead of a new one, so importing the same
   * bundle twice changes nothing.
   *
   * @param kind {@link #CONTENT} or {@link #LAYOUT}; must match the template's earlier revisions
   * @param layouts the layout revisions a content revision lists, the default first
   * @throws KindMismatchException if the template is of the other kind
   */
  public Stored createRevision(
      String templateId, Map<String, byte[]> files, String kind, List<LayoutPin> layouts) {
    return createRevision(templateId, files, kind, layouts, null);
  }

  /**
   * As {@link #createRevision(String, Map, String, List)}, only if the latest revision is still
   * {@code expectedLatest}; checked under the same lock that numbers the revision, so two changes
   * based on the same revision cannot both pass.
   *
   * @param expectedLatest the latest revision the change is based on (0: no revision yet), or
   *     {@code null} for any
   * @throws StaleException if the latest revision is another one
   */
  public Stored createRevision(
      String templateId,
      Map<String, byte[]> files,
      String kind,
      List<LayoutPin> layouts,
      Integer expectedLatest) {
    return transaction(
        c -> {
          update(
              c,
              "insert into template (id, kind) values (?, ?) on conflict do nothing",
              templateId,
              kind);
          // Serializes revision numbers per template.
          try (PreparedStatement s =
              c.prepareStatement("select 1 from template where id = ? for update")) {
            s.setString(1, templateId);
            s.executeQuery().close();
          }
          try (PreparedStatement s = c.prepareStatement("select kind from template where id = ?")) {
            s.setString(1, templateId);
            try (ResultSet r = s.executeQuery()) {
              r.next();
              if (!r.getString(1).equals(kind)) {
                throw new KindMismatchException(
                    "'" + templateId + "' is a " + r.getString(1) + " template, not a " + kind);
              }
            }
          }
          if (expectedLatest != null) {
            try (PreparedStatement s =
                c.prepareStatement(
                    "select coalesce(max(number), 0) from revision where template_id = ?")) {
              s.setString(1, templateId);
              try (ResultSet r = s.executeQuery()) {
                r.next();
                if (r.getInt(1) != expectedLatest) {
                  throw new StaleException(r.getInt(1));
                }
              }
            }
          }
          Map<String, String> hashes = new TreeMap<>();
          files.forEach((path, content) -> hashes.put(path, sha256(content)));
          String manifest = manifestHash(hashes);
          try (PreparedStatement s =
              c.prepareStatement(
                  "select number from revision where template_id = ? and sha256 = ?"
                      + " order by number desc limit 1")) {
            s.setString(1, templateId);
            s.setString(2, manifest);
            try (ResultSet r = s.executeQuery()) {
              if (r.next()) {
                return new Stored(revision(c, templateId, r.getInt(1)).orElseThrow(), false);
              }
            }
          }
          int number;
          try (PreparedStatement s =
              c.prepareStatement(
                  "select coalesce(max(number), 0) + 1 from revision where template_id = ?")) {
            s.setString(1, templateId);
            try (ResultSet r = s.executeQuery()) {
              r.next();
              number = r.getInt(1);
            }
          }
          for (Map.Entry<String, byte[]> file : files.entrySet()) {
            try (PreparedStatement s =
                c.prepareStatement(
                    "insert into asset (sha256, media_type, size, content) values (?, ?, ?, ?)"
                        + " on conflict do nothing")) {
              s.setString(1, hashes.get(file.getKey()));
              s.setString(2, Bundle.mediaType(file.getKey()));
              s.setLong(3, file.getValue().length);
              s.setBytes(4, file.getValue());
              s.executeUpdate();
            }
          }
          update(
              c,
              "insert into revision (template_id, number, status, sha256) values (?, ?, ?, ?)",
              templateId,
              number,
              DRAFT,
              manifest);
          for (int position = 0; position < layouts.size(); position++) {
            update(
                c,
                "insert into revision_layout (template_id, number, position, layout_id,"
                    + " layout_revision) values (?, ?, ?, ?, ?)",
                templateId,
                number,
                position,
                layouts.get(position).id(),
                layouts.get(position).revision());
          }
          try (PreparedStatement s =
              c.prepareStatement(
                  "insert into revision_file (template_id, number, path, asset_sha256)"
                      + " values (?, ?, ?, ?)")) {
            for (Map.Entry<String, String> file : hashes.entrySet()) {
              s.setString(1, templateId);
              s.setInt(2, number);
              s.setString(3, file.getKey());
              s.setString(4, file.getValue());
              s.addBatch();
            }
            s.executeBatch();
          }
          return new Stored(revision(c, templateId, number).orElseThrow(), true);
        });
  }

  public Optional<Revision> revision(String templateId, int number) {
    return transaction(c -> revision(c, templateId, number));
  }

  public List<Revision> revisions(String templateId) {
    return transaction(
        c -> {
          List<Integer> numbers = new ArrayList<>();
          try (PreparedStatement s =
              c.prepareStatement(
                  "select number from revision where template_id = ? order by number")) {
            s.setString(1, templateId);
            try (ResultSet r = s.executeQuery()) {
              while (r.next()) {
                numbers.add(r.getInt(1));
              }
            }
          }
          List<Revision> result = new ArrayList<>();
          for (int number : numbers) {
            revision(c, templateId, number).ifPresent(result::add);
          }
          return result;
        });
  }

  /** The file contents of a revision, by path. */
  public Map<String, byte[]> files(String templateId, int number) {
    Map<String, byte[]> files = new TreeMap<>();
    query(
        "select f.path, a.content from revision_file f join asset a on a.sha256 = f.asset_sha256"
            + " where f.template_id = ? and f.number = ?",
        List.of(templateId, number),
        r -> files.put(r.getString(1), r.getBytes(2)));
    return files;
  }

  /**
   * Releases a revision for use: a freshly validated draft, or an archived revision uploaded again.
   *
   * @return {@code false} if it does not exist or is already published
   */
  public boolean release(String templateId, int number) {
    return transaction(
            c ->
                update(
                    c,
                    "update revision set status = 'published', published_at ="
                        + " coalesce(published_at, now()), archived_at = null where template_id = ?"
                        + " and number = ? and status in ('draft', 'archived')",
                    templateId,
                    number))
        == 1;
  }

  /** Retires a released revision. {@code false} if it does not exist or is not published. */
  public boolean archive(String templateId, int number) {
    return transaction(
            c ->
                update(
                    c,
                    "update revision set status = 'archived', archived_at = now()"
                        + " where template_id = ? and number = ? and status = 'published'",
                    templateId,
                    number))
        == 1;
  }

  /** Deletes a template with all its revisions. */
  public boolean deleteTemplate(String templateId) {
    return transaction(c -> update(c, "delete from template where id = ?", templateId)) == 1;
  }

  /** Deletes a draft revision; published revisions are kept. */
  public boolean deleteDraft(String templateId, int number) {
    return transaction(
            c ->
                update(
                    c,
                    "delete from revision where template_id = ? and number = ? and status ="
                        + " 'draft'",
                    templateId,
                    number))
        == 1;
  }

  /** The content revisions that list a revision of a layout. */
  public List<Dependent> dependents(String layoutId) {
    return query(
        "select r.template_id, r.number, r.status, l.layout_revision from revision_layout l"
            + " join revision r on r.template_id = l.template_id and r.number = l.number"
            + " where l.layout_id = ? order by r.template_id, r.number",
        List.of(layoutId),
        r -> new Dependent(r.getString(1), r.getInt(2), r.getString(3), r.getInt(4)));
  }

  public Optional<Asset> asset(String sha256) {
    return query(
            "select sha256, media_type, content from asset where sha256 = ?",
            List.of(sha256),
            r -> new Asset(r.getString(1), r.getString(2), r.getBytes(3)))
        .stream()
        .findFirst();
  }

  /** The latest published revision of every template. */
  public List<Revision> latestPublished() {
    List<String[]> keys =
        query(
            "select template_id, max(number) from revision where status = 'published'"
                + " group by template_id order by template_id",
            List.of(),
            r -> new String[] {r.getString(1), r.getString(2)});
    List<Revision> result = new ArrayList<>();
    for (String[] key : keys) {
      revision(key[0], Integer.parseInt(key[1])).ifPresent(result::add);
    }
    return result;
  }

  private static Optional<Revision> revision(Connection c, String templateId, int number)
      throws SQLException {
    Map<String, String> files = new TreeMap<>();
    try (PreparedStatement s =
        c.prepareStatement(
            "select path, asset_sha256 from revision_file where template_id = ? and number = ?")) {
      s.setString(1, templateId);
      s.setInt(2, number);
      try (ResultSet r = s.executeQuery()) {
        while (r.next()) {
          files.put(r.getString(1), r.getString(2));
        }
      }
    }
    List<LayoutPin> layouts = new ArrayList<>();
    try (PreparedStatement s =
        c.prepareStatement(
            "select layout_id, layout_revision from revision_layout"
                + " where template_id = ? and number = ? order by position")) {
      s.setString(1, templateId);
      s.setInt(2, number);
      try (ResultSet r = s.executeQuery()) {
        while (r.next()) {
          layouts.add(new LayoutPin(r.getString(1), r.getInt(2)));
        }
      }
    }
    try (PreparedStatement s =
        c.prepareStatement(
            "select status, sha256, created_at, published_at, archived_at from revision"
                + " where template_id = ? and number = ?")) {
      s.setString(1, templateId);
      s.setInt(2, number);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Revision(
                templateId,
                number,
                r.getString(1),
                r.getString(2),
                r.getObject(3, OffsetDateTime.class),
                r.getObject(4, OffsetDateTime.class),
                r.getObject(5, OffsetDateTime.class),
                List.copyOf(layouts),
                java.util.Collections.unmodifiableMap(files)));
      }
    }
  }

  private static Template template(ResultSet r) throws SQLException {
    int published = r.getInt(4);
    boolean none = r.wasNull();
    return new Template(
        r.getString(1),
        r.getString(2),
        r.getObject(3, OffsetDateTime.class),
        none ? null : published,
        r.getInt(5));
  }

  // ---------------------------------------------------------------------------------------------
  // JDBC helpers

  @FunctionalInterface
  private interface Work<T> {
    T run(Connection c) throws SQLException;
  }

  @FunctionalInterface
  private interface Row<T> {
    T map(ResultSet r) throws SQLException;
  }

  private <T> T transaction(Work<T> work) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        T result = work.run(c);
        c.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("database error: " + e.getMessage(), e);
    }
  }

  private <T> List<T> query(String sql, List<Object> params, Row<T> row) {
    return transaction(
        c -> {
          try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
              s.setObject(i + 1, params.get(i));
            }
            List<T> result = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
              while (r.next()) {
                result.add(row.map(r));
              }
            }
            return result;
          }
        });
  }

  private static int update(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        s.setObject(i + 1, params[i]);
      }
      return s.executeUpdate();
    }
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The hash the files would have as a revision, so a draft and the revision it becomes share
   * everything the renderer derives from them.
   */
  public static String manifest(Map<String, byte[]> files) {
    Map<String, String> hashes = new TreeMap<>();
    files.forEach((path, content) -> hashes.put(path, sha256(content)));
    return manifestHash(hashes);
  }

  /** Hash of a revision: over its sorted paths and file hashes. */
  static String manifestHash(Map<String, String> files) {
    StringBuilder manifest = new StringBuilder();
    new TreeMap<>(files)
        .forEach((path, sha) -> manifest.append(path).append('\0').append(sha).append('\n'));
    return sha256(manifest.toString().getBytes(StandardCharsets.UTF_8));
  }
}
