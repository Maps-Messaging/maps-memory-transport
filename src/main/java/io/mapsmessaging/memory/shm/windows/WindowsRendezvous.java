/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm.windows;

import io.mapsmessaging.memory.WindowsPipeAddress;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native Windows rendezvous using owner-restricted named pipes, OS token checks
 * and anonymous file-mapping handles duplicated into the verified client.
 */
public final class WindowsRendezvous implements AutoCloseable {

  private static final int VERSION = 1;
  private final WindowsPipeAddress address;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object listenerLock = new Object();
  private MemorySegment pendingPipe;

  public static WindowsRendezvous listen(WindowsPipeAddress address) throws IOException {
    if (!windowsSupported()) {
      throw new IOException("Windows native shared memory requires Windows x64 or ARM64");
    }
    return new WindowsRendezvous(address);
  }

  private static boolean windowsSupported() {
    String os = System.getProperty("os.name", "");
    String architecture = System.getProperty("os.arch", "");
    return os.startsWith("Windows")
        && (architecture.equals("amd64") || architecture.equals("x86_64")
            || architecture.equals("aarch64"));
  }

  private WindowsRendezvous(WindowsPipeAddress address) {
    this.address = address;
  }

  public WindowsPipeAddress localAddress() {
    return address;
  }

  public MemorySegment acceptHandle() throws IOException {
    MemorySegment candidate;
    synchronized (listenerLock) {
      if (closed.get()) {
        throw new IOException("Windows shared-memory listener is closed");
      }
      candidate = WindowsNative.newPipe(address.pipeName());
      pendingPipe = candidate;
    }
    boolean transferred = false;
    try {
      while (!closed.get()) {
        if (WindowsNative.connectPipe(candidate)) {
          synchronized (listenerLock) {
            if (!closed.get()) {
              pendingPipe = null;
              transferred = true;
              return candidate;
            }
          }
        }
        try {
          Thread.sleep(5);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IOException("Windows pipe listener interrupted", exception);
        }
      }
      throw new IOException("Windows shared-memory listener is closed");
    } finally {
      if (!transferred) {
        synchronized (listenerLock) {
          if (pendingPipe == candidate) {
            pendingPipe = null;
          }
        }
        WindowsNative.closePipe(candidate, true);
      }
    }
  }

  public WindowsMemoryTransport completeAccept(MemorySegment pipe, int slotSize, int slotCount,
      int timeoutMillis) throws IOException {
    boolean transportOwnsPipe = false;
    try {
      WindowsNative.verifyPeer(pipe, true);
      long size = WindowsMemoryTransport.regionSize(slotSize, slotCount);
      MemorySegment outbound = WindowsNative.createRegion(size);
      MemorySegment inbound = MemorySegment.NULL;
      MemorySegment remoteOutbound = MemorySegment.NULL;
      MemorySegment remoteInbound = MemorySegment.NULL;
      boolean established = false;
      try {
        inbound = WindowsNative.createRegion(size);
        remoteOutbound = WindowsNative.duplicateIntoPeer(pipe, outbound);
        remoteInbound = WindowsNative.duplicateIntoPeer(pipe, inbound);

        MemorySegment mappedOutgoing = outbound;
        MemorySegment mappedIncoming = inbound;
        outbound = MemorySegment.NULL;
        inbound = MemorySegment.NULL;
        WindowsMemoryTransport transport = WindowsMemoryTransport.attach(pipe, mappedOutgoing, mappedIncoming,
            slotSize, slotCount, true, true, "shm-windows:" + address.name());
        transportOwnsPipe = true;
        try {
          WindowsNative.sendByte(pipe, VERSION);
          sendInt(pipe, slotSize);
          sendInt(pipe, slotCount);
          sendLong(pipe, remoteOutbound.address());
          sendLong(pipe, remoteInbound.address());
          if (WindowsNative.receiveByte(pipe, timeoutMillis) != 0x51) {
            throw new IOException("Windows client did not confirm shared mapping attachment");
          }
          WindowsNative.sendByte(pipe, 0x52);
          transport.start();
          established = true;
          return transport;
        } catch (IOException | RuntimeException exception) {
          // Revoke duplicate handles before closing the control pipe.
          WindowsNative.closeRemoteHandle(pipe, remoteOutbound);
          WindowsNative.closeRemoteHandle(pipe, remoteInbound);
          remoteOutbound = MemorySegment.NULL;
          remoteInbound = MemorySegment.NULL;
          try {
            transport.close();
          } catch (IOException cleanupFailure) {
            exception.addSuppressed(cleanupFailure);
          }
          throw exception;
        }
      } finally {
        if (!established) {
          WindowsNative.closeRemoteHandle(pipe, remoteOutbound);
          WindowsNative.closeRemoteHandle(pipe, remoteInbound);
        }
        WindowsNative.close(outbound);
        WindowsNative.close(inbound);
      }
    } catch (IOException | RuntimeException exception) {
      if (!transportOwnsPipe) {
        WindowsNative.closePipe(pipe, true);
      }
      throw exception;
    }
  }

