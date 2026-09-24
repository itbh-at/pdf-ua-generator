/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import at.itbh.pdfuagen.core.LanguageVariants;
import at.itbh.pdfuagen.core.Messages;
import at.itbh.pdfuagen.core.RenderException;
import at.itbh.pdfuagen.core.TemplateRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The names a document template or layout may use, for the completion of an editor: the fields of
 * the data model, and the areas, components, catalog styles and texts of the layout. Derived from
 * the descriptors, without parsing a template; a descriptor that does not parse contributes nothing
 * (the checks report it).
 *
 * @param fields the data fields; object properties as {@code a.b}, the entries of a list as {@code
 *     items[]} and their properties as {@code items[].price}
 * @param areas the areas a document template fills ({@code {#title}…{/title}})
 * @param components the components of the layout ({@code {#box title="…"}…{/box}})
 * @param styles the catalog styles ({@code class="lead"})
 * @param texts the keys of the layout's texts ({@code {msg:page}})
 */
public record Vocabulary(
    List<FieldName> fields,
    List<AreaName> areas,
    List<ComponentName> components,
    List<StyleName> styles,
    List<String> texts) {

  /** A data field; {@code type} is a field type id: text, number, date, boolean, … */
  public record FieldName(String path, String type, String label, String description) {}

  public record AreaName(String name, String label, boolean required) {}

  public record ComponentName(
      String name, String label, List<String> parameters, List<String> required) {}

  /** A catalog style; {@code kind} is {@code paragraph} or {@code character}. */
  public record StyleName(String name, String kind, String label) {}

  private static final String LAYOUT = "layout/";

  /**
   * The vocabulary of a document template composed with a layout (its files under {@code layout/}),
   * or of a layout on its own.
   *
   * @param templateId the default variant, e.g. {@code template.xhtml}
   */
  public static Vocabulary of(TemplateRepository repository, String templateId) {
    boolean isLayout = repository.resource(LayoutDescriptor.FILE).isPresent();
    Optional<LayoutDescriptor> layout =
        repository
            .resource(LAYOUT + LayoutDescriptor.FILE)
            .flatMap(b -> parse(() -> LayoutDescriptor.parse(b, LAYOUT + LayoutDescriptor.FILE)));
    List<FieldName> fields = new ArrayList<>();
    layout.ifPresent(l -> fields(l.fields(), "", fields));
    if (!isLayout) {
      String path = LanguageVariants.descriptorPath(templateId);
      repository
          .resource(path)
          .flatMap(b -> parse(() -> TemplateDescriptor.parse(b, path)))
          .ifPresent(d -> fields(d.fields(), "", fields));
    }
    List<AreaName> areas = new ArrayList<>();
    List<ComponentName> components = new ArrayList<>();
    List<StyleName> styles = new ArrayList<>();
    layout.ifPresent(
        l -> {
          l.areas().forEach((name, a) -> areas.add(new AreaName(name, a.label(), a.required())));
          l.components()
              .forEach(
                  (name, c) ->
                      components.add(
                          new ComponentName(
                              name,
                              c.label(),
                              List.copyOf(c.parameters().keySet()),
                              c.parameters().entrySet().stream()
                                  .filter(p -> p.getValue().required())
                                  .map(Map.Entry::getKey)
                                  .toList())));
          l.styles()
              .forEach(
                  (name, s) ->
                      styles.add(
                          new StyleName(
                              name, s.kind().name().toLowerCase(Locale.ROOT), s.label())));
        });
    List<String> texts = new ArrayList<>();
    try {
      Messages.read(repository, LAYOUT + Messages.DEFAULT_FILE)
          .ifPresent(m -> texts.addAll(m.keySet()));
    } catch (IOException e) {
      // Reported by the checks.
    }
    return new Vocabulary(
        List.copyOf(fields),
        List.copyOf(areas),
        List.copyOf(components),
        List.copyOf(styles),
        List.copyOf(texts));
  }

  /**
   * What several vocabularies have in common: the names a document template may use with every
   * layout it lists.
   */
  public static Vocabulary common(List<Vocabulary> vocabularies) {
    if (vocabularies.isEmpty()) {
      return new Vocabulary(List.of(), List.of(), List.of(), List.of(), List.of());
    }
    Vocabulary first = vocabularies.getFirst();
    return new Vocabulary(
        common(vocabularies, Vocabulary::fields, FieldName::path),
        common(vocabularies, Vocabulary::areas, AreaName::name),
        common(vocabularies, Vocabulary::components, ComponentName::name),
        common(vocabularies, Vocabulary::styles, StyleName::name),
        first.texts().stream()
            .filter(t -> vocabularies.stream().allMatch(v -> v.texts().contains(t)))
            .toList());
  }

  private static <T> List<T> common(
      List<Vocabulary> all, Function<Vocabulary, List<T>> list, Function<T, String> name) {
    List<Set<String>> names =
        all.stream()
            .map(v -> list.apply(v).stream().map(name).collect(Collectors.toSet()))
            .toList();
    return list.apply(all.getFirst()).stream()
        .filter(t -> names.stream().allMatch(n -> n.contains(name.apply(t))))
        .toList();
  }

  private static void fields(Map<String, Field> fields, String prefix, Collection<FieldName> out) {
    fields.forEach(
        (name, field) -> {
          String path = prefix + name;
          out.add(new FieldName(path, field.type().id(), field.label(), field.description()));
          if (field.type() == FieldType.OBJECT) {
            fields(field.fields(), path + ".", out);
          } else if (field.type() == FieldType.LIST && field.items() != null) {
            Field items = field.items();
            out.add(
                new FieldName(path + "[]", items.type().id(), items.label(), items.description()));
            if (items.type() == FieldType.OBJECT) {
              fields(items.fields(), path + "[].", out);
            }
          }
        });
  }

  @FunctionalInterface
  private interface Parse<T> {
    T get() throws RenderException;
  }

  private static <T> Optional<T> parse(Parse<T> parse) {
    try {
      return Optional.of(parse.get());
    } catch (RenderException e) {
      return Optional.empty();
    }
  }
}
