/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm.macos;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal macOS native local connection establishment. One Unix domain socket
 * exchanges two anonymous POSIX shared-memory descriptors per connection, with explicit ready/ack.
 * The socket remains open for heartbeat and failure detection.
 */
public final class MacRendezvous implements AutoCloseable {

  private static final int PROTOCOL_VERSION = 1;
  private final Path path;
  private final int serverDescriptor;
  private final int slotSize;
  private final int slotCount;
  private final AtomicBoolean closed = new AtomicBoolean();

  public static MacRendezvous listen(UnixDomainSocketAddress address, int slotSize, int slotCount, int backlog)
      throws IOException {
    requireMacBackend();
    long regionSize = MacMemoryTransport.regionSize(slotSize, slotCount);
    if (regionSize > Integer.MAX_VALUE || backlog < 1) {
      throw new IllegalArgumentException("Unsupported region size or backlog");
    }
    Path path = address.getPath().toAbsolutePath().normalize();
    verifyParent(path);
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Unix memory listener path already exists: " + path);
    }
    int descriptor = MacNative.openSocket();
    boolean bound = false;
    try {
      MacNative.bind(descriptor, path.toString().getBytes(StandardCharsets.UTF_8));
      bound = true;
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
      MacNative.listen(descriptor, backlog);
      return new MacRendezvous(path, descriptor, slotSize, slotCount);
    } catch (IOException | RuntimeException exception) {
      MacNative.close(descriptor);
      if (bound) {
        try {
          Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
          exception.addSuppressed(cleanupFailure);
        }
      }
      throw exception;
    }
  }

  private MacRendezvous(Path path, int descriptor, int slotSize, int slotCount) {
    this.path = path;
    serverDescriptor = descriptor;
    this.slotSize = slotSize;
    this.slotCount = slotCount;
  }

  public int acceptDescriptor() throws IOException {
    while (!closed.get()) {
      if (!MacNative.acceptReady(serverDescriptor, 200)) {
        continue;
      }
      int descriptor = MacNative.accept(serverDescriptor);
      if (!closed.get()) {
        return descriptor;
      }
      MacNative.close(descriptor);
    }
    throw new IOException("macOS rendezvous listener is closed");
  }

  public MacMemoryTransport accept() throws IOException {
    return completeAccept(acceptDescriptor(), 5_000);
  }

  public MacMemoryTransport completeAccept(int peer, int timeoutMillis) throws IOException {
    boolean transportOwnsSocket = false;
    try {
      MacNative.setSocketTimeout(peer, timeoutMillis);
      verifyPeer(peer);
      MacNative.sendByte(peer, PROTOCOL_VERSION);
      sendInt(peer, slotSize);
      sendInt(peer, slotCount);
      long size = MacMemoryTransport.regionSize(slotSize, slotCount);
      int serverToClient = MacNative.createMemoryFile("maps-send", size);
      int clientToServer = -1;
      try {
        clientToServer = MacNative.createMemoryFile("maps-receive", size);
        MacNative.sendDescriptors(peer, serverToClient, clientToServer);
        int outgoingDescriptor = serverToClient;
        int incomingDescriptor = clientToServer;
        serverToClient = -1;
        clientToServer = -1;
        MacMemoryTransport transport = MacMemoryTransport.attach(peer, outgoingDescriptor, incomingDescriptor,
            slotSize, slotCount, true, "shm-macos:" + path);
        transportOwnsSocket = true;
        try {
          if (MacNative.receiveByte(peer) != 0x51) {
            throw new IOException("macOS shared-memory client did not confirm attachment");
          }
          MacNative.sendByte(peer, 0x52);
          MacNative.setSocketTimeout(peer, 6_000);
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
        MacNative.close(serverToClient);
        MacNative.close(clientToServer);
      }
    } catch (IOException | RuntimeException exception) {
      if (!transportOwnsSocket) {
        MacNative.shutdown(peer);
        MacNative.close(peer);
      }
      throw exception;
    }
  }

  public static MacMemoryTransport connect(UnixDomainSocketAddress address) throws IOException {
    return connect(address, 5_000);
  }

  public static MacMemoryTransport connect(UnixDomainSocketAddress address, int timeoutMillis) throws IOException {
    requireMacBackend();
    Path path = address.getPath().toAbsolutePath().normalize();
    int socket = MacNative.openSocket();
    boolean transportOwnsSocket = false;
    try {
      MacNative.connect(socket, path.toString().getBytes(StandardCharsets.UTF_8));
      MacNative.setSocketTimeout(socket, timeoutMillis);
      verifyPeer(socket);
      int version = MacNative.receiveByte(socket);
      if (version != PROTOCOL_VERSION) {
        throw new IOException("Unsupported macOS shared-memory version " + version);
      }
      int slotSize = receiveInt(socket);
      int slotCount = receiveInt(socket);
      MacMemoryTransport.regionSize(slotSize, slotCount);
      int[] descriptors = MacNative.receiveDescriptors(socket);
      try {
        long expectedSize = MacMemoryTransport.regionSize(slotSize, slotCount);
        MacNative.validateRegion(descriptors[0], expectedSize);
        MacNative.validateRegion(descriptors[1], expectedSize);
      } catch (IOException exception) {
        MacNative.close(descriptors[0]);
        MacNative.close(descriptors[1]);
        throw exception;
      }
      MacMemoryTransport transport = MacMemoryTransport.attach(socket, descriptors[1], descriptors[0],
          slotSize, slotCount, false, "shm-macos:" + path);
      transportOwnsSocket = true;
      try {
        MacNative.sendByte(socket, 0x51);
        if (MacNative.receiveByte(socket) != 0x52) {
          throw new IOException("macOS shared-memory server did not confirm readiness");
        }
        MacNative.setSocketTimeout(socket, 6_000);
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
    } catch (IOException | RuntimeException exception) {
      if (!transportOwnsSocket) {
        MacNative.shutdown(socket);
        MacNative.close(socket);
      }
      throw exception;
    }
  }

  public static void shutdownPeer(int descriptor) {
    MacNative.shutdown(descriptor);
  }

  public static void closePeer(int descriptor) {
    MacNative.shutdown(descriptor);
    MacNative.close(descriptor);
  }

  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    MacNative.shutdown(serverDescriptor);
    MacNative.close(serverDescriptor);
    Files.deleteIfExists(path);
  }

  public UnixDomainSocketAddress localAddress() {
    return UnixDomainSocketAddress.of(path);
  }

  private static void requireMacBackend() throws IOException {
    String os = System.getProperty("os.name", "");
    String architecture = System.getProperty("os.arch", "");
    if (!os.equalsIgnoreCase("Mac OS X")
        || !(architecture.equals("amd64") || architecture.equals("x86_64")
            || architecture.equals("aarch64"))) {
      throw new IOException("macOS shared-memory transport requires macOS x86_64 or aarch64");
    }
  }

  private static void verifyPeer(int socket) throws IOException {
    MacNative.PeerCredentials credentials = MacNative.peerCredentials(socket);
    if (credentials.uid() != MacNative.effectiveUid()) {
      throw new IOException("Unix shared-memory peer credentials rejected");
    }
  }

  private static void verifyParent(Path path) throws IOException {
    Path parent = path.getParent();
    if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
      throw new IOException("Shared-memory control socket requires an existing private directory");
    }
    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS);
    if (permissions.contains(PosixFilePermission.GROUP_WRITE)
        || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
        || permissions.contains(PosixFilePermission.OTHERS_WRITE)
        || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
      throw new IOException("Shared-memory control directory must be private");
    }
  }

  private static void sendInt(int socket, int value) throws IOException {
    for (int shift = 24; shift >= 0; shift -= 8) {
      MacNative.sendByte(socket, value >>> shift);
    }
  }

  private static int receiveInt(int socket) throws IOException {
    int value = 0;
    for (int index = 0; index < 4; index++) {
      value = (value << 8) | MacNative.receiveByte(socket);
    }
    return value;
  }
}
