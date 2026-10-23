/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import io.mapsmessaging.memory.rdma.RdmaTransport;
import io.mapsmessaging.memory.shm.SharedMemoryTransport;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Establishes outbound RDMA or negotiated local shared-memory connections. */
public final class MemoryClient {

  private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

  private MemoryClient() {}

  public static MemoryConnection connect(MemoryConnectionConfig config) throws IOException {
    return MemoryProviders.provider(config.transport()).connect(config);
  }

  static MemoryConnection connectConfigured(MemoryConnectionConfig config) throws IOException {
    if (config.security() != null) {
      throw new IOException("Authenticated transport is not yet implemented");
    }
    if (config.transport() == MemoryTransportType.RDMA) {
      return connectRdma(config.address());
    }
    return connectSharedMemory(config.address(), config.connectTimeoutMillis());
  }

  public static MemoryConnection connectSharedMemory(InetSocketAddress address) throws IOException {
    return connectSharedMemory(address, CONNECT_TIMEOUT_MILLIS);
  }

  private static MemoryConnection connectSharedMemory(InetSocketAddress address, int timeoutMillis) throws IOException {
    MemoryServer.requireLoopback(address);
    Socket controlSocket = new Socket();
    try {
      controlSocket.connect(address, timeoutMillis);
      controlSocket.setSoTimeout(timeoutMillis);
      DataInputStream input = new DataInputStream(controlSocket.getInputStream());
      DataOutputStream output = new DataOutputStream(controlSocket.getOutputStream());
      String regionName = input.readUTF();
      int slotSize = input.readInt();
      int slotCount = input.readInt();
      SharedMemoryTransport transport;
      try {
        transport = new SharedMemoryTransport(regionName, false, slotSize, slotCount);
      } catch (IOException | RuntimeException exception) {
        output.writeBoolean(false);
        output.flush();
        throw exception;
      }
      try {
        output.writeBoolean(true);
        output.flush();
        controlSocket.setSoTimeout(0);
        return new MemoryConnection(transport, controlSocket, false);
      } catch (IOException | RuntimeException exception) {
        transport.close();
        throw exception;
      }
    } catch (IOException | RuntimeException exception) {
      try {
        controlSocket.close();
      } catch (IOException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      throw exception;
    }
  }

  public static MemoryConnection connectRdma(InetSocketAddress address) throws IOException {
    return new MemoryConnection(RdmaTransport.connect(address), null, false);
  }
}
