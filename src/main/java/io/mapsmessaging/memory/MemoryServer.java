/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import io.mapsmessaging.memory.rdma.RdmaListener;
import io.mapsmessaging.memory.rdma.RdmaTransport;
import io.mapsmessaging.memory.shm.SharedMemoryTransport;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A listener supporting multiple independent incoming transport connections. */
public final class MemoryServer implements AutoCloseable {

  private static final int HANDSHAKE_TIMEOUT_MILLIS = 5_000;
  private static final int DEFAULT_BACKLOG = 64;

  private final ServerSocket localListener;
  private final RdmaListener rdmaListener;
  private final int slotSize;
  private final int slotCount;
  private final BlockingQueue<MemoryConnection> pending = new LinkedBlockingQueue<>();
  private final Set<MemoryConnection> connections = ConcurrentHashMap.newKeySet();
  private final Set<Socket> negotiating = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Thread acceptThread;

  public static MemoryServer listenSharedMemory(InetSocketAddress address) throws IOException {
    return listenSharedMemory(address, SharedMemoryTransport.DEFAULT_SLOT_SIZE, SharedMemoryTransport.DEFAULT_SLOT_COUNT);
  }

  public static MemoryServer listenSharedMemory(InetSocketAddress address, int slotSize, int slotCount) throws IOException {
    requireLoopback(address);
    if (slotSize < 256 || slotCount < 2) {
      throw new IllegalArgumentException("Invalid shared-memory ring configuration");
    }
    ServerSocket listener = new ServerSocket();
    listener.bind(address, DEFAULT_BACKLOG);
    return new MemoryServer(listener, null, slotSize, slotCount);
  }

  public static MemoryServer listenRdma(InetSocketAddress address) throws IOException {
    return new MemoryServer(null, RdmaListener.bind(address), 0, 0);
  }

  private MemoryServer(ServerSocket localListener, RdmaListener rdmaListener, int slotSize, int slotCount) {
    this.localListener = localListener;
    this.rdmaListener = rdmaListener;
    this.slotSize = slotSize;
    this.slotCount = slotCount;
    acceptThread = Thread.ofVirtual().name("memory-server-accept").start(this::acceptLoop);
  }

  public SocketAddress localAddress() {
    return localListener != null ? localListener.getLocalSocketAddress() : rdmaListener.localAddress();
  }

  public MemoryConnection accept() throws IOException {
    while (!closed.get()) {
      try {
        MemoryConnection connection = pending.poll(200, TimeUnit.MILLISECONDS);
        if (connection != null && !connection.isClosed()) {
          return connection;
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while accepting memory connection", exception);
      }
    }
    throw new IOException("Memory server is closed");
  }

  public boolean isClosed() {
    return closed.get();
  }

  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    acceptThread.interrupt();
    IOException failure = null;
    try {
      if (localListener != null) {
        localListener.close();
      } else {
        rdmaListener.close();
      }
    } catch (IOException exception) {
      failure = exception;
    }
    for (Socket socket : negotiating) {
      try {
        socket.close();
      } catch (IOException exception) {
        if (failure == null) {
          failure = exception;
        } else {
          failure.addSuppressed(exception);
        }
      }
    }
    for (MemoryConnection connection : connections) {
      try {
        connection.close();
      } catch (IOException exception) {
        if (failure == null) {
          failure = exception;
        } else {
          failure.addSuppressed(exception);
        }
      }
    }
    pending.clear();
    if (failure != null) {
      throw failure;
    }
  }

  private void acceptLoop() {
    while (!closed.get()) {
      try {
        if (localListener != null) {
          Socket socket = localListener.accept();
          negotiating.add(socket);
          Thread.ofVirtual().name("memory-region-negotiate").start(() -> negotiate(socket));
        } else {
          RdmaTransport transport = rdmaListener.accept();
          register(new MemoryConnection(transport, null, false));
        }
      } catch (IOException exception) {
        if (!closed.get()) {
          Thread.yield();
        }
      }
    }
  }

  private void negotiate(Socket socket) {
    try {
      socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
      String regionName = "maps-" + UUID.randomUUID().toString().replace("-", "");
      SharedMemoryTransport transport = new SharedMemoryTransport(regionName, true, slotSize, slotCount);
      boolean transferred = false;
      try {
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        DataInputStream input = new DataInputStream(socket.getInputStream());
        output.writeUTF(regionName);
        output.writeInt(slotSize);
        output.writeInt(slotCount);
        output.flush();
        if (!input.readBoolean() || closed.get()) {
          return;
        }
        socket.setSoTimeout(0);
        MemoryConnection connection = new MemoryConnection(transport, socket, true);
        transferred = true;
        register(connection);
      } finally {
        if (!transferred) {
          try {
            transport.close();
          } finally {
            try {
              Files.deleteIfExists(transport.path());
            } finally {
              socket.close();
            }
          }
        }
      }
    } catch (IOException | RuntimeException exception) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // Already failing to establish a connection.
      }
    } finally {
      negotiating.remove(socket);
    }
  }

  private void register(MemoryConnection connection) {
    if (closed.get()) {
      try {
        connection.close();
      } catch (IOException ignored) {
        // Shutdown is already in progress.
      }
      return;
    }
    connections.add(connection);
    connection.onClose(() -> {
      connections.remove(connection);
      pending.remove(connection);
    });
    if (!connection.isClosed()) {
      pending.add(connection);
    }
  }

  static void requireLoopback(InetSocketAddress address) {
    if (address == null || address.isUnresolved() || !address.getAddress().isLoopbackAddress()) {
      throw new IllegalArgumentException("Shared-memory rendezvous requires a resolved loopback address");
    }
  }
}
