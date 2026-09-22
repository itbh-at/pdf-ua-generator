/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL for {@link ApiIT}, reached over the host gateway instead of a container network alias.
 *
 * <p>Dev Services would start the database too, but when ApiIT runs the built image the
 * integration-test launcher rewrites the datasource URL to the Dev Services container's network
 * alias and joins the app container to the Testcontainers network. Under podman on CI that alias is
 * not reliably reachable, so the app never connects and startup times out. Starting the database
 * here and handing the app an explicit URL disables Dev Services and its URL rewriting: the app
 * connects to the published port directly — {@code localhost} when it runs as a host process (the
 * ordinary build), {@code host.containers.internal} when it runs as a container (the {@code image}
 * profile), selected by the system property {@code pdfuagen.it.db-host}.
 */
public class PostgresResource implements QuarkusTestResourceLifecycleManager {

  // Same image the Dev Services default resolved to, so nothing about the database changes.
  private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

  @Override
  public Map<String, String> start() {
    postgres.start();
    String host = System.getProperty("pdfuagen.it.db-host", "localhost");
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
    postgres.stop();
  }
}
