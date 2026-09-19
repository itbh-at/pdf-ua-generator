/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Everything needed for one render.
 *
 * @param templateId id of the main template in the repository
 * @param repository source of templates and resources
 * @param data the template data, as parsed by {@link JsonData}
 * @param attachments request attachments by name, referenced as {@code attachment:<name>}
 * @param publicBaseUrl base of the public asset URLs in email HTML for this request, or {@code
 *     null} for the renderer's default
 */
public record RenderRequest(
    String templateId,
    TemplateRepository repository,
    Map<String, Object> data,
    Map<String, byte[]> attachments,
    URI publicBaseUrl) {

  public RenderRequest(
      String templateId,
      TemplateRepository repository,
      Map<String, Object> data,
      Map<String, byte[]> attachments) {
    this(templateId, repository, data, attachments, null);
  }

  public RenderRequest {
    Objects.requireNonNull(templateId, "templateId");
    Objects.requireNonNull(repository, "repository");
    data = Map.copyOf(Objects.requireNonNull(data, "data"));
    attachments = Map.copyOf(Objects.requireNonNull(attachments, "attachments"));
  }
}
