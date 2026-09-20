/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Keeps templates and resources of an immutable repository in memory, so repeated renders do not
 * read them again. Only for repositories whose content does not change, e.g. a published template
 * revision.
 */
public final class CachingTemplateRepository implements TemplateRepository {

  private final TemplateRepository delegate;
  private final ConcurrentMap<String, Optional<String>> templates = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Optional<byte[]>> resources = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Set<String>> languages = new ConcurrentHashMap<>();

  public CachingTemplateRepository(TemplateRepository delegate) {
    this.delegate = delegate;
  }

  @Override
  public String contentKey() {
    return delegate.contentKey();
  }

  @Override
  public Optional<String> template(String id) {
    return templates.computeIfAbsent(id, delegate::template);
  }

  @Override
  public Optional<byte[]> resource(String path) {
    return resources.computeIfAbsent(path, delegate::resource);
  }

  @Override
  public Set<String> languages(String templateId) {
    return languages.computeIfAbsent(templateId, delegate::languages);
  }
}
