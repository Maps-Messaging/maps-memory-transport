/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm.linux;

import io.mapsmessaging.memory.MemoryClient;
import io.mapsmessaging.memory.MemoryConnection;
import io.mapsmessaging.memory.MemoryConnectionConfig;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class LinuxMemfdProcess {

  private LinuxMemfdProcess() {}

  public static void main(String[] arguments) throws Exception {
    UnixDomainSocketAddress address = UnixDomainSocketAddress.of(Path.of(arguments[0]));
    try (MemoryConnection connection = MemoryClient.connect(MemoryConnectionConfig.linuxSharedMemory(address))) {
      if (connection.write(ByteBuffer.wrap(new byte[] {3, 5, 7})) != 3) {
        throw new IllegalStateException("Child failed to write payload");
      }
      if (arguments.length > 1 && "hold".equals(arguments[1])) {
        Thread.sleep(60_000);
        return;
      }
      ByteBuffer destination = ByteBuffer.allocate(3);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (destination.hasRemaining() && System.nanoTime() < deadline) {
        if (connection.hasData()) {
          connection.read(destination);
        } else {
          Thread.sleep(1);
        }
      }
      if (destination.hasRemaining() || destination.get(0) != 2 || destination.get(1) != 4 || destination.get(2) != 6) {
        throw new IllegalStateException("Child received unexpected reply");
      }
    }
  }
}
