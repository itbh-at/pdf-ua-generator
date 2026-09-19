/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import at.itbh.pdfuagen.core.Problem;
import io.quarkus.qute.Expression;
import io.quarkus.qute.IfSectionHelper;
import io.quarkus.qute.IncludeSectionHelper;
import io.quarkus.qute.LoopSectionHelper;
import io.quarkus.qute.SectionBlock;
import io.quarkus.qute.SectionNode;
import io.quarkus.qute.SetSectionHelper;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateNode;
import io.quarkus.qute.UserTagSectionHelper;
import io.quarkus.qute.WhenSectionHelper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Walks the Qute syntax tree of a template and records every use of template data: which path is
 * read, under which conditions, and whether a default makes it optional.
 *
 * <p>Names bound by {@code {#for}}, {@code {#each}}, {@code {#let}} and {@code {#set}} are resolved
 * to the data path they stand for, so {@code {#for p in order.positions}{p.price}} records {@code
 * order.positions[].price}. Included templates are walked in the scope of the {@code {#include}}.
 */
final class UsageScanner {

  /** One step of a data path. */
  sealed interface Step {
    /** A property: {@code .name}. */
    record Prop(String name) implements Step {}

    /** Every element of a list, as bound by a loop. */
    record Each() implements Step {}

    /** A virtual method call: {@code .name(…)}. */
    record Call(String name) implements Step {}
  }

  /**
   * The condition under which a part of the template is rendered.
   *
   * @param paths the data paths the condition tests; empty if it says nothing about the data (e.g.
   *     an {@code {#else}} block)
   */
  record Guard(List<List<Step>> paths) {}

  /**
   * One read of template data.
   *
   * @param steps the path from the data root
   * @param optional whether a default ({@code ??}, {@code ?:}, {@code or(…)}, {@code orEmpty})
   *     covers a missing value
   * @param guards the conditions of all enclosing sections, outermost first
   * @param location template id, line and column
   */
  record Usage(List<Step> steps, boolean optional, List<Guard> guards, String location) {}

  /**
   * A component call, {@code {#box title='…'}…{/box}}.
   *
   * @param parameters the names of the parameters passed
   */
  record Call(String component, List<String> parameters, String location) {}

  /** A layout text read with {@code {msg:key}}. */
  record MessageUse(String key, String location) {}

  record Result(
      List<Usage> usages, List<Problem> problems, List<Call> calls, List<MessageUse> messages) {}

  private static final List<String> DEFAULT_METHODS = List.of("or", "?:");
  private static final String OR_EMPTY = "orEmpty";

  /** A name bound in a section; {@code steps == null} for values that are not template data. */
  private record Binding(List<Step> steps, boolean loop) {}

  private final Function<String, Optional<Template>> includes;
  private final List<Usage> usages = new ArrayList<>();
  private final List<Problem> problems = new ArrayList<>();
  private final List<Call> calls = new ArrayList<>();
  private final List<MessageUse> messages = new ArrayList<>();
  private final Deque<Map<String, Binding>> scopes = new ArrayDeque<>();
  private final Deque<Guard> guards = new ArrayDeque<>();
  private final Deque<String> includeStack = new ArrayDeque<>();

  private UsageScanner(Function<String, Optional<Template>> includes) {
    this.includes = includes;
  }

  /**
   * @param includes finds templates referenced by {@code {#include}}
   */
  static Result scan(Template template, Function<String, Optional<Template>> includes) {
    UsageScanner scanner = new UsageScanner(includes);
    scanner.includeStack.push(template.getId());
    scanner.nodes(template.getNodes());
    return new Result(
        List.copyOf(scanner.usages),
        List.copyOf(scanner.problems),
        List.copyOf(scanner.calls),
        List.copyOf(scanner.messages));
  }

  private void nodes(List<TemplateNode> nodes) {
    for (TemplateNode node : nodes) {
      if (node.isExpression()) {
        node.getExpressions().forEach(e -> expression(e, false));
      } else if (node.isSection()) {
        section(node.asSection());
      }
    }
  }

  private void section(SectionNode section) {
    switch (section.getHelper()) {
      case LoopSectionHelper _ -> loop(section);
      case IfSectionHelper _ -> conditional(section);
      case WhenSectionHelper _ -> when(section);
      case SetSectionHelper _ -> let(section);
      // Before IncludeSectionHelper, which it extends.
      case UserTagSectionHelper _ -> component(section);
      case IncludeSectionHelper _ -> include(section);
      default -> {
        // #insert, #fragment and other sections without own data semantics.
        for (SectionBlock block : section.getBlocks()) {
          block.expressions.values().forEach(e -> expression(e, false));
          nodes(block.nodes);
        }
      }
    }
  }

  private void loop(SectionNode section) {
    SectionBlock main = section.getBlocks().getFirst();
    Expression iterable = main.expressions.get("iterable");
    List<Step> path = iterable == null ? null : expression(iterable, false);
    String alias = main.parameters.get("alias");
    if (alias == null || alias.equals("$empty$")) {
      alias = "it";
    }
    List<Step> element = null;
    if (path != null) {
      element = new ArrayList<>(path);
      element.add(new Step.Each());
    }
    guards.push(new Guard(path == null ? List.of() : List.of(path)));
    scopes.push(Map.of(alias, new Binding(element == null ? null : List.copyOf(element), true)));
    nodes(main.nodes);
    scopes.pop();
    guards.pop();
    // {#else} of a loop: rendered when the list is empty.
    for (SectionBlock block : section.getBlocks().subList(1, section.getBlocks().size())) {
      guarded(new Guard(List.of()), () -> nodes(block.nodes));
    }
  }

  private void conditional(SectionNode section) {
    List<SectionBlock> blocks = section.getBlocks();
    for (int i = 0; i < blocks.size(); i++) {
      SectionBlock block = blocks.get(i);
      if (i > 0) {
        // {#else if} and {#else}: reached only when every earlier condition was false.
        guards.push(new Guard(List.of()));
      }
      List<List<Step>> paths = new ArrayList<>();
      for (Expression condition : block.expressions.values()) {
        List<Step> path = expression(condition, false);
        if (path != null) {
          paths.add(path);
        }
      }
      guarded(new Guard(List.copyOf(paths)), () -> nodes(block.nodes));
    }
    for (int i = 1; i < blocks.size(); i++) {
      guards.pop();
    }
  }

  private void when(SectionNode section) {
    List<SectionBlock> blocks = section.getBlocks();
    SectionBlock main = blocks.getFirst();
    main.expressions.values().forEach(e -> expression(e, false));
    for (SectionBlock block : blocks.subList(1, blocks.size())) {
      guarded(
          new Guard(List.of()),
          () -> {
            block.expressions.values().forEach(e -> expression(e, false));
            nodes(block.nodes);
          });
    }
  }

  private void let(SectionNode section) {
    SectionBlock main = section.getBlocks().getFirst();
    Map<String, Binding> scope = new HashMap<>();
    main.expressions.forEach(
        (key, value) -> {
          List<Step> path = value.isLiteral() ? null : expression(value, false);
          scope.put(key, new Binding(path, false));
        });
    scopes.push(scope);
    nodes(main.nodes);
    scopes.pop();
  }

  /**
   * A component: its parameters are read in the caller's scope, its nested content too. The
   * component's own template reads only its parameters and is checked on its own.
   */
  private void component(SectionNode section) {
    SectionBlock main = section.getBlocks().getFirst();
    List<String> parameters = new ArrayList<>();
    main.parameters.forEach(
        (key, value) -> {
          if (!key.startsWith("_") && !(key.equals("it") && value.equals("it"))) {
            parameters.add(key);
          }
        });
    calls.add(new Call(section.getName(), List.copyOf(parameters), location(section.getOrigin())));
    main.expressions.values().forEach(e -> expression(e, false));
    for (SectionBlock block : section.getBlocks()) {
      nodes(block.nodes);
    }
  }

  private void include(SectionNode section) {
    SectionBlock main = section.getBlocks().getFirst();
    String id = main.parameters.get("template");
    if (id != null && !includeStack.contains(id)) {
      Optional<Template> included = includes.apply(id);
      if (included.isEmpty()) {
        problems.add(
            new Problem(
                Problem.TEMPLATE_ERROR,
                "included template '" + id + "' not found",
                location(section.getOrigin())));
      } else {
        includeStack.push(id);
        nodes(included.get().getNodes());
        includeStack.pop();
      }
    }
    // Blocks of the {#include} fill the {#insert} areas of the included template.
    for (SectionBlock block : section.getBlocks()) {
      nodes(block.nodes);
    }
  }

  private void guarded(Guard guard, Runnable body) {
    guards.push(guard);
    body.run();
    guards.pop();
  }

  /**
   * Records the data read by an expression.
   *
   * @return the data path the expression starts with, up to a default or a value that is not
   *     template data; {@code null} if it reads no template data
   */
  private List<Step> expression(Expression expression, boolean optional) {
    if (expression.isLiteral()) {
      return null;
    }
    String location = location(expression.getOrigin());
    List<Expression.Part> parts = expression.getParts();
    List<Step> steps;
    int start;
    if (expression.hasNamespace()) {
      if (expression.getNamespace().equals(at.itbh.pdfuagen.core.Messages.NAMESPACE)) {
        messages.add(new MessageUse(parts.getFirst().getName(), location));
        return null;
      }
      if (expression.getNamespace().equals("doc")) {
        return null;
      }
      if (!expression.getNamespace().equals("data")) {
        problems.add(
            new Problem(
                Problem.TEMPLATE_ERROR,
                "unknown namespace '" + expression.getNamespace() + ":'",
                location));
        return null;
      }
      steps = new ArrayList<>();
      start = 0;
    } else {
      Expression.Part first = parts.getFirst();
      if (first.isVirtualMethod()) {
        first.asVirtualMethod().getParameters().forEach(p -> expression(p, optional));
        steps = null;
      } else {
        steps = resolve(first.getName());
      }
      start = 1;
    }
    List<Step> subject = steps;
    boolean defaulted = false;
    for (Expression.Part part : parts.subList(start, parts.size())) {
      String name = part.getName();
      if (part.isVirtualMethod()) {
        boolean isDefault = DEFAULT_METHODS.contains(name);
        // The alternative value is read only when the path before it is missing.
        part.asVirtualMethod().getParameters().forEach(p -> expression(p, optional || isDefault));
        if (isDefault) {
          defaulted = true;
          subject = steps;
          steps = null;
        } else if (steps != null) {
          steps.add(new Step.Call(name));
        }
      } else if (name.equals(OR_EMPTY)) {
        defaulted = true;
        subject = steps;
        steps = null;
      } else if (steps != null) {
        steps.add(new Step.Prop(name));
      }
    }
    if (defaulted) {
      if (subject != null) {
        record(subject, true, location);
      }
      return subject == null ? null : List.copyOf(subject);
    }
    if (steps != null) {
      record(steps, optional, location);
      return List.copyOf(steps);
    }
    return null;
  }

  /** The path a name stands for: a bound name, loop metadata ({@code null}) or top-level data. */
  private List<Step> resolve(String name) {
    for (Map<String, Binding> scope : scopes) {
      if (scope.containsKey(name)) {
        Binding binding = scope.get(name);
        return binding.steps() == null ? null : new ArrayList<>(binding.steps());
      }
      for (Map.Entry<String, Binding> entry : scope.entrySet()) {
        // Iteration metadata such as item_index, item_hasNext.
        if (entry.getValue().loop() && name.startsWith(entry.getKey() + "_")) {
          return null;
        }
      }
    }
    List<Step> steps = new ArrayList<>();
    steps.add(new Step.Prop(name));
    return steps;
  }

  private void record(List<Step> steps, boolean optional, String location) {
    List<Guard> enclosing = new ArrayList<>(guards);
    java.util.Collections.reverse(enclosing);
    usages.add(new Usage(List.copyOf(steps), optional, List.copyOf(enclosing), location));
  }

  static String location(io.quarkus.qute.TemplateNode.Origin origin) {
    return origin.getTemplateId()
        + ", line "
        + origin.getLine()
        + ", column "
        + origin.getLineCharacterStart();
  }
}
