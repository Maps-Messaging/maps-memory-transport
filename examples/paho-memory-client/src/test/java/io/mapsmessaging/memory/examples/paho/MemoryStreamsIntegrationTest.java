/*
 * Copyright [ 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.examples.paho;

import io.mapsmessaging.memory.MemoryTransport;
import io.mapsmessaging.memory.security.DataProtection;
import io.mapsmessaging.memory.security.MemorySecurity;
import io.mapsmessaging.memory.security.ProtectedTransport;
import io.mapsmessaging.memory.security.SecureHandshake;
import io.mapsmessaging.memory.security.SecureSession;
import io.mapsmessaging.memory.shm.SharedMemoryTransport;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStreamsIntegrationTest {
  @TestFactory
  List<DynamicTest> mapped_memory_and_protected_records_preserve_streams() {
    List<DynamicTest> tests = new ArrayList<>();
    tests.add(DynamicTest.dynamicTest("unsecured", () -> exchange(null)));
    for (DataProtection protection : DataProtection.values()) {
      tests.add(DynamicTest.dynamicTest(protection.name(), () -> exchange(protection)));
    }
    return tests;
  }

  private void exchange(DataProtection protection) throws Exception {
    String name = "paho-streams-" + UUID.randomUUID();
    SharedMemoryTransport server = new SharedMemoryTransport(name, true, 256, 4);
    try (server; SharedMemoryTransport client = new SharedMemoryTransport(name, false, 256, 4)) {
      MemoryTransport serverTransport = server;
      MemoryTransport clientTransport = client;
      if (protection != null) {
        MemorySecurity[] security = security(protection);
        SecureHandshake serverHandshake = SecureHandshake.begin(security[0]);
        SecureHandshake clientHandshake = SecureHandshake.begin(security[1]);
        SecureSession serverSession = serverHandshake.finish(clientHandshake.offer(), true);
        SecureSession clientSession = clientHandshake.finish(serverHandshake.offer(), false);
        serverSession.verifyConfirmation(clientSession.confirmation());
        clientSession.verifyConfirmation(serverSession.confirmation());
        serverTransport = new ProtectedTransport(server, serverSession);
        clientTransport = new ProtectedTransport(client, clientSession);
      }
      try (MemoryStreams incoming = new MemoryStreams(serverTransport); MemoryStreams outgoing = new MemoryStreams(clientTransport)) {
        byte[] payload = new byte[50_000];
        for (int index = 0; index < payload.length; index++) {
          payload[index] = (byte) index;
        }
        transfer(outgoing, incoming, payload);
        transfer(incoming, outgoing, payload);
      }
    } finally {
      Files.deleteIfExists(server.path());
    }
  }

  private static void transfer(MemoryStreams source, MemoryStreams destination, byte[] payload) throws Exception {
    FutureTask<byte[]> receiver = new FutureTask<>(() -> destination.input().readNBytes(payload.length));
    FutureTask<Void> writer = new FutureTask<>(() -> {
      source.output().write(payload);
      return null;
    });
    Thread readerThread = Thread.ofVirtual().start(receiver);
    Thread writerThread = Thread.ofVirtual().start(writer);
    try {
      writer.get(5, TimeUnit.SECONDS);
      assertArrayEquals(payload, receiver.get(5, TimeUnit.SECONDS));
    } finally {
      writer.cancel(true);
      receiver.cancel(true);
      writerThread.join(2000);
      readerThread.join(2000);
      assertFalse(writerThread.isAlive());
      assertFalse(readerThread.isAlive());
    }
  }

  static MemorySecurity[] security(DataProtection protection) throws Exception {
    KeyPair server = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair client = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    return new MemorySecurity[] {
        MemorySecurity.builder().identity("server", server.getPrivate(), server.getPublic()).protection(protection)
            .trust((peer, key) -> peer.equals("client") && key.equals(client.getPublic())).build(),
        MemorySecurity.builder().identity("client", client.getPrivate(), client.getPublic()).protection(protection)
            .trust((peer, key) -> peer.equals("server") && key.equals(server.getPublic())).build()
    };
  }
}
