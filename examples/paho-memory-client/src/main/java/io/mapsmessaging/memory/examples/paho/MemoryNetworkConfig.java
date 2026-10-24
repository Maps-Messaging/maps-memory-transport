/*
 * Copyright [ 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.examples.paho;

import io.mapsmessaging.memory.MemoryConnectionConfig;
import io.mapsmessaging.memory.MemoryTransportType;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Scoped mapping from a Paho URI to immutable managed-transport settings; credentials stay out of URIs. */
public final class MemoryNetworkConfig {
  private static final ConcurrentMap<URI, Registration> CONFIGURATIONS = new ConcurrentHashMap<>();
  static final Set<String> SCHEMES = Set.of("shm", "rdma");

  private MemoryNetworkConfig() {
  }

  /** Keep the registration alive until all clients using it, including reconnects, have closed. */
  public static Registration register(String serverURI, MemoryConnectionConfig config) {
    URI uri = URI.create(serverURI);
    validate(uri);
    Objects.requireNonNull(config, "config");
    MemoryTransportType expected = "shm".equals(uri.getScheme()) ? MemoryTransportType.SHARED_MEMORY : MemoryTransportType.RDMA;
    if (config.transport() != expected) {
      throw new IllegalArgumentException("Memory configuration transport must match the Paho URI scheme");
    }
    Registration registration = new Registration(uri, config);
    if (CONFIGURATIONS.putIfAbsent(uri, registration) != null) {
      throw new IllegalStateException("A memory transport configuration is already registered for " + uri);
    }
    return registration;
  }

  static void validate(URI uri) {
    Objects.requireNonNull(uri, "uri");
    if (!SCHEMES.contains(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
        || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || uri.getPort() > 65535) {
      throw new IllegalArgumentException("Expected shm://profile or rdma://profile without paths, credentials or parameters");
    }
  }

  static MemoryConnectionConfig resolve(URI uri) throws IOException {
    validate(uri);
    Registration registration = CONFIGURATIONS.get(uri);
    if (registration == null) {
      throw new IOException("No memory transport configuration registered for " + uri);
    }
    return registration.config;
  }

  public static final class Registration implements AutoCloseable {
    private final URI uri;
    private final MemoryConnectionConfig config;

    private Registration(URI uri, MemoryConnectionConfig config) {
      this.uri = uri;
      this.config = config;
    }

    /** Prevents future factory resolution; existing connections retain their own configuration and lifetime. */
    @Override
    public void close() {
      CONFIGURATIONS.remove(uri, this);
    }
  }
}
