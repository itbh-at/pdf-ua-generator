/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import at.itbh.pdfuagen.core.Formatters;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.schema.UsageScanner.Guard;
import at.itbh.pdfuagen.core.schema.UsageScanner.Step;
import at.itbh.pdfuagen.core.schema.UsageScanner.Usage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.quarkus.qute.Template;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The data a template needs: its field definitions, combined with what the template actually reads.
 *
 * <p>The field definitions give the types; the template's syntax tree gives which fields are
 * required. A field is required in its parent object when the template reads it without a default
 * wherever the parent exists — that is, not only under an {@code {#if}}, {@code {#else}}, {@code
 * {#when}} branch or loop that tests something other than the parent itself. Fields that the
 * template reads but the definitions lack, and reads that do not fit the field type, are errors.
 *
 * <p>Immutable and thread-safe once derived.
 */
public final class DataSchema {

  /** {@code src} of an image: a request attachment or an external HTTPS URL. */
  public static final Pattern IMAGE_SOURCE = Pattern.compile("^(attachment:\\S+|https://\\S+)$");

  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

  private static final ObjectWriter JSON =
      JsonMapper.builder()
          .build()
          .writer(
              new DefaultPrettyPrinter()
                  .withSeparators(
                      Separators.createDefaultInstance()
                          .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                          .withArrayEmptySeparator("")
                          .withObjectEmptySeparator(""))
                  .withArrayIndenter(new DefaultIndenter("  ", "\n"))
                  .withObjectIndenter(new DefaultIndenter("  ", "\n")));

  /** Methods Qute offers on every value (comparison, ternary). */
  private static final Set<String> ANY_METHODS =
      Set.of("eq", "==", "is", "ne", "!=", "ifTruthy", "?", "&&", "||");

  /** Arithmetic Qute offers on numbers. */
  private static final Set<String> NUMBER_ARITHMETIC = Set.of("plus", "+", "minus", "-", "mod");

  private static final Set<String> LIST_PROPERTIES = Set.of("size", "isEmpty", "length");

  /**
   * The outcome of a derivation.
   *
   * @param schema the schema; usable only if {@code problems} is empty
   * @param problems template errors: undefined fields, reads that do not fit the field type,
   *     unresolvable includes
   * @param warnings defined fields the template never reads
   */
  public record Derivation(DataSchema schema, List<Problem> problems, List<String> warnings) {}

  /** A field in the derived tree; {@code children} for objects and images. */
  private static final class Node {
    final Field field;
    final Map<String, Node> children = new LinkedHashMap<>();
    final Set<String> required = new LinkedHashSet<>();
    Node items;
    boolean used;

    Node(Field field) {
      this.field = field;
      switch (field.type()) {
        case OBJECT -> field.fields().forEach((name, f) -> children.put(name, new Node(f)));
        case LIST -> items = new Node(field.items());
        case IMAGE -> {
          children.put("src", new Node(new Field(FieldType.TEXT, null, null, null, null)));
          children.put("alt", new Node(new Field(FieldType.TEXT, null, null, null, null)));
          required.addAll(children.keySet());
        }
        default -> {}
      }
    }

    FieldType type() {
      return field.type();
    }
  }

  private final Node root;

  private DataSchema(Node root) {
    this.root = root;
  }

  /**
   * Derives the schema of a template.
   *
   * @param includes finds templates referenced by {@code {#include}}
   */
  public static Derivation derive(
      TemplateDescriptor descriptor,
      Template template,
      Function<String, Optional<Template>> includes) {
    UsageScanner.Result scan = UsageScanner.scan(template, includes);
    Node root = new Node(new Field(FieldType.OBJECT, null, null, descriptor.fields(), null));
    root.used = true;
    List<Problem> problems = new ArrayList<>(scan.problems());
    for (Usage usage : scan.usages()) {
      apply(root, usage, problems);
    }
    List<String> warnings = new ArrayList<>();
    unused(root, "", warnings);
    return new Derivation(new DataSchema(root), List.copyOf(problems), List.copyOf(warnings));
  }

  /** Walks one usage along the field tree, marking fields used and required. */
  private static void apply(Node root, Usage usage, List<Problem> problems) {
    Node current = root;
    List<Step> prefix = new ArrayList<>();
    String path = "";
    boolean indexed = false;
    for (Step step : usage.steps()) {
      String name =
          switch (step) {
            case Step.Prop p -> p.name();
            case Step.Call c -> c.name();
            case Step.Each e -> "[]";
          };
      if (step instanceof Step.Call && ANY_METHODS.contains(name)) {
        return;
      }
      if (step instanceof Step.Each && current.type() != FieldType.LIST) {
        problems.add(problem(describe(path, current) + " and cannot be iterated", usage));
        return;
      }
      switch (current.type()) {
        case OBJECT, IMAGE -> {
          if (!(step instanceof Step.Prop)) {
            problems.add(problem(describe(path, current) + " and has no '" + name + "'", usage));
            return;
          }
          Node child = current.children.get(name);
          String childPath = path.isEmpty() ? name : path + "." + name;
          if (child == null) {
            problems.add(
                problem(
                    current == root
                        ? "field '" + childPath + "' is not defined in the field definitions"
                        : "field '"
                            + childPath
                            + "' is not defined in the field definitions of '"
                            + path
                            + "'",
                    usage));
            return;
          }
          if (!usage.optional() && !indexed && covered(root, usage.guards(), prefix)) {
            current.required.add(name);
          }
          child.used = true;
          prefix.add(step);
          current = child;
          path = childPath;
        }
        case LIST -> {
          if (step instanceof Step.Each) {
            prefix.add(step);
            current = current.items;
            current.used = true;
            path = path + "[]";
          } else if (step instanceof Step.Prop && LIST_PROPERTIES.contains(name)) {
            return;
          } else if (step instanceof Step.Prop && (isIndex(name) || isEnd(name))
              || step instanceof Step.Call && name.equals("get")) {
            // One element, not every element: its fields are not required.
            indexed = true;
            prefix.add(new Step.Each());
            current = current.items;
            current.used = true;
            path = path + "[]";
          } else if (step instanceof Step.Call
              && (name.equals("take") || name.equals("takeLast") || name.equals("contains"))) {
            if (name.equals("contains")) {
              return;
            }
          } else {
            problems.add(problem(describe(path, current) + " and has no '" + name + "'", usage));
            return;
          }
        }
        case NUMBER -> {
          if (!Formatters.NUMBER_FUNCTIONS.contains(name) && !NUMBER_ARITHMETIC.contains(name)) {
            problems.add(
                problem(
                    describe(path, current)
                        + " and has no '"
                        + name
                        + "'; format it with .number or .currency('EUR')",
                    usage));
          }
          return;
        }
        case DATE -> {
          if (!Formatters.DATE_FUNCTIONS.contains(name)) {
            problems.add(
                problem(
                    describe(path, current) + " and has no '" + name + "'; format it with .date",
                    usage));
          }
          return;
        }
        case TEXT, BOOLEAN -> {
          String hint =
              Formatters.NUMBER_FUNCTIONS.contains(name)
                  ? "; define it as type number"
                  : Formatters.DATE_FUNCTIONS.contains(name) ? "; define it as type date" : "";
          problems.add(
              problem(describe(path, current) + " and has no '" + name + "'" + hint, usage));
          return;
        }
      }
    }
  }

  private static boolean isIndex(String name) {
    return !name.isEmpty() && name.chars().allMatch(Character::isDigit);
  }

  private static boolean isEnd(String name) {
    return name.equals("first") || name.equals("last");
  }

  /**
   * Whether every enclosing condition holds wherever the parent at {@code prefix} exists: each
   * guard must test the parent or one of its ancestors.
   */
  private static boolean covered(Node root, List<Guard> guards, List<Step> prefix) {
    for (Guard guard : guards) {
      boolean covers = false;
      for (List<Step> path : guard.paths()) {
        List<Step> data = dataPrefix(root, path);
        if (data.size() <= prefix.size() && prefix.subList(0, data.size()).equals(data)) {
          covers = true;
          break;
        }
      }
      if (!covers) {
        return false;
      }
    }
    return true;
  }

  /** The part of a path that names data: up to the first step that is not a defined field. */
  private static List<Step> dataPrefix(Node root, List<Step> path) {
    List<Step> data = new ArrayList<>();
    Node current = root;
    for (Step step : path) {
      if (step instanceof Step.Prop p
          && (current.type() == FieldType.OBJECT || current.type() == FieldType.IMAGE)
          && current.children.containsKey(p.name())) {
        current = current.children.get(p.name());
      } else if (step instanceof Step.Each && current.type() == FieldType.LIST) {
        current = current.items;
      } else {
        break;
      }
      data.add(step);
    }
    return data;
  }

  private static String describe(String path, Node node) {
    String type =
        switch (node.type()) {
          case TEXT -> "a text field";
          case NUMBER -> "a number field";
          case DATE -> "a date field";
          case BOOLEAN -> "a boolean field";
          case IMAGE -> "an image field";
          case LIST -> "a list";
          case OBJECT -> "an object";
        };
    return path.isEmpty() ? "the data root is " + type : "'" + path + "' is " + type;
  }

  private static Problem problem(String detail, Usage usage) {
    return new Problem(Problem.TEMPLATE_ERROR, detail, usage.location());
  }

  private static void unused(Node node, String path, List<String> warnings) {
    if (node.type() == FieldType.IMAGE) {
      return;
    }
    for (Map.Entry<String, Node> entry : node.children.entrySet()) {
      String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
      if (!entry.getValue().used) {
        warnings.add("field '" + childPath + "' is defined but not used by the template");
      } else {
        unused(entry.getValue(), childPath, warnings);
      }
    }
    if (node.items != null && node.items.used) {
      unused(node.items, path + "[]", warnings);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // JSON Schema

  /** The schema as JSON Schema (draft 2020-12), pretty-printed. */
  public String toJson() {
    try {
      return JSON.writeValueAsString(toJsonSchema());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The schema as JSON Schema (draft 2020-12), as maps and lists. */
  public Map<String, Object> toJsonSchema() {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    schema.putAll(jsonSchema(root, false));
    return schema;
  }

  private static Map<String, Object> jsonSchema(Node node, boolean nullable) {
    Map<String, Object> schema = new LinkedHashMap<>();
    if (node.field.label() != null) {
      schema.put("title", node.field.label());
    }
    if (node.field.description() != null) {
      schema.put("description", node.field.description());
    }
    String type =
        switch (node.type()) {
          case TEXT, DATE -> "string";
          case NUMBER -> "number";
          case BOOLEAN -> "boolean";
          case LIST -> "array";
          case OBJECT, IMAGE -> "object";
        };
    schema.put("type", nullable ? List.of(type, "null") : type);
    switch (node.type()) {
      case DATE -> schema.put("format", "date");
      case LIST -> schema.put("items", jsonSchema(node.items, false));
      case OBJECT, IMAGE -> {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, Node> entry : node.children.entrySet()) {
          boolean isRequired = node.required.contains(entry.getKey());
          Map<String, Object> property = jsonSchema(entry.getValue(), !isRequired);
          if (node.type() == FieldType.IMAGE && entry.getKey().equals("src")) {
            property.put("pattern", IMAGE_SOURCE.pattern());
          }
          properties.put(entry.getKey(), property);
          if (isRequired) {
            required.add(entry.getKey());
          }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
          schema.put("required", required);
        }
      }
      default -> {}
    }
    return schema;
  }

  /**
   * Whether two schemas describe the same data: same fields, same types, same required fields.
   * Labels and descriptions are ignored.
   */
  public boolean sameData(DataSchema other) {
    return signature(root, "").equals(signature(other.root, ""));
  }

  /** The differences to another schema, one line per field, e.g. {@code order.total: required}. */
  public List<String> differences(DataSchema other) {
    Set<String> mine = new LinkedHashSet<>(signature(root, ""));
    Set<String> theirs = new LinkedHashSet<>(signature(other.root, ""));
    List<String> result = new ArrayList<>();
    for (String line : mine) {
      if (!theirs.contains(line)) {
        result.add(line);
      }
    }
    for (String line : theirs) {
      if (!mine.contains(line)) {
        result.add(line);
      }
    }
    return result.stream().map(l -> l.substring(0, l.indexOf(':'))).distinct().toList();
  }

  private static List<String> signature(Node node, String path) {
    List<String> lines = new ArrayList<>();
    for (Map.Entry<String, Node> entry : node.children.entrySet()) {
      String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
      Node child = entry.getValue();
      lines.add(
          childPath
              + ": "
              + child.type().id()
              + (node.required.contains(entry.getKey()) ? " required" : " optional"));
      lines.addAll(signature(child, childPath));
    }
    if (node.items != null) {
      lines.add(path + "[]: " + node.items.type().id());
      lines.addAll(signature(node.items, path + "[]"));
    }
    return lines;
  }

  // ---------------------------------------------------------------------------------------------
  // Validation

  /**
   * Validates template data. Properties the schema does not know are allowed.
   *
   * @return one {@link Problem#INVALID_DATA} per violation, located by a JSON Pointer fragment
   *     ({@code #/items/2/price}); empty if the data is valid
   */
  public List<Problem> validate(Map<String, Object> data) {
    List<Problem> problems = new ArrayList<>();
    validate(root, data, new ArrayList<>(), problems);
    return List.copyOf(problems);
  }

  private static void validate(Node node, Object value, List<Object> pointer, List<Problem> out) {
    switch (node.type()) {
      case TEXT -> {
        if (!(value instanceof String)) {
          out.add(invalid("must be text (a JSON string)", pointer));
        }
      }
      case NUMBER -> {
        if (!(value instanceof Number)) {
          out.add(invalid("must be a number", pointer));
        }
      }
      case BOOLEAN -> {
        if (!(value instanceof Boolean)) {
          out.add(invalid("must be true or false", pointer));
        }
      }
      case DATE -> {
        if (!(value instanceof String text) || !isDate(text)) {
          out.add(invalid("must be a date in the form YYYY-MM-DD", pointer));
        }
      }
      case LIST -> {
        if (!(value instanceof List<?> list)) {
          out.add(invalid("must be a list (a JSON array)", pointer));
          return;
        }
        for (int i = 0; i < list.size(); i++) {
          pointer.add(i);
          if (list.get(i) == null) {
            out.add(invalid("must not be null", pointer));
          } else {
            validate(node.items, list.get(i), pointer, out);
          }
          pointer.removeLast();
        }
      }
      case OBJECT, IMAGE -> {
        if (!(value instanceof Map<?, ?> map)) {
          out.add(
              invalid(
                  node.type() == FieldType.IMAGE
                      ? "must be an image: an object with 'src' and 'alt'"
                      : "must be an object",
                  pointer));
          return;
        }
        for (Map.Entry<String, Node> entry : node.children.entrySet()) {
          pointer.add(entry.getKey());
          Object child = map.get(entry.getKey());
          if (child == null) {
            if (node.required.contains(entry.getKey())) {
              out.add(
                  invalid(
                      map.containsKey(entry.getKey()) ? "must not be null" : "is required",
                      pointer));
            }
          } else {
            validate(entry.getValue(), child, pointer, out);
            if (node.type() == FieldType.IMAGE
                && entry.getKey().equals("src")
                && child instanceof String src
                && !IMAGE_SOURCE.matcher(src).matches()) {
              out.add(invalid("must be 'attachment:<name>' or an https:// URL", pointer));
            }
          }
          pointer.removeLast();
        }
      }
    }
  }

  private static boolean isDate(String text) {
    if (!DATE.matcher(text).matches()) {
      return false;
    }
    try {
      LocalDate.parse(text);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private static Problem invalid(String detail, List<Object> pointer) {
    return new Problem(Problem.INVALID_DATA, detail, JsonPointer.fragment(pointer));
  }
}
