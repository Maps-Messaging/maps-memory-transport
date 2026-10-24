/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class NativePlatformChild {

  private NativePlatformChild() {}

  public static void main(String[] args) throws Exception {
    SocketAddress address = "windows".equals(args[0])
        ? WindowsPipeAddress.of(args[1])
        : UnixDomainSocketAddress.of(Path.of(args[1]));
    MemoryConnectionConfig config = new MemoryConnectionConfig(
        MemoryTransportType.SHARED_MEMORY, address, 5_000, null);
    try (MemoryConnection connection = MemoryClient.connect(config)) {
      ByteBuffer request = ByteBuffer.wrap(new byte[] {7, 8, 9});
      if (connection.write(request) != 3) {
        throw new IllegalStateException("Child failed to transmit request");
      }
      if ("hold".equals(args[2])) {
        Thread.sleep(60_000);
        return;
      }
      ByteBuffer reply = ByteBuffer.allocate(3);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (reply.hasRemaining() && System.nanoTime() < deadline) {
        if (connection.hasData()) {
          connection.read(reply);
        } else {
          Thread.sleep(1);
        }
      }
      if (reply.hasRemaining() || reply.get(0) != 4 || reply.get(1) != 5 || reply.get(2) != 6) {
        throw new IllegalStateException("Child received unexpected response");
      }
    }
  }
}
