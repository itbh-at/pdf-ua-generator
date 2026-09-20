/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.EvalContext;
import io.quarkus.qute.NamespaceResolver;
import io.quarkus.qute.Results;
import io.quarkus.qute.TemplateInstance;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Texts of a layout per language, written {@code {msg:page}}: {@code messages.json} holds the
 * default texts, {@code messages.<tag>.json} the translations. A text is looked up for the language
 * of the rendered variant, shortened step by step ({@code de-AT}, {@code de}), then in the default
 * file.
 */
public final class Messages {

  /** The Qute namespace of layout texts. */
  public static final String NAMESPACE = "msg";

  public static final String DEFAULT_FILE = "messages.json";

  private static final ObjectMapper JSON = new ObjectMapper();

  private Messages() {}

  /** The file names searched for a language, most specific first. */
  public static List<String> files(Locale locale) {
    List<String> files = new ArrayList<>();
    if (locale != null && !locale.getLanguage().isEmpty()) {
      String tag = locale.toLanguageTag();
      while (true) {
        files.add("messages." + tag + ".json");
        int dash = tag.lastIndexOf('-');
        if (dash < 0) {
          break;
        }
        tag = tag.substring(0, dash);
      }
    }
    files.add(DEFAULT_FILE);
    return files;
  }

  /**
   * Reads a messages file: a JSON object of texts.
   *
   * @return texts by key; empty if the file does not exist
   * @throws IOException if it is not a flat JSON object of strings
   */
  public static Optional<Map<String, String>> read(TemplateRepository repository, String path)
      throws IOException {
    Optional<byte[]> bytes = repository.resource(path);
    if (bytes.isEmpty()) {
      return Optional.empty();
    }
    JsonNode root = JSON.readTree(bytes.get());
    if (root == null || !root.isObject()) {
      throw new IOException(path + " must be a JSON object of texts");
    }
    Map<String, String> texts = new LinkedHashMap<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      if (!e.getValue().isTextual()) {
        throw new IOException(path + ": '" + e.getKey() + "' must be a string");
      }
      texts.put(e.getKey(), e.getValue().asText());
    }
    return Optional.of(texts);
  }

  /** The {@code msg:} resolver for an engine; it lives and dies with the engine. */
  static NamespaceResolver resolver(TemplateRepository repository, String prefix) {
    Map<String, Optional<Map<String, String>>> files = new ConcurrentHashMap<>();
    return NamespaceResolver.builder(NAMESPACE)
        .resolveAsync(ctx -> lookup(repository, prefix, files, ctx))
        .build();
  }

  private static CompletionStage<Object> lookup(
      TemplateRepository repository,
      String prefix,
      Map<String, Optional<Map<String, String>>> cache,
      EvalContext ctx) {
    if (repository == null) {
      return Results.notFound(ctx);
    }
    Locale locale =
        switch (ctx.getAttribute(TemplateInstance.LOCALE)) {
          case Locale l -> l;
          case String tag -> Locale.forLanguageTag(tag);
          case null, default -> Locale.ROOT;
        };
    for (String file : files(locale)) {
      Optional<Map<String, String>> texts =
          cache.computeIfAbsent(
              prefix + file,
              path -> {
                try {
                  return read(repository, path);
                } catch (IOException e) {
                  return Optional.empty();
                }
              });
      if (texts.isPresent() && texts.get().containsKey(ctx.getName())) {
        return CompletableFuture.completedFuture(texts.get().get(ctx.getName()));
      }
    }
    return Results.notFound(ctx);
  }
}
