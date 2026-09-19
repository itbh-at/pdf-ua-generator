/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.util.Map;
import java.util.Objects;

/**
 * Everything needed for one render.
 *
 * @param templateId id of the main template in the repository
 * @param repository source of templates and resources
 * @param data the template data, as parsed by {@link JsonData}
 * @param attachments request attachments by name, referenced as {@code attachment:<name>}
 */
public record RenderRequest(
    String templateId,
    TemplateRepository repository,
    Map<String, Object> data,
    Map<String, byte[]> attachments) {

  public RenderRequest {
    Objects.requireNonNull(templateId, "templateId");
    Objects.requireNonNull(repository, "repository");
    data = Map.copyOf(Objects.requireNonNull(data, "data"));
    attachments = Map.copyOf(Objects.requireNonNull(attachments, "attachments"));
  }
}
