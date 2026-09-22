/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server;

import static at.itbh.pdfuagen.server.DemoBundles.layoutBundle;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** The read-only management UI renders the template catalogue and a template's revisions. */
@QuarkusTest
class UiTest {

  // Unique per run so the test does not collide with other tests on a reused database.
  private static final String ID = "ui-layout-" + Long.toHexString(System.nanoTime());

  @Test
  void listsAndShowsATemplate() throws Exception {
    given()
        .contentType("application/zip")
        .body(layoutBundle("Example"))
        .post("/templates/" + ID + "/revisions")
        .then()
        .statusCode(201);

    // The catalogue page: HTML, the bundled assets, and the new template linked.
    given()
        .accept("text/html")
        .get("/ui")
        .then()
        .statusCode(200)
        .contentType(startsWith("text/html"))
        .body(containsString("<script"))
        .body(containsString("/ui/templates/" + ID));

    // The detail page: the template's revisions.
    given()
        .accept("text/html")
        .get("/ui/templates/" + ID)
        .then()
        .statusCode(200)
        .body(containsString("Revisions"))
        .body(containsString("draft"));

    given().accept("text/html").get("/ui/templates/does-not-exist").then().statusCode(404);
  }
}
