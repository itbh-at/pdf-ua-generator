/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import at.itbh.pdfuagen.core.LanguageVariants;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The descriptor of a template ({@code <name>.json} next to {@code <name>.xhtml}): the language of
 * the default variant and the field definitions.
 *
 * <pre>{@code
 * {
 *   "language": "en",
 *   "fields": {
 *     "customer": { "type": "object", "label": "Customer", "fields": {
 *       "name": { "type": "text" } } },
 *     "positions": { "type": "list", "items": { "type": "object", "fields": {
 *       "price": { "type": "number" } } } }
 *   }
 * }
 * }</pre>
 *
 * @param language language of the default variant
 * @param fields the top-level fields, in definition order
 */
public record TemplateDescriptor(Locale language, Map<String, Field> fields) {

  /** Field names must be usable in Qute expressions ({@code {order.total}}). */
  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private static final Set<String> NAMES_TAKEN_BY_LOOPS = Set.of("it");

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();

  /**
   * Parses a descriptor.
   *
   * @param name the descriptor's path, used in problem locations
   * @throws RenderException listing every problem found, as {@link Problem#TEMPLATE_ERROR}
   */
  public static TemplateDescriptor parse(byte[] json, String name) throws RenderException {
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
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
    Parser parser = new Parser(name);
    TemplateDescriptor descriptor = parser.descriptor(root);
    if (!parser.problems.isEmpty()) {
      throw new RenderException(parser.problems);
    }
    return descriptor;
  }

  private static final class Parser {

    private final String name;
    private final List<Problem> problems = new ArrayList<>();

    Parser(String name) {
      this.name = name;
    }

    TemplateDescriptor descriptor(JsonNode root) {
      if (root == null || !root.isObject()) {
        problem("#", "the descriptor must be a JSON object");
        return null;
      }
      onlyKeys(root, "#", Set.of("language", "fields"));
      Locale language = null;
      JsonNode lang = root.get("language");
      if (lang == null) {
        problem("#", "'language' is required: the BCP 47 tag of the default variant, e.g. \"en\"");
      } else if (!lang.isTextual() || !LanguageVariants.isLanguageTag(lang.asText())) {
        problem(
            "#/language", "'language' must be a BCP 47 language tag such as \"en\" or \"de-AT\"");
      } else {
        language = Locale.forLanguageTag(lang.asText());
      }
      Map<String, Field> fields = Map.of();
      JsonNode fieldsNode = root.get("fields");
      if (fieldsNode == null) {
        problem("#", "'fields' is required: the definitions of the template data");
      } else {
        fields = fields(fieldsNode, "#/fields");
      }
      return new TemplateDescriptor(language, fields);
    }

    private Map<String, Field> fields(JsonNode node, String pointer) {
      Map<String, Field> fields = new LinkedHashMap<>();
      if (!node.isObject()) {
        problem(pointer, "'fields' must be an object of field definitions");
        return fields;
      }
      for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> entry = it.next();
        String fieldPointer = pointer + "/" + JsonPointer.escape(entry.getKey());
        if (!NAME.matcher(entry.getKey()).matches()
            || NAMES_TAKEN_BY_LOOPS.contains(entry.getKey())) {
          problem(
              fieldPointer,
              "field name '"
                  + entry.getKey()
                  + "' is not usable in templates; use letters, digits and '_', not starting with"
                  + " a digit, and not 'it'");
        }
        Field field = field(entry.getValue(), fieldPointer);
        if (field != null) {
          fields.put(entry.getKey(), field);
        }
      }
      return fields;
    }

    private Field field(JsonNode node, String pointer) {
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

    private String text(JsonNode node, String key, String pointer) {
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

    private void onlyKeys(JsonNode node, String pointer, Set<String> allowed) {
      node.fieldNames()
          .forEachRemaining(
              key -> {
                if (!allowed.contains(key)) {
                  problem(
                      pointer + "/" + JsonPointer.escape(key),
                      "unknown key '" + key + "'; allowed: " + String.join(", ", sorted(allowed)));
                }
              });
    }

    private static List<String> sorted(Set<String> set) {
      return set.stream().sorted().toList();
    }

    private void problem(String pointer, String detail) {
      problems.add(new Problem(Problem.TEMPLATE_ERROR, detail, name + pointer));
    }
  }
}
