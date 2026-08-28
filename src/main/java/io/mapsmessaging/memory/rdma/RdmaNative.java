/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.rdma;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteOrder;

final class RdmaNative implements AutoCloseable {

  static final int POLLIN = 0x001;
  static final int POLLOUT = 0x004;
  static final int POLLERR = 0x008;
  static final int POLLHUP = 0x010;
  static final int POLLNVAL = 0x020;

  private static final int AF_INET = 2;
  private static final int AF_INET6 = 10;
  private static final int SOCK_STREAM = 1;
  private static final int MSG_DONTWAIT = 0x40;
  private static final int EAGAIN = 11;
  private static final int EINTR = 4;
  private static final int SOCKADDR_STORAGE_SIZE = 128;

  private static final Linker LINKER = Linker.nativeLinker();
  private static final Linker.Option CAPTURE_ERRNO = Linker.Option.captureCallState("errno");
  private static final StructLayout CAPTURED_STATE_LAYOUT = Linker.Option.captureStateLayout();
  private static final VarHandle ERRNO_HANDLE =
      CAPTURED_STATE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));
  private static final ValueLayout.OfShort NETWORK_SHORT = ValueLayout.JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN);

  private final Arena arena;
  private final SymbolLookup verbs;
  private final SymbolLookup rdmaCm;
  private final ThreadLocal<MemorySegment> callState;

  private final MethodHandle rsocket;
  private final MethodHandle rbind;
  private final MethodHandle rlisten;
  private final MethodHandle raccept;
  private final MethodHandle rconnect;
  private final MethodHandle rrecv;
  private final MethodHandle rsend;
  private final MethodHandle rpoll;
  private final MethodHandle rgetpeername;
  private final MethodHandle rgetsockname;
  private final MethodHandle rclose;

  RdmaNative() throws IOException {
    arena = Arena.ofShared();
    try {
      verbs = loadLibrary(arena, "ibverbs", "libibverbs.so.1");
      rdmaCm = loadLibrary(arena, "rdmacm", "librdmacm.so.1");
      requireSymbol(verbs, "ibv_get_device_list");
      requireSymbol(verbs, "ibv_free_device_list");

      rsocket = intHandle("rsocket", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
      rbind = intHandle("rbind", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
      rlisten = intHandle("rlisten", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
      raccept = intHandle("raccept", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
      rconnect = intHandle("rconnect", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
      rrecv = longHandle("rrecv", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);
      rsend = longHandle("rsend", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);
      rpoll = intHandle("rpoll", ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);
      rgetpeername = intHandle("rgetpeername", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
      rgetsockname = intHandle("rgetsockname", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
      rclose = intHandle("rclose", ValueLayout.JAVA_INT);
      callState = ThreadLocal.withInitial(() -> arena.allocate(CAPTURED_STATE_LAYOUT));
    } catch (RuntimeException | IOException exception) {
      arena.close();
      if (exception instanceof IOException ioException) {
        throw ioException;
      }
      throw new IOException("Unable to load rdma-core native libraries", exception);
    }
  }

  int deviceCount() throws IOException {
    MethodHandle getDeviceList =
        LINKER.downcallHandle(
            requireSymbol(verbs, "ibv_get_device_list"),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    MethodHandle freeDeviceList =
        LINKER.downcallHandle(
            requireSymbol(verbs, "ibv_free_device_list"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    try (Arena probeArena = Arena.ofConfined()) {
      MemorySegment count = probeArena.allocate(ValueLayout.JAVA_INT);
      MemorySegment list;
      try {
        list = (MemorySegment) getDeviceList.invokeExact(count);
      } catch (Throwable throwable) {
        throw new IOException("Unable to enumerate RDMA devices", throwable);
      }

      if (list.address() == 0) {
        throw new IOException("ibv_get_device_list returned null");
      }

      try {
        return Math.max(0, count.get(ValueLayout.JAVA_INT, 0));
      } finally {
        try {
          freeDeviceList.invokeExact(list);
        } catch (Throwable throwable) {
          throw new IOException("Unable to release RDMA device list", throwable);
        }
      }
    }
  }

  int createStreamSocket(InetAddress address) throws IOException {
    int domain = address instanceof Inet6Address ? AF_INET6 : AF_INET;
    int fd = invokeInt(rsocket, domain, SOCK_STREAM, 0);
    if (fd < 0) {
      throw nativeFailure("rsocket");
    }
    return fd;
  }

  void bind(int fd, InetSocketAddress address) throws IOException {
    try (Arena localArena = Arena.ofConfined()) {
      SockAddr sockAddr = encodeAddress(address, localArena);
      if (invokeInt(rbind, fd, sockAddr.segment(), sockAddr.length()) < 0) {
        throw nativeFailure("rbind");
      }
    }
  }

  void listen(int fd, int backlog) throws IOException {
    if (invokeInt(rlisten, fd, backlog) < 0) {
      throw nativeFailure("rlisten");
    }
  }

  int accept(int fd) throws IOException {
    try (Arena localArena = Arena.ofConfined()) {
      MemorySegment address = localArena.allocate(SOCKADDR_STORAGE_SIZE, 8);
      MemorySegment length = localArena.allocate(ValueLayout.JAVA_INT);
      length.set(ValueLayout.JAVA_INT, 0, SOCKADDR_STORAGE_SIZE);
      int accepted = invokeInt(raccept, fd, address, length);
      if (accepted < 0) {
        throw nativeFailure("raccept");
      }
      return accepted;
    }
  }

  void connect(int fd, InetSocketAddress address) throws IOException {
    try (Arena localArena = Arena.ofConfined()) {
      SockAddr sockAddr = encodeAddress(address, localArena);
      if (invokeInt(rconnect, fd, sockAddr.segment(), sockAddr.length()) < 0) {
        throw nativeFailure("rconnect");
      }
    }
  }

  long send(int fd, MemorySegment buffer, long length, boolean nonBlocking) throws IOException {
    long result = invokeLong(rsend, fd, buffer, length, nonBlocking ? MSG_DONTWAIT : 0);
    if (result >= 0) {
      return result;
    }
    int errno = errno();
    if (nonBlocking && (errno == EAGAIN || errno == EINTR)) {
      return 0;
    }
    throw nativeFailure("rsend", errno);
  }

  long receive(int fd, MemorySegment buffer, long length, boolean nonBlocking) throws IOException {
    long result = invokeLong(rrecv, fd, buffer, length, nonBlocking ? MSG_DONTWAIT : 0);
    if (result >= 0) {
      return result;
    }
    int errno = errno();
    if (nonBlocking && (errno == EAGAIN || errno == EINTR)) {
      return 0;
    }
    throw nativeFailure("rrecv", errno);
  }

  int poll(int fd, int events) throws IOException {
    try (Arena localArena = Arena.ofConfined()) {
      MemorySegment pollfd = localArena.allocate(8, 4);
      pollfd.set(ValueLayout.JAVA_INT, 0, fd);
      pollfd.set(ValueLayout.JAVA_SHORT, 4, (short) events);
      pollfd.set(ValueLayout.JAVA_SHORT, 6, (short) 0);
      int result = invokeInt(rpoll, pollfd, 1L, 0);
      if (result < 0) {
        int errno = errno();
        if (errno == EINTR) {
          return 0;
        }
        throw nativeFailure("rpoll", errno);
      }
      return result == 0 ? 0 : Short.toUnsignedInt(pollfd.get(ValueLayout.JAVA_SHORT, 6));
    }
  }

  InetSocketAddress peerAddress(int fd) throws IOException {
    return socketAddress(rgetpeername, "rgetpeername", fd);
  }

  InetSocketAddress localAddress(int fd) throws IOException {
    return socketAddress(rgetsockname, "rgetsockname", fd);
  }

  void closeSocket(int fd) {
    if (fd >= 0) {
      try {
        invokeInt(rclose, fd);
      } catch (IOException ignored) {
        // Best effort close.
      }
    }
  }

  @Override
  public void close() {
    arena.close();
  }

  private InetSocketAddress socketAddress(MethodHandle handle, String operation, int fd) throws IOException {
    try (Arena localArena = Arena.ofConfined()) {
      MemorySegment address = localArena.allocate(SOCKADDR_STORAGE_SIZE, 8);
      MemorySegment length = localArena.allocate(ValueLayout.JAVA_INT);
      length.set(ValueLayout.JAVA_INT, 0, SOCKADDR_STORAGE_SIZE);
      if (invokeInt(handle, fd, address, length) < 0) {
        throw nativeFailure(operation);
      }
      return decodeAddress(address);
    }
  }

  private MethodHandle intHandle(String symbol, MemoryLayout... arguments) throws IOException {
    return LINKER.downcallHandle(
        requireSymbol(rdmaCm, symbol),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, arguments),
        CAPTURE_ERRNO);
  }

  private MethodHandle longHandle(String symbol, MemoryLayout... arguments) throws IOException {
    return LINKER.downcallHandle(
        requireSymbol(rdmaCm, symbol),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, arguments),
        CAPTURE_ERRNO);
  }

  private int invokeInt(MethodHandle handle, Object... arguments) throws IOException {
    MemorySegment state = callState.get();
    try {
      Object[] invocation = new Object[arguments.length + 1];
      invocation[0] = state;
      System.arraycopy(arguments, 0, invocation, 1, arguments.length);
      return (int) handle.invokeWithArguments(invocation);
    } catch (Throwable throwable) {
      throw new IOException("RDMA native invocation failed", throwable);
    }
  }

  private long invokeLong(MethodHandle handle, Object... arguments) throws IOException {
    MemorySegment state = callState.get();
    try {
      Object[] invocation = new Object[arguments.length + 1];
      invocation[0] = state;
      System.arraycopy(arguments, 0, invocation, 1, arguments.length);
      return (long) handle.invokeWithArguments(invocation);
    } catch (Throwable throwable) {
      throw new IOException("RDMA native invocation failed", throwable);
    }
  }

  private int errno() {
    return (int) ERRNO_HANDLE.get(callState.get(), 0L);
  }

  private IOException nativeFailure(String operation) {
    return nativeFailure(operation, errno());
  }

  private static IOException nativeFailure(String operation, int errno) {
    return new IOException(operation + " failed with errno=" + errno);
  }

  private static SockAddr encodeAddress(InetSocketAddress socketAddress, Arena arena) throws IOException {
    InetAddress address = socketAddress.getAddress();
    if (address == null) {
      try {
        address = InetAddress.getByName(socketAddress.getHostString());
      } catch (UnknownHostException exception) {
        throw new IOException("Unable to resolve RDMA address " + socketAddress.getHostString(), exception);
      }
    }

    if (address instanceof Inet4Address) {
      MemorySegment segment = arena.allocate(16, 4);
      segment.set(ValueLayout.JAVA_SHORT, 0, (short) AF_INET);
      segment.set(NETWORK_SHORT, 2, (short) socketAddress.getPort());
      copyBytes(address.getAddress(), segment, 4);
      return new SockAddr(segment, 16);
    }

    if (address instanceof Inet6Address inet6Address) {
      MemorySegment segment = arena.allocate(28, 4);
      segment.set(ValueLayout.JAVA_SHORT, 0, (short) AF_INET6);
      segment.set(NETWORK_SHORT, 2, (short) socketAddress.getPort());
      segment.set(ValueLayout.JAVA_INT, 4, 0);
      copyBytes(address.getAddress(), segment, 8);
      segment.set(ValueLayout.JAVA_INT, 24, inet6Address.getScopeId());
      return new SockAddr(segment, 28);
    }

    throw new IOException("Unsupported RDMA address family: " + address);
  }

  private static InetSocketAddress decodeAddress(MemorySegment segment) throws IOException {
    int family = Short.toUnsignedInt(segment.get(ValueLayout.JAVA_SHORT, 0));
    int port = Short.toUnsignedInt(segment.get(NETWORK_SHORT, 2));
    try {
      if (family == AF_INET) {
        byte[] bytes = readBytes(segment, 4, 4);
        return new InetSocketAddress(InetAddress.getByAddress(bytes), port);
      }
      if (family == AF_INET6) {
        byte[] bytes = readBytes(segment, 8, 16);
        int scopeId = segment.get(ValueLayout.JAVA_INT, 24);
        return new InetSocketAddress(Inet6Address.getByAddress(null, bytes, scopeId), port);
      }
      throw new IOException("Unsupported RDMA peer address family " + family);
    } catch (UnknownHostException exception) {
      throw new IOException("Unable to decode RDMA peer address", exception);
    }
  }

  private static void copyBytes(byte[] source, MemorySegment destination, long offset) {
    for (int i = 0; i < source.length; i++) {
      destination.set(ValueLayout.JAVA_BYTE, offset + i, source[i]);
    }
  }

  private static byte[] readBytes(MemorySegment source, long offset, int length) {
    byte[] result = new byte[length];
    for (int i = 0; i < length; i++) {
      result[i] = source.get(ValueLayout.JAVA_BYTE, offset + i);
    }
    return result;
  }

  private static SymbolLookup loadLibrary(Arena arena, String... names) throws IOException {
    RuntimeException lastFailure = null;
    for (String name : names) {
      try {
        return SymbolLookup.libraryLookup(name, arena);
      } catch (RuntimeException exception) {
        lastFailure = exception;
      }
    }
    throw new IOException("Unable to load native library: " + String.join(" or ", names), lastFailure);
  }

  private static MemorySegment requireSymbol(SymbolLookup lookup, String symbol) throws IOException {
    return lookup.find(symbol).orElseThrow(() -> new IOException("Required RDMA symbol not found: " + symbol));
  }

  private record SockAddr(MemorySegment segment, int length) {}
}
