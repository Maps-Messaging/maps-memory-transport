/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm;

import io.mapsmessaging.memory.MemoryTransport;
import io.mapsmessaging.memory.PeerGenerationChangedException;
import io.mapsmessaging.memory.internal.MemoryRing;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.regex.Pattern;

public final class SharedMemoryTransport implements MemoryTransport {

  public static final int DEFAULT_SLOT_SIZE = 64 * 1024;
  public static final int DEFAULT_SLOT_COUNT = 256;

  private static final int MAGIC = 0x4d415053;
  private static final int VERSION = 3;
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
  private static final VarHandle LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle();
  private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

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

  private volatile long observedPeerGeneration;
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
    preparePath(path);
    long ringSize = (long) slotSize * slotCount;
    long regionSize = HEADER_SIZE + ringSize * 2;

    FileChannel openedChannel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
    secureFile(path);
    Arena openedArena = null;
    MemorySegment mappedMemory = null;
    boolean claimed = false;
    try {
      initialise(openedChannel, regionSize, slotSize, slotCount);
      openedArena = Arena.ofShared();
      mappedMemory = openedChannel.map(FileChannel.MapMode.READ_WRITE, 0, regionSize, openedArena);
      validate(mappedMemory, slotSize, slotCount);
      long claimedGeneration = claimSide(openedChannel, mappedMemory, sideA, ownerPid, sessionId);
      claimed = true;

      channel = openedChannel;
      arena = openedArena;
      memory = mappedMemory;
      generation = claimedGeneration;

      long aDataOffset = HEADER_SIZE;
      long bDataOffset = HEADER_SIZE + ringSize;
      MemoryRing aToB =
          new MemoryRing(mappedMemory, A_PRODUCER_OFFSET, A_CONSUMER_OFFSET, aDataOffset, slotSize, slotCount, () -> generation, this::peerGenerationRaw);
      MemoryRing bToA =
          new MemoryRing(mappedMemory, B_PRODUCER_OFFSET, B_CONSUMER_OFFSET, bDataOffset, slotSize, slotCount, this::peerGenerationRaw, () -> generation);
      transmitRing = sideA ? aToB : bToA;
      receiveRing = sideA ? bToA : aToB;
      observedPeerGeneration = peerGenerationRaw();
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
    checkPeerGeneration();
    return receiveRing.read(destination);
  }

  @Override
  public boolean hasData() {
    if (closed) {
      return false;
    }
    heartbeat();
    return peerGenerationChanged() || receiveRing.hasData();
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
    long pid = getLongAcquire(memory, peerOwnerPidOffset());
    long peerSession = getLongAcquire(memory, peerSessionOffset());
    return pid > 0 && peerSession != 0 && isAlive(pid);
  }

  public long peerHeartbeatMillis() {
    if (closed) {
      return 0;
    }
    return getLongAcquire(memory, peerHeartbeatOffset());
  }

  public long generation() {
    return generation;
  }

  public long peerGeneration() {
    return closed ? 0 : peerGenerationRaw();
  }

  public Path path() {
    return path;
  }

  long sessionId() {
    return sessionId;
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    releaseSide(channel, memory, sideA, ownerPid, sessionId);

    RuntimeException arenaFailure = null;
    try {
      arena.close();
    } catch (RuntimeException exception) {
      arenaFailure = exception;
    }

    try {
      channel.close();
    } catch (IOException exception) {
      if (arenaFailure != null) {
        exception.addSuppressed(arenaFailure);
      }
      throw exception;
    }

    if (arenaFailure != null) {
      throw arenaFailure;
    }
  }

  private void checkPeerGeneration() throws PeerGenerationChangedException {
    long current = peerGenerationRaw();
    long previous = observedPeerGeneration;
    if (previous == 0 && current != 0) {
      observedPeerGeneration = current;
      return;
    }
    if (previous != 0 && current != 0 && previous != current) {
      observedPeerGeneration = current;
      throw new PeerGenerationChangedException(previous, current);
    }
  }

