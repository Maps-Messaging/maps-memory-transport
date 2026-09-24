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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MemoryRingMurphyTest {

  private static final int SLOT_SIZE = 64;
  private static final int SLOT_COUNT = 4;
  private static final long HEADER_SIZE = 128;

  @Test
  void rejectsNegativeSlotLength() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = allocateRing(arena);
      MemoryRing ring = new MemoryRing(segment, 0, 8, HEADER_SIZE, SLOT_SIZE, SLOT_COUNT);

      assertEquals(3, ring.write(ByteBuffer.wrap(new byte[] {1, 2, 3})));
      segment.set(ValueLayout.JAVA_INT, HEADER_SIZE, -1);

      assertThrows(IllegalStateException.class, () -> ring.read(ByteBuffer.allocate(3)));
    }
  }

  @Test
  void rejectsSlotLengthLargerThanPayloadCapacity() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = allocateRing(arena);
      MemoryRing ring = new MemoryRing(segment, 0, 8, HEADER_SIZE, SLOT_SIZE, SLOT_COUNT);

      assertEquals(3, ring.write(ByteBuffer.wrap(new byte[] {1, 2, 3})));
      segment.set(ValueLayout.JAVA_INT, HEADER_SIZE, SLOT_SIZE);

      assertThrows(IllegalStateException.class, () -> ring.read(ByteBuffer.allocate(3)));
    }
  }

  @Test
  void rejectsSlotLengthCorruptedBelowPartialReadOffset() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = allocateRing(arena);
      MemoryRing ring = new MemoryRing(segment, 0, 8, HEADER_SIZE, SLOT_SIZE, SLOT_COUNT);

      assertEquals(10, ring.write(ByteBuffer.wrap(new byte[10])));
      assertEquals(6, ring.read(ByteBuffer.allocate(6)));

      segment.set(ValueLayout.JAVA_INT, HEADER_SIZE, 5);

      assertThrows(IllegalStateException.class, () -> ring.read(ByteBuffer.allocate(4)));
    }
  }

  @Test
  void discardsPartialRemainderWhenWriterGenerationChanges() {
    AtomicLong writerGeneration = new AtomicLong(1);
    AtomicLong targetGeneration = new AtomicLong(10);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = allocateRing(arena);
      MemoryRing ring =
          new MemoryRing(
              segment,
              0,
              8,
              HEADER_SIZE,
              SLOT_SIZE,
              SLOT_COUNT,
              writerGeneration::get,
              targetGeneration::get);

      byte[] oldPayload = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
      assertEquals(oldPayload.length, ring.write(ByteBuffer.wrap(oldPayload)));
      ByteBuffer first = ByteBuffer.allocate(3);
      assertEquals(3, ring.read(first));

      writerGeneration.set(2);
      assertEquals(0, ring.read(ByteBuffer.allocate(16)));
      assertFalse(ring.hasData());

      byte[] replacement = new byte[] {9, 10, 11, 12};
      assertEquals(replacement.length, ring.write(ByteBuffer.wrap(replacement)));
      ByteBuffer destination = ByteBuffer.allocate(replacement.length);
      assertEquals(replacement.length, ring.read(destination));
      destination.flip();
      byte[] actual = new byte[destination.remaining()];
      destination.get(actual);
      assertArrayEquals(replacement, actual);
    }
  }

  @Test
  void sequenceCountersCanCrossLongOverflowWithoutInvalidSlotAddressing() {
    int slotCount = 3;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = allocateRing(arena, slotCount);
      long start = Long.MAX_VALUE - 1;
      segment.set(ValueLayout.JAVA_LONG, 0, start);
      segment.set(ValueLayout.JAVA_LONG, 8, start);
      MemoryRing ring = new MemoryRing(segment, 0, 8, HEADER_SIZE, SLOT_SIZE, slotCount);

      for (int i = 0; i < 12; i++) {
        byte[] expected = new byte[] {(byte) i, (byte) (i + 1), (byte) (i + 2)};
        assertEquals(expected.length, ring.write(ByteBuffer.wrap(expected)), "write iteration=" + i);

        ByteBuffer destination = ByteBuffer.allocate(expected.length);
        assertEquals(expected.length, ring.read(destination), "read iteration=" + i);
        destination.flip();
        byte[] actual = new byte[destination.remaining()];
        destination.get(actual);
        assertArrayEquals(expected, actual, "iteration=" + i);
      }
    }
  }

  private static MemorySegment allocateRing(Arena arena) {
    return allocateRing(arena, SLOT_COUNT);
  }

  private static MemorySegment allocateRing(Arena arena, int slotCount) {
    return arena.allocate(HEADER_SIZE + (long) SLOT_SIZE * slotCount, 8);
  }
}
