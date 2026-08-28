/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.internal;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.function.LongSupplier;

public final class MemoryRing {

  public static final int SLOT_HEADER_SIZE = 24;
  private static final long WRITER_GENERATION_OFFSET = 8;
  private static final long TARGET_GENERATION_OFFSET = 16;
  private static final VarHandle LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle();

  private final MemorySegment memory;
  private final long producerOffset;
  private final long consumerOffset;
  private final long dataOffset;
  private final int slotSize;
  private final int slotCount;
  private final int payloadSize;
  private final LongSupplier writerGenerationSupplier;
  private final LongSupplier targetGenerationSupplier;
  private int partialReadOffset;

  public MemoryRing(MemorySegment memory, long producerOffset, long consumerOffset, long dataOffset, int slotSize, int slotCount) {
    this(memory, producerOffset, consumerOffset, dataOffset, slotSize, slotCount, () -> 0L, () -> 0L);
  }

  public MemoryRing(
      MemorySegment memory,
      long producerOffset,
      long consumerOffset,
      long dataOffset,
      int slotSize,
      int slotCount,
      LongSupplier writerGenerationSupplier,
      LongSupplier targetGenerationSupplier) {
    if (slotSize <= SLOT_HEADER_SIZE) {
      throw new IllegalArgumentException("slotSize must be greater than " + SLOT_HEADER_SIZE);
    }
    if (slotCount < 2) {
      throw new IllegalArgumentException("slotCount must be at least 2");
    }
    this.memory = memory;
    this.producerOffset = producerOffset;
    this.consumerOffset = consumerOffset;
    this.dataOffset = dataOffset;
    this.slotSize = slotSize;
    this.slotCount = slotCount;
    this.writerGenerationSupplier = writerGenerationSupplier;
    this.targetGenerationSupplier = targetGenerationSupplier;
    payloadSize = slotSize - SLOT_HEADER_SIZE;
  }

  public boolean hasData() {
    if (partialReadOffset != 0) {
      return true;
    }
    long producer = producerAcquire();
    long consumer = consumerAcquire();
    return occupancy(producer, consumer) != 0;
  }

  public int write(ByteBuffer source) {
    int written = 0;
    while (source.hasRemaining()) {
      long producer = producerAcquire();
      long consumer = consumerAcquire();
      if (occupancy(producer, consumer) == slotCount) {
        break;
      }
      int length = Math.min(payloadSize, source.remaining());
      long slotOffset = slotOffset(producer);
      copy(source, memory.asSlice(slotOffset + SLOT_HEADER_SIZE, length).asByteBuffer(), length);
      memory.set(ValueLayout.JAVA_INT, slotOffset, length);
      memory.set(ValueLayout.JAVA_LONG, slotOffset + WRITER_GENERATION_OFFSET, writerGenerationSupplier.getAsLong());
      memory.set(ValueLayout.JAVA_LONG, slotOffset + TARGET_GENERATION_OFFSET, targetGenerationSupplier.getAsLong());
      producerRelease(producer + 1);
      written += length;
    }
    return written;
  }

  public int read(ByteBuffer destination) {
    int read = 0;
    while (destination.hasRemaining()) {
      long consumer = consumerAcquire();
      long producer = producerAcquire();
      if (occupancy(producer, consumer) == 0) {
        break;
      }
      long slotOffset = slotOffset(consumer);
      long expectedWriterGeneration = writerGenerationSupplier.getAsLong();
      long expectedTargetGeneration = targetGenerationSupplier.getAsLong();
      long slotWriterGeneration = memory.get(ValueLayout.JAVA_LONG, slotOffset + WRITER_GENERATION_OFFSET);
      long slotTargetGeneration = memory.get(ValueLayout.JAVA_LONG, slotOffset + TARGET_GENERATION_OFFSET);
      if (slotWriterGeneration != expectedWriterGeneration || slotTargetGeneration != expectedTargetGeneration) {
        partialReadOffset = 0;
        consumerRelease(consumer + 1);
        continue;
      }
      int length = memory.get(ValueLayout.JAVA_INT, slotOffset);
      if (length < 0 || length > payloadSize || partialReadOffset > length) {
        throw new IllegalStateException("Invalid shared memory slot length " + length);
      }
      int bytesToRead = Math.min(length - partialReadOffset, destination.remaining());
      ByteBuffer source = memory.asSlice(slotOffset + SLOT_HEADER_SIZE + partialReadOffset, bytesToRead).asByteBuffer();
      destination.put(source);
      partialReadOffset += bytesToRead;
      read += bytesToRead;
      if (partialReadOffset == length) {
        partialReadOffset = 0;
        consumerRelease(consumer + 1);
      } else {
        break;
      }
    }
    return read;
  }

  public long availableSlots() {
    long producer = producerAcquire();
    long consumer = consumerAcquire();
    return slotCount - occupancy(producer, consumer);
  }

  private long occupancy(long producer, long consumer) {
    long occupancy = producer - consumer;
    if (occupancy < 0 || occupancy > slotCount) {
      throw new IllegalStateException(
          "Invalid shared memory ring counters: producer=" + producer + ", consumer=" + consumer + ", slotCount=" + slotCount);
    }
    return occupancy;
  }

  private long slotOffset(long sequence) {
    return dataOffset + (sequence % slotCount) * slotSize;
  }

  private long producerAcquire() {
    return (long) LONG_HANDLE.getAcquire(memory, producerOffset);
  }

  private long consumerAcquire() {
    return (long) LONG_HANDLE.getAcquire(memory, consumerOffset);
  }

  private void producerRelease(long value) {
    LONG_HANDLE.setRelease(memory, producerOffset, value);
  }

  private void consumerRelease(long value) {
    LONG_HANDLE.setRelease(memory, consumerOffset, value);
  }

  private static void copy(ByteBuffer source, ByteBuffer destination, int length) {
    int originalLimit = source.limit();
    source.limit(source.position() + length);
    try {
      destination.put(source);
    } finally {
      source.limit(originalLimit);
    }
  }
}
