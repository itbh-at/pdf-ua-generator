/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HttpsFetcherTest {

  private final HttpsFetcher fetcher =
      new HttpsFetcher(List.of("images.example.org", "*.cdn.example"), Duration.ofSeconds(1), 1024);

  @Test
  void matchesTheAllowlist() {
    assertTrue(fetcher.allowed("images.example.org"));
    assertTrue(fetcher.allowed("a.cdn.example"));
    assertFalse(fetcher.allowed("cdn.example"));
    assertFalse(fetcher.allowed("evilcdn.example"));
    assertFalse(fetcher.allowed("example.org"));
  }

  @Test
  void refusesWhatIsNotAllowed() {
    assertEquals(Optional.empty(), fetcher.fetch(URI.create("http://images.example.org/a.png")));
    assertEquals(Optional.empty(), fetcher.fetch(URI.create("https://other.example/a.png")));
    // Allowed name, but resolving to loopback.
    HttpsFetcher local = new HttpsFetcher(List.of("localhost"), Duration.ofSeconds(1), 1024);
    assertEquals(Optional.empty(), local.fetch(URI.create("https://localhost/a.png")));
  }

  @Test
  void recognisesInternalAddresses() throws Exception {
    for (String address :
        List.of(
            "127.0.0.1",
            "10.1.2.3",
            "172.16.0.1",
            "192.168.1.1",
            "169.254.1.1",
            "100.64.0.1",
            "::1",
            "fd00::1",
            "fe80::1",
            "0.0.0.0")) {
      assertTrue(HttpsFetcher.internal(InetAddress.getByName(address)), address);
    }
    assertFalse(HttpsFetcher.internal(InetAddress.getByName("93.184.216.34")));
    assertFalse(HttpsFetcher.internal(InetAddress.getByName("2606:2800:220:1::1")));
  }
}
