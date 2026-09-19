/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.util.List;
import java.util.stream.Collectors;

/** Rendering failed; {@link #problems()} lists every reason found. */
public final class RenderException extends Exception {

  private static final long serialVersionUID = 1L;

  private final transient List<Problem> problems;

  public RenderException(List<Problem> problems) {
    this(problems, null);
  }

  public RenderException(List<Problem> problems, Throwable cause) {
    super(problems.stream().map(Problem::toString).collect(Collectors.joining("; ")), cause);
    this.problems = List.copyOf(problems);
  }

  public RenderException(Problem problem, Throwable cause) {
    this(List.of(problem), cause);
  }

  public List<Problem> problems() {
    return problems;
  }
}
