/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

/**
 * One reason why a template could not be rendered.
 *
 * @param type short problem name; the API maps it to {@code
 *     urn:itbh:pdf-ua-generator:problem:<type>}
 * @param detail human-readable explanation
 * @param location where the problem occurred (template id and line, resource URI, …), or {@code
 *     null}
 */
public record Problem(String type, String detail, String location) {

  public static final String TEMPLATE_ERROR = "template-error";
  public static final String IMAGE_REJECTED = "image-rejected";
  public static final String RESOURCE_REJECTED = "resource-rejected";
  public static final String ATTACHMENT_MISSING = "attachment-missing";
  public static final String INVALID_DATA = "invalid-data";

  @Override
  public String toString() {
    return location == null ? type + ": " + detail : type + ": " + detail + " (" + location + ")";
  }
}
