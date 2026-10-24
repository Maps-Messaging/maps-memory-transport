/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MemoryManagedEndpointTest {

  private static final InetSocketAddress BIND = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

  private static MemoryEndpointConfig.Builder config() {
    return MemoryEndpointConfig.builder()
        .address(BIND)
        .slotSize(1024)
        .slotCount(4);
  }

  @Test
  void configuredEndpointsExchangeData() throws Exception {
    try (MemoryServer server = MemoryServer.listen(config().build())) {
      InetSocketAddress address = (InetSocketAddress) server.localAddress();
      try (MemoryConnection client = MemoryClient.connect(MemoryConnectionConfig.sharedMemory(address));
           MemoryConnection accepted = server.accept()) {
        assertEquals(MemoryConnectionState.ACTIVE, client.state());
        assertEquals(MemoryConnectionState.ACTIVE, accepted.state());
        assertEquals(3, client.write(ByteBuffer.wrap(new byte[] {1, 2, 3})));
        ByteBuffer destination = ByteBuffer.allocate(3);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (destination.hasRemaining() && System.nanoTime() < deadline) {
          if (accepted.hasData()) {
            accepted.read(destination);
          } else {
            Thread.sleep(1);
          }
        }
        assertFalse(destination.hasRemaining());
        assertEquals(1, destination.array()[0]);
      }
    }
  }

  @Test
  void rejectsExcessPeersWithoutDisconnectingEstablishedOne() throws Exception {
    try (MemoryServer server = MemoryServer.listen(config().maxConnections(1).maxPendingConnections(1).build())) {
      InetSocketAddress address = (InetSocketAddress) server.localAddress();
      try (MemoryConnection first = MemoryClient.connect(MemoryConnectionConfig.sharedMemory(address));
           MemoryConnection accepted = server.accept()) {
        assertThrows(IOException.class, () -> MemoryClient.connect(MemoryConnectionConfig.sharedMemory(address)));
        assertFalse(first.isClosed());
        assertEquals(1, first.write(ByteBuffer.wrap(new byte[] {8})));
      }
    }
  }

  @Test
  void rejectsNegotiationsWhenCapacityIsExhausted() throws Exception {
    try (MemoryServer server = MemoryServer.listen(config().maxConnections(2).maxNegotiations(1).build());
         Socket stalled = new Socket()) {
      InetSocketAddress address = (InetSocketAddress) server.localAddress();
      stalled.connect(address);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      IOException failure = null;
      while (System.nanoTime() < deadline) {
        try (MemoryConnection connection = MemoryClient.connect(MemoryConnectionConfig.sharedMemory(address))) {
          // A race before the first accept has reserved its slot is harmless; retry.
          connection.close();
        } catch (IOException exception) {
          failure = exception;
          break;
        }
        Thread.sleep(10);
      }
      assertTrue(failure != null, "Expected a concurrent negotiation to be rejected");
    }
  }

  @Test
  void closingListenerTerminatesOutstandingNegotiationsAndActivePeers() throws Exception {
    MemoryServer server = MemoryServer.listen(config().build());
    try (Socket stalled = new Socket()) {
      stalled.connect((InetSocketAddress) server.localAddress());
      try (MemoryConnection client = MemoryClient.connect(
          MemoryConnectionConfig.sharedMemory((InetSocketAddress) server.localAddress()));
           MemoryConnection peer = server.accept()) {
        server.close();
        assertTrue(server.isClosed());
        assertTrue(peer.isClosed());
        assertEquals(MemoryConnectionState.CLOSED, peer.state());
        assertThrows(IOException.class, server::accept);
      }
    } finally {
      server.close();
    }
  }

  @Test
  void rejectsSecurityUntilAuthenticatedTransportIsImplemented() throws Exception {
    java.security.KeyPair identity = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    io.mapsmessaging.memory.security.MemorySecurity security =
        io.mapsmessaging.memory.security.MemorySecurity.builder()
            .identity("local", identity.getPrivate(), identity.getPublic())
            .trust((peer, key) -> false)
            .build();
    assertThrows(IOException.class, () -> MemoryServer.listen(config().security(security).build()));
  }

  @Test
  void configurationAndProviderSelectionAreValidated() {
    assertThrows(IllegalArgumentException.class, () -> config().maxConnections(-1).build());
    assertThrows(IllegalArgumentException.class, () -> config().maxNegotiations(-1).build());
    assertThrows(IllegalArgumentException.class, () -> config().handshakeTimeoutMillis(0).build());
    assertEquals(MemoryTransportType.SHARED_MEMORY, MemoryProviders.provider(MemoryTransportType.SHARED_MEMORY).type());
    assertEquals(MemoryTransportType.RDMA, MemoryProviders.provider(MemoryTransportType.RDMA).type());
  }
}
