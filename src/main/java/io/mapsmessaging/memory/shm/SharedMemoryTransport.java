/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm;

import io.mapsmessaging.memory.MemoryTransport;
import io.mapsmessaging.memory.internal.MemoryRing;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Locale;

public final class SharedMemoryTransport implements MemoryTransport {

  public static final int DEFAULT_SLOT_SIZE = 64 * 1024;
  public static final int DEFAULT_SLOT_COUNT = 256;

  private static final int MAGIC = 0x4d415053;
  private static final int VERSION = 2;
  private static final long HEADER_SIZE = 128;
  private static final long MAGIC_OFFSET = 0;
  private static final long VERSION_OFFSET = 4;
  private static final long SLOT_SIZE_OFFSET = 8;
  private static final long SLOT_COUNT_OFFSET = 12;
  private static final long A_PRODUCER_OFFSET = 16;
  private static final long A_CONSUMER_OFFSET = 24;
  private static final long B_PRODUCER_OFFSET = 32;
  private static final long B_CONSUMER_OFFSET = 40;
  private static final long A_OWNER_PID_OFFSET = 48;
  private static final long B_OWNER_PID_OFFSET = 56;
  private static final long A_SESSION_OFFSET = 64;
  private static final long B_SESSION_OFFSET = 72;
  private static final long A_HEARTBEAT_OFFSET = 80;
  private static final long B_HEARTBEAT_OFFSET = 88;
  private static final long GENERATION_OFFSET = 96;
  private static final long A_GENERATION_OFFSET = 104;
  private static final long B_GENERATION_OFFSET = 112;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final Arena arena;
  private final FileChannel channel;
  private final MemorySegment memory;
  private final MemoryRing transmitRing;
  private final MemoryRing receiveRing;
  private final Path path;
  private final boolean sideA;
  private final long ownerPid;
  private final long sessionId;
  private final long generation;

  private volatile boolean closed;

  public SharedMemoryTransport(String name, boolean sideA) throws IOException {
    this(name, sideA, DEFAULT_SLOT_SIZE, DEFAULT_SLOT_COUNT);
  }

