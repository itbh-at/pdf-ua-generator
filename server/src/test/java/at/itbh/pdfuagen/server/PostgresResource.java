/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL for {@link ApiIT} when the container runtime cannot resolve the Dev Services network
 * alias.
 *
 * <p>Normally Dev Services provides the database and the integration-test launcher reaches it by
 * the container's network alias — which works on Docker and in local development. Under rootless
 * podman on CI that alias is not reliably reachable (aardvark-dns), so the smoke-test workflow sets
 * {@code PDFUAGEN_IT_DB_HOST=host.containers.internal}: this resource then starts the database
 * itself and hands the app an explicit URL over the host gateway, bypassing Dev Services and its
 * alias rewriting. Without that variable it starts nothing and lets Dev Services do its job.
 */
public class PostgresResource implements QuarkusTestResourceLifecycleManager {

  // Started only when a host gateway is requested; the same image Dev Services resolves to.
  private PostgreSQLContainer<?> postgres;

  @Override
  public Map<String, String> start() {
    String host = System.getenv("PDFUAGEN_IT_DB_HOST");
    if (host == null || host.isBlank()) {
      return Map.of();
    }
    postgres = new PostgreSQLContainer<>("postgres:18");
    postgres.start();
    String url =
        "jdbc:postgresql://"
            + host
            + ":"
            + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/"
            + postgres.getDatabaseName();
    return Map.of(
        "quarkus.datasource.jdbc.url", url,
        "quarkus.datasource.username", postgres.getUsername(),
        "quarkus.datasource.password", postgres.getPassword());
  }

  @Override
  public void stop() {
    if (postgres != null) {
      postgres.stop();
    }
  }
}
