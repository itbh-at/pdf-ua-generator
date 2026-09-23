/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class TemplateCommandsTest {

  private static final Path DEMO = Path.of("..", "demo");
  private static final Path VARIANTS = Path.of("..", "core", "src", "test", "resources", "schema");

  @TempDir Path tmp;

  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();

  private int run(String... args) {
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    return cmd.execute(args);
  }

  private String demo() {
    return DEMO.resolve("content/template.xhtml").toString();
  }

  @Test
  void printsTheSchema() {
    assertEquals(
        0,
        run("schema", "-t", demo(), "--layout", DEMO.resolve("layout").toString()),
        err::toString);
    assertTrue(
        out.toString().contains("\"$schema\": \"https://json-schema.org/draft/2020-12/schema\""));
    assertTrue(out.toString().contains("\"format\": \"date\""));
  }

  @Test
  void schemaFailsWithoutFieldDefinitions() throws Exception {
    Path template = tmp.resolve("t.xhtml");
    Files.writeString(template, "<p>{x}</p>");
    assertEquals(1, run("schema", "-t", template.toString()));
    assertEquals(
        "error: the template has no field definitions; add t.json (t.xhtml)\n", err.toString());
  }

  @Test
  void validatesData() throws Exception {
    assertEquals(
        0,
        run(
            "validate",
            "-t",
            demo(),
            "--layout",
            DEMO.resolve("layout").toString(),
            "-d",
            DEMO.resolve("content/example.json").toString()));
    assertEquals("valid\n", out.toString());

    Path data = tmp.resolve("data.json");
    Files.writeString(
        data,
        "{\"customer\": {\"name\": \"x\"}, \"items\": [], \"order\": {\"number\": \"1\","
            + " \"date\": \"19.09.2026\", \"positions\": [{\"name\": \"a\", \"price\": 1}],"
            + " \"total\": 1}}",
        StandardCharsets.UTF_8);
    assertEquals(
        1,
        run(
            "validate",
            "-t",
            demo(),
            "--layout",
            DEMO.resolve("layout").toString(),
            "-d",
            data.toString()));
    assertEquals(
        "error: must be a date in the form YYYY-MM-DD (#/order/date)\n"
            + "error: is required (#/order/positions/0/quantity)\n",
        err.toString());
  }

  @Test
  void checksTheDemo() {
    int exit =
        run(
            "check",
            "-t",
            demo(),
            "--layout",
            DEMO.resolve("layout").toString(),
            "-d",
            DEMO.resolve("content/example.json").toString(),
            "-a",
            "photo=" + DEMO.resolve("content/example/photo.png"));
    assertEquals(0, exit, err::toString);
    assertEquals("passed\n", out.toString());
  }

  @Test
  void rendersTheLanguageVariant() throws Exception {
    Path data = tmp.resolve("data.json");
    Files.writeString(data, "{\"name\": \"Jane\", \"total\": 1450, \"due\": \"2026-12-31\"}");
    Path output = tmp.resolve("out.txt");
    int exit =
        run(
            "render",
            "-t",
            VARIANTS.resolve("variants").toString(),
            "-d",
            data.toString(),
            "-f",
            "text",
            "--lang",
            "de-AT-x-foo, en;q=0.5",
            "-o",
            output.toString());
    assertEquals(0, exit, err::toString);
    assertTrue(Files.readString(output).contains("bis 31. Dezember 2026."));

    assertEquals(
        2,
        run(
            "render",
            "-t",
            demo(),
            "--layout",
            DEMO.resolve("layout").toString(),
            "--lang",
            "de;q=x"));
  }
}
