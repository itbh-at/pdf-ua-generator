/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.bench;

import at.itbh.pdfuagen.core.CachingTemplateRepository;
import at.itbh.pdfuagen.core.DirectoryTemplateRepository;
import at.itbh.pdfuagen.core.DocumentRenderer;
import at.itbh.pdfuagen.core.JsonData;
import at.itbh.pdfuagen.core.OutputFormat;
import at.itbh.pdfuagen.core.RenderException;
import at.itbh.pdfuagen.core.RenderRequest;
import at.itbh.pdfuagen.core.TemplateRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Renders the demo template. "warm" reuses one cached repository, as the server does for a
 * published revision; "cold" uses a new repository per render, so templates are parsed and fonts
 * measured again. Run with {@code mise run bench} from the repository root.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = "-Djava.awt.headless=true")
public class RenderBenchmark {

  private static final Path DEMO = Path.of(System.getProperty("bench.demo", "demo"));

  private final DocumentRenderer renderer = new DocumentRenderer();
  private TemplateRepository warmRepository;
  private Map<String, Object> data;

  @Setup
  public void setUp() throws IOException, RenderException {
    try (InputStream in = Files.newInputStream(DEMO.resolve("data.json"))) {
      data = JsonData.parse(in);
    }
    warmRepository =
        new CachingTemplateRepository(new DirectoryTemplateRepository(DEMO, List.of()));
  }

  private RenderRequest request(TemplateRepository repository) {
    return new RenderRequest("demo.xhtml", repository, data, Map.of());
  }

  @Benchmark
  public String quteOnlyWarm() throws RenderException {
    return renderer.renderSource(request(warmRepository));
  }

  @Benchmark
  public byte[] pdfWarm() throws RenderException {
    return renderer.render(request(warmRepository), OutputFormat.PDF).content();
  }

  @Benchmark
  public byte[] xhtmlWarm() throws RenderException {
    return renderer.render(request(warmRepository), OutputFormat.XHTML).content();
  }

  @Benchmark
  public byte[] pdfCold() throws RenderException {
    TemplateRepository fresh = new DirectoryTemplateRepository(DEMO, List.of());
    return renderer.render(request(fresh), OutputFormat.PDF).content();
  }
}
