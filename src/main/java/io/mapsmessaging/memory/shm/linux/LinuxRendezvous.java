/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm.linux;

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
 * Internal Linux native local connection establishment. One Unix domain socket
 * exchanges two memfd descriptors per connection, with explicit ready/ack.
 * The socket remains open for heartbeat and failure detection.
 */
public final class LinuxRendezvous implements AutoCloseable {

  private static final int PROTOCOL_VERSION = 1;
  private final Path path;
  private final int serverDescriptor;
  private final int slotSize;
  private final int slotCount;
  private final AtomicBoolean closed = new AtomicBoolean();

  public static LinuxRendezvous listen(UnixDomainSocketAddress address, int slotSize, int slotCount, int backlog)
      throws IOException {
    requireLinuxBackend();
    long regionSize = LinuxMemoryTransport.regionSize(slotSize, slotCount);
    if (regionSize > Integer.MAX_VALUE || backlog < 1) {
      throw new IllegalArgumentException("Unsupported region size or backlog");
    }
    Path path = address.getPath().toAbsolutePath().normalize();
    verifyParent(path);
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Unix memory listener path already exists: " + path);
    }
    int descriptor = LinuxNative.openSocket();
    boolean bound = false;
    try {
      LinuxNative.bind(descriptor, path.toString().getBytes(StandardCharsets.UTF_8));
      bound = true;
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
      LinuxNative.listen(descriptor, backlog);
      return new LinuxRendezvous(path, descriptor, slotSize, slotCount);
    } catch (IOException | RuntimeException exception) {
      LinuxNative.close(descriptor);
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

  private LinuxRendezvous(Path path, int descriptor, int slotSize, int slotCount) {
    this.path = path;
    serverDescriptor = descriptor;
    this.slotSize = slotSize;
    this.slotCount = slotCount;
  }

  public int acceptDescriptor() throws IOException {
    if (closed.get()) {
      throw new IOException("Linux rendezvous listener is closed");
    }
    return LinuxNative.accept(serverDescriptor);
  }

  public LinuxMemoryTransport accept() throws IOException {
    return completeAccept(acceptDescriptor(), 5_000);
  }

  public LinuxMemoryTransport completeAccept(int peer, int timeoutMillis) throws IOException {
    boolean transportOwnsSocket = false;
    try {
      LinuxNative.setSocketTimeout(peer, timeoutMillis);
      verifyPeer(peer);
      LinuxNative.sendByte(peer, PROTOCOL_VERSION);
      sendInt(peer, slotSize);
      sendInt(peer, slotCount);
      long size = LinuxMemoryTransport.regionSize(slotSize, slotCount);
      int serverToClient = LinuxNative.createMemoryFile("maps-send", size);
      int clientToServer = -1;
      try {
        clientToServer = LinuxNative.createMemoryFile("maps-receive", size);
        LinuxNative.sendDescriptors(peer, serverToClient, clientToServer);
        LinuxMemoryTransport transport = LinuxMemoryTransport.attach(peer, serverToClient, clientToServer,
            slotSize, slotCount, true, "shm-linux:" + path);
        transportOwnsSocket = true;
        serverToClient = -1;
        clientToServer = -1;
        try {
          if (LinuxNative.receiveByte(peer) != 0x51) {
            throw new IOException("Linux shared-memory client did not confirm attachment");
          }
          LinuxNative.sendByte(peer, 0x52);
          LinuxNative.setSocketTimeout(peer, 6_000);
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
        LinuxNative.close(serverToClient);
        LinuxNative.close(clientToServer);
      }
    } catch (IOException | RuntimeException exception) {
      if (!transportOwnsSocket) {
        LinuxNative.shutdown(peer);
        LinuxNative.close(peer);
      }
      throw exception;
    }
  }

  public static LinuxMemoryTransport connect(UnixDomainSocketAddress address) throws IOException {
    return connect(address, 5_000);
  }

  public static LinuxMemoryTransport connect(UnixDomainSocketAddress address, int timeoutMillis) throws IOException {
    requireLinuxBackend();
    Path path = address.getPath().toAbsolutePath().normalize();
    int socket = LinuxNative.openSocket();
    boolean transportOwnsSocket = false;
    try {
      LinuxNative.connect(socket, path.toString().getBytes(StandardCharsets.UTF_8));
      LinuxNative.setSocketTimeout(socket, timeoutMillis);
      verifyPeer(socket);
      int version = LinuxNative.receiveByte(socket);
      if (version != PROTOCOL_VERSION) {
        throw new IOException("Unsupported Linux shared-memory version " + version);
      }
      int slotSize = receiveInt(socket);
      int slotCount = receiveInt(socket);
      LinuxMemoryTransport.regionSize(slotSize, slotCount);
      int[] descriptors = LinuxNative.receiveDescriptors(socket);
      try {
        long expectedSize = LinuxMemoryTransport.regionSize(slotSize, slotCount);
        LinuxNative.validateRegion(descriptors[0], expectedSize);
        LinuxNative.validateRegion(descriptors[1], expectedSize);
      } catch (IOException exception) {
        LinuxNative.close(descriptors[0]);
        LinuxNative.close(descriptors[1]);
        throw exception;
      }
      LinuxMemoryTransport transport = LinuxMemoryTransport.attach(socket, descriptors[1], descriptors[0],
          slotSize, slotCount, false, "shm-linux:" + path);
      transportOwnsSocket = true;
      try {
        LinuxNative.sendByte(socket, 0x51);
        if (LinuxNative.receiveByte(socket) != 0x52) {
          throw new IOException("Linux shared-memory server did not confirm readiness");
        }
        LinuxNative.setSocketTimeout(socket, 6_000);
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
        LinuxNative.shutdown(socket);
        LinuxNative.close(socket);
      }
      throw exception;
    }
  }

  public static void shutdownPeer(int descriptor) {
    LinuxNative.shutdown(descriptor);
  }

  public static void closePeer(int descriptor) {
    LinuxNative.shutdown(descriptor);
    LinuxNative.close(descriptor);
  }

  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    LinuxNative.shutdown(serverDescriptor);
    LinuxNative.close(serverDescriptor);
    Files.deleteIfExists(path);
  }

  public UnixDomainSocketAddress localAddress() {
    return UnixDomainSocketAddress.of(path);
  }

  private static void requireLinuxBackend() throws IOException {
    String os = System.getProperty("os.name", "");
    String architecture = System.getProperty("os.arch", "");
    if (!os.equalsIgnoreCase("Linux")
        || !(architecture.equals("amd64") || architecture.equals("x86_64")
            || architecture.equals("aarch64"))) {
      throw new IOException("Linux memfd transport requires Linux x86_64 or aarch64");
    }
  }

  private static void verifyPeer(int socket) throws IOException {
    LinuxNative.PeerCredentials credentials = LinuxNative.peerCredentials(socket);
    if (credentials.pid() <= 0 || credentials.uid() != LinuxNative.effectiveUid()) {
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
      LinuxNative.sendByte(socket, value >>> shift);
    }
  }

  private static int receiveInt(int socket) throws IOException {
    int value = 0;
    for (int index = 0; index < 4; index++) {
      value = (value << 8) | LinuxNative.receiveByte(socket);
    }
    return value;
  }
}
