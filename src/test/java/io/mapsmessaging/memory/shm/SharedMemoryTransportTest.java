/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SharedMemoryTransportTest {

  @Test
  void transfersBidirectionally() throws Exception {
    String name = "test-" + UUID.randomUUID();
    try (SharedMemoryTransport a = new SharedMemoryTransport(name, true, 1024, 8);
         SharedMemoryTransport b = new SharedMemoryTransport(name, false, 1024, 8)) {

      ByteBuffer outbound = ByteBuffer.wrap("hello-b".getBytes(StandardCharsets.UTF_8));
      assertEquals(outbound.remaining(), a.write(outbound));
      assertTrue(b.hasData());

      ByteBuffer inbound = ByteBuffer.allocate(64);
      assertEquals(7, b.read(inbound));
      inbound.flip();
      byte[] actual = new byte[inbound.remaining()];
      inbound.get(actual);
      assertArrayEquals("hello-b".getBytes(StandardCharsets.UTF_8), actual);

      outbound = ByteBuffer.wrap("hello-a".getBytes(StandardCharsets.UTF_8));
      assertEquals(outbound.remaining(), b.write(outbound));
      assertTrue(a.hasData());

      inbound.clear();
      assertEquals(7, a.read(inbound));
      inbound.flip();
      actual = new byte[inbound.remaining()];
      inbound.get(actual);
      assertArrayEquals("hello-a".getBytes(StandardCharsets.UTF_8), actual);
    }
  }

  @Test
  void supportsPartialReads() throws Exception {
    String name = "test-" + UUID.randomUUID();
    byte[] payload = new byte[700];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (i & 0xff);
    }

    try (SharedMemoryTransport a = new SharedMemoryTransport(name, true, 1024, 8);
         SharedMemoryTransport b = new SharedMemoryTransport(name, false, 1024, 8)) {

      assertEquals(payload.length, a.write(ByteBuffer.wrap(payload)));

      ByteBuffer first = ByteBuffer.allocate(200);
      ByteBuffer second = ByteBuffer.allocate(500);
      assertEquals(200, b.read(first));
      assertTrue(b.hasData());
      assertEquals(500, b.read(second));
      assertFalse(b.hasData());

      byte[] actual = new byte[700];
      first.flip();
      second.flip();
      first.get(actual, 0, 200);
      second.get(actual, 200, 500);
      assertArrayEquals(payload, actual);
    }
  }

  @Test
  void appliesBackpressureWhenRingIsFull() throws Exception {
    String name = "test-" + UUID.randomUUID();
    try (SharedMemoryTransport a = new SharedMemoryTransport(name, true, 256, 2);
         SharedMemoryTransport b = new SharedMemoryTransport(name, false, 256, 2)) {

      byte[] payload = new byte[1000];
      int written = a.write(ByteBuffer.wrap(payload));
      assertEquals((256 - Integer.BYTES) * 2, written);
      assertFalse(a.canWrite());

      ByteBuffer drain = ByteBuffer.allocate(256 - Integer.BYTES);
      assertEquals(256 - Integer.BYTES, b.read(drain));
      assertTrue(a.canWrite());
    }
  }

  @Test
  void rejectsMismatchedLayout() throws Exception {
    String name = "test-" + UUID.randomUUID();
    try (SharedMemoryTransport ignored = new SharedMemoryTransport(name, true, 1024, 8)) {
      assertThrows(IOException.class, () -> new SharedMemoryTransport(name, false, 2048, 8));
    }
  }

  @Test
  void canReopenExistingRegion() throws Exception {
    String name = "test-" + UUID.randomUUID();
    Path path;
    try (SharedMemoryTransport first = new SharedMemoryTransport(name, true, 1024, 8)) {
      path = first.path();
      assertTrue(Files.exists(path));
    }
    try (SharedMemoryTransport reopened = new SharedMemoryTransport(name, true, 1024, 8)) {
      assertEquals(path, reopened.path());
    }
  }

  @Test
  void rejectsDuplicateLiveSideOwnership() throws Exception {
    String name = "test-" + UUID.randomUUID();
    try (SharedMemoryTransport owner = new SharedMemoryTransport(name, true, 1024, 8)) {
      IOException exception = assertThrows(IOException.class, () -> new SharedMemoryTransport(name, true, 1024, 8));
      assertTrue(exception.getMessage().contains("already owned"));
    }
  }

  @Test
  void reportsPeerPresenceAndHeartbeat() throws Exception {
    String name = "test-" + UUID.randomUUID();
    try (SharedMemoryTransport a = new SharedMemoryTransport(name, true, 1024, 8)) {
      assertFalse(a.peerPresent());
      try (SharedMemoryTransport b = new SharedMemoryTransport(name, false, 1024, 8)) {
        assertTrue(a.peerPresent());
        assertTrue(b.peerPresent());
        assertTrue(a.peerHeartbeatMillis() > 0);
        assertTrue(b.peerHeartbeatMillis() > 0);
      }
      assertFalse(a.peerPresent());
    }
  }

  @Test
  void advancesGenerationWhenSideIsReopened() throws Exception {
    String name = "test-" + UUID.randomUUID();
    long firstGeneration;
    long firstSession;
    try (SharedMemoryTransport first = new SharedMemoryTransport(name, true, 1024, 8)) {
      firstGeneration = first.generation();
      firstSession = first.sessionId();
    }

    try (SharedMemoryTransport second = new SharedMemoryTransport(name, true, 1024, 8)) {
      assertTrue(second.generation() > firstGeneration);
      assertNotEquals(firstSession, second.sessionId());
    }
  }

  @Test
  void reclaimsSideAfterOwnerProcessCrashes() throws Exception {
    String name = "test-" + UUID.randomUUID();
    Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
    Process process = new ProcessBuilder(
        javaExecutable.toString(),
        "-cp",
        System.getProperty("java.class.path"),
        SharedMemoryCrashOwner.class.getName(),
        name)
        .inheritIO()
        .start();

    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());

    try (SharedMemoryTransport replacement = new SharedMemoryTransport(name, true, 1024, 8)) {
      assertTrue(replacement.generation() > 1);
    }
  }
}
