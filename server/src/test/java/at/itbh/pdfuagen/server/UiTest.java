/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server;

import static at.itbh.pdfuagen.server.DemoBundles.demoBundle;
import static at.itbh.pdfuagen.server.DemoBundles.layoutBundle;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** The management UI renders the catalogue and a template, and archives and deletes through it. */
@QuarkusTest
class UiTest {

  // Unique per run so the tests do not collide with other tests on a reused database.
  private static final String ID = "ui-" + Long.toHexString(System.nanoTime());

  @Test
  void listsArchivesAndDeletes() throws Exception {
    String layout = ID + "-layout";

    // The layouts page takes only layouts: a document template is refused, nothing stored.
    given()
        .multiPart("id", layout)
        .multiPart("kind", "layout")
        .multiPart("bundle", "bundle.zip", demoBundle("demo-layout@1"), "application/zip")
        .post("/ui/templates")
        .then()
        .statusCode(200)
        .body(containsString("is a document template"));
    given().accept("text/html").get("/ui/templates/" + layout).then().statusCode(404);

    // Upload validates and releases in one step; htmx navigates to the new layout.
    given()
        .multiPart("id", layout)
        .multiPart("kind", "layout")
        .multiPart("bundle", "bundle.zip", layoutBundle("Example"), "application/zip")
        .post("/ui/templates")
        .then()
        .statusCode(200)
        .header("HX-Redirect", "/ui/templates/" + layout);

    // Layouts and document templates are listed on pages of their own; /ui starts with the
    // document templates.
    given()
        .accept("text/html")
        .get("/ui/layouts")
        .then()
        .statusCode(200)
        .contentType(startsWith("text/html"))
        .body(containsString("<script"))
        .body(containsString("<h1>Layouts</h1>"))
        .body(containsString("/ui/templates/" + layout));
    given()
        .accept("text/html")
        .get("/ui")
        .then()
        .statusCode(200)
        .body(containsString("<h1>Document templates</h1>"))
        .body(not(containsString("/ui/templates/" + layout)));

    // Template detail: the revisions panel with the released revision, an archive action and
    // delete.
    given()
        .accept("text/html")
        .get("/ui/templates/" + layout)
        .then()
        .statusCode(200)
        .body(containsString("id=\"revisions\""))
        .body(containsString("published"))
        .body(containsString("hx-post"))
        .body(containsString("← Layouts"))
        .body(containsString("Delete</button>"));

    // Revision detail: the files of the revision.
    given()
        .accept("text/html")
        .get("/ui/templates/" + layout + "/revisions/1")
        .then()
        .statusCode(200)
        .body(containsString("Files"))
        .body(containsString("layout.json"));

    // Archive through the UI: the panel comes back with the archived status and a notice.
    given()
        .accept("text/html")
        .post("/ui/templates/" + layout + "/revisions/1/archive")
        .then()
        .statusCode(200)
        .body(containsString("archived"))
        .body(containsString("Revision 1 archived."));

    // Delete the layout (nothing lists it) through the UI; back to the layouts page.
    given()
        .accept("text/html")
        .post("/ui/templates/" + layout + "/delete")
        .then()
        .statusCode(200)
        .header("HX-Redirect", "/ui/layouts");
    given().accept("text/html").get("/ui/templates/" + layout).then().statusCode(404);
  }

  @Test
  void generatesWithAChosenLayout() throws Exception {
    String plain = ID + "-plain";
    String memo = ID + "-memo";
    String content = ID + "-content";
    upload(plain, layoutBundle("Plain"));
    upload(memo, layoutBundle("layout-memo", "Internal memo"));
    upload(content, demoBundle(plain + "@1", memo + "@1"));

    // The form offers the listed layouts, the first as the default.
    given()
        .accept("text/html")
        .get("/ui/templates/" + content + "/render")
        .then()
        .statusCode(200)
        .body(containsString("<select name=\"layout\">"))
        .body(containsString(plain + "@1 (default)"))
        .body(containsString(memo + "@1"))
        .body(containsString("<select name=\"lang\">"))
        .body(containsString("en (default)"))
        .body(containsString("<option value=\"de\">"));

    // The editor lists the text files of the latest revision and offers the preview choices.
    given()
        .accept("text/html")
        .get("/ui/templates/" + content + "/edit")
        .then()
        .statusCode(200)
        .body(containsString("id=\"editor\""))
        .body(containsString("data-base=\"1\""))
        .body(containsString("data-file=\"template.xhtml\""))
        .body(containsString("data-file=\"template.de.xhtml\""))
        .body(containsString("data-file=\"template.json\""))
        .body(not(containsString("data-file=\"example/photo.png\"")))
        .body(containsString("<select name=\"layout\">"));

    // The chosen language picks the German variant.
    given()
        .multiPart("data", DemoBundles.data("data-email.json"))
        .multiPart("format", "xhtml")
        .multiPart("lang", "de")
        .post("/ui/templates/" + content + "/render")
        .then()
        .statusCode(200)
        .body(containsString("Hallo Jane Doe."));

    given()
        .multiPart("data", DemoBundles.data("data-email.json"))
        .multiPart("format", "xhtml")
        .multiPart("layout", memo + "@1")
        .post("/ui/templates/" + content + "/render")
        .then()
        .statusCode(200)
        .body(containsString("MEMORANDUM"));
  }

  private static void upload(String id, byte[] bundle) {
    given()
        .multiPart("id", id)
        .multiPart("bundle", "bundle.zip", bundle, "application/zip")
        .post("/ui/templates")
        .then()
        .statusCode(200)
        .header("HX-Redirect", "/ui/templates/" + id);
  }

  @Test
  void createsAndRendersThroughTheUi() throws Exception {
    String id = ID + "-new";

    // Upload a bundle; it is validated and released right away.
    given()
        .multiPart("id", id)
        .multiPart("bundle", "bundle.zip", layoutBundle("Created via UI"), "application/zip")
        .post("/ui/templates")
        .then()
        .statusCode(200)
        .header("HX-Redirect", "/ui/templates/" + id);

    given()
        .accept("text/html")
        .get("/ui/templates/" + id)
        .then()
        .statusCode(200)
        .body(containsString("published"))
        .body(containsString("Preview the layout"));

    // The generate form offers the template's formats.
    given()
        .accept("text/html")
        .get("/ui/templates/" + id + "/render")
        .then()
        .statusCode(200)
        .body(containsString("<textarea"))
        .body(containsString("pdf"));

    // Generate a PDF from the form fields (multipart, so attachments can ride along).
    given()
        .multiPart("data", "{}")
        .multiPart("format", "pdf")
        .post("/ui/templates/" + id + "/render")
        .then()
        .statusCode(200)
        .contentType(startsWith("application/pdf"));
  }
}
