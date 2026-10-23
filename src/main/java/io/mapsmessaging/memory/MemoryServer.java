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
import io.mapsmessaging.memory.shm.linux.LinuxRendezvous;
import io.mapsmessaging.memory.shm.linux.LinuxMemoryTransport;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** A listener supporting multiple independent incoming transport connections. */
public final class MemoryServer implements AutoCloseable {

  private static final Logger LOGGER = Logger.getLogger(MemoryServer.class.getName());
  private static final int HANDSHAKE_TIMEOUT_MILLIS = 5_000;
  private static final int DEFAULT_BACKLOG = 64;

  private final ServerSocket localListener;
  private final RdmaListener rdmaListener;
  private final LinuxRendezvous linuxListener;
  private final Set<Integer> linuxNegotiating = ConcurrentHashMap.newKeySet();
  private final int slotSize;
  private final int slotCount;
  private final BlockingQueue<MemoryConnection> pending = new LinkedBlockingQueue<>();
  private final Set<MemoryConnection> connections = ConcurrentHashMap.newKeySet();
  private final Set<Socket> negotiating = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Thread acceptThread;
  private final Semaphore negotiationSlots;
  private final int maxConnections;
  private final int maxPendingConnections;
  private final int handshakeTimeoutMillis;
  private final Object lifecycleLock = new Object();
  private volatile MemoryServerState state = MemoryServerState.ACTIVE;

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

  public static MemoryServer listen(MemoryEndpointConfig config) throws IOException {
    return MemoryProviders.provider(config.transport()).listen(config);
  }

  static MemoryServer listenConfigured(MemoryEndpointConfig config) throws IOException {
    if (config.security() != null) {
      throw new IOException("Authenticated transport is not yet implemented");
    }
    if (config.transport() == MemoryTransportType.SHARED_MEMORY) {
      if (config.address() instanceof UnixDomainSocketAddress unixAddress) {
        return new MemoryServer(LinuxRendezvous.listen(unixAddress,
            config.slotSize(), config.slotCount(), DEFAULT_BACKLOG), config);
      }
      if (!(config.address() instanceof InetSocketAddress address)) {
        throw new IllegalArgumentException("Unsupported shared-memory address type");
      }
      requireLoopback(address);
      ServerSocket listener = new ServerSocket();
      listener.bind(address, DEFAULT_BACKLOG);
      return new MemoryServer(listener, null, config.slotSize(), config.slotCount(), config);
    }
    if (!(config.address() instanceof InetSocketAddress address)) {
      throw new IllegalArgumentException("RDMA requires an Internet socket address");
    }
    return new MemoryServer(null, RdmaListener.bind(address), 0, 0, config);
  }

  private MemoryServer(ServerSocket localListener, RdmaListener rdmaListener, int slotSize, int slotCount) {
    this(localListener, rdmaListener, slotSize, slotCount, null);
  }

  private MemoryServer(LinuxRendezvous linuxListener, MemoryEndpointConfig config) {
    this(null, null, linuxListener, config.slotSize(), config.slotCount(), config);
  }

  private MemoryServer(ServerSocket localListener, RdmaListener rdmaListener, int slotSize, int slotCount, MemoryEndpointConfig config) {
    this(localListener, rdmaListener, null, slotSize, slotCount, config);
  }

  private MemoryServer(ServerSocket localListener, RdmaListener rdmaListener,
      LinuxRendezvous linuxListener, int slotSize, int slotCount, MemoryEndpointConfig config) {
    this.localListener = localListener;
    this.rdmaListener = rdmaListener;
    this.linuxListener = linuxListener;
    this.slotSize = slotSize;
    this.slotCount = slotCount;
    maxConnections = config == null ? 0 : config.maxConnections();
    maxPendingConnections = config == null ? 0 : config.maxPendingConnections();
    handshakeTimeoutMillis = config == null ? HANDSHAKE_TIMEOUT_MILLIS : config.handshakeTimeoutMillis();
    negotiationSlots = config == null || config.maxNegotiations() == 0 ? null : new Semaphore(config.maxNegotiations());
    acceptThread = (linuxListener != null ? Thread.ofPlatform() : Thread.ofVirtual())
        .name("memory-server-accept").start(this::acceptLoop);
  }

