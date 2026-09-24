/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server;

import static at.itbh.pdfuagen.server.DemoBundles.DEMO;
import static at.itbh.pdfuagen.server.DemoBundles.data;
import static at.itbh.pdfuagen.server.DemoBundles.demoBundle;
import static at.itbh.pdfuagen.server.DemoBundles.layoutBundle;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.itbh.pdfuagen.server.store.Bundle;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The API end to end, against PostgreSQL from Dev Services, with the demo template. A bundle is
 * validated and released for use on upload; a released revision is retired by archiving it.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiTest {

  private static final String PROBLEM = "urn:itbh:pdf-ua-generator:problem:";
  private static final String ZIP = "application/zip";
  private static final String MULTIPART_ALTERNATIVE = "multipart/alternative";
  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  @Inject Flyway flyway;

  // The ordered methods build up revisions from an empty database and assert their
  // numbers, so start from a clean schema. Without this the test fails on a reused
  // Dev Services database (testcontainers.reuse.enable=true) that still holds a
  // previous run's templates. The start-up warm-up is off under %test, so nothing
  // renders the leftovers concurrently with the tests before this clean runs.
  @BeforeAll
  void resetDatabase() {
    flyway.clean();
    flyway.migrate();
  }

  @Test
  @Order(0)
  void releasesALayoutOnUpload() throws Exception {
    given()
        .contentType(ZIP)
        .body(layoutBundle("Accessible document example"))
        .post("/templates/demo-layout/revisions")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .body("kind", equalTo("layout"))
        .body("revision", equalTo(1))
        .body("status", equalTo("published"));
  }

  @Test
  @Order(1)
  void releasesContentOnUpload() throws Exception {
    given()
        .contentType(ZIP)
        .body(demoBundle(1))
        .post("/templates/demo/revisions")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .header("Location", containsString("/templates/demo/revisions/1"))
        .body("revision", equalTo(1))
        .body("status", equalTo("published"))
        .body("language", equalTo("en"))
        .body("kind", equalTo("content"))
        .body("layouts", contains("demo-layout@1"))
        .body("formats", hasItem("docx"))
        .body("problems.size()", equalTo(0));

    // Released, so its schema is available at once.
    given()
        .get("/templates/demo/schema")
        .then()
        .statusCode(200)
        .contentType("application/schema+json")
        .body("$schema", equalTo("https://json-schema.org/draft/2020-12/schema"));
  }

  @Test
  @Order(2)
  void rendersARevisionAsPreview() throws Exception {
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
  void uploadingTheSameFilesAgainChangesNothing() throws Exception {
    given()
        .contentType(ZIP)
        .body(demoBundle(1))
        .post("/templates/demo/revisions")
        .then()
        .statusCode(200)
        .header("Content-Location", containsString("/templates/demo/revisions/1"))
        .body("revision", equalTo(1))
        .body("status", equalTo("published"));
    given()
        .get("/templates/demo")
        .then()
        .body("latestRevision", equalTo(1))
        .body("publishedRevision", equalTo(1));
  }

  @Test
  @Order(4)
  void validates() throws Exception {
    given()
        .contentType("application/json")
        .body(data("example.json"))
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
        .multiPart("data", data("example.json"), "application/json")
        .multiPart("photo", DEMO.resolve("content/example/photo.png").toFile(), "image/png")
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
        .body(data("example.json"))
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
        .contentType(ZIP)
        .body(Bundle.write(files))
        .post("/templates/greeting/revisions")
        .then()
        .statusCode(201)
        .body("status", equalTo("published"))
        .body("languages", hasItem("de"));

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
        .contentType(ZIP)
        .body(layoutBundle("Changed header"))
        .post("/templates/demo-layout/revisions")
        .then()
        .statusCode(201)
        .body("revision", equalTo(2))
        .body("status", equalTo("published"));

    // demo still pins revision 1 of the layout.
    String before = xhtml("demo");
    assertTrue(before.contains("Accessible document example"), before);
    assertTrue(!before.contains("Changed header"));

    given()
        .contentType(ZIP)
        .body(demoBundle(2))
        .post("/templates/demo/revisions")
        .then()
        .statusCode(201)
        .body("revision", equalTo(2))
        .body("status", equalTo("published"))
        .body("layouts", contains("demo-layout@2"));
    assertTrue(xhtml("demo").contains("Changed header"));

    given()
        .get("/templates/demo-layout")
        .then()
        .statusCode(200)
        .body("kind", equalTo("layout"))
        .body("usedBy.find { it.revision == 1 }.layoutRevision", equalTo(1))
        .body("usedBy.find { it.revision == 2 }.layoutRevision", equalTo(2));
    // A referenced layout cannot be deleted, nor a pinned revision archived.
    given()
        .delete("/templates/demo-layout")
        .then()
        .statusCode(409)
        .body("type", equalTo(PROBLEM + "conflict"));
    given().delete("/templates/demo-layout/revisions/1").then().statusCode(409);
  }

  @Test
  @Order(9)
  void rejectsContentThatPinsAMissingLayoutAndKeepsTheKind() throws Exception {
    // A content bundle pinning a layout revision that does not exist is rejected; nothing is kept.
    given()
        .contentType(ZIP)
        .body(demoBundle("demo-layout@99"))
        .post("/templates/demo-missing/revisions")
        .then()
        .statusCode(422)
        .body("type", equalTo(PROBLEM + "publish-rejected"))
        .body("errors[0].detail", containsString("demo-layout@99 does not exist"));
    given().get("/templates/demo-missing").then().statusCode(404);

    // A template keeps the kind of its first revision.
    given()
        .contentType(ZIP)
        .body(layoutBundle("x"))
        .post("/templates/demo/revisions")
        .then()
        .statusCode(409);
  }

  @Test
  @Order(10)
  void rendersTheSameDocumentInAnotherLayout() throws Exception {
    given()
        .contentType(ZIP)
        .body(layoutBundle("layout-memo", "Internal memo"))
        .post("/templates/memo-layout/revisions")
        .then()
        .statusCode(201)
        .body("status", equalTo("published"));
    given()
        .contentType(ZIP)
        .body(demoBundle("memo-layout@1"))
        .post("/templates/demo-memo/revisions")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .body("status", equalTo("published"));

    String memo = xhtml("demo-memo");
    String plain = xhtml("demo");
    assertTrue(memo.contains("MEMORANDUM") && memo.contains("Hello Jane Doe."), memo);
    assertTrue(!plain.contains("MEMORANDUM") && plain.contains("Hello Jane Doe."), plain);
  }

  @Test
  @Order(16)
  void rendersWithAnyListedLayout() throws Exception {
    // One revision listing both layouts: checked with each on upload, the first is the default.
    given()
        .contentType(ZIP)
        .body(demoBundle("demo-layout@2", "memo-layout@1"))
        .post("/templates/demo/revisions")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .body("revision", equalTo(3))
        .body("layouts", contains("demo-layout@2", "memo-layout@1"));

    Response byDefault = render("demo", "");
    byDefault.then().statusCode(200).header("Template-Layout", "demo-layout@2");
    assertTrue(!byDefault.asString().contains("MEMORANDUM"));
    Response memo = render("demo", "memo-layout");
    memo.then().statusCode(200).header("Template-Layout", "memo-layout@1");
    assertTrue(memo.asString().contains("MEMORANDUM"), memo.asString());
    render("demo", "memo-layout@1").then().statusCode(200);

    render("demo", "unknown-layout")
        .then()
        .statusCode(400)
        .body("type", equalTo(PROBLEM + "invalid-request"))
        .body("detail", containsString("demo-layout@2, memo-layout@1"));

    // A listed layout revision cannot be archived while released content lists it.
    given().delete("/templates/memo-layout/revisions/1").then().statusCode(409);
  }

  @Test
  @Order(17)
  void rejectsContentWhenOneListedLayoutIsMissing() throws Exception {
    given()
        .contentType(ZIP)
        .body(demoBundle("demo-layout@2", "missing-layout@1"))
        .post("/templates/demo-partial/revisions")
        .then()
        .statusCode(422)
        .body("errors[0].detail", containsString("missing-layout@1 does not exist"))
        .body("errors[0].location", equalTo("template.json#/layouts/1"));
    given().get("/templates/demo-partial").then().statusCode(404);
  }

  @Test
  @Order(11)
  void rejectsBadInput() {
    given()
        .contentType(ZIP)
        .body("not a zip".getBytes(StandardCharsets.UTF_8))
        .post("/templates/broken/revisions")
        .then()
        .statusCode(400)
        .body("type", equalTo(PROBLEM + "invalid-bundle"));
    given()
        .contentType(ZIP)
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

  /**
   * What the import job of the chart does: upload releases at once, and repeats without a change.
   */
  @Test
  @Order(13)
  void importsAndRepeatsWithoutAChange() throws Exception {
    given()
        .contentType(ZIP)
        .body(layoutBundle("Imported"))
        .post("/templates/imported-layout/revisions")
        .then()
        .statusCode(201)
        .body("revision", equalTo(1))
        .body("status", equalTo("published"));
    given()
        .contentType(ZIP)
        .body(demoBundle("imported-layout@1"))
        .post("/templates/imported/revisions")
        .then()
        .statusCode(201)
        .body("status", equalTo("published"));

    // The same bundle again: the stored revision, no new one.
    given()
        .contentType(ZIP)
        .body(demoBundle("imported-layout@1"))
        .post("/templates/imported/revisions")
        .then()
        .statusCode(200)
        .header("Content-Location", containsString("/templates/imported/revisions/1"))
        .body("revision", equalTo(1))
        .body("status", equalTo("published"));

    // Changed files are a new revision, released by the same call.
    given()
        .contentType(ZIP)
        .body(layoutBundle("Imported again"))
        .post("/templates/imported-layout/revisions")
        .then()
        .statusCode(201)
        .body("revision", equalTo(2))
        .body("status", equalTo("published"));
    // The released content keeps the layout revision it pins.
    given().get("/templates/imported").then().body("publishedRevision", equalTo(1));
  }

  @Test
  @Order(14)
  void rendersMultipartAlternativeAsTextThenHtml() throws Exception {
    String body =
        given()
            .contentType("application/json")
            .accept(MULTIPART_ALTERNATIVE)
            .body(data("data-email.json"))
            .post("/templates/demo/render")
            .then()
            .statusCode(200)
            .contentType(startsWith(MULTIPART_ALTERNATIVE))
            .header("Content-Language", "en")
            .extract()
            .asString();
    int text = body.indexOf("Content-Type: text/plain");
    int html = body.indexOf("Content-Type: text/html");
    assertTrue(text >= 0, body);
    assertTrue(html > text, "text part comes before the html part");
    assertTrue(body.contains("<html"), "the html part is the email HTML");
  }

  @Test
  @Order(15)
  void archivesReactivatesAndDeletes() throws Exception {
    // A content revision can be archived (retired); it is kept as history.
    given()
        .delete("/templates/imported/revisions/1")
        .then()
        .statusCode(200)
        .body("status", equalTo("archived"));
    given().get("/templates/imported").then().body("publishedRevision", nullValue());

    // Uploading the same files again releases it once more.
    given()
        .contentType(ZIP)
        .body(demoBundle("imported-layout@1"))
        .post("/templates/imported/revisions")
        .then()
        .statusCode(200)
        .body("status", equalTo("published"));

    // The content is deleted; then its layout, which nothing references any more.
    given().delete("/templates/imported").then().statusCode(204);
    given().delete("/templates/imported-layout").then().statusCode(204);
    given().get("/templates/imported").then().statusCode(404);
  }

  private static byte[] page(String lang, String body) {
    return ("<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\""
            + lang
            + "\"><head><title>Greeting</title></head><body><p>"
            + body
            + "</p></body></html>")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @Order(18)
  void editsChecksPreviewsAndSavesFilesOfARevision() throws Exception {
    // demo@3 lists demo-layout@2 and memo-layout@1 (see rendersWithAnyListedLayout).
    String source =
        given()
            .get("/templates/demo/revisions/3/files/template.xhtml")
            .then()
            .statusCode(200)
            .contentType(startsWith("application/xhtml+xml"))
            .extract()
            .asString();
    assertTrue(source.contains("Hello {customer.name}."), source);
    given().get("/templates/demo/revisions/3/files/nope.xhtml").then().statusCode(404);

    // The quick check: nothing changed is fine; a broken language variant has a located problem.
    given()
        .contentType("application/json")
        .body(Map.of())
        .post("/templates/demo/revisions/3/check")
        .then()
        .statusCode(200)
        .body("problems.size()", equalTo(0));
    given()
        .contentType("application/json")
        .body(Map.of("files", Map.of("template.xhtml", source.replace("{customer.name}", "{#if}"))))
        .post("/templates/demo/revisions/3/check")
        .then()
        .statusCode(200)
        .body("problems.size()", org.hamcrest.Matchers.greaterThan(0))
        .body("problems[0].location", containsString("template.xhtml, line"));

    // Preview the unsaved text in the memo layout; nothing is stored.
    String edited = source.replace("Hello {customer.name}.", "Good day {customer.name}.");
    String preview =
        given()
            .contentType("application/json")
            .body(Map.of("files", Map.of("template.xhtml", edited)))
            .post("/templates/demo/revisions/3/preview?format=xhtml&layout=memo-layout")
            .then()
            .statusCode(200)
            .header("Template-Layout", "memo-layout@1")
            .extract()
            .asString();
    assertTrue(preview.contains("Good day Jane Doe.") && preview.contains("MEMORANDUM"), preview);
    given().get("/templates/demo").then().body("latestRevision", equalTo(3));

    // Save: a new revision, validated and released like an upload.
    given()
        .contentType("application/json")
        .body(Map.of("base", 3, "files", Map.of("template.xhtml", edited)))
        .post("/templates/demo/revisions")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .body("revision", equalTo(4))
        .body("status", equalTo("published"));
    assertTrue(xhtml("demo").contains("Good day Jane Doe."));

    // A broken edit is refused and stores nothing.
    given()
        .contentType("application/json")
        .body(Map.of("base", 4, "files", Map.of("template.xhtml", "<html>")))
        .post("/templates/demo/revisions")
        .then()
        .statusCode(422)
        .body("type", equalTo(PROBLEM + "publish-rejected"));
    given().get("/templates/demo").then().body("latestRevision", equalTo(4));
  }

  @Test
  @Order(19)
  void addsAndRemovesLanguageVariantsInAnEdit() throws Exception {
    String source =
        given().get("/templates/demo/revisions/4/files/template.xhtml").then().extract().asString();

    // A French variant: the layouts have no French texts, so the quick check says so.
    given()
        .contentType("application/json")
        .body(Map.of("files", Map.of("template.fr.xhtml", source)))
        .post("/templates/demo/revisions/4/check")
        .then()
        .statusCode(200)
        .body("problems.detail", hasItem(containsString("has no fr text")));

    // Removing the German variant is a new revision without it.
    given()
        .contentType("application/json")
        .body(Map.of("base", 4, "delete", java.util.List.of("template.de.xhtml")))
        .post("/templates/demo/revisions")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .body("revision", equalTo(5))
        .body("languages", org.hamcrest.Matchers.not(hasItem("de")));
  }

  /** Renders the template as XHTML with a listed layout; {@code ""} for the default. */
  private static Response render(String template, String layout) throws Exception {
    return given()
        .contentType("application/json")
        .body(data("data-email.json"))
        .post(
            "/templates/"
                + template
                + "/render?format=xhtml"
                + (layout.isEmpty() ? "" : "&layout=" + layout));
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
}
