/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Language variants of a template: the default {@code <name>.xhtml} plus optional {@code
 * <name>.<bcp47>.xhtml}, e.g. {@code invoice.de-AT.xhtml}. All variants share the descriptor {@code
 * <name>.json}.
 */
public final class LanguageVariants {

  static final String EXTENSION = ".xhtml";
  static final String DESCRIPTOR_EXTENSION = ".json";

  private LanguageVariants() {}

  /**
   * A template id split into the id of its default variant and its language tag.
   *
   * @param baseId id of the default variant, e.g. {@code invoice.xhtml}
   * @param tag the language tag, or {@code null} for the default variant
   */
  public record Variant(String baseId, String tag) {}

  /**
   * Whether a string is a well-formed BCP 47 language tag with a two- or three-letter language, as
   * used in variant names ({@code de}, {@code de-AT}, {@code sr-Latn}).
   */
  public static boolean isLanguageTag(String tag) {
    try {
      Locale locale = new Locale.Builder().setLanguageTag(tag).build();
      return locale.getLanguage().length() >= 2
          && locale.getLanguage().length() <= 3
          && locale.toLanguageTag().equalsIgnoreCase(tag);
    } catch (IllformedLocaleException e) {
      return false;
    }
  }

  /** Splits {@code invoice.de-AT.xhtml} into {@code invoice.xhtml} and {@code de-AT}. */
  public static Variant parse(String templateId) {
    if (templateId.endsWith(EXTENSION)) {
      String stem = templateId.substring(0, templateId.length() - EXTENSION.length());
      int dot = stem.lastIndexOf('.');
      int slash = stem.lastIndexOf('/');
      if (dot > slash + 1 && isLanguageTag(stem.substring(dot + 1))) {
        return new Variant(stem.substring(0, dot) + EXTENSION, stem.substring(dot + 1));
      }
    }
    return new Variant(templateId, null);
  }

  /** {@code invoice.xhtml} with {@code de-AT} gives {@code invoice.de-AT.xhtml}. */
  public static String variantId(String baseId, String tag) {
    return stem(baseId) + "." + tag + EXTENSION;
  }

  /** The descriptor of a template and all its variants: {@code invoice.json}. */
  public static String descriptorPath(String baseId) {
    return stem(baseId) + DESCRIPTOR_EXTENSION;
  }

  private static String stem(String baseId) {
    return baseId.endsWith(EXTENSION)
        ? baseId.substring(0, baseId.length() - EXTENSION.length())
        : baseId;
  }

  /**
   * Picks the variant for a list of language ranges by RFC 4647 lookup.
   *
   * @param defaultLanguage language of the default variant
   * @param tags languages of the other variants
   * @param ranges the accepted languages in priority order, e.g. from {@code Accept-Language}
   * @return the tag of the chosen variant, or empty for the default variant — also when nothing
   *     matches
   */
  public static Optional<String> select(
      Locale defaultLanguage, Collection<String> tags, List<Locale.LanguageRange> ranges) {
    if (ranges.isEmpty() || tags.isEmpty()) {
      return Optional.empty();
    }
    List<String> candidates = new ArrayList<>(tags);
    String defaultTag = defaultLanguage == null ? null : defaultLanguage.toLanguageTag();
    if (defaultTag != null) {
      candidates.addFirst(defaultTag);
    }
    String match = Locale.lookupTag(ranges, candidates);
    if (match == null || match.equals(defaultTag)) {
      return Optional.empty();
    }
    return Optional.of(match);
  }

  /**
   * Parses an {@code Accept-Language} header or a {@code lang} parameter such as {@code de-AT,
   * en;q=0.8}.
   *
   * @throws IllegalArgumentException if it is malformed
   */
  public static List<Locale.LanguageRange> ranges(String value) {
    return Locale.LanguageRange.parse(value);
  }
}
