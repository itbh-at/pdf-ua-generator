/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import at.itbh.pdfuagen.core.DirectoryTemplateRepository;
import at.itbh.pdfuagen.core.DocumentRenderer;
import at.itbh.pdfuagen.core.JsonData;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The schema corpus under {@code src/test/resources/schema/}: one directory per case with {@code
 * template.xhtml} and {@code template.json}, and either {@code expected-schema.json} (the derived
 * JSON Schema) or {@code expected-problems.txt} (one {@code detail (location)} per line). Data
 * samples in {@code data/<name>.json} come with {@code data/<name>.expected}, the JSON Pointers of
 * all violations; every sample is also checked with an independent JSON Schema validator against
 * the derived schema.
 */
class SchemaCorpusTest {

  private static final Path CORPUS = Path.of("src/test/resources/schema");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  private final DocumentRenderer renderer = new DocumentRenderer();

  @TestFactory
  Stream<DynamicTest> corpus() throws IOException {
    List<DynamicTest> tests = new ArrayList<>();
    try (Stream<Path> cases = Files.list(CORPUS)) {
      for (Path dir : cases.filter(Files::isDirectory).sorted().toList()) {
        String name = dir.getFileName().toString();
        if (Files.exists(dir.resolve("expected-schema.json"))) {
          tests.add(DynamicTest.dynamicTest(name + ": schema", () -> schema(dir)));
        } else {
          tests.add(DynamicTest.dynamicTest(name + ": problems", () -> problems(dir)));
        }
        Path data = dir.resolve("data");
        if (Files.isDirectory(data)) {
          try (Stream<Path> samples = Files.list(data)) {
            for (Path sample :
                samples.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
              tests.add(
                  DynamicTest.dynamicTest(
                      name + ": data " + sample.getFileName(), () -> data(dir, sample)));
            }
          }
        }
      }
    }
    return tests.stream();
  }

  private DataSchema derive(Path dir) throws RenderException {
    return renderer.schema(new DirectoryTemplateRepository(dir, List.of()), "template.xhtml");
  }

  private void schema(Path dir) throws Exception {
    JsonNode expected = JSON.readTree(dir.resolve("expected-schema.json").toFile());
    JsonNode actual = JSON.readTree(derive(dir).toJson());
    assertEquals(expected, actual, () -> derivedJson(dir));
    // The derived schema is valid JSON Schema.
    REGISTRY.getSchema(JSON.writeValueAsString(actual), InputFormat.JSON);
  }

  private String derivedJson(Path dir) {
    try {
      return derive(dir).toJson();
    } catch (RenderException e) {
      return e.getMessage();
    }
  }

  private void problems(Path dir) throws IOException {
    List<String> expected =
        Files.readAllLines(dir.resolve("expected-problems.txt"), StandardCharsets.UTF_8).stream()
            .filter(l -> !l.isBlank())
            .toList();
    RenderException e = assertThrows(RenderException.class, () -> derive(dir));
    List<String> actual =
        e.problems().stream()
            .map(p -> p.detail() + (p.location() == null ? "" : " (" + p.location() + ")"))
            .toList();
    assertEquals(String.join("\n", expected), String.join("\n", actual));
  }

  private void data(Path dir, Path sample) throws Exception {
    Map<String, Object> data;
    try (InputStream in = Files.newInputStream(sample)) {
      data = JsonData.parse(in);
    }
    DataSchema schema = derive(dir);
    List<String> expected =
        Files.readAllLines(
                sample.resolveSibling(
                    sample.getFileName().toString().replace(".json", ".expected")))
            .stream()
            .filter(l -> !l.isBlank())
            .sorted()
            .toList();
    List<String> actual = schema.validate(data).stream().map(Problem::location).sorted().toList();
    assertEquals(expected, actual);

    Schema independent = REGISTRY.getSchema(schema.toJson(), InputFormat.JSON);
    var errors =
        independent.validate(
            Files.readString(sample),
            InputFormat.JSON,
            ctx -> ctx.executionConfig(c -> c.formatAssertionsEnabled(true)));
    assertEquals(
        expected.isEmpty(), errors.isEmpty(), () -> "independent validator reports " + errors);
  }
}
