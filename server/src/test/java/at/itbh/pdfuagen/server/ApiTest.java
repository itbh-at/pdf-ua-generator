/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.itbh.pdfuagen.server.store.Bundle;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** The API end to end, against PostgreSQL from Dev Services, with the demo template. */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiTest {

  private static final Path DEMO = Path.of("..", "demo");
  private static final String PROBLEM = "urn:itbh:pdf-ua-generator:problem:";
  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  static byte[] demoBundle(int layoutRevision) throws Exception {
    return demoBundle("demo-layout@" + layoutRevision);
  }

  /** The demo content, pinning the given layout revision. */
  static byte[] demoBundle(String layout) throws Exception {
    Map<String, byte[]> files = new TreeMap<>();
    files.put("template.xhtml", Files.readAllBytes(DEMO.resolve("demo.xhtml")));
    files.put(
        "template.json",
        Files.readString(DEMO.resolve("demo.json"))
            .replace("demo-layout@1", layout)
            .getBytes(StandardCharsets.UTF_8));
    files.put("example.json", Files.readAllBytes(DEMO.resolve("data.json")));
    files.put("example/photo.png", Files.readAllBytes(DEMO.resolve("photo.png")));
    return Bundle.write(files);
  }

  /** The demo layout; {@code header} replaces its page header text. */
  static byte[] layoutBundle(String header) throws Exception {
    return layoutBundle("layout", header);
  }

  static byte[] layoutBundle(String directory, String header) throws Exception {
    Path layout = DEMO.resolve(directory);
    Map<String, byte[]> files = new TreeMap<>();
    try (var paths = Files.walk(layout)) {
      for (Path file : paths.filter(Files::isRegularFile).toList()) {
        files.put(layout.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
      }
    }
    files.put(
        "messages.json",
        Files.readString(layout.resolve("messages.json"))
            .replace("Accessible document example", header)
            .getBytes(StandardCharsets.UTF_8));
    files.put("example.json", "{}".getBytes(StandardCharsets.UTF_8));
    return Bundle.write(files);
  }

  private static String xhtml(String template) throws Exception {
    return given()
        .contentType("application/json")
        .body(data("data-email.json"))
        .post("/templates/" + template + "/render?format=xhtml")
        .then()
        .statusCode(200)
        .extract()
        .asString();
  }

  private static String data(String file) throws Exception {
    return Files.readString(DEMO.resolve(file), StandardCharsets.UTF_8);
  }

  @Test
  @Order(0)
  void publishesALayout() throws Exception {
    given()
        .contentType("application/zip")
        .body(layoutBundle("Accessible document example"))
        .post("/templates/demo-layout/revisions")
        .then()
        .statusCode(201)
        .body("kind", equalTo("layout"))
        .body("problems.size()", equalTo(0));
    given()
        .post("/templates/demo-layout/revisions/1/publish")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200);
  }

  @Test
  @Order(1)
  void createsADraft() throws Exception {
    given()
        .contentType("application/zip")
        .body(demoBundle(1))
        .post("/templates/demo/revisions")
        .then()
        .statusCode(201)
        .header("Location", containsString("/templates/demo/revisions/1"))
        .body("revision", equalTo(1))
        .body("status", equalTo("draft"))
        .body("language", equalTo("en"))
        .body("kind", equalTo("content"))
        .body("layout", equalTo("demo-layout@1"))
        .body("formats", hasItem("docx"))
        .body("problems.size()", equalTo(0));

    // Not published yet.
    given()
        .get("/templates/demo/schema")
        .then()
        .statusCode(404)
        .contentType("application/problem+json")
        .body("type", equalTo(PROBLEM + "not-found"));
  }

  @Test
  @Order(2)
  void rendersADraftAsPreview() throws Exception {
    byte[] pdf =
        given()
            .contentType("application/json")
            .accept("application/pdf")
            .body(data("data-email.json"))
            .post("/templates/demo/revisions/1/render")
            .then()
            .statusCode(200)
            .contentType("application/pdf")
            .header("Content-Language", "en")
            .header("Vary", containsString("Accept-Language"))
            .extract()
            .asByteArray();
    assertTrue(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF-"));
  }

  @Test
  @Order(3)
  void publishes() {
    given()
        .post("/templates/demo/revisions/1/publish")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("status", equalTo("published"));
    given()
        .post("/templates/demo/revisions/1/publish")
        .then()
        .statusCode(409)
        .body("type", equalTo(PROBLEM + "conflict"));
    given()
        .get("/templates/demo/schema")
        .then()
        .statusCode(200)
        .contentType("application/schema+json")
        .body("$schema", equalTo("https://json-schema.org/draft/2020-12/schema"));
  }

  @Test
  @Order(4)
  void validates() throws Exception {
    given()
        .contentType("application/json")
        .body(data("data.json"))
        .post("/templates/demo/validate")
        .then()
        .statusCode(204);
    given()
        .contentType("application/json")
        .body(
            "{\"customer\": {}, \"items\": [], \"order\": {\"number\": \"1\", \"date\": \"x\","
                + " \"positions\": [], \"total\": 1}}")
        .post("/templates/demo/validate")
        .then()
        .statusCode(422)
        .contentType("application/problem+json")
        .body("type", equalTo(PROBLEM + "invalid-data"))
        .body("errors.pointer", hasItem("#/customer/name"))
        .body("errors.pointer", hasItem("#/order/date"));
  }

  @Test
  @Order(5)
  void rendersWithAttachmentsAndNegotiatesTheFormat() throws Exception {
    given()
        .multiPart("data", data("data.json"), "application/json")
        .multiPart("photo", DEMO.resolve("photo.png").toFile(), "image/png")
        .accept(DOCX)
        .post("/templates/demo/render")
        .then()
        .statusCode(200)
        .contentType(DOCX)
        .header("Template-Revision", "1");

    given()
        .contentType("application/json")
        .accept("application/json")
        .body(data("data-email.json"))
        .post("/templates/demo/render")
        .then()
        .statusCode(406)
        .body("type", equalTo(PROBLEM + "format-not-supported"));

    given()
        .contentType("application/json")
        .body(data("data.json"))
        .post("/templates/demo/render?format=pdf")
        .then()
        .statusCode(422)
        .body("type", equalTo(PROBLEM + "attachment-missing"));
  }

  @Test
  @Order(6)
  void servesEmailImagesAsPublicAssets() throws Exception {
    String html =
        given()
            .contentType("application/json")
            .body(data("data-email.json"))
            .post("/templates/demo/render?format=email-html")
            .then()
            .statusCode(200)
            .contentType(startsWith("text/html"))
            .extract()
            .asString();
    Matcher asset = Pattern.compile("/assets/([0-9a-f]{64}\\.png)").matcher(html);
    assertTrue(asset.find(), html);
    given()
        .get("/assets/" + asset.group(1))
        .then()
        .statusCode(200)
        .contentType("image/png")
        .header("Cache-Control", containsString("immutable"));
  }

  @Test
  @Order(7)
  void selectsTheLanguageVariant() throws Exception {
    Map<String, byte[]> files = new TreeMap<>();
    files.put(
        "template.json",
        "{\"language\": \"en\", \"formats\": [\"text\"], \"fields\": {\"name\": {\"type\": \"text\"}}}"
            .getBytes(StandardCharsets.UTF_8));
    files.put("template.xhtml", page("en", "Hello {name}"));
    files.put("template.de.xhtml", page("de", "Hallo {name}"));
    files.put("example.json", "{\"name\": \"Jane\"}".getBytes(StandardCharsets.UTF_8));
    given()
        .contentType("application/zip")
        .body(Bundle.write(files))
        .post("/templates/greeting/revisions")
        .then()
        .statusCode(201)
        .body("languages", hasItem("de"));
    given().post("/templates/greeting/revisions/1/publish").then().statusCode(200);

    Response german =
        given()
            .contentType("application/json")
            .header("Accept-Language", "de-AT, en;q=0.5")
            .body("{\"name\": \"Jane\"}")
            .post("/templates/greeting/render");
    german.then().statusCode(200).header("Content-Language", "de");
    assertTrue(german.asString().contains("Hallo Jane"), german.asString());

    given()
        .contentType("application/json")
        .header("Accept-Language", "de")
        .body("{\"name\": \"Jane\"}")
        .post("/templates/greeting/render?lang=fr")
        .then()
        .statusCode(200)
        .header("Content-Language", "en");
  }

  @Test
  @Order(8)
  void aLayoutChangeReachesContentOnlyWhenItMovesToTheNewRevision() throws Exception {
    given()
        .contentType("application/zip")
        .body(layoutBundle("Changed header"))
        .post("/templates/demo-layout/revisions")
        .then()
        .statusCode(201)
        .body("revision", equalTo(2));
    given().post("/templates/demo-layout/revisions/2/publish").then().statusCode(200);

    // demo still pins revision 1 of the layout.
    String before = xhtml("demo");
    assertTrue(before.contains("Accessible document example"), before);
    assertTrue(!before.contains("Changed header"));

    given()
        .contentType("application/zip")
        .body(demoBundle(2))
        .post("/templates/demo/revisions")
        .then()
        .statusCode(201)
        .body("revision", equalTo(2))
        .body("layout", equalTo("demo-layout@2"));
    given().post("/templates/demo/revisions/2/publish").then().statusCode(200);
    assertTrue(xhtml("demo").contains("Changed header"));

    given()
        .get("/templates/demo-layout")
        .then()
        .statusCode(200)
        .body("kind", equalTo("layout"))
        .body("usedBy.find { it.revision == 1 }.layoutRevision", equalTo(1))
        .body("usedBy.find { it.revision == 2 }.layoutRevision", equalTo(2));
    given()
        .delete("/templates/demo-layout")
        .then()
        .statusCode(409)
        .body("type", equalTo(PROBLEM + "conflict"));
    given().delete("/templates/demo-layout/revisions/1").then().statusCode(409);
  }

  @Test
  @Order(9)
  void contentPinsOnlyPublishedLayouts() throws Exception {
    given()
        .contentType("application/zip")
        .body(layoutBundle("Draft header"))
        .post("/templates/demo-layout/revisions")
        .then()
        .statusCode(201)
        .body("revision", equalTo(3));
    given()
        .contentType("application/zip")
        .body(demoBundle(3))
        .post("/templates/demo/revisions")
        .then()
        .statusCode(201);
    given()
        .post("/templates/demo/revisions/3/publish")
        .then()
        .statusCode(422)
        .body("type", equalTo(PROBLEM + "publish-rejected"))
        .body("errors[0].detail", containsString("demo-layout@3 is not published"));
    // A template keeps its kind.
    given()
        .contentType("application/zip")
        .body(layoutBundle("x"))
        .post("/templates/demo/revisions")
        .then()
        .statusCode(409);
  }

  @Test
  @Order(10)
  void rendersTheSameDocumentInAnotherLayout() throws Exception {
    given()
        .contentType("application/zip")
        .body(layoutBundle("layout-memo", "Internal memo"))
        .post("/templates/memo-layout/revisions")
        .then()
        .statusCode(201);
    given().post("/templates/memo-layout/revisions/1/publish").then().statusCode(200);
    given()
        .contentType("application/zip")
        .body(demoBundle("memo-layout@1"))
        .post("/templates/demo-memo/revisions")
        .then()
        .statusCode(201);
    given()
        .post("/templates/demo-memo/revisions/1/publish")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200);

    String memo = xhtml("demo-memo");
    String plain = xhtml("demo");
    assertTrue(memo.contains("MEMORANDUM") && memo.contains("Hello Jane Doe."), memo);
    assertTrue(!plain.contains("MEMORANDUM") && plain.contains("Hello Jane Doe."), plain);
  }

  @Test
  @Order(11)
  void rejectsBadInput() {
    given()
        .contentType("application/zip")
        .body("not a zip".getBytes(StandardCharsets.UTF_8))
        .post("/templates/broken/revisions")
        .then()
        .statusCode(400)
        .body("type", equalTo(PROBLEM + "invalid-bundle"));
    given()
        .contentType("application/zip")
        .body(new byte[0])
        .post("/templates/Not_Valid/revisions")
        .then()
        .statusCode(400)
        .body("type", equalTo(PROBLEM + "invalid-request"));
    given().get("/templates/missing").then().statusCode(404);
    given().get("/assets/" + "0".repeat(64)).then().statusCode(404);
  }

  @Test
  @Order(12)
  void reportsReady() {
    given().get("/q/health/ready").then().statusCode(200);
    given().get("/q/health/live").then().statusCode(200);
  }

  private static byte[] page(String lang, String body) {
    return ("<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\""
            + lang
            + "\"><head><title>Greeting</title></head><body><p>"
            + body
            + "</p></body></html>")
        .getBytes(StandardCharsets.UTF_8);
  }
}
