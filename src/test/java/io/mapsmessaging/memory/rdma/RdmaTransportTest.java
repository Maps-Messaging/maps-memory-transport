/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.rdma;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RdmaTransportTest {

  @Test
  void rejectsInvalidPublicConfiguration() {
    assertThrows(IllegalArgumentException.class, () -> RdmaTransport.connect((InetSocketAddress) null));
    assertThrows(IllegalArgumentException.class, () -> RdmaTransport.connect(new InetSocketAddress("127.0.0.1", 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> RdmaTransport.connect(new InetSocketAddress("127.0.0.1", 7471), 512));

    assertThrows(IllegalArgumentException.class, () -> RdmaListener.bind((InetSocketAddress) null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RdmaListener.bind(new InetSocketAddress("127.0.0.1", 7471), 0, RdmaTransport.DEFAULT_IO_BUFFER_SIZE));
    assertThrows(
        IllegalArgumentException.class,
        () -> RdmaListener.bind(new InetSocketAddress("127.0.0.1", 7471), 1, 512));
  }

  @Test
  void failsGracefullyWhenRdmaIsUnavailable() {
    RdmaAvailability availability = RdmaSupport.probe();
    assumeTrue(!availability.available(), "RDMA is available on this host");

    assertThrows(
        RdmaUnavailableException.class,
        () -> RdmaTransport.connect(new InetSocketAddress("127.0.0.1", 7471)));
    assertThrows(
        RdmaUnavailableException.class,
        () -> RdmaListener.bind(new InetSocketAddress("127.0.0.1", 0)));
  }

  @Test
  void streamsBidirectionallyWhenRdmaLoopbackIsConfigured() throws Exception {
    RdmaAvailability availability = RdmaSupport.probe();
    assumeTrue(availability.available(), availability.detail());

    String testAddress = System.getenv("MAPS_RDMA_TEST_ADDRESS");
    assumeTrue(testAddress != null && !testAddress.isBlank(), "Set MAPS_RDMA_TEST_ADDRESS to an RDMA-capable local IP");

    InetAddress bindAddress = InetAddress.getByName(testAddress);
    byte[] clientToServer = payload(1_250_000, 17);
    byte[] serverToClient = payload(1_375_000, 91);

    try (RdmaListener listener = RdmaListener.bind(new InetSocketAddress(bindAddress, 0))) {
      AtomicReference<RdmaTransport> accepted = new AtomicReference<>();
      AtomicReference<Throwable> acceptFailure = new AtomicReference<>();
      Thread acceptThread =
          Thread.ofPlatform().start(
              () -> {
                try {
                  accepted.set(listener.accept());
                } catch (Throwable throwable) {
                  acceptFailure.set(throwable);
                }
              });

      try (RdmaTransport client = RdmaTransport.connect(listener.localAddress())) {
        acceptThread.join(10_000);
        assertFalse(acceptThread.isAlive(), "RDMA accept did not complete");
        if (acceptFailure.get() != null) {
          throw new AssertionError("RDMA accept failed", acceptFailure.get());
        }

        try (RdmaTransport server = accepted.get()) {
          assertTrue(client.remoteAddress().startsWith("rdma://"));
          assertTrue(server.remoteAddress().startsWith("rdma://"));
          assertNotEquals(0, client.sessionId());
          assertNotEquals(0, server.sessionId());
          assertNotEquals(client.sessionId(), client.peerSessionId());
          assertNotEquals(server.sessionId(), server.peerSessionId());

          byte[] receivedAtServer = transfer(client, server, clientToServer);
          byte[] receivedAtClient = transfer(server, client, serverToClient);
          assertArrayEquals(clientToServer, receivedAtServer);
          assertArrayEquals(serverToClient, receivedAtClient);

          assertDoesNotThrow(server::close);
          assertDoesNotThrow(server::close);
        }
      }
    }
  }

  private static byte[] transfer(RdmaTransport sender, RdmaTransport receiver, byte[] payload) throws Exception {
    ByteBuffer source = ByteBuffer.wrap(payload);
    ByteBuffer destination = ByteBuffer.allocate(payload.length);
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

    assertFalse(source.hasRemaining(), "RDMA sender did not complete before deadline");
    assertFalse(destination.hasRemaining(), "RDMA receiver did not complete before deadline");
    return destination.array();
  }

  private static byte[] payload(int size, int seed) {
    byte[] payload = new byte[size];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (seed + i * 31);
    }
    return payload;
  }
}
