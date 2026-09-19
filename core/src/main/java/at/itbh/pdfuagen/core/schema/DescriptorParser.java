/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import at.itbh.pdfuagen.core.LanguageVariants;
import at.itbh.pdfuagen.core.OutputFormat;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Reads the JSON descriptors of templates and layouts: strict keys, every problem collected with a
 * JSON Pointer into the descriptor.
 */
final class DescriptorParser {

  /** Names usable in Qute expressions: fields, styles, areas, components, parameters. */
  static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /** Names of catalog styles and layout classes: CSS class names. */
  static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

  private static final Set<String> NAMES_TAKEN_BY_LOOPS = Set.of("it");

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();

  final String name;
  final List<Problem> problems = new ArrayList<>();

  DescriptorParser(String name) {
    this.name = name;
  }

  /** Reads the JSON document; malformed JSON is reported with line and column. */
  static JsonNode read(byte[] json, String name) throws RenderException {
    try {
      return MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      String location =
          e.getLocation() == null
              ? name
              : name
                  + ", line "
                  + e.getLocation().getLineNr()
                  + ", column "
                  + e.getLocation().getColumnNr();
      throw new RenderException(
          new Problem(Problem.TEMPLATE_ERROR, "invalid JSON: " + e.getOriginalMessage(), location),
          e);
    } catch (IOException e) {
      throw new RenderException(
          new Problem(Problem.TEMPLATE_ERROR, "cannot read: " + e.getMessage(), name), e);
    }
  }

  void failOnProblems() throws RenderException {
    if (!problems.isEmpty()) {
      throw new RenderException(problems);
    }
  }

  boolean object(JsonNode node, String pointer, String what) {
    if (node == null || !node.isObject()) {
      problem(pointer, what + " must be a JSON object");
      return false;
    }
    return true;
  }

  Locale language(JsonNode root, String what) {
    JsonNode lang = root.get("language");
    if (lang == null) {
      problem("#", "'language' is required: the BCP 47 tag of " + what + ", e.g. \"en\"");
    } else if (!lang.isTextual() || !LanguageVariants.isLanguageTag(lang.asText())) {
      problem("#/language", "'language' must be a BCP 47 language tag such as \"en\" or \"de-AT\"");
    } else {
      return Locale.forLanguageTag(lang.asText());
    }
    return null;
  }

  Set<OutputFormat> formats(JsonNode node) {
    Set<OutputFormat> formats = EnumSet.noneOf(OutputFormat.class);
    String allowed =
        String.join(", ", Arrays.stream(OutputFormat.values()).map(OutputFormat::id).toList());
    if (!node.isArray() || node.isEmpty()) {
      problem("#/formats", "'formats' must be a non-empty list of: " + allowed);
      return formats;
    }
    for (int i = 0; i < node.size(); i++) {
      var format = OutputFormat.of(node.get(i).asText());
      if (format.isPresent()) {
        formats.add(format.get());
      } else {
        problem("#/formats/" + i, "unknown format; use one of: " + allowed);
      }
    }
    return formats;
  }

  Map<String, Field> fields(JsonNode node, String pointer) {
    return named(
        node,
        pointer,
        "field",
        NAME,
        (value, p) -> field(value, p),
        "use letters, digits and '_', not starting with a digit, and not 'it'");
  }

  /**
   * An object of named entries, e.g. {@code "styles": {"lead": {…}}}.
   *
   * @param entry parses one entry; {@code null} drops it
   */
  <T> Map<String, T> named(
      JsonNode node,
      String pointer,
      String what,
      Pattern names,
      BiFunction<JsonNode, String, T> entry,
      String nameRule) {
    Map<String, T> result = new LinkedHashMap<>();
    if (!node.isObject()) {
      problem(pointer, "must be an object of " + what + " definitions");
      return result;
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      String entryPointer = pointer + "/" + JsonPointer.escape(e.getKey());
      if (!names.matcher(e.getKey()).matches() || NAMES_TAKEN_BY_LOOPS.contains(e.getKey())) {
        problem(entryPointer, what + " name '" + e.getKey() + "' is not usable; " + nameRule);
      }
      T value = entry.apply(e.getValue(), entryPointer);
      if (value != null) {
        result.put(e.getKey(), value);
      }
    }
    return result;
  }

  Field field(JsonNode node, String pointer) {
    if (!node.isObject()) {
      problem(pointer, "a field definition must be an object with at least 'type'");
      return null;
    }
    JsonNode typeNode = node.get("type");
    FieldType type = typeNode == null ? null : FieldType.of(typeNode.asText()).orElse(null);
    if (type == null) {
      problem(
          pointer + "/type",
          "'type' must be one of text, number, date, boolean, image, list, object");
      return null;
    }
    Set<String> allowed =
        switch (type) {
          case OBJECT -> Set.of("type", "label", "description", "fields");
          case LIST -> Set.of("type", "label", "description", "items");
          default -> Set.of("type", "label", "description");
        };
    onlyKeys(node, pointer, allowed);
    String label = text(node, "label", pointer);
    String description = text(node, "description", pointer);
    Map<String, Field> fields = null;
    Field items = null;
    if (type == FieldType.OBJECT) {
      JsonNode fieldsNode = node.get("fields");
      if (fieldsNode == null) {
        problem(pointer, "an object field needs 'fields'");
      } else {
        fields = fields(fieldsNode, pointer + "/fields");
      }
    } else if (type == FieldType.LIST) {
      JsonNode itemsNode = node.get("items");
      if (itemsNode == null) {
        problem(pointer, "a list field needs 'items', the definition of its elements");
      } else {
        items = field(itemsNode, pointer + "/items");
      }
    }
    return new Field(type, label, description, fields, items);
  }

  String text(JsonNode node, String key, String pointer) {
    JsonNode value = node.get(key);
    if (value == null) {
      return null;
    }
    if (!value.isTextual()) {
      problem(pointer + "/" + key, "'" + key + "' must be a string");
      return null;
    }
    return value.asText();
  }

  boolean bool(JsonNode node, String key, String pointer, boolean fallback) {
    JsonNode value = node.get(key);
    if (value == null) {
      return fallback;
    }
    if (!value.isBoolean()) {
      problem(pointer + "/" + key, "'" + key + "' must be true or false");
      return fallback;
    }
    return value.asBoolean();
  }

  /** A list of strings, each matching {@code names}. */
  List<String> strings(JsonNode node, String pointer, Pattern names, String what) {
    List<String> result = new ArrayList<>();
    if (!node.isArray()) {
      problem(pointer, "must be a list of " + what);
      return result;
    }
    for (int i = 0; i < node.size(); i++) {
      JsonNode value = node.get(i);
      if (!value.isTextual() || (names != null && !names.matcher(value.asText()).matches())) {
        problem(pointer + "/" + i, "not a valid " + what);
      } else {
        result.add(value.asText());
      }
    }
    return result;
  }

  void onlyKeys(JsonNode node, String pointer, Set<String> allowed) {
    node.fieldNames()
        .forEachRemaining(
            key -> {
              if (!allowed.contains(key)) {
                problem(
                    pointer + "/" + JsonPointer.escape(key),
                    "unknown key '"
                        + key
                        + "'; allowed: "
                        + String.join(", ", allowed.stream().sorted().toList()));
              }
            });
  }

  void problem(String pointer, String detail) {
    problems.add(new Problem(Problem.TEMPLATE_ERROR, detail, name + pointer));
  }
}
