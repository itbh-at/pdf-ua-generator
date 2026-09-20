/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server;

import static at.itbh.pdfuagen.server.DemoBundles.demoBundle;
import static at.itbh.pdfuagen.server.DemoBundles.layoutBundle;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * The packaged service — the runner jar, or the container image when one was built — importing the
 * demo layout and template and rendering them in every format it offers.
 *
 * <p>This is also the training run for the AOT cache ({@code quarkus.package.jar.aot.phase=
 * integration-tests}), so it does the work a production instance does: parse templates, measure
 * fonts, render every format. Anything it leaves out stays uncached and slow at startup.
 */
@QuarkusIntegrationTest
class ApiIT {

  // Unique per run so the test is reuse-safe: with a reused Dev Services database
  // (testcontainers.reuse.enable=true) it neither collides with ApiTest's demo
  // template nor with its own earlier runs, which would turn a fresh 201 into a
  // 200 for the already-stored content.
  private static final String SUFFIX = Long.toHexString(System.nanoTime());
  private static final String LAYOUT = "demo-layout-" + SUFFIX;
  private static final String TEMPLATE = "demo-" + SUFFIX;

  @Test
  void importsTheDemoAndRendersEveryFormat() throws Exception {
    given()
        .contentType("application/zip")
        .body(layoutBundle("Accessible document example"))
        .post("/templates/" + LAYOUT + "/revisions")
        .then()
        .statusCode(201);
    given().post("/templates/" + LAYOUT + "/revisions/1/publish").then().statusCode(200);
    given()
        .contentType("application/zip")
        .body(demoBundle(LAYOUT + "@1"))
        .post("/templates/" + TEMPLATE + "/revisions")
        .then()
        .statusCode(201);
    given().post("/templates/" + TEMPLATE + "/revisions/1/publish").then().statusCode(200);

    for (String format : new String[] {"pdf", "xhtml", "email-html", "text", "docx", "odt"}) {
      byte[] document =
          given()
              .contentType("application/json")
              .body(data())
              .post("/templates/" + TEMPLATE + "/render?format=" + format)
              .then()
              .statusCode(200)
              .extract()
              .asByteArray();
      assertTrue(document.length > 1000, format + " is " + document.length + " bytes");
    }

    // The same data again: now from the caches, which is what most requests do.
    given()
        .contentType("application/json")
        .body(data())
        .post("/templates/" + TEMPLATE + "/render?format=pdf")
        .then()
        .statusCode(200);

    given().get("/q/health/ready").then().statusCode(200);
    given().get("/q/metrics").then().statusCode(200);
    given()
        .contentType("application/json")
        .body("{}")
        .post("/templates/" + TEMPLATE + "/validate")
        .then()
        .statusCode(422)
        .body("type", equalTo("urn:itbh:pdf-ua-generator:problem:invalid-data"));
  }

  private static byte[] data() throws Exception {
    return java.nio.file.Files.readAllBytes(DemoBundles.DEMO.resolve("data-email.json"));
  }
}
