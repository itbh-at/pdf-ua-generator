/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import at.itbh.pdfuagen.core.schema.DataSchema;
import at.itbh.pdfuagen.core.schema.Field;
import at.itbh.pdfuagen.core.schema.LayoutDescriptor;
import at.itbh.pdfuagen.core.schema.LayoutRules;
import at.itbh.pdfuagen.core.schema.TemplateDescriptor;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * What is known about a template before any data arrives: its descriptor, its language variants,
 * the parse result of each variant and the data schema they share.
 *
 * @param baseId id of the default variant
 * @param descriptorFound whether the template has a descriptor file, valid or not
 * @param descriptor the descriptor, or {@code null} if the template has none or it is invalid
 * @param variants variant ids by language tag, without the default variant
 * @param schema the data schema, or {@code null} without a valid descriptor or with problems
 * @param problems parse errors, descriptor errors, schema errors and differences between variants
 * @param warnings fields that are defined but not used
 */
record TemplateInspection(
    String baseId,
    boolean descriptorFound,
    TemplateDescriptor descriptor,
    Map<String, String> variants,
    DataSchema schema,
    List<Problem> problems,
    List<String> warnings) {

  static TemplateInspection inspect(Engine engine, TemplateRepository repository, String baseId) {
    List<Problem> problems = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    // A layout on its own has layout.json at its root and is also mounted at layout/.
    boolean isLayout = repository.resource(LayoutDescriptor.FILE).isPresent();
    String layoutPath = LayoutRules.PREFIX + LayoutDescriptor.FILE;
    Optional<byte[]> layoutBytes = repository.resource(layoutPath);
    LayoutDescriptor layout = null;
    if (layoutBytes.isPresent()) {
      try {
        layout = LayoutDescriptor.parse(layoutBytes.get(), layoutPath);
      } catch (RenderException e) {
        problems.addAll(e.problems());
      }
    }

    TemplateDescriptor descriptor = null;
    TemplateDescriptor content = null;
    String descriptorPath = LanguageVariants.descriptorPath(baseId);
    Optional<byte[]> descriptorBytes = isLayout ? layoutBytes : repository.resource(descriptorPath);
    if (isLayout) {
      if (layout != null) {
        descriptor =
            new TemplateDescriptor(
                layout.language(),
                java.util.List.of(),
                TemplateDescriptor.Styling.CATALOG,
                EnumSet.allOf(OutputFormat.class),
                layout.fields());
      }
    } else if (descriptorBytes.isPresent()) {
      try {
        content = TemplateDescriptor.parse(descriptorBytes.get(), descriptorPath);
        descriptor = content;
      } catch (RenderException e) {
        problems.addAll(e.problems());
      }
    }
    if (content != null) {
      if (content.layout() != null && layoutBytes.isEmpty()) {
        problems.add(
            new Problem(
                Problem.TEMPLATE_ERROR,
                "the template fills the layout "
                    + content.layout()
                    + ", which is not available; give it with the template (CLI: --layout)",
                descriptorPath + "#/layout"));
      } else if (content.layout() == null && layoutBytes.isPresent()) {
        problems.add(
            new Problem(
                Problem.TEMPLATE_ERROR,
                "a layout is given, but the template names none; add \"layouts\" to "
                    + descriptorPath,
                descriptorPath));
      }
      if (layout != null) {
        Map<String, Field> fields = new LinkedHashMap<>(layout.fields());
        for (Map.Entry<String, Field> field : content.fields().entrySet()) {
          if (fields.containsKey(field.getKey())) {
            problems.add(
                new Problem(
                    Problem.TEMPLATE_ERROR,
                    "field '" + field.getKey() + "' is already defined by the layout",
                    descriptorPath + "#/fields/" + field.getKey()));
          } else {
            fields.put(field.getKey(), field.getValue());
          }
        }
        descriptor = content.withFields(fields);
      }
    }

    Map<String, Template> templates = new LinkedHashMap<>();
    Map<String, String> variants = new LinkedHashMap<>();
    parse(engine, baseId, problems).ifPresent(t -> templates.put(baseId, t));
    for (String tag : repository.languages(baseId)) {
      String variantId = LanguageVariants.variantId(baseId, tag);
      variants.put(tag, variantId);
      parse(engine, variantId, problems).ifPresent(t -> templates.put(variantId, t));
    }

    DataSchema schema = null;
    DataSchema baseSchema = null;
    if (descriptor != null && templates.containsKey(baseId)) {
      DataSchema.Derivation base =
          DataSchema.derive(descriptor, templates.get(baseId), id -> parse(engine, id, problems));
      problems.addAll(base.problems());
      warnings.addAll(base.warnings());
      baseSchema = base.schema();
      for (Map.Entry<String, String> variant : variants.entrySet()) {
        Template template = templates.get(variant.getValue());
        if (template == null) {
          continue;
        }
        DataSchema.Derivation derived =
            DataSchema.derive(descriptor, template, id -> parse(engine, id, problems));
        problems.addAll(derived.problems());
        if (derived.problems().isEmpty() && !base.schema().sameData(derived.schema())) {
          problems.add(
              new Problem(
                  Problem.TEMPLATE_ERROR,
                  "language variant '"
                      + variant.getKey()
                      + "' reads different data than the default variant; every variant must use"
                      + " the same fields, required in the same places. Differs in: "
                      + String.join(", ", base.schema().differences(derived.schema())),
                  variant.getValue()));
        }
      }
    }
    if (layout != null) {
      Function<String, Optional<Template>> parser = id -> parse(engine, id, problems);
      LayoutRules.Findings findings = LayoutRules.checkLayout(layout, repository, parser);
      problems.addAll(findings.problems());
      warnings.addAll(findings.warnings());
      if (content != null) {
        List<String> ids = new ArrayList<>();
        ids.add(baseId);
        ids.addAll(variants.values());
        findings =
            LayoutRules.checkContent(layout, content, descriptorPath, ids, repository, parser);
        problems.addAll(findings.problems());
        warnings.addAll(findings.warnings());
      }
      if (descriptor != null && descriptor.language() != null) {
        Set<Locale> languages = new LinkedHashSet<>();
        languages.add(descriptor.language());
        variants.keySet().forEach(tag -> languages.add(Locale.forLanguageTag(tag)));
        findings = LayoutRules.checkTexts(repository, layout, languages);
        problems.addAll(findings.problems());
        warnings.addAll(findings.warnings());
      }
    }
    if (descriptor != null && templates.containsKey(baseId) && problems.isEmpty()) {
      schema = baseSchema;
    }
    return new TemplateInspection(
        baseId,
        isLayout || descriptorBytes.isPresent(),
        descriptor,
        Map.copyOf(variants),
        schema,
        problems.stream().distinct().toList(),
        List.copyOf(warnings));
  }

  private static Optional<Template> parse(Engine engine, String id, List<Problem> problems) {
    try {
      Template template = engine.getTemplate(id);
      if (template == null) {
        problems.add(new Problem(Problem.TEMPLATE_ERROR, "template not found", id));
      }
      return Optional.ofNullable(template);
    } catch (TemplateException e) {
      problems.add(new Problem(Problem.TEMPLATE_ERROR, e.getMessage(), location(e, id)));
      return Optional.empty();
    }
  }

  static String location(TemplateException e, String templateId) {
    if (e.getOrigin() == null) {
      return templateId;
    }
    String id =
        e.getOrigin().hasNonGeneratedTemplateId() ? e.getOrigin().getTemplateId() : templateId;
    return id
        + ", line "
        + e.getOrigin().getLine()
        + ", column "
        + e.getOrigin().getLineCharacterStart();
  }
}
