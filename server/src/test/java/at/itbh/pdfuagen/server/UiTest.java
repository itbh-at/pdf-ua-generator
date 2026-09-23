/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server;

import static at.itbh.pdfuagen.server.DemoBundles.layoutBundle;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** The management UI renders the catalogue and a template, and publishes and deletes through it. */
@QuarkusTest
class UiTest {

  // Unique per run so the tests do not collide with other tests on a reused database.
  private static final String ID = "ui-" + Long.toHexString(System.nanoTime());

  @Test
  void listsShowsPublishesAndDeletes() throws Exception {
    String layout = ID + "-layout";
    given()
        .contentType("application/zip")
        .body(layoutBundle("Example"))
        .post("/templates/" + layout + "/revisions")
        .then()
        .statusCode(201);

    // Catalogue: HTML with the bundle and the new template linked.
    given()
        .accept("text/html")
        .get("/ui")
        .then()
        .statusCode(200)
        .contentType(startsWith("text/html"))
        .body(containsString("<script"))
        .body(containsString("/ui/templates/" + layout));

    // Template detail: the revisions panel with the draft and its actions.
    given()
        .accept("text/html")
        .get("/ui/templates/" + layout)
        .then()
        .statusCode(200)
        .body(containsString("id=\"revisions\""))
        .body(containsString("draft"))
        .body(containsString("hx-post"));

    // Revision detail: the files of the revision.
    given()
        .accept("text/html")
        .get("/ui/templates/" + layout + "/revisions/1")
        .then()
        .statusCode(200)
        .body(containsString("Files"))
        .body(containsString("layout.json"));

    // Publish through the UI: the panel comes back with the published status and a notice.
    given()
        .accept("text/html")
        .post("/ui/templates/" + layout + "/revisions/1/publish")
        .then()
        .statusCode(200)
        .body(containsString("published"))
        .body(containsString("Revision 1 published."));

    // Delete a fresh draft through the UI.
    String draft = ID + "-draft";
    given()
        .contentType("application/zip")
        .body(layoutBundle("Draft"))
        .post("/templates/" + draft + "/revisions")
        .then()
        .statusCode(201);
    given()
        .accept("text/html")
        .post("/ui/templates/" + draft + "/revisions/1/delete")
        .then()
        .statusCode(200)
        .body(containsString("Draft 1 deleted."))
        .body(not(containsString("hx-post")));

    given().accept("text/html").get("/ui/templates/does-not-exist").then().statusCode(404);
  }
}
