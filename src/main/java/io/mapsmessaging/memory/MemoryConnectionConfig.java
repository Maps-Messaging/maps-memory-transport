/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import io.mapsmessaging.memory.security.MemorySecurity;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.util.Objects;

/** Immutable outbound configuration; security is reserved for the authenticated-transport phase. */
public record MemoryConnectionConfig(MemoryTransportType transport, SocketAddress address,
                                     int connectTimeoutMillis, MemorySecurity security) {
  public MemoryConnectionConfig {
    Objects.requireNonNull(transport, "transport");
    Objects.requireNonNull(address, "address");
    if (connectTimeoutMillis < 1) {
      throw new IllegalArgumentException("connectTimeoutMillis must be positive");
    }
  }

  public static MemoryConnectionConfig sharedMemory(InetSocketAddress address) {
    return new MemoryConnectionConfig(MemoryTransportType.SHARED_MEMORY, address, 5_000, null);
  }

  public static MemoryConnectionConfig linuxSharedMemory(UnixDomainSocketAddress address) {
    return new MemoryConnectionConfig(MemoryTransportType.SHARED_MEMORY, address, 5_000, null);
  }

  public static MemoryConnectionConfig windowsSharedMemory(WindowsPipeAddress address) {
    return new MemoryConnectionConfig(MemoryTransportType.SHARED_MEMORY, address, 5_000, null);
  }

  public static MemoryConnectionConfig rdma(InetSocketAddress address) {
    return new MemoryConnectionConfig(MemoryTransportType.RDMA, address, 5_000, null);
  }
}
