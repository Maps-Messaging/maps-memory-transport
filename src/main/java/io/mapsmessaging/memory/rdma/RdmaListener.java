/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.rdma;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

public final class RdmaListener implements AutoCloseable {

  public static final int DEFAULT_BACKLOG = 64;

  private final RdmaNative nativeAccess;
  private final int socket;
  private final int ioBufferSize;
  private final InetSocketAddress localAddress;
  private volatile boolean closed;

  public static RdmaListener bind(String host, int port) throws IOException {
    return bind(new InetSocketAddress(host, port), DEFAULT_BACKLOG, RdmaTransport.DEFAULT_IO_BUFFER_SIZE);
  }

  public static RdmaListener bind(InetSocketAddress address) throws IOException {
    return bind(address, DEFAULT_BACKLOG, RdmaTransport.DEFAULT_IO_BUFFER_SIZE);
  }

  public static RdmaListener bind(InetSocketAddress address, int backlog, int ioBufferSize) throws IOException {
    if (address == null) {
      throw new IllegalArgumentException("address must not be null");
    }
    if (address.getPort() < 0 || address.getPort() > 65535) {
      throw new IllegalArgumentException("port must be between 0 and 65535");
    }
    if (backlog < 1) {
      throw new IllegalArgumentException("backlog must be at least 1");
    }
    if (ioBufferSize < 1024) {
      throw new IllegalArgumentException("ioBufferSize must be at least 1024 bytes");
    }

    RdmaSupport.requireAvailable();
    RdmaNative nativeAccess = new RdmaNative();
    int socket = -1;
    try {
      InetSocketAddress resolved = resolve(address);
      socket = nativeAccess.createStreamSocket(resolved.getAddress());
      nativeAccess.bind(socket, resolved);
      nativeAccess.listen(socket, backlog);
      InetSocketAddress localAddress = nativeAccess.localAddress(socket);
      return new RdmaListener(nativeAccess, socket, ioBufferSize, localAddress);
    } catch (Throwable throwable) {
      nativeAccess.closeSocket(socket);
      nativeAccess.close();
      if (throwable instanceof IOException ioException) {
        throw ioException;
      }
      if (throwable instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IOException("Unable to bind RDMA listener", throwable);
    }
  }

  private RdmaListener(RdmaNative nativeAccess, int socket, int ioBufferSize, InetSocketAddress localAddress) {
    this.nativeAccess = nativeAccess;
    this.socket = socket;
    this.ioBufferSize = ioBufferSize;
    this.localAddress = localAddress;
  }

  public RdmaTransport accept() throws IOException {
    if (closed) {
      throw new IOException("RDMA listener is closed");
    }
    int accepted = nativeAccess.accept(socket);
    return RdmaTransport.accepted(nativeAccess, accepted, ioBufferSize);
  }

  public InetSocketAddress localAddress() {
    return localAddress;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    nativeAccess.closeSocket(socket);
    nativeAccess.close();
  }

  private static InetSocketAddress resolve(InetSocketAddress address) throws IOException {
    if (address.getAddress() != null) {
      return address;
    }
    try {
      InetAddress resolved = InetAddress.getByName(address.getHostString());
      return new InetSocketAddress(resolved, address.getPort());
    } catch (UnknownHostException exception) {
      throw new IOException("Unable to resolve RDMA bind address " + address.getHostString(), exception);
    }
  }
}
