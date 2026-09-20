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

  @Test
  void importsTheDemoAndRendersEveryFormat() throws Exception {
    given()
        .contentType("application/zip")
        .body(layoutBundle("Accessible document example"))
        .post("/templates/demo-layout/revisions")
        .then()
        .statusCode(201);
    given().post("/templates/demo-layout/revisions/1/publish").then().statusCode(200);
    given()
        .contentType("application/zip")
        .body(demoBundle(1))
        .post("/templates/demo/revisions")
        .then()
        .statusCode(201);
    given().post("/templates/demo/revisions/1/publish").then().statusCode(200);

    for (String format : new String[] {"pdf", "xhtml", "email-html", "text", "docx", "odt"}) {
      byte[] document =
          given()
              .contentType("application/json")
              .body(data())
              .post("/templates/demo/render?format=" + format)
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
        .post("/templates/demo/render?format=pdf")
        .then()
        .statusCode(200);

    given().get("/q/health/ready").then().statusCode(200);
    given().get("/q/metrics").then().statusCode(200);
    given()
        .contentType("application/json")
        .body("{}")
        .post("/templates/demo/validate")
        .then()
        .statusCode(422)
        .body("type", equalTo("urn:itbh:pdf-ua-generator:problem:invalid-data"));
  }

  private static byte[] data() throws Exception {
    return java.nio.file.Files.readAllBytes(DemoBundles.DEMO.resolve("data-email.json"));
  }
}