  public SocketAddress localAddress() {
    if (linuxListener != null) {
      return linuxListener.localAddress();
    }
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

  public MemoryServerState state() {
    return state;
  }

  @Override
  public void close() throws IOException {
    synchronized (lifecycleLock) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      state = MemoryServerState.CLOSING;
    }
    acceptThread.interrupt();
    IOException failure = null;
    try {
      if (localListener != null) {
        localListener.close();
      } else if (linuxListener != null) {
        linuxListener.close();
      } else {
        rdmaListener.close();
      }
    } catch (IOException exception) {
      failure = exception;
    }
    synchronized (lifecycleLock) {
      for (int descriptor : linuxNegotiating) {
        LinuxRendezvous.shutdownPeer(descriptor);
      }
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
    state = failure == null ? MemoryServerState.CLOSED : MemoryServerState.FAILED;
    if (failure != null) {
      throw failure;
    }
  }

  private void acceptLoop() {
    while (!closed.get()) {
      try {
        if (localListener != null) {
          Socket socket = localListener.accept();
          if (!reserveNegotiation(socket)) {
            socket.close();
            continue;
          }
          Thread.ofVirtual().name("memory-region-negotiate").start(() -> negotiate(socket));
        } else if (linuxListener != null) {
          int descriptor = linuxListener.acceptDescriptor();
          if (!reserveLinuxNegotiation(descriptor)) {
            LinuxRendezvous.closePeer(descriptor);
            continue;
          }
          Thread.ofPlatform().name("memfd-negotiate").start(() -> negotiateLinux(descriptor));
        } else {
          RdmaTransport transport = rdmaListener.accept();
          register(new MemoryConnection(transport, null, false));
        }
      } catch (IOException exception) {
        if (!closed.get()) {
          if (linuxListener != null) {
            LOGGER.log(Level.WARNING, "Linux shared-memory listener failed to accept a connection", exception);
          }
          Thread.yield();
        }
      }
    }
  }

  private void negotiate(Socket socket) {
    try {
      socket.setSoTimeout(handshakeTimeoutMillis);
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
      if (negotiationSlots != null) {
        negotiationSlots.release();
      }
    }
  }

  private boolean reserveLinuxNegotiation(int descriptor) {
    if (negotiationSlots != null && !negotiationSlots.tryAcquire()) {
      return false;
    }
    synchronized (lifecycleLock) {
      if (closed.get() || capacityExceeded()) {
        if (negotiationSlots != null) {
          negotiationSlots.release();
        }
        return false;
      }
      linuxNegotiating.add(descriptor);
      return true;
    }
  }

  private void negotiateLinux(int descriptor) {
    try {
      LinuxMemoryTransport transport = linuxListener.completeAccept(descriptor, handshakeTimeoutMillis);
      synchronized (lifecycleLock) {
        linuxNegotiating.remove(descriptor);
      }
      register(new MemoryConnection(transport, null, false));
    } catch (IOException | RuntimeException exception) {
      LOGGER.log(Level.WARNING, "Linux shared-memory peer negotiation failed", exception);
    } finally {
      linuxNegotiating.remove(descriptor);
      if (negotiationSlots != null) {
        negotiationSlots.release();
      }
    }
  }

  private boolean reserveNegotiation(Socket socket) {
    if (negotiationSlots != null && !negotiationSlots.tryAcquire()) {
      return false;
    }
    synchronized (lifecycleLock) {
      if (closed.get() || capacityExceeded()) {
        if (negotiationSlots != null) {
          negotiationSlots.release();
        }
        return false;
      }
      negotiating.add(socket);
      return true;
    }
  }

  private boolean capacityExceeded() {
    return (maxConnections > 0 && connections.size() + negotiating.size() + linuxNegotiating.size() >= maxConnections)
        || (maxPendingConnections > 0 && pending.size() >= maxPendingConnections);
  }

  private void register(MemoryConnection connection) {
    boolean accepted;
    synchronized (lifecycleLock) {
      accepted = !closed.get()
          && (maxConnections == 0 || connections.size() < maxConnections)
          && (maxPendingConnections == 0 || pending.size() < maxPendingConnections);
      if (accepted) {
        connections.add(connection);
        connection.onClose(() -> {
          connections.remove(connection);
          pending.remove(connection);
        });
        if (!connection.isClosed()) {
          pending.add(connection);
        }
      }
    }
    if (!accepted) {
      try {
        connection.close();
      } catch (IOException ignored) {
        // Connection cannot be published; release its owned resources.
      }
    }
  }

  static void requireLoopback(InetSocketAddress address) {
    if (address == null || address.isUnresolved() || !address.getAddress().isLoopbackAddress()) {
      throw new IllegalArgumentException("Shared-memory rendezvous requires a resolved loopback address");
    }
  }
}
