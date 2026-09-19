/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class RenderCommandTest {

  private static final Path DEMO = Path.of("..", "demo");

  @TempDir Path tmp;

  private final StringWriter err = new StringWriter();

  private int run(String... args) {
    CommandLine cmd = Main.commandLine();
    cmd.setErr(new PrintWriter(err));
    cmd.setOut(new PrintWriter(new StringWriter()));
    return cmd.execute(args);
  }

  @Test
  void rendersVerifiedPdf() throws Exception {
    Path out = tmp.resolve("demo.pdf");
    int exit =
        run(
            "render",
            "-t",
            DEMO.resolve("demo.xhtml").toString(),
            "--layout",
            DEMO.resolve("layout").toString(),
            "-d",
            DEMO.resolve("data-email.json").toString(),
            "-o",
            out.toString(),
            "--verify");

    assertEquals(0, exit, err::toString);
    assertTrue(
        new String(Files.readAllBytes(out), 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)
            .startsWith("%PDF-"));
  }

  @Test
  void rendersXhtml() throws Exception {
    Path out = tmp.resolve("demo.xhtml");
    int exit =
        run(
            "render",
            "-t",
            DEMO.resolve("demo.xhtml").toString(),
            "--layout",
            DEMO.resolve("layout").toString(),
            "-d",
            DEMO.resolve("data-email.json").toString(),
            "-f",
            "XHTML",
            "-o",
            out.toString());

    assertEquals(0, exit, err::toString);
    assertTrue(Files.readString(out).contains("Second bullet item"));
  }

  @Test
  void reportsProblemsWithExitCodeOne() throws Exception {
    Path template = tmp.resolve("t.xhtml");
    Files.writeString(
        template,
        "<html lang=\"en\"><head><title>T</title></head><body>"
            + "<img src=\"attachment:photo\" alt=\"Photo\"/></body></html>");

    assertEquals(
        1, run("render", "-t", template.toString(), "-o", tmp.resolve("t.pdf").toString()));
    assertTrue(err.toString().contains("attachment 'photo' was not provided"), err::toString);
  }

  @Test
  void missingDataValueIsAnError() throws Exception {
    assertEquals(
        1,
        run(
            "render",
            "-t",
            DEMO.resolve("demo.xhtml").toString(),
            "--layout",
            DEMO.resolve("layout").toString(),
            "-o",
            tmp.resolve("x.pdf").toString()));
    // The data is validated against the schema before Qute runs.
    assertTrue(err.toString().contains("error: is required (#/customer)"), err::toString);
  }

  @Test
  void rendersOfficeAndTextFormats() throws Exception {
    for (String format : new String[] {"docx", "odt", "text"}) {
      Path out = tmp.resolve("demo." + format);
      int exit =
          run(
              "render",
              "-t",
              DEMO.resolve("demo.xhtml").toString(),
              "--layout",
              DEMO.resolve("layout").toString(),
              "-d",
              DEMO.resolve("data.json").toString(),
              "-a",
              "photo=" + DEMO.resolve("photo.png"),
              "-f",
              format,
              "-o",
              out.toString());
      assertEquals(0, exit, err::toString);
      assertTrue(Files.size(out) > 0);
    }
  }

  @Test
  void emailHtmlNeedsAPublicBaseUrlForAssets() {
    String[] args = {
      "render",
      "-t",
      DEMO.resolve("demo.xhtml").toString(),
      "--layout",
      DEMO.resolve("layout").toString(),
      "-d",
      DEMO.resolve("data-email.json").toString(),
      "-f",
      "email-html",
      "-o",
      tmp.resolve("demo.html").toString()
    };
    assertEquals(1, run(args));
    assertTrue(err.toString().contains("public base URL"), err::toString);
  }

  @Test
  void verifyCommandChecksPdfFiles() throws Exception {
    Path pdf = tmp.resolve("demo.pdf");
    run(
        "render",
        "-t",
        DEMO.resolve("demo.xhtml").toString(),
        "--layout",
        DEMO.resolve("layout").toString(),
        "-d",
        DEMO.resolve("data-email.json").toString(),
        "-o",
        pdf.toString());
    assertEquals(0, run("verify", pdf.toString()), err::toString);
  }

  @Test
  void verifyRequiresPdf() {
    assertEquals(
        2,
        run(
            "render",
            "-t",
            DEMO.resolve("demo.xhtml").toString(),
            "--layout",
            DEMO.resolve("layout").toString(),
            "-f",
            "xhtml",
            "--verify"));
  }
}
