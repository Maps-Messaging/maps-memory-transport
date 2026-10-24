/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.rdma;

import io.mapsmessaging.memory.MemoryTransport;
import io.mapsmessaging.memory.security.MemorySecurity;
import io.mapsmessaging.memory.security.ProtectedTransport;
import io.mapsmessaging.memory.security.SecureSession;
import io.mapsmessaging.memory.security.SecureWire;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, mutually authenticated negotiation on an established RDMA byte stream.
 * Uses the same protocol and protection as the native shared-memory endpoints.
 */
public final class RdmaSecure {

  private RdmaSecure() {}

  public static MemoryTransport authenticate(RdmaTransport transport, MemorySecurity security,
      boolean serverRole, int timeoutMillis) throws IOException {
    if (security == null) {
      return transport;
    }
    if (timeoutMillis < 1) {
      throw new IllegalArgumentException("timeoutMillis must be positive");
    }
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    SecureSession session = null;
    try {
      session = SecureWire.authenticate(security, serverRole,
          () -> receiveByte(transport, deadline), value -> sendByte(transport, value, deadline));
      return new ProtectedTransport(transport, session);
    } catch (IOException | RuntimeException exception) {
      if (session != null) {
        session.close();
      }
      transport.close();
      throw exception;
    }
  }

  private static int receiveByte(RdmaTransport transport, long deadline) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(1);
    while (System.nanoTime() < deadline) {
      int count = transport.read(buffer);
      if (count == 1) {
        return Byte.toUnsignedInt(buffer.get(0));
      }
      Thread.onSpinWait();
    }
    throw new IOException("RDMA authentication receive timed out");
  }

  private static void sendByte(RdmaTransport transport, int value, long deadline) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(1);
    buffer.put((byte) value).flip();
    while (buffer.hasRemaining() && System.nanoTime() < deadline) {
      transport.write(buffer);
      if (buffer.hasRemaining()) {
        Thread.onSpinWait();
      }
    }
    if (buffer.hasRemaining()) {
      throw new IOException("RDMA authentication send timed out");
    }
  }
}
