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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
  void handlesBoundaryPayloadSizes() throws Exception {
    int slotSize = 256;
    int slotCount = 4;
    int slotPayload = slotSize - Integer.BYTES;
    int ringPayload = slotPayload * slotCount;
    int[] sizes = {0, 1, slotPayload, slotPayload + 1, ringPayload};

    for (int size : sizes) {
      String name = "test-" + UUID.randomUUID();
      byte[] payload = payload(size, 17);
      try (SharedMemoryTransport a = new SharedMemoryTransport(name, true, slotSize, slotCount);
           SharedMemoryTransport b = new SharedMemoryTransport(name, false, slotSize, slotCount)) {
        ByteBuffer source = ByteBuffer.wrap(payload);
        assertEquals(size, a.write(source), "size=" + size);
        assertEquals(0, source.remaining(), "size=" + size);
        assertEquals(size != 0, b.hasData(), "size=" + size);

        ByteBuffer destination = ByteBuffer.allocate(size);
        assertEquals(size, b.read(destination), "size=" + size);
        destination.flip();
        byte[] actual = new byte[destination.remaining()];
        destination.get(actual);
        assertArrayEquals(payload, actual, "size=" + size);
      }
    }
  }

  @Test
  void supportsPartialReads() throws Exception {
    String name = "test-" + UUID.randomUUID();
    byte[] payload = payload(700, 23);

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
  void streamsPayloadLargerThanEntireRingCapacity() throws Exception {
    int slotSize = 256;
    int slotCount = 4;
    int ringPayload = (slotSize - Integer.BYTES) * slotCount;
    byte[] payload = payload(ringPayload * 7 + 37, 31);
    String name = "test-" + UUID.randomUUID();

    try (SharedMemoryTransport a = new SharedMemoryTransport(name, true, slotSize, slotCount);
         SharedMemoryTransport b = new SharedMemoryTransport(name, false, slotSize, slotCount)) {
      ByteBuffer source = ByteBuffer.wrap(payload);
      ByteBuffer destination = ByteBuffer.allocate(payload.length);

      int totalWritten = 0;
      int totalRead = 0;
      int cycles = 0;
      while (source.hasRemaining() || b.hasData()) {
        if (source.hasRemaining() && a.canWrite()) {
          totalWritten += a.write(source);
        }
        if (b.hasData()) {
          totalRead += b.read(destination);
        }
        assertTrue(++cycles < 1000, "transport failed to make forward progress");
      }

      assertEquals(payload.length, totalWritten);
      assertEquals(payload.length, totalRead);
      destination.flip();
      byte[] actual = new byte[destination.remaining()];
      destination.get(actual);
      assertArrayEquals(payload, actual);
    }
  }

  @Test
  void repeatedlyWrapsAndReusesRing() throws Exception {
    int slotSize = 256;
    int slotCount = 4;
    String name = "test-" + UUID.randomUUID();

    try (SharedMemoryTransport a = new SharedMemoryTransport(name, true, slotSize, slotCount);
         SharedMemoryTransport b = new SharedMemoryTransport(name, false, slotSize, slotCount)) {
      for (int i = 0; i < 500; i++) {
        byte[] expected = payload(1 + (i % 200), i);
        ByteBuffer source = ByteBuffer.wrap(expected);
        assertEquals(expected.length, a.write(source));

        ByteBuffer destination = ByteBuffer.allocate(expected.length);
        assertEquals(expected.length, b.read(destination));
        destination.flip();
        byte[] actual = new byte[destination.remaining()];
        destination.get(actual);
        assertArrayEquals(expected, actual, "iteration=" + i);
      }
    }
  }

  @Test
  void streamsConcurrentlyInBothDirections() throws Exception {
    int slotSize = 512;
    int slotCount = 8;
    byte[] aToB = payload(250_000, 41);
    byte[] bToA = payload(275_000, 73);
    String name = "test-" + UUID.randomUUID();

    try (SharedMemoryTransport a = new SharedMemoryTransport(name, true, slotSize, slotCount);
         SharedMemoryTransport b = new SharedMemoryTransport(name, false, slotSize, slotCount)) {
      CountDownLatch start = new CountDownLatch(1);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      byte[] receivedAtA = new byte[bToA.length];
      byte[] receivedAtB = new byte[aToB.length];

      Thread threadA = Thread.ofPlatform().start(() -> transferBothWays(a, aToB, receivedAtA, start, failure));
      Thread threadB = Thread.ofPlatform().start(() -> transferBothWays(b, bToA, receivedAtB, start, failure));
      start.countDown();

      threadA.join(10_000);
      threadB.join(10_000);
      assertFalse(threadA.isAlive(), "side A did not complete");
      assertFalse(threadB.isAlive(), "side B did not complete");
      if (failure.get() != null) {
        throw new AssertionError("concurrent transfer failed", failure.get());
      }
      assertArrayEquals(bToA, receivedAtA);
      assertArrayEquals(aToB, receivedAtB);
    }
  }

  @Test
  void rejectsMismatchedLayout() throws Exception {
    String name = "test-" + UUID.randomUUID();
    try (SharedMemoryTransport ignored = new SharedMemoryTransport(name, true, 1024, 8)) {
      assertThrows(IOException.class, () -> new SharedMemoryTransport(name, false, 2048, 8));
      assertThrows(IOException.class, () -> new SharedMemoryTransport(name, false, 1024, 16));
    }
  }

  @Test
  void rejectsInvalidConfiguration() {
    String name = "test-" + UUID.randomUUID();
    assertThrows(IllegalArgumentException.class, () -> new SharedMemoryTransport("", true, 1024, 8));
    assertThrows(IllegalArgumentException.class, () -> new SharedMemoryTransport(name, true, 255, 8));
    assertThrows(IllegalArgumentException.class, () -> new SharedMemoryTransport(name, true, 1024, 1));
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

  private static void transferBothWays(
      SharedMemoryTransport transport,
      byte[] outbound,
      byte[] inbound,
      CountDownLatch start,
      AtomicReference<Throwable> failure) {
    try {
      start.await();
      ByteBuffer source = ByteBuffer.wrap(outbound);
      ByteBuffer destination = ByteBuffer.wrap(inbound);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
      while ((source.hasRemaining() || destination.hasRemaining()) && System.nanoTime() < deadline) {
        if (source.hasRemaining() && transport.canWrite()) {
          transport.write(source);
        }
        if (destination.hasRemaining() && transport.hasData()) {
          transport.read(destination);
        }
        Thread.onSpinWait();
      }
      if (source.hasRemaining() || destination.hasRemaining()) {
        throw new AssertionError("concurrent transport did not complete before deadline");
      }
    } catch (Throwable throwable) {
      failure.compareAndSet(null, throwable);
    }
  }

  private static byte[] payload(int size, int seed) {
    byte[] payload = new byte[size];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (seed + i * 31);
    }
    return payload;
  }
}
