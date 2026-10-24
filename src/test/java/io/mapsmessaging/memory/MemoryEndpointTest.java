/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryEndpointTest {

  @Test
  void negotiatesIndependentSharedRegionsForMultiplePeers() throws Exception {
    InetSocketAddress bind = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    try (MemoryServer server = MemoryServer.listenSharedMemory(bind, 1024, 4)) {
      InetSocketAddress address = (InetSocketAddress) server.localAddress();
      List<MemoryConnection> clients = new ArrayList<>();
      List<MemoryConnection> accepted = new ArrayList<>();
      try {
        for (int index = 0; index < 8; index++) {
          clients.add(MemoryClient.connectSharedMemory(address));
          accepted.add(server.accept());
        }
        for (int index = 0; index < clients.size(); index++) {
          MemoryConnection client = clients.get(index);
          MemoryConnection peer = accepted.get(index);
          assertNotEquals(client.remoteAddress(), accepted.get((index + 1) % clients.size()).remoteAddress());
          byte[] payload = new byte[] {(byte) index, 3, 9};
          assertTrue(client.write(ByteBuffer.wrap(payload)) > 0);
          ByteBuffer destination = ByteBuffer.allocate(payload.length);
          long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
          while (destination.hasRemaining() && System.nanoTime() < deadline) {
            if (peer.hasData()) {
              peer.read(destination);
            } else {
              Thread.sleep(1);
            }
          }
          assertFalse(destination.hasRemaining());
          assertArrayEquals(payload, destination.array());
        }
      } finally {
        for (MemoryConnection connection : clients) {
          connection.close();
        }
        for (MemoryConnection connection : accepted) {
          connection.close();
        }
      }
    }
  }

  @Test
  void rejectsNonLoopbackSharedMemoryEndpoints() {
    InetSocketAddress remote = new InetSocketAddress("192.0.2.1", 7471);
    assertThrows(IllegalArgumentException.class, () -> MemoryServer.listenSharedMemory(remote));
    assertThrows(IllegalArgumentException.class, () -> MemoryClient.connectSharedMemory(remote));
  }

  @Test
  void closingServerStopsAcceptAndConnectedPeers() throws Exception {
    InetSocketAddress bind = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    MemoryServer server = MemoryServer.listenSharedMemory(bind, 1024, 4);
    try (MemoryConnection client = MemoryClient.connectSharedMemory((InetSocketAddress) server.localAddress());
         MemoryConnection peer = server.accept()) {
      server.close();
      assertTrue(peer.isClosed());
      assertThrows(java.io.IOException.class, server::accept);
    } finally {
      server.close();
    }
  }
}
