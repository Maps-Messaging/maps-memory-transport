/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.rdma;

import io.mapsmessaging.memory.MemoryTransport;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.net.InetSocketAddress;

public final class RdmaTransport implements MemoryTransport {

  public static final int DEFAULT_IO_BUFFER_SIZE = 64 * 1024;

  private static final int HANDSHAKE_MAGIC = 0x4d4d5244; // MMRD
  private static final int HANDSHAKE_VERSION = 1;
  private static final int HANDSHAKE_SIZE = Integer.BYTES * 2 + Long.BYTES;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final RdmaNative nativeAccess;
  private final int socket;
  private final Arena arena;
  private final MemorySegment sendBuffer;
  private final MemorySegment receiveBuffer;
  private final InetSocketAddress remoteAddress;
  private final long sessionId;
  private final long peerSessionId;

  private volatile IOException terminalFailure;
  private volatile boolean closed;

  public static RdmaTransport connect(String host, int port) throws IOException {
    return connect(new InetSocketAddress(host, port), DEFAULT_IO_BUFFER_SIZE);
  }

  public static RdmaTransport connect(InetSocketAddress address) throws IOException {
    return connect(address, DEFAULT_IO_BUFFER_SIZE);
  }

  public static RdmaTransport connect(InetSocketAddress address, int ioBufferSize) throws IOException {
    validateAddress(address);
    validateBufferSize(ioBufferSize);
    RdmaSupport.requireAvailable();

    RdmaNative nativeAccess = new RdmaNative();
    int socket = -1;
    try {
      socket = nativeAccess.createStreamSocket(resolveAddress(address));
      nativeAccess.connect(socket, address);
      return new RdmaTransport(nativeAccess, socket, nativeAccess.peerAddress(socket), ioBufferSize, true);
    } catch (Throwable throwable) {
      nativeAccess.closeSocket(socket);
      nativeAccess.close();
      if (throwable instanceof IOException ioException) {
        throw ioException;
      }
      if (throwable instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IOException("Unable to connect RDMA transport", throwable);
    }
  }

  static RdmaTransport accepted(RdmaNative acceptNative, int socket, int ioBufferSize) throws IOException {
    validateBufferSize(ioBufferSize);
    RdmaNative nativeAccess = new RdmaNative();
    try {
      InetSocketAddress remote = nativeAccess.peerAddress(socket);
      return new RdmaTransport(nativeAccess, socket, remote, ioBufferSize, false);
    } catch (Throwable throwable) {
      acceptNative.closeSocket(socket);
      nativeAccess.close();
      if (throwable instanceof IOException ioException) {
        throw ioException;
      }
      if (throwable instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IOException("Unable to accept RDMA transport", throwable);
    }
  }

  private RdmaTransport(
      RdmaNative nativeAccess,
      int socket,
      InetSocketAddress remoteAddress,
      int ioBufferSize,
      boolean client) throws IOException {
    this.nativeAccess = nativeAccess;
    this.socket = socket;
    this.remoteAddress = remoteAddress;
    arena = Arena.ofShared();
    sendBuffer = arena.allocate(ioBufferSize, 8);
    receiveBuffer = arena.allocate(ioBufferSize, 8);
    sessionId = newSessionId();

    try {
      peerSessionId = client ? clientHandshake() : serverHandshake();
    } catch (Throwable throwable) {
      closeResources();
      if (throwable instanceof IOException ioException) {
        throw ioException;
      }
      throw new IOException("RDMA transport handshake failed", throwable);
    }
  }

  @Override
  public synchronized int write(ByteBuffer source) throws IOException {
    ensureOpen();
    if (!source.hasRemaining()) {
      return 0;
    }

    int length = Math.min(source.remaining(), Math.toIntExact(sendBuffer.byteSize()));
    ByteBuffer duplicate = source.duplicate();
    duplicate.limit(duplicate.position() + length);
    ByteBuffer nativeBuffer = sendBuffer.asSlice(0, length).asByteBuffer();
    nativeBuffer.put(duplicate);

    long written;
    try {
      written = nativeAccess.send(socket, sendBuffer, length, true);
    } catch (IOException exception) {
      terminalFailure = exception;
      throw exception;
    }
    if (written > 0) {
      source.position(source.position() + Math.toIntExact(written));
    }
    return Math.toIntExact(written);
  }

  @Override
  public synchronized int read(ByteBuffer destination) throws IOException {
    ensureOpen();
    if (!destination.hasRemaining()) {
      return 0;
    }

    int length = Math.min(destination.remaining(), Math.toIntExact(receiveBuffer.byteSize()));
    long read;
    try {
      read = nativeAccess.receive(socket, receiveBuffer, length, true);
    } catch (IOException exception) {
      terminalFailure = exception;
      throw exception;
    }

    if (read == 0) {
      if (hasTerminalPollEvent()) {
        IOException exception = new IOException("RDMA peer disconnected: " + remoteAddress);
        terminalFailure = exception;
        throw exception;
      }
      return 0;
    }

    destination.put(receiveBuffer.asSlice(0, read).asByteBuffer());
    return Math.toIntExact(read);
  }

  @Override
  public boolean hasData() {
    if (closed) {
      return false;
    }
    if (terminalFailure != null) {
      return true;
    }
    try {
      int events = nativeAccess.poll(socket, RdmaNative.POLLIN | RdmaNative.POLLERR | RdmaNative.POLLHUP | RdmaNative.POLLNVAL);
      if ((events & (RdmaNative.POLLERR | RdmaNative.POLLHUP | RdmaNative.POLLNVAL)) != 0) {
        terminalFailure = new IOException("RDMA transport is no longer connected: " + remoteAddress);
        return true;
      }
      return (events & RdmaNative.POLLIN) != 0;
    } catch (IOException exception) {
      terminalFailure = exception;
      return true;
    }
  }

  @Override
  public boolean canWrite() {
    if (closed || terminalFailure != null) {
      return false;
    }
    try {
      int events = nativeAccess.poll(socket, RdmaNative.POLLOUT | RdmaNative.POLLERR | RdmaNative.POLLHUP | RdmaNative.POLLNVAL);
      if ((events & (RdmaNative.POLLERR | RdmaNative.POLLHUP | RdmaNative.POLLNVAL)) != 0) {
        terminalFailure = new IOException("RDMA transport is no longer connected: " + remoteAddress);
        return false;
      }
      return (events & RdmaNative.POLLOUT) != 0;
    } catch (IOException exception) {
      terminalFailure = exception;
      return false;
    }
  }

  @Override
  public String remoteAddress() {
    return "rdma://" + remoteAddress.getHostString() + ":" + remoteAddress.getPort();
  }

  public InetSocketAddress socketAddress() {
    return remoteAddress;
  }

  public long sessionId() {
    return sessionId;
  }

  public long peerSessionId() {
    return peerSessionId;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    closeResources();
  }

  private long clientHandshake() throws IOException {
    sendHandshake(sessionId);
    return receiveHandshake();
  }

  private long serverHandshake() throws IOException {
    long peer = receiveHandshake();
    sendHandshake(sessionId);
    return peer;
  }

  private void sendHandshake(long session) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(HANDSHAKE_SIZE).order(ByteOrder.BIG_ENDIAN);
    buffer.putInt(HANDSHAKE_MAGIC);
    buffer.putInt(HANDSHAKE_VERSION);
    buffer.putLong(session);
    buffer.flip();

    ByteBuffer nativeBuffer = sendBuffer.asSlice(0, HANDSHAKE_SIZE).asByteBuffer();
    nativeBuffer.put(buffer);
    long offset = 0;
    while (offset < HANDSHAKE_SIZE) {
      long written = nativeAccess.send(socket, sendBuffer.asSlice(offset), HANDSHAKE_SIZE - offset, false);
      if (written <= 0) {
        throw new IOException("RDMA handshake send made no progress");
      }
      offset += written;
    }
  }

  private long receiveHandshake() throws IOException {
    long offset = 0;
    while (offset < HANDSHAKE_SIZE) {
      long read = nativeAccess.receive(socket, receiveBuffer.asSlice(offset), HANDSHAKE_SIZE - offset, false);
      if (read <= 0) {
        throw new IOException("RDMA peer closed during handshake");
      }
      offset += read;
    }

    ByteBuffer buffer = receiveBuffer.asSlice(0, HANDSHAKE_SIZE).asByteBuffer().order(ByteOrder.BIG_ENDIAN);
    int magic = buffer.getInt();
    int version = buffer.getInt();
    long peerSession = buffer.getLong();
    if (magic != HANDSHAKE_MAGIC) {
      throw new IOException("RDMA peer did not present a Maps memory transport handshake");
    }
    if (version != HANDSHAKE_VERSION) {
      throw new IOException("Unsupported RDMA transport version " + version);
    }
    if (peerSession == 0) {
      throw new IOException("RDMA peer supplied an invalid session identifier");
    }
    return peerSession;
  }

  private boolean hasTerminalPollEvent() {
    try {
      int events = nativeAccess.poll(socket, RdmaNative.POLLERR | RdmaNative.POLLHUP | RdmaNative.POLLNVAL);
      return (events & (RdmaNative.POLLERR | RdmaNative.POLLHUP | RdmaNative.POLLNVAL)) != 0;
    } catch (IOException exception) {
      terminalFailure = exception;
      return true;
    }
  }

  private void ensureOpen() throws IOException {
    if (closed) {
      throw new IOException("RDMA transport is closed");
    }
    if (terminalFailure != null) {
      throw terminalFailure;
    }
  }

  private void closeResources() {
    nativeAccess.closeSocket(socket);
    try {
      arena.close();
    } finally {
      nativeAccess.close();
    }
  }

  private static java.net.InetAddress resolveAddress(InetSocketAddress address) throws IOException {
    if (address.getAddress() != null) {
      return address.getAddress();
    }
    try {
      return java.net.InetAddress.getByName(address.getHostString());
    } catch (java.net.UnknownHostException exception) {
      throw new IOException("Unable to resolve RDMA host " + address.getHostString(), exception);
    }
  }

  private static void validateAddress(InetSocketAddress address) {
    if (address == null) {
      throw new IllegalArgumentException("address must not be null");
    }
    if (address.getPort() <= 0 || address.getPort() > 65535) {
      throw new IllegalArgumentException("port must be between 1 and 65535");
    }
  }

  private static void validateBufferSize(int ioBufferSize) {
    if (ioBufferSize < 1024) {
      throw new IllegalArgumentException("ioBufferSize must be at least 1024 bytes");
    }
  }

  private static long newSessionId() {
    long value;
    do {
      value = RANDOM.nextLong();
    } while (value == 0);
    return value;
  }
}