  public static WindowsMemoryTransport connect(WindowsPipeAddress address, int timeoutMillis)
      throws IOException {
    if (!WindowsNative.supported()) {
      throw new IOException("Windows native shared memory requires Windows x64 or ARM64");
    }
    long deadline = System.nanoTime() + (long) timeoutMillis * 1_000_000L;
    MemorySegment pipe = MemorySegment.NULL;
    IOException lastFailure = null;
    while (System.nanoTime() < deadline) {
      try {
        pipe = WindowsNative.openPipe(address.pipeName());
        break;
      } catch (IOException exception) {
        lastFailure = exception;
        try {
          Thread.sleep(10);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while connecting to Windows shared-memory listener", interrupted);
        }
      }
    }
    if (pipe.address() == 0) {
      throw new IOException("Unable to open Windows shared-memory pipe", lastFailure);
    }
    boolean transferred = false;
    try {
      WindowsNative.verifyPeer(pipe, false);
      int version = WindowsNative.receiveByte(pipe, timeoutMillis);
      if (version != VERSION) {
        throw new IOException("Unsupported Windows shared-memory protocol version " + version);
      }
      int slotSize = receiveInt(pipe, timeoutMillis);
      int slotCount = receiveInt(pipe, timeoutMillis);
      WindowsMemoryTransport.regionSize(slotSize, slotCount);
      MemorySegment inbound = MemorySegment.NULL;
      MemorySegment outbound = MemorySegment.NULL;
      try {
        inbound = MemorySegment.ofAddress(receiveLong(pipe, timeoutMillis));
        outbound = MemorySegment.ofAddress(receiveLong(pipe, timeoutMillis));
        MemorySegment mappedOutgoing = outbound;
        MemorySegment mappedIncoming = inbound;
        outbound = MemorySegment.NULL;
        inbound = MemorySegment.NULL;
        WindowsMemoryTransport transport = WindowsMemoryTransport.attach(pipe, mappedOutgoing, mappedIncoming,
            slotSize, slotCount, false, false, "shm-windows:" + address.name());
        transferred = true;
        try {
          WindowsNative.sendByte(pipe, 0x51);
          if (WindowsNative.receiveByte(pipe, timeoutMillis) != 0x52) {
            throw new IOException("Windows server did not confirm attachment");
          }
          transport.start();
          return transport;
        } catch (IOException | RuntimeException exception) {
          try {
            transport.close();
          } catch (IOException cleanupFailure) {
            exception.addSuppressed(cleanupFailure);
          }
          throw exception;
        }
      } finally {
        WindowsNative.close(outbound);
        WindowsNative.close(inbound);
      }
    } catch (IOException | RuntimeException exception) {
      if (!transferred) {
        WindowsNative.closePipe(pipe, false);
      }
      throw exception;
    }
  }

  public static void shutdownPeer(MemorySegment pipe) {
    WindowsNative.cancel(pipe);
  }

  public static void closePeer(MemorySegment pipe) {
    WindowsNative.closePipe(pipe, true);
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    // acceptHandle polls a nonblocking pipe and releases its own pending handle.
    // Closing it here would race with that worker and could double-close a reused HANDLE.
  }

  private static void sendInt(MemorySegment pipe, int value) throws IOException {
    for (int shift = 24; shift >= 0; shift -= 8) {
      WindowsNative.sendByte(pipe, value >>> shift);
    }
  }

  private static int receiveInt(MemorySegment pipe, int timeoutMillis) throws IOException {
    int result = 0;
    for (int index = 0; index < 4; index++) {
      result = (result << 8) | WindowsNative.receiveByte(pipe, timeoutMillis);
    }
    return result;
  }

  private static void sendLong(MemorySegment pipe, long value) throws IOException {
    for (int shift = 56; shift >= 0; shift -= 8) {
      WindowsNative.sendByte(pipe, (int) (value >>> shift));
    }
  }

  private static long receiveLong(MemorySegment pipe, int timeoutMillis) throws IOException {
    long result = 0;
    for (int index = 0; index < 8; index++) {
      result = (result << 8) | WindowsNative.receiveByte(pipe, timeoutMillis);
    }
    return result;
  }
}