  private boolean peerGenerationChanged() {
    long current = peerGenerationRaw();
    long previous = observedPeerGeneration;
    if (previous == 0 && current != 0) {
      observedPeerGeneration = current;
      return false;
    }
    return previous != 0 && current != 0 && previous != current;
  }

  private void heartbeat() {
    setLongRelease(memory, ownHeartbeatOffset(), System.currentTimeMillis());
  }

  private void ensureOpen() throws IOException {
    if (closed) {
      throw new IOException("Shared memory transport is closed");
    }
  }

  private long peerGenerationRaw() {
    return getLongAcquire(memory, sideA ? B_GENERATION_OFFSET : A_GENERATION_OFFSET);
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
            "Shared memory region size does not match requested configuration: existing=" + existingSize + ", requested=" + regionSize);
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
      long existingPid = getLongAcquire(segment, ownerOffset);
      long existingSession = getLongAcquire(segment, sessionOffset);
      if (existingPid > 0 && existingSession != 0 && isAlive(existingPid)) {
        throw new IOException("Shared memory side " + (sideA ? "A" : "B") + " is already owned by live process " + existingPid);
      }

      long nextGeneration = getLongAcquire(segment, GENERATION_OFFSET) + 1;
      if (nextGeneration <= 0) {
        nextGeneration = 1;
      }
      setLongRelease(segment, GENERATION_OFFSET, nextGeneration);
      setLongRelease(segment, ownerOffset, ownerPid);
      setLongRelease(segment, sessionOffset, sessionId);
      setLongRelease(segment, heartbeatOffset, System.currentTimeMillis());
      setLongRelease(segment, sideGenerationOffset, nextGeneration);
      segment.force();
      return nextGeneration;
    }
  }

  private static void releaseSide(FileChannel channel, MemorySegment segment, boolean sideA, long ownerPid, long sessionId) {
    long ownerOffset = sideA ? A_OWNER_PID_OFFSET : B_OWNER_PID_OFFSET;
    long sessionOffset = sideA ? A_SESSION_OFFSET : B_SESSION_OFFSET;
    long heartbeatOffset = sideA ? A_HEARTBEAT_OFFSET : B_HEARTBEAT_OFFSET;

    try (var ignored = channel.lock()) {
      if (getLongAcquire(segment, ownerOffset) == ownerPid && getLongAcquire(segment, sessionOffset) == sessionId) {
        setLongRelease(segment, ownerOffset, 0L);
        setLongRelease(segment, sessionOffset, 0L);
        setLongRelease(segment, heartbeatOffset, 0L);
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

  private static long getLongAcquire(MemorySegment segment, long offset) {
    return (long) LONG_HANDLE.getAcquire(segment, offset);
  }

  private static void setLongRelease(MemorySegment segment, long offset, long value) {
    LONG_HANDLE.setRelease(segment, offset, value);
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
    if (name == null || !NAME_PATTERN.matcher(name).matches()) {
      throw new IllegalArgumentException("name must match " + NAME_PATTERN.pattern());
    }
    String user = System.getProperty("user.name", "unknown").replaceAll("[^A-Za-z0-9._-]", "_");
    Path sharedMemory = Path.of("/dev/shm");
    Path base =
        Files.isDirectory(sharedMemory)
            ? sharedMemory.resolve("mapsmessaging").resolve(user)
            : Path.of(System.getProperty("java.io.tmpdir"), "mapsmessaging-shm", user);
    return base.resolve(name + ".shm");
  }

  private static void preparePath(Path path) throws IOException {
    Files.createDirectories(path.getParent());
    secureDirectory(path.getParent());
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
      throw new IOException("Shared memory path must not be a symbolic link: " + path);
    }
  }

  private static void secureDirectory(Path directory) throws IOException {
    try {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX platform.
    }
  }

  private static void secureFile(Path file) throws IOException {
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX platform.
    }
  }
}
