/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

final class SharedMemoryMurphyProcess {

  private static final int SLOT_SIZE = 1024;
  private static final int SLOT_COUNT = 8;

  private SharedMemoryMurphyProcess() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.exit(2);
    }

    try {
      switch (args[0]) {
        case "echo" -> echo(args);
        case "race-claim" -> raceClaim(args);
        case "hold-data" -> holdData(args);
        default -> System.exit(2);
      }
    } catch (IOException exception) {
      exception.printStackTrace(System.err);
      System.exit(3);
    } catch (Throwable throwable) {
      throwable.printStackTrace(System.err);
      System.exit(4);
    }
  }

  private static void echo(String[] args) throws Exception {
    String name = args[1];
    int size = Integer.parseInt(args[2]);
    byte[] received = new byte[size];

    try (SharedMemoryTransport transport =
        new SharedMemoryTransport(name, false, SLOT_SIZE, SLOT_COUNT)) {
      ByteBuffer destination = ByteBuffer.wrap(received);
      awaitRead(transport, destination, 15);

      ByteBuffer source = ByteBuffer.wrap(received);
      awaitWrite(transport, source, 15);
    }
  }

  private static void raceClaim(String[] args) throws Exception {
    String name = args[1];
    boolean sideA = Boolean.parseBoolean(args[2]);
    Path start = Path.of(args[3]);
    Path release = Path.of(args[4]);
    Path acquired = Path.of(args[5]);

    awaitFile(start, 10);
    try (SharedMemoryTransport ignored =
        new SharedMemoryTransport(name, sideA, SLOT_SIZE, SLOT_COUNT)) {
      Files.writeString(acquired, "acquired");
      awaitFile(release, 10);
    }
  }

  private static void holdData(String[] args) throws Exception {
    String name = args[1];
    Path ready = Path.of(args[2]);
    int size = Integer.parseInt(args[3]);

    try (SharedMemoryTransport transport =
        new SharedMemoryTransport(name, false, SLOT_SIZE, SLOT_COUNT)) {
      ByteBuffer source = ByteBuffer.wrap(payload(size, 37));
      awaitWrite(transport, source, 10);
      Files.writeString(ready, "ready");
      Thread.sleep(TimeUnit.SECONDS.toMillis(30));
    }
  }

  private static void awaitRead(
      SharedMemoryTransport transport, ByteBuffer destination, int timeoutSeconds)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (destination.hasRemaining() && System.nanoTime() < deadline) {
      if (transport.hasData()) {
        transport.read(destination);
      } else {
        Thread.onSpinWait();
      }
    }
    if (destination.hasRemaining()) {
      throw new AssertionError("timed out waiting for shared-memory input");
    }
  }

  private static void awaitWrite(
      SharedMemoryTransport transport, ByteBuffer source, int timeoutSeconds)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (source.hasRemaining() && System.nanoTime() < deadline) {
      if (transport.canWrite()) {
        transport.write(source);
      } else {
        Thread.onSpinWait();
      }
    }
    if (source.hasRemaining()) {
      throw new AssertionError("timed out waiting for shared-memory output capacity");
    }
  }

  private static void awaitFile(Path path, int timeoutSeconds) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (!Files.exists(path) && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    if (!Files.exists(path)) {
      throw new AssertionError("timed out waiting for " + path);
    }
  }

  private static byte[] payload(int size, int seed) {
    byte[] data = new byte[size];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) (seed + i * 31);
    }
    return data;
  }
}
