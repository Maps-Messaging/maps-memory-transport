/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import io.mapsmessaging.memory.shm.SharedMemoryTransport;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

/** An independent bidirectional transport, with automatic local-peer liveness monitoring. */
public final class MemoryConnection implements MemoryTransport {

  private static final long HEARTBEAT_INTERVAL_MILLIS = 1_000;
  private static final long HEARTBEAT_TIMEOUT_MILLIS = 5_000;

  private final MemoryTransport transport;
  private final Socket controlSocket;
  private final boolean regionOwner;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Thread keepaliveThread;
  private volatile Runnable onClose;

  MemoryConnection(MemoryTransport transport, Socket controlSocket, boolean regionOwner) {
    this.transport = transport;
    this.controlSocket = controlSocket;
    this.regionOwner = regionOwner;
    keepaliveThread = transport instanceof SharedMemoryTransport
        ? Thread.ofVirtual().name("memory-peer-keepalive").start(this::monitorSharedMemory)
        : null;
  }

  @Override
  public int write(ByteBuffer source) throws IOException {
    ensureOpen();
    return transport.write(source);
  }

  @Override
  public int read(ByteBuffer destination) throws IOException {
    ensureOpen();
    return transport.read(destination);
  }

  @Override
  public boolean hasData() {
    return !closed.get() && transport.hasData();
  }

  @Override
  public boolean canWrite() {
    return !closed.get() && transport.canWrite();
  }

  @Override
  public String remoteAddress() {
    return transport.remoteAddress();
  }

  public boolean isClosed() {
    return closed.get();
  }

  void onClose(Runnable listener) {
    onClose = listener;
    if (closed.get()) {
      listener.run();
    }
  }

  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (keepaliveThread != null) {
      keepaliveThread.interrupt();
    }
    IOException failure = null;
    try {
      transport.close();
    } catch (IOException exception) {
      failure = exception;
    }
    if (controlSocket != null) {
      try {
        controlSocket.close();
      } catch (IOException exception) {
        if (failure == null) {
          failure = exception;
        } else {
          failure.addSuppressed(exception);
        }
      }
    }
    if (regionOwner && transport instanceof SharedMemoryTransport sharedMemoryTransport) {
      try {
        Files.deleteIfExists(sharedMemoryTransport.path());
      } catch (IOException exception) {
        if (failure == null) {
          failure = exception;
        } else {
          failure.addSuppressed(exception);
        }
      }
    }
    Runnable listener = onClose;
    if (listener != null) {
      listener.run();
    }
    if (failure != null) {
      throw failure;
    }
  }

  private void monitorSharedMemory() {
    SharedMemoryTransport sharedMemoryTransport = (SharedMemoryTransport) transport;
    long peerGeneration = sharedMemoryTransport.peerGeneration();
    while (!closed.get()) {
      try {
        // Refresh the existing shared-memory heartbeat without consuming payload bytes.
        sharedMemoryTransport.hasData();
        if (!sharedMemoryTransport.peerPresent()
            || sharedMemoryTransport.peerGeneration() != peerGeneration
            || System.currentTimeMillis() - sharedMemoryTransport.peerHeartbeatMillis() > HEARTBEAT_TIMEOUT_MILLIS) {
          close();
          return;
        }
        Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return;
      } catch (IOException | RuntimeException exception) {
        try {
          close();
        } catch (IOException ignored) {
          // Cleanup is best effort after liveness failure.
        }
        return;
      }
    }
  }

  private void ensureOpen() throws IOException {
    if (closed.get()) {
      throw new IOException("Memory connection is closed");
    }
  }
}
