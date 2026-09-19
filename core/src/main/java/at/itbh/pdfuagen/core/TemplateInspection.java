/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import at.itbh.pdfuagen.core.schema.DataSchema;
import at.itbh.pdfuagen.core.schema.TemplateDescriptor;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    TemplateDescriptor descriptor = null;
    String descriptorPath = LanguageVariants.descriptorPath(baseId);
    Optional<byte[]> descriptorBytes = repository.resource(descriptorPath);
    if (descriptorBytes.isPresent()) {
      try {
        descriptor = TemplateDescriptor.parse(descriptorBytes.get(), descriptorPath);
      } catch (RenderException e) {
        problems.addAll(e.problems());
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
    if (descriptor != null && templates.containsKey(baseId)) {
      DataSchema.Derivation base =
          DataSchema.derive(descriptor, templates.get(baseId), id -> parse(engine, id, problems));
      problems.addAll(base.problems());
      warnings.addAll(base.warnings());
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
      if (problems.isEmpty()) {
        schema = base.schema();
      }
    }
    return new TemplateInspection(
        baseId,
        descriptorBytes.isPresent(),
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
