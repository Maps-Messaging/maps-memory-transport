/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm.windows;

import io.mapsmessaging.memory.MemoryTransport;
import io.mapsmessaging.memory.ConnectionCloseNotifier;
import io.mapsmessaging.memory.internal.MemoryRing;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One bidirectional Windows connection: independent anonymous mappings and a
 * private authenticated named pipe. Mapping handles and pipe are owned
 * solely by this connection after successful attachment.
 */
public final class WindowsMemoryTransport implements MemoryTransport, ConnectionCloseNotifier {

  private static final int HEADER_SIZE = 64;
  private static final long HEARTBEAT_INTERVAL_MILLIS = 1_000;
  private static final long HEARTBEAT_TIMEOUT_NANOS = 5_000_000_000L;
  private static final int SLOT_MINIMUM = 256;
  private static final long MAX_REGION_BYTES = 256L * 1024L * 1024L;

  private final MemorySegment socket;
  private final boolean serverSide;
  private final Arena arena;
  private final MemoryRing outgoing;
  private final MemoryRing incoming;
  private final String remote;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object socketWriteLock = new Object();
  private volatile Thread controlReader;
  private volatile Thread controlHeartbeat;
  private volatile IOException terminalFailure;
  private volatile Runnable closeListener;
  private volatile long lastPeerHeartbeat = System.nanoTime();

  static long regionSize(int slotSize, int slotCount) {
    if (slotSize < SLOT_MINIMUM || slotCount < 2) {
      throw new IllegalArgumentException("Invalid ring dimensions");
    }
    long size = Math.addExact(HEADER_SIZE, Math.multiplyExact((long) slotSize, slotCount));
    if (size > MAX_REGION_BYTES) {
      throw new IllegalArgumentException("Shared-memory region exceeds 256 MiB");
    }
    return size;
  }

  static WindowsMemoryTransport attach(MemorySegment socket, MemorySegment sendDescriptor, MemorySegment receiveDescriptor,
      int slotSize, int slotCount, boolean initialise, boolean serverSide, String remote) throws IOException {
    long size = regionSize(slotSize, slotCount);
    Arena arena = Arena.ofShared();
    try {
      MemorySegment sendRegion = WindowsNative.map(sendDescriptor, size, arena);
      MemorySegment receiveRegion = WindowsNative.map(receiveDescriptor, size, arena);
      if (initialise) {
        sendRegion.fill((byte) 0);
        receiveRegion.fill((byte) 0);
      }
      return new WindowsMemoryTransport(socket, arena, sendRegion, receiveRegion, slotSize, slotCount, serverSide, remote);
    } catch (IOException | RuntimeException | Error failure) {
      try {
        arena.close();
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    } finally {
      WindowsNative.close(sendDescriptor);
      WindowsNative.close(receiveDescriptor);
    }
  }

  private WindowsMemoryTransport(MemorySegment socket, Arena arena, MemorySegment sendRegion, MemorySegment receiveRegion,
      int slotSize, int slotCount, boolean serverSide, String remote) {
    this.socket = socket;
    this.serverSide = serverSide;
    this.arena = arena;
    this.remote = remote;
    outgoing = new MemoryRing(sendRegion, 0, 8, HEADER_SIZE, slotSize, slotCount);
    incoming = new MemoryRing(receiveRegion, 0, 8, HEADER_SIZE, slotSize, slotCount);
    // Start the control workers only after both endpoints confirm attachment.
  }

  /** Starts liveness monitoring after completion of the rendezvous handshake. */
  void start() {
    if (closed.get()) {
      throw new IllegalStateException("Cannot activate a closed Windows memory transport");
    }
    controlReader = Thread.ofPlatform().daemon(true).name("windows-shm-control-reader").start(this::readControl);
    controlHeartbeat = Thread.ofVirtual().name("windows-shm-heartbeat").start(this::sendHeartbeats);
  }

  @Override
  public synchronized int write(ByteBuffer source) throws IOException {
    ensureOpen();
    return outgoing.write(source);
  }

  @Override
  public synchronized int read(ByteBuffer destination) throws IOException {
    ensureOpen();
    return incoming.read(destination);
  }

  public void onClose(Runnable listener) {
    closeListener = listener;
    if (closed.get()) {
      listener.run();
    }
  }

  @Override
  public synchronized boolean hasData() {
    return terminalFailure != null || (!closed.get() && incoming.hasData());
  }

  @Override
  public synchronized boolean canWrite() {
    return terminalFailure == null && !closed.get() && outgoing.availableSlots() > 0;
  }

  @Override
  public String remoteAddress() {
    return remote;
  }

  @Override
  public synchronized void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (controlHeartbeat != null) {
      controlHeartbeat.interrupt();
    }
    synchronized (socketWriteLock) {
      WindowsNative.closePipe(socket, serverSide);
    }
    try {
      arena.close();
    } catch (RuntimeException failure) {
      throw new IOException("Failed to release Windows shared-memory mappings", failure);
    } finally {
      Runnable listener = closeListener;
      if (listener != null) {
        listener.run();
      }
    }
  }

  private void sendHeartbeats() {
    while (!closed.get()) {
      try {
        synchronized (socketWriteLock) {
          if (closed.get()) {
            return;
          }
          WindowsNative.sendByte(socket, 0x4b);
        }
        if (System.nanoTime() - lastPeerHeartbeat > HEARTBEAT_TIMEOUT_NANOS) {
          fail(new IOException("Shared-memory peer heartbeat timed out"));
          return;
        }
        Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return;
      } catch (IOException exception) {
        fail(exception);
        return;
      }
    }
  }

  private void readControl() {
    while (!closed.get()) {
      try {
        if (WindowsNative.receiveByte(socket, 6_000) != 0x4b) {
          fail(new IOException("Unexpected shared-memory control frame"));
          return;
        }
        lastPeerHeartbeat = System.nanoTime();
      } catch (IOException exception) {
        if (!closed.get()) {
          fail(exception);
        }
        return;
      }
    }
  }

  private void fail(IOException exception) {
    if (terminalFailure == null) {
      terminalFailure = exception;
    }
    try {
      close();
    } catch (IOException cleanupFailure) {
      exception.addSuppressed(cleanupFailure);
    }
  }

  private void ensureOpen() throws IOException {
    if (terminalFailure != null) {
      throw terminalFailure;
    }
    if (closed.get()) {
      throw new IOException("Windows memory transport is closed");
    }
  }
}
