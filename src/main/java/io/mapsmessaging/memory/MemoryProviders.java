/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import java.io.IOException;

/** Internal routing seam for platform-specific shared-memory and RDMA implementations. */
final class MemoryProviders {

  private static final MemoryConnectionProvider SHARED_MEMORY = new BuiltinProvider(MemoryTransportType.SHARED_MEMORY);
  private static final MemoryConnectionProvider RDMA = new BuiltinProvider(MemoryTransportType.RDMA);

  private MemoryProviders() {}

  static MemoryConnectionProvider provider(MemoryTransportType type) {
    return switch (type) {
      case SHARED_MEMORY -> SHARED_MEMORY;
      case RDMA -> RDMA;
    };
  }

  private static final class BuiltinProvider implements MemoryConnectionProvider {

    private final MemoryTransportType transportType;

    private BuiltinProvider(MemoryTransportType transportType) {
      this.transportType = transportType;
    }

    @Override
    public MemoryTransportType type() {
      return transportType;
    }

    @Override
    public MemoryConnection connect(MemoryConnectionConfig config) throws IOException {
      return MemoryClient.connectConfigured(config);
    }

    @Override
    public MemoryServer listen(MemoryEndpointConfig config) throws IOException {
      return MemoryServer.listenConfigured(config);
    }
  }
}
