/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.rdma;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.mapsmessaging.memory.MemoryClient;
import io.mapsmessaging.memory.MemoryConnection;
import io.mapsmessaging.memory.MemoryConnectionConfig;
import io.mapsmessaging.memory.MemoryEndpointConfig;
import io.mapsmessaging.memory.MemoryServer;
import io.mapsmessaging.memory.MemoryTransportType;
import io.mapsmessaging.memory.security.DataProtection;
import io.mapsmessaging.memory.security.MemorySecurity;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ManagedRdmaIntegrationTest {

  @Test
  void concurrentManagedConnectionsAndSecureRecords() throws Exception {
    RdmaAvailability availability = RdmaSupport.probe();
    assumeTrue(availability.available(), availability.detail());
    String testAddress = System.getenv("MAPS_RDMA_TEST_ADDRESS");
    assumeTrue(testAddress != null && !testAddress.isBlank(), "Set MAPS_RDMA_TEST_ADDRESS");
    InetAddress host = InetAddress.getByName(testAddress);
    KeyPair clientKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair serverKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    for (DataProtection mode : DataProtection.values()) {
      MemorySecurity clientSecurity = MemorySecurity.builder()
          .identity("rdma-client", clientKey.getPrivate(), clientKey.getPublic())
          .trust((identity, key) -> identity.equals("rdma-server") && key.equals(serverKey.getPublic()))
          .protection(mode).build();
      MemorySecurity serverSecurity = MemorySecurity.builder()
          .identity("rdma-server", serverKey.getPrivate(), serverKey.getPublic())
          .trust((identity, key) -> identity.equals("rdma-client") && key.equals(clientKey.getPublic()))
          .protection(mode).build();
      MemoryEndpointConfig endpoint = MemoryEndpointConfig.builder()
          .transport(MemoryTransportType.RDMA)
          .address(new InetSocketAddress(host, 0))
          .maxConnections(6)
          .maxPendingConnections(6)
          .maxNegotiations(3)
          .handshakeTimeoutMillis(10_000)
          .security(serverSecurity).build();
      try (MemoryServer server = MemoryServer.listen(endpoint)) {
        InetSocketAddress remote = (InetSocketAddress) server.localAddress();
        List<MemoryConnection> clients = new ArrayList<>();
        List<MemoryConnection> peers = new ArrayList<>();
        try {
          for (int index = 0; index < 3; index++) {
            MemoryConnectionConfig config = new MemoryConnectionConfig(
                MemoryTransportType.RDMA, remote, 10_000, clientSecurity);
            clients.add(MemoryClient.connect(config));
            peers.add(server.accept());
          }
          for (int index = 0; index < clients.size(); index++) {
            byte[] payload = new byte[4096];
            for (int cursor = 0; cursor < payload.length; cursor++) {
              payload[cursor] = (byte) (cursor + index);
            }
            assertArrayEquals(payload, transfer(clients.get(index), peers.get(index), payload));
            byte[] reply = new byte[] {(byte) index, 22, 33};
            assertArrayEquals(reply, transfer(peers.get(index), clients.get(index), reply));
          }
        } finally {
          for (MemoryConnection client : clients) {
            client.close();
          }
          for (MemoryConnection peer : peers) {
            peer.close();
          }
        }
      }
    }
  }

  private static byte[] transfer(MemoryConnection sender, MemoryConnection receiver, byte[] bytes)
      throws Exception {
    ByteBuffer source = ByteBuffer.wrap(bytes);
    ByteBuffer destination = ByteBuffer.allocate(bytes.length);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while ((source.hasRemaining() || destination.hasRemaining()) && System.nanoTime() < deadline) {
      if (source.hasRemaining() && sender.canWrite()) {
        sender.write(source);
      }
      if (destination.hasRemaining() && receiver.hasData()) {
        receiver.read(destination);
      }
      Thread.onSpinWait();
    }
    assertFalse(source.hasRemaining(), "RDMA sender timed out");
    assertFalse(destination.hasRemaining(), "RDMA receiver timed out");
    return destination.array();
  }
}
