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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.mapsmessaging.memory.PeerGenerationChangedException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SharedMemoryTransportMurphyTest {

  private static final int SLOT_SIZE = 1024;
  private static final int SLOT_COUNT = 8;

  @Test
  void rejectsCorruptMagicInExistingRegion() throws Exception {
    String name = name();
    Path path = createAndClose(name);
    try {
      overwriteWithZeros(path, 0, 4);
      assertThrows(IOException.class,
          () -> new SharedMemoryTransport(name, true, SLOT_SIZE, SLOT_COUNT));
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void rejectsCorruptVersionInExistingRegion() throws Exception {
    String name = name();
    Path path = createAndClose(name);
    try {
      overwriteWithZeros(path, 4, 4);
      assertThrows(IOException.class,
          () -> new SharedMemoryTransport(name, true, SLOT_SIZE, SLOT_COUNT));
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void rejectsTruncatedExistingRegion() throws Exception {
    String name = name();
    Path path = createAndClose(name);
    try {
      try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
        channel.truncate(64);
      }
      assertThrows(IOException.class,
          () -> new SharedMemoryTransport(name, true, SLOT_SIZE, SLOT_COUNT));
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void rejectsSymbolicLinkAtSharedMemoryPathWhenSupported() throws Exception {
    String name = name();
    Path path = createAndClose(name);
    Path target = null;
    try {
      Files.deleteIfExists(path);
      target = Files.createTempFile(path.getParent(), "maps-shm-target-", ".tmp");
      try {
        Files.createSymbolicLink(path, target);
      } catch (UnsupportedOperationException | IOException exception) {
        assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
      }

      assertThrows(IOException.class,
          () -> new SharedMemoryTransport(name, true, SLOT_SIZE, SLOT_COUNT));
    } finally {
      Files.deleteIfExists(path);
      if (target != null) {
        Files.deleteIfExists(target);
      }
    }
  }

  @Test
  void twoProcessesCannotClaimTheSameSideAtTheSameTime() throws Exception {
    String name = name();
    Path start = marker("start");
    Path release = marker("release");
    Path acquiredOne = marker("acquired-one");
    Path acquiredTwo = marker("acquired-two");
    Files.deleteIfExists(start);
    Files.deleteIfExists(release);
    Files.deleteIfExists(acquiredOne);
    Files.deleteIfExists(acquiredTwo);

    Process first = process("race-claim", name, "true", start.toString(), release.toString(), acquiredOne.toString());
    Process second = process("race-claim", name, "true", start.toString(), release.toString(), acquiredTwo.toString());
    try {
      Files.writeString(start, "go");

      Process loser = awaitOneExit(first, second, 10);
      assertEquals(3, loser.exitValue(), "one claimant must be rejected while the other owns side A");
      assertTrue(Files.exists(acquiredOne) ^ Files.exists(acquiredTwo),
          "exactly one process must acquire side A");

      Files.writeString(release, "release");
      Process winner = loser == first ? second : first;
      assertTrue(winner.waitFor(10, TimeUnit.SECONDS));
      assertEquals(0, winner.exitValue());
    } finally {
      first.destroyForcibly();
      second.destroyForcibly();
      first.waitFor(5, TimeUnit.SECONDS);
      second.waitFor(5, TimeUnit.SECONDS);
      Files.deleteIfExists(start);
      Files.deleteIfExists(release);
      Files.deleteIfExists(acquiredOne);
      Files.deleteIfExists(acquiredTwo);
    }
  }

  @Test
  void peerCrashDuringPartialReadCannotMixOldAndReplacementBytes() throws Exception {
    String name = name();
    Path ready = marker("ready");
    Files.deleteIfExists(ready);

    try (SharedMemoryTransport sideA =
        new SharedMemoryTransport(name, true, SLOT_SIZE, SLOT_COUNT)) {
      Process oldPeer = process("hold-data", name, ready.toString(), "100");
      try {
        awaitFile(ready, 10);

        ByteBuffer prefix = ByteBuffer.allocate(10);
        assertEquals(10, sideA.read(prefix));

        oldPeer.destroyForcibly();
        assertTrue(oldPeer.waitFor(10, TimeUnit.SECONDS));

        try (SharedMemoryTransport replacement =
            new SharedMemoryTransport(name, false, SLOT_SIZE, SLOT_COUNT)) {
          byte[] newPayload = "replacement-peer".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          assertEquals(newPayload.length, replacement.write(ByteBuffer.wrap(newPayload)));

          assertTrue(sideA.hasData());
          assertThrows(PeerGenerationChangedException.class,
              () -> sideA.read(ByteBuffer.allocate(128)));

          ByteBuffer destination = ByteBuffer.allocate(128);
          assertEquals(newPayload.length, sideA.read(destination));
          destination.flip();
          byte[] actual = new byte[destination.remaining()];
          destination.get(actual);
          assertArrayEquals(newPayload, actual);
          assertFalse(sideA.hasData());
        }
      } finally {
        oldPeer.destroyForcibly();
        oldPeer.waitFor(5, TimeUnit.SECONDS);
        Files.deleteIfExists(ready);
      }
    }
  }

  @Test
  void survivesSustainedCrossProcessTransferAcrossManyRingWraps() throws Exception {
    String name = name();
    int size = 512 * 1024;
    byte[] expected = payload(size, 91);
    Process peer = process("echo", name, Integer.toString(size));

    try (SharedMemoryTransport sideA =
        new SharedMemoryTransport(name, true, SLOT_SIZE, SLOT_COUNT)) {
      ByteBuffer source = ByteBuffer.wrap(expected);
      long writeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (source.hasRemaining() && System.nanoTime() < writeDeadline) {
        if (sideA.canWrite()) {
          sideA.write(source);
        } else {
          Thread.onSpinWait();
        }
      }
      assertFalse(source.hasRemaining(), "parent failed to stream complete payload to peer");

      ByteBuffer destination = ByteBuffer.allocate(size);
      long readDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (destination.hasRemaining() && System.nanoTime() < readDeadline) {
        if (sideA.hasData()) {
          sideA.read(destination);
        } else {
          Thread.onSpinWait();
        }
      }
      assertFalse(destination.hasRemaining(), "parent failed to receive complete echoed payload");

      destination.flip();
      byte[] actual = new byte[destination.remaining()];
      destination.get(actual);
      assertArrayEquals(expected, actual);

      assertTrue(peer.waitFor(10, TimeUnit.SECONDS));
      assertEquals(0, peer.exitValue());
    } finally {
      peer.destroyForcibly();
      peer.waitFor(5, TimeUnit.SECONDS);
    }
  }

  private static Path createAndClose(String name) throws Exception {
    try (SharedMemoryTransport transport =
        new SharedMemoryTransport(name, true, SLOT_SIZE, SLOT_COUNT)) {
      return transport.path();
    }
  }

  private static void overwriteWithZeros(Path path, long offset, int length) throws Exception {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      channel.position(offset);
      ByteBuffer zeros = ByteBuffer.allocate(length);
      while (zeros.hasRemaining()) {
        channel.write(zeros);
      }
      channel.force(true);
    }
  }

  private static Process process(String... arguments) throws IOException {
    Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
    String[] command = new String[4 + arguments.length];
    command[0] = javaExecutable.toString();
    command[1] = "-cp";
    command[2] = System.getProperty("java.class.path");
    command[3] = SharedMemoryMurphyProcess.class.getName();
    System.arraycopy(arguments, 0, command, 4, arguments.length);
    return new ProcessBuilder(command).inheritIO().start();
  }

  private static Process awaitOneExit(Process first, Process second, int timeoutSeconds)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (System.nanoTime() < deadline) {
      if (!first.isAlive()) {
        return first;
      }
      if (!second.isAlive()) {
        return second;
      }
      Thread.sleep(5);
    }
    throw new AssertionError("neither competing claimant exited before deadline");
  }

  private static void awaitFile(Path path, int timeoutSeconds) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (!Files.exists(path) && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(Files.exists(path), "timed out waiting for " + path);
  }

  private static Path marker(String suffix) throws IOException {
    Path path = Files.createTempFile("maps-shm-" + suffix + "-", ".marker");
    Files.deleteIfExists(path);
    return path;
  }

  private static String name() {
    return "murphy-" + UUID.randomUUID();
  }

  private static byte[] payload(int size, int seed) {
    byte[] data = new byte[size];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) (seed + i * 31);
    }
    return data;
  }
}