  public SharedMemoryTransport(String name, boolean sideA, int slotSize, int slotCount) throws IOException {
    if (slotSize < 256) {
      throw new IllegalArgumentException("slotSize must be at least 256 bytes");
    }
    if (slotCount < 2) {
      throw new IllegalArgumentException("slotCount must be at least 2");
    }

    this.sideA = sideA;
    ownerPid = ProcessHandle.current().pid();
    sessionId = newSessionId();
    path = resolvePath(name);
    Files.createDirectories(path.getParent());
    long ringSize = (long) slotSize * slotCount;
    long regionSize = HEADER_SIZE + ringSize * 2;

    FileChannel openedChannel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
    Arena openedArena = null;
    MemorySegment mappedMemory = null;
    long claimedGeneration = 0;
    boolean claimed = false;
    try {
      initialise(openedChannel, regionSize, slotSize, slotCount);
      openedArena = Arena.ofShared();
      mappedMemory = openedChannel.map(FileChannel.MapMode.READ_WRITE, 0, regionSize, openedArena);
      validate(mappedMemory, slotSize, slotCount);
      claimedGeneration = claimSide(openedChannel, mappedMemory, sideA, ownerPid, sessionId);
      claimed = true;

      long aDataOffset = HEADER_SIZE;
      long bDataOffset = HEADER_SIZE + ringSize;
      MemoryRing aToB = new MemoryRing(mappedMemory, A_PRODUCER_OFFSET, A_CONSUMER_OFFSET, aDataOffset, slotSize, slotCount);
      MemoryRing bToA = new MemoryRing(mappedMemory, B_PRODUCER_OFFSET, B_CONSUMER_OFFSET, bDataOffset, slotSize, slotCount);
      transmitRing = sideA ? aToB : bToA;
      receiveRing = sideA ? bToA : aToB;
      channel = openedChannel;
      arena = openedArena;
      memory = mappedMemory;
      generation = claimedGeneration;
      heartbeat();
    } catch (Throwable throwable) {
      if (claimed && mappedMemory != null) {
        releaseSide(openedChannel, mappedMemory, sideA, ownerPid, sessionId);
      }
      if (openedArena != null) {
        openedArena.close();
      }
      openedChannel.close();
      if (throwable instanceof IOException ioException) {
        throw ioException;
      }
      if (throwable instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IOException("Unable to open shared memory transport", throwable);
    }
  }

  @Override
  public int write(ByteBuffer source) throws IOException {
    ensureOpen();
    heartbeat();
    return transmitRing.write(source);
  }

  @Override
  public int read(ByteBuffer destination) throws IOException {
    ensureOpen();
    heartbeat();
    return receiveRing.read(destination);
  }

  @Override
  public boolean hasData() {
    if (closed) {
      return false;
    }
    heartbeat();
    return receiveRing.hasData();
  }

  @Override
  public boolean canWrite() {
    if (closed) {
      return false;
    }
    heartbeat();
    return transmitRing.availableSlots() > 0;
  }

  @Override
  public String remoteAddress() {
    return "shm:" + path;
  }

  public boolean peerPresent() {
    if (closed) {
      return false;
    }
    long pid = memory.get(ValueLayout.JAVA_LONG, peerOwnerPidOffset());
    long peerSession = memory.get(ValueLayout.JAVA_LONG, peerSessionOffset());
    return pid > 0 && peerSession != 0 && isAlive(pid);
  }

  public long peerHeartbeatMillis() {
    if (closed) {
      return 0;
    }
    return memory.get(ValueLayout.JAVA_LONG, peerHeartbeatOffset());
  }

  public Path path() {
    return path;
  }

  long generation() {
    return generation;
  }

  long sessionId() {
    return sessionId;
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    releaseSide(channel, memory, sideA, ownerPid, sessionId);
    arena.close();
    channel.close();
  }

  private void heartbeat() {
    memory.set(ValueLayout.JAVA_LONG, ownHeartbeatOffset(), System.currentTimeMillis());
  }

  private void ensureOpen() throws IOException {
    if (closed) {
      throw new IOException("Shared memory transport is closed");
    }
  }

  private long ownHeartbeatOffset() {
    return sideA ? A_HEARTBEAT_OFFSET : B_HEARTBEAT_OFFSET;
  }

  private long peerOwnerPidOffset() {
    return sideA ? B_OWNER_PID_OFFSET : A_OWNER_PID_OFFSET;
  }

  private long peerSessionOffset() {
    return sideA ? B_SESSION_OFFSET : A_SESSION_OFFSET;
  }

  private long peerHeartbeatOffset() {
    return sideA ? B_HEARTBEAT_OFFSET : A_HEARTBEAT_OFFSET;
  }

  private static void initialise(FileChannel channel, long regionSize, int slotSize, int slotCount) throws IOException {
    try (var ignored = channel.lock()) {
      long existingSize = channel.size();
      if (existingSize == 0) {
        channel.position(regionSize - 1);
        channel.write(ByteBuffer.wrap(new byte[] {0}));
        try (Arena initArena = Arena.ofConfined()) {
          MemorySegment segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, regionSize, initArena);
          segment.fill((byte) 0);
          segment.set(ValueLayout.JAVA_INT, MAGIC_OFFSET, MAGIC);
          segment.set(ValueLayout.JAVA_INT, VERSION_OFFSET, VERSION);
          segment.set(ValueLayout.JAVA_INT, SLOT_SIZE_OFFSET, slotSize);
          segment.set(ValueLayout.JAVA_INT, SLOT_COUNT_OFFSET, slotCount);
          segment.force();
        }
        return;
      }

      if (existingSize != regionSize) {
        throw new IOException(
            "Shared memory region size does not match requested configuration: existing="
                + existingSize
                + ", requested="
                + regionSize);
      }

      try (Arena validationArena = Arena.ofConfined()) {
        MemorySegment segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, regionSize, validationArena);
        validate(segment, slotSize, slotCount);
      }
    }
  }

  private static long claimSide(FileChannel channel, MemorySegment segment, boolean sideA, long ownerPid, long sessionId) throws IOException {
    long ownerOffset = sideA ? A_OWNER_PID_OFFSET : B_OWNER_PID_OFFSET;
    long sessionOffset = sideA ? A_SESSION_OFFSET : B_SESSION_OFFSET;
    long heartbeatOffset = sideA ? A_HEARTBEAT_OFFSET : B_HEARTBEAT_OFFSET;
    long sideGenerationOffset = sideA ? A_GENERATION_OFFSET : B_GENERATION_OFFSET;

    try (var ignored = channel.lock()) {
      long existingPid = segment.get(ValueLayout.JAVA_LONG, ownerOffset);
      long existingSession = segment.get(ValueLayout.JAVA_LONG, sessionOffset);
      if (existingPid > 0 && existingSession != 0 && isAlive(existingPid)) {
        throw new IOException("Shared memory side " + (sideA ? "A" : "B") + " is already owned by live process " + existingPid);
      }

      long nextGeneration = segment.get(ValueLayout.JAVA_LONG, GENERATION_OFFSET) + 1;
      if (nextGeneration <= 0) {
        nextGeneration = 1;
      }
      segment.set(ValueLayout.JAVA_LONG, GENERATION_OFFSET, nextGeneration);
      segment.set(ValueLayout.JAVA_LONG, ownerOffset, ownerPid);
      segment.set(ValueLayout.JAVA_LONG, sessionOffset, sessionId);
      segment.set(ValueLayout.JAVA_LONG, heartbeatOffset, System.currentTimeMillis());
      segment.set(ValueLayout.JAVA_LONG, sideGenerationOffset, nextGeneration);
      segment.force();
      return nextGeneration;
    }
  }

  private static void releaseSide(FileChannel channel, MemorySegment segment, boolean sideA, long ownerPid, long sessionId) {
    long ownerOffset = sideA ? A_OWNER_PID_OFFSET : B_OWNER_PID_OFFSET;
    long sessionOffset = sideA ? A_SESSION_OFFSET : B_SESSION_OFFSET;
    long heartbeatOffset = sideA ? A_HEARTBEAT_OFFSET : B_HEARTBEAT_OFFSET;

    try (var ignored = channel.lock()) {
      if (segment.get(ValueLayout.JAVA_LONG, ownerOffset) == ownerPid
          && segment.get(ValueLayout.JAVA_LONG, sessionOffset) == sessionId) {
        segment.set(ValueLayout.JAVA_LONG, ownerOffset, 0L);
        segment.set(ValueLayout.JAVA_LONG, sessionOffset, 0L);
        segment.set(ValueLayout.JAVA_LONG, heartbeatOffset, 0L);
        segment.force();
      }
    } catch (IOException | IllegalStateException ignored) {
      // Best effort during close or failed construction.
    }
  }

  private static void validate(MemorySegment segment, int slotSize, int slotCount) throws IOException {
    int magic = segment.get(ValueLayout.JAVA_INT, MAGIC_OFFSET);
    int version = segment.get(ValueLayout.JAVA_INT, VERSION_OFFSET);
    int configuredSlotSize = segment.get(ValueLayout.JAVA_INT, SLOT_SIZE_OFFSET);
    int configuredSlotCount = segment.get(ValueLayout.JAVA_INT, SLOT_COUNT_OFFSET);
    if (magic != MAGIC || version != VERSION || configuredSlotSize != slotSize || configuredSlotCount != slotCount) {
      throw new IOException("Shared memory region layout does not match requested configuration");
    }
  }

  private static boolean isAlive(long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  private static long newSessionId() {
    long value;
    do {
      value = RANDOM.nextLong();
    } while (value == 0);
    return value;
  }

  private static Path resolvePath(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    String safeName = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    Path sharedMemory = Path.of("/dev/shm");
    Path base = Files.isDirectory(sharedMemory) ? sharedMemory.resolve("mapsmessaging") : Path.of(System.getProperty("java.io.tmpdir"), "mapsmessaging-shm");
    return base.resolve(safeName + ".shm");
  }
}
