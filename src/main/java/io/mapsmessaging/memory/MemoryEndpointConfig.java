/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import io.mapsmessaging.memory.security.MemorySecurity;
import java.net.SocketAddress;
import java.util.Objects;

/** Immutable managed-listener configuration. Zero limits mean unlimited legacy-style capacity. */
public final class MemoryEndpointConfig {

  private final MemoryTransportType transport;
  private final SocketAddress address;
  private final int slotSize;
  private final int slotCount;
  private final int maxConnections;
  private final int maxPendingConnections;
  private final int maxNegotiations;
  private final int handshakeTimeoutMillis;
  private final MemorySecurity security;

  private MemoryEndpointConfig(Builder builder) {
    transport = Objects.requireNonNull(builder.transport, "transport");
    address = Objects.requireNonNull(builder.address, "address");
    slotSize = builder.slotSize;
    slotCount = builder.slotCount;
    maxConnections = builder.maxConnections;
    maxPendingConnections = builder.maxPendingConnections;
    maxNegotiations = builder.maxNegotiations;
    handshakeTimeoutMillis = builder.handshakeTimeoutMillis;
    security = builder.security;
    if (slotSize < 256 || slotCount < 2 || maxConnections < 0 || maxPendingConnections < 0
        || maxNegotiations < 0 || handshakeTimeoutMillis < 1) {
      throw new IllegalArgumentException("Invalid endpoint limits or ring dimensions");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public MemoryTransportType transport() {
    return transport;
  }
  public SocketAddress address() {
    return address;
  }
  public int slotSize() {
    return slotSize;
  }
  public int slotCount() {
    return slotCount;
  }
  public int maxConnections() {
    return maxConnections;
  }
  public int maxPendingConnections() {
    return maxPendingConnections;
  }
  public int maxNegotiations() {
    return maxNegotiations;
  }
  public int handshakeTimeoutMillis() {
    return handshakeTimeoutMillis;
  }
  public MemorySecurity security() {
    return security;
  }

  public static final class Builder {
    private MemoryTransportType transport = MemoryTransportType.SHARED_MEMORY;
    private SocketAddress address;
    private int slotSize = 64 * 1024;
    private int slotCount = 256;
    private int maxConnections = 64;
    private int maxPendingConnections = 64;
    private int maxNegotiations = 16;
    private int handshakeTimeoutMillis = 5_000;
    private MemorySecurity security;

    private Builder() {}

    public Builder transport(MemoryTransportType value) {
      transport = value;
      return this;
    }
    public Builder address(SocketAddress value) {
      address = value;
      return this;
    }
    public Builder slotSize(int value) {
      slotSize = value;
      return this;
    }
    public Builder slotCount(int value) {
      slotCount = value;
      return this;
    }
    public Builder maxConnections(int value) {
      maxConnections = value;
      return this;
    }
    public Builder maxPendingConnections(int value) {
      maxPendingConnections = value;
      return this;
    }
    public Builder maxNegotiations(int value) {
      maxNegotiations = value;
      return this;
    }
    public Builder handshakeTimeoutMillis(int value) {
      handshakeTimeoutMillis = value;
      return this;
    }
    public Builder security(MemorySecurity value) {
      security = value;
      return this;
    }
    public MemoryEndpointConfig build() {
    return new MemoryEndpointConfig(this);
  }
  }
}
