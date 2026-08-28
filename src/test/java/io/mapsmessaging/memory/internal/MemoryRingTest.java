/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MemoryRingTest {

  @Test
  void wrapsAndReusesSlots() {
    int slotSize = 64;
    int slotCount = 4;
    long headerSize = 128;
    long regionSize = headerSize + (long) slotSize * slotCount;

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = arena.allocate(regionSize, 8);
      MemoryRing ring = new MemoryRing(segment, 0, 8, headerSize, slotSize, slotCount);

      for (int i = 0; i < 20; i++) {
        byte[] payload = new byte[] {(byte) i, (byte) (i + 1), (byte) (i + 2)};
        ByteBuffer source = ByteBuffer.wrap(payload);
        assertEquals(payload.length, ring.write(source));
        assertTrue(ring.hasData());

        ByteBuffer destination = ByteBuffer.allocate(payload.length);
        assertEquals(payload.length, ring.read(destination));
        destination.flip();
        byte[] actual = new byte[destination.remaining()];
        destination.get(actual);
        assertArrayEquals(payload, actual);
        assertFalse(ring.hasData());
        assertEquals(slotCount, ring.availableSlots());
      }
    }
  }

  @Test
  void streamsAcrossMultipleSlots() {
    int slotSize = 64;
    int slotCount = 8;
    int payloadPerSlot = slotSize - MemoryRing.SLOT_HEADER_SIZE;
    long headerSize = 128;

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = arena.allocate(headerSize + (long) slotSize * slotCount, 8);
      MemoryRing ring = new MemoryRing(segment, 0, 8, headerSize, slotSize, slotCount);
      byte[] payload = new byte[payloadPerSlot * 3 + 7];
      for (int i = 0; i < payload.length; i++) {
        payload[i] = (byte) i;
      }

      assertEquals(payload.length, ring.write(ByteBuffer.wrap(payload)));
      ByteBuffer destination = ByteBuffer.allocate(payload.length);
      assertEquals(payload.length, ring.read(destination));
      destination.flip();
      byte[] actual = new byte[destination.remaining()];
      destination.get(actual);
      assertArrayEquals(payload, actual);
    }
  }

  @Test
  void streamsPayloadLargerThanEntireRingCapacity() {
    int slotSize = 64;
    int slotCount = 4;
    int payloadPerSlot = slotSize - MemoryRing.SLOT_HEADER_SIZE;
    int ringPayloadCapacity = payloadPerSlot * slotCount;
    long headerSize = 128;

    byte[] payload = new byte[ringPayloadCapacity * 5 + 17];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (i * 31);
    }

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = arena.allocate(headerSize + (long) slotSize * slotCount, 8);
      MemoryRing ring = new MemoryRing(segment, 0, 8, headerSize, slotSize, slotCount);
      ByteBuffer source = ByteBuffer.wrap(payload);
      ByteBuffer destination = ByteBuffer.allocate(payload.length);

      int totalWritten = 0;
      int totalRead = 0;
      int cycles = 0;
      while (source.hasRemaining() || ring.hasData()) {
        if (source.hasRemaining()) {
          totalWritten += ring.write(source);
        }
        if (ring.hasData()) {
          totalRead += ring.read(destination);
        }
        assertTrue(++cycles < 100, "ring failed to make forward progress");
      }

      assertEquals(payload.length, totalWritten);
      assertEquals(payload.length, totalRead);
      assertEquals(slotCount, ring.availableSlots());
      destination.flip();
      byte[] actual = new byte[destination.remaining()];
      destination.get(actual);
      assertArrayEquals(payload, actual);
    }
  }

  @Test
  void discardsSlotsForOldWriterOrTargetGeneration() {
    int slotSize = 128;
    int slotCount = 4;
    long headerSize = 128;
    AtomicLong writerGeneration = new AtomicLong(1);
    AtomicLong targetGeneration = new AtomicLong(10);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = arena.allocate(headerSize + (long) slotSize * slotCount, 8);
      MemoryRing ring =
          new MemoryRing(
              segment,
              0,
              8,
              headerSize,
              slotSize,
              slotCount,
              writerGeneration::get,
              targetGeneration::get);

      assertEquals(3, ring.write(ByteBuffer.wrap(new byte[] {1, 2, 3})));
      writerGeneration.set(2);
      assertEquals(0, ring.read(ByteBuffer.allocate(3)));
      assertFalse(ring.hasData());

      assertEquals(3, ring.write(ByteBuffer.wrap(new byte[] {4, 5, 6})));
      targetGeneration.set(11);
      assertEquals(0, ring.read(ByteBuffer.allocate(3)));
      assertFalse(ring.hasData());
    }
  }

  @Test
  void rejectsCorruptCounters() {
    int slotSize = 64;
    int slotCount = 4;
    long headerSize = 128;

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = arena.allocate(headerSize + (long) slotSize * slotCount, 8);
      MemoryRing ring = new MemoryRing(segment, 0, 8, headerSize, slotSize, slotCount);

      segment.set(ValueLayout.JAVA_LONG, 0, 10L);
      segment.set(ValueLayout.JAVA_LONG, 8, 0L);
      assertThrows(IllegalStateException.class, ring::availableSlots);
      assertThrows(IllegalStateException.class, ring::hasData);
      assertThrows(IllegalStateException.class, () -> ring.write(ByteBuffer.wrap(new byte[] {1})));
      assertThrows(IllegalStateException.class, () -> ring.read(ByteBuffer.allocate(1)));
    }
  }
}
