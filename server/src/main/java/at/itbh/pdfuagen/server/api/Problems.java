/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.api;

import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.server.render.RenderService;
import io.quarkiverse.httpproblem.HttpProblem;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;

/**
 * RFC 9457 problem details with the URNs documented on the problem-types page. Every problem found
 * by the core becomes one entry of the {@code errors} member: {@code detail} plus {@code pointer}
 * (a JSON Pointer into the request data) or {@code location} (template, line, column).
 */
final class Problems {

  static final String URN = "urn:itbh:pdf-ua-generator:problem:";

  static final String NOT_FOUND = "not-found";
  static final String INVALID_BUNDLE = "invalid-bundle";
  static final String INVALID_REQUEST = "invalid-request";
  static final String PUBLISH_REJECTED = "publish-rejected";
  static final String CONFLICT = "conflict";
  static final String REVISION_CONFLICT = "revision-conflict";
  static final String OVERLOADED = "overloaded";

  private Problems() {}

  static URI type(String name) {
    return URI.create(URN + name);
  }

  static HttpProblem of(String type, int status, String title, String detail) {
    return HttpProblem.builder()
        .withType(type(type))
        .withStatus(status)
        .withTitle(title)
        .withDetail(detail)
        .build();
  }

  static HttpProblem notFound(String detail) {
    return of(NOT_FOUND, 404, "Not found", detail);
  }

  static HttpProblem invalidRequest(String detail) {
    return of(INVALID_REQUEST, 400, "Invalid request", detail);
  }

  static HttpProblem conflict(String detail) {
    return of(CONFLICT, 409, "Conflict", detail);
  }

  /**
   * A change based on a revision that is no longer the latest: someone saved meanwhile. The member
   * {@code latestRevision} names the latest one.
   */
  static HttpProblem revisionConflict(int latest, Integer base) {
    return HttpProblem.builder()
        .withType(type(REVISION_CONFLICT))
        .withStatus(412)
        .withTitle("Revision conflict")
        .withDetail(
            "revision "
                + latest
                + " was saved meanwhile; the change is based on "
                + (base == null || base < 0 ? "another revision" : "revision " + base))
        .with("latestRevision", latest)
        .build();
  }

  static HttpProblem overloaded() {
    return HttpProblem.builder()
        .withType(type(OVERLOADED))
        .withStatus(503)
        .withTitle("Overloaded")
        .withDetail("All rendering threads are busy; retry shortly.")
        .withHeader("Retry-After", 1)
        .build();
  }

  /** Runs a task in the render pool; a full pool is answered with 503 and {@code Retry-After}. */
  static <T> CompletionStage<T> submit(RenderService service, Callable<T> task) {
    try {
      return service.submit(task);
    } catch (RenderService.OverloadedException e) {
      throw overloaded();
    }
  }

  /** The problems of a failed render, validation or check. */
  static HttpProblem from(List<Problem> problems) {
    String type = problems.getFirst().type();
    boolean mixed = problems.stream().anyMatch(p -> !p.type().equals(type));
    return build(type, status(type, problems), title(type), problems, mixed);
  }

  /** A publish check that failed: every entry carries its own type. */
  static HttpProblem publishRejected(List<Problem> problems) {
    return build(PUBLISH_REJECTED, 422, "Publish rejected", problems, true);
  }

  private static HttpProblem build(
      String type, int status, String title, List<Problem> problems, boolean typed) {
    List<Map<String, Object>> errors =
        problems.stream()
            .map(
                p -> {
                  Map<String, Object> error = new LinkedHashMap<>();
                  if (typed) {
                    error.put("type", type(p.type()).toString());
                  }
                  error.put("detail", p.detail());
                  if (p.location() != null) {
                    error.put(p.location().startsWith("#") ? "pointer" : "location", p.location());
                  }
                  return error;
                })
            .toList();
    String detail =
        problems.size() == 1
            ? problems.getFirst().detail()
            : problems.size() + " problems; see errors";
    return HttpProblem.builder()
        .withType(type(type))
        .withStatus(status)
        .withTitle(title)
        .withDetail(detail)
        .with("errors", errors)
        .build();
  }

  private static int status(String type, List<Problem> problems) {
    return switch (type) {
      case Problem.FORMAT_NOT_SUPPORTED -> 406;
      // Data that is not even a JSON object is a malformed request.
      case Problem.INVALID_DATA ->
          problems.stream().allMatch(p -> p.location() != null && p.location().startsWith("#"))
              ? 422
              : 400;
      default -> 422;
    };
  }

  private static String title(String type) {
    return switch (type) {
      case Problem.INVALID_DATA -> "Invalid data";
      case Problem.TEMPLATE_ERROR -> "Template error";
      case Problem.ACCESSIBILITY -> "Not accessible";
      case Problem.IMAGE_REJECTED -> "Image rejected";
      case Problem.RESOURCE_REJECTED -> "Resource rejected";
      case Problem.ATTACHMENT_MISSING -> "Attachment missing";
      case Problem.FORMAT_NOT_SUPPORTED -> "Format not supported";
      default -> "Rendering failed";
    };
  }
}
