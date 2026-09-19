/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server.render;

import at.itbh.pdfuagen.core.ResourceFetcher;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Fetches external images for PDF, DOCX and ODT: HTTPS only, hosts from the allowlist only, no
 * redirects, no private, loopback or link-local addresses, with time and size limits.
 */
public final class HttpsFetcher implements ResourceFetcher {

  private static final Logger LOG = Logger.getLogger(HttpsFetcher.class);

  private final List<String> allowedHosts;
  private final Duration timeout;
  private final long maxBytes;
  private final HttpClient client;

  /**
   * @param allowedHosts host names; {@code *.example.org} matches every subdomain of example.org
   */
  public HttpsFetcher(List<String> allowedHosts, Duration timeout, long maxBytes) {
    this.allowedHosts = allowedHosts.stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
    this.timeout = timeout;
    this.maxBytes = maxBytes;
    this.client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(timeout)
            .build();
  }

  @Override
  public Optional<byte[]> fetch(URI uri) {
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
      return Optional.empty();
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (!allowed(host)) {
      return Optional.empty();
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(host)) {
        if (internal(address)) {
          LOG.warnf("refusing %s: %s is not a public address", uri, address.getHostAddress());
          return Optional.empty();
        }
      }
      HttpResponse<InputStream> response =
          client.send(
              HttpRequest.newBuilder(uri).timeout(timeout).GET().build(),
              HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        if (response.statusCode() != 200) {
          return Optional.empty();
        }
        byte[] bytes = body.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
        return bytes.length > maxBytes ? Optional.empty() : Optional.of(bytes);
      }
    } catch (UnknownHostException e) {
      return Optional.empty();
    } catch (IOException e) {
      LOG.debugf("cannot fetch %s: %s", uri, e.getMessage());
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  boolean allowed(String host) {
    for (String allowed : allowedHosts) {
      if (allowed.startsWith("*.")
          ? host.endsWith(allowed.substring(1)) && host.length() > allowed.length() - 1
          : host.equals(allowed)) {
        return true;
      }
    }
    return false;
  }

  static boolean internal(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }
    byte[] b = address.getAddress();
    if (address instanceof Inet6Address) {
      // fc00::/7 unique local addresses
      return (b[0] & 0xfe) == 0xfc;
    }
    // 100.64.0.0/10 shared address space
    return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
  }
}
