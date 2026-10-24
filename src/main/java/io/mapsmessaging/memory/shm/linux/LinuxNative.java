/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm.linux;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;

/**
 * Linux-only native bindings. The ancillary message and sockaddr structures are
 * defined for the Linux 64-bit ABIs supported by JDK 25 (x86_64 and aarch64).
 * Every native descriptor remains caller-owned until it is explicitly transferred.
 */
final class LinuxNative {

  static final int AF_UNIX = 1;
  static final int SOCK_STREAM = 1;
  static final int SOCK_CLOEXEC = 0x80000;
  static final int SOL_SOCKET = 1;
  static final int SO_PEERCRED = 17;
  static final int SO_RCVTIMEO = 20;
  static final int SO_SNDTIMEO = 21;
  static final int SCM_RIGHTS = 1;
  static final int MFD_CLOEXEC = 0x0001;
  static final int MFD_ALLOW_SEALING = 0x0002;
  static final int F_ADD_SEALS = 1033;
  static final int F_SEAL_SEAL = 0x0001;
  static final int F_SEAL_SHRINK = 0x0002;
  static final int F_SEAL_GROW = 0x0004;
  static final int PROT_READ = 1;
  static final int PROT_WRITE = 2;
  static final int MAP_SHARED = 1;
  static final int MSG_CMSG_CLOEXEC = 0x40000000;
  static final int EINTR = 4;
  static final int EAGAIN = 11;

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIBC = LINKER.defaultLookup();
  private static final Linker.Option ERRNO = Linker.Option.captureCallState("errno");
  private static final ThreadLocal<Integer> LAST_ERRNO = ThreadLocal.withInitial(() -> 0);
  private static final java.lang.invoke.VarHandle ERRNO_HANDLE =
      Linker.Option.captureStateLayout().varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("errno"));

  private static final MethodHandle MEMFD_CREATE = nativeMethod("memfd_create", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
  private static final MethodHandle FTRUNCATE = nativeMethod("ftruncate", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG);
  private static final MethodHandle FCNTL = nativeVariadicMethod("fcntl", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
  private static final MethodHandle MMAP = nativeMethod("mmap", ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG);
  private static final MethodHandle MUNMAP = nativeMethod("munmap", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
  private static final MethodHandle CLOSE = nativeMethod("close", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
  private static final MethodHandle SOCKET = nativeMethod("socket", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
  private static final MethodHandle BIND = nativeMethod("bind", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
  private static final MethodHandle LISTEN = nativeMethod("listen", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
  private static final MethodHandle ACCEPT = nativeMethod("accept4", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
  private static final MethodHandle CONNECT = nativeMethod("connect", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
  private static final MethodHandle SETSOCKOPT = nativeMethod("setsockopt", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
  private static final MethodHandle LSEEK = nativeMethod("lseek", ValueLayout.JAVA_LONG,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);
  private static final MethodHandle GETSOCKOPT = nativeMethod("getsockopt", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS);
  private static final MethodHandle SENDMSG = nativeMethod("sendmsg", ValueLayout.JAVA_LONG,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
  private static final MethodHandle RECVMSG = nativeMethod("recvmsg", ValueLayout.JAVA_LONG,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
  private static final MethodHandle SEND = nativeMethod("send", ValueLayout.JAVA_LONG,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);
  private static final MethodHandle RECV = nativeMethod("recv", ValueLayout.JAVA_LONG,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);

  private LinuxNative() {}

  static boolean supported() {
    String os = System.getProperty("os.name", "");
    String architecture = System.getProperty("os.arch", "");
    return os.equalsIgnoreCase("Linux")
        && (architecture.equals("amd64") || architecture.equals("x86_64")
            || architecture.equals("aarch64"));
  }

  static int createMemoryFile(String name, long size) throws IOException {
    requireLinux();
    if (size <= 0) {
      throw new IllegalArgumentException("Memory file size must be positive");
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nameSegment = arena.allocateFrom(name);
      int descriptor = integer(MEMFD_CREATE, nameSegment, MFD_CLOEXEC | MFD_ALLOW_SEALING);
      if (descriptor < 0) {
        throw failure("memfd_create");
      }
      try {
        if (integer(FTRUNCATE, descriptor, size) != 0) {
          throw failure("ftruncate");
        }
        int seals = F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_SEAL;
        if (integer(FCNTL, descriptor, F_ADD_SEALS, seals) < 0) {
          throw failure("fcntl(F_ADD_SEALS)");
        }
        return descriptor;
      } catch (IOException | RuntimeException exception) {
        close(descriptor);
        throw exception;
      }
    }
  }

  static MemorySegment map(int descriptor, long size, Arena lifetime) throws IOException {
    requireLinux();
    MemorySegment segment = pointer(MMAP, MemorySegment.NULL, size,
        PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0L);
    if (segment.address() == -1L) {
      throw failure("mmap");
    }
    return segment.reinterpret(size, lifetime, cleanupSegment -> unmap(cleanupSegment, size));
  }

  private static void unmap(MemorySegment segment, long size) {
    try {
      integer(MUNMAP, segment, size);
    } catch (IOException exception) {
      throw new IllegalStateException("munmap failed", exception);
    }
  }

  static void close(int descriptor) {
    if (descriptor >= 0) {
      try {
        integer(CLOSE, descriptor);
      } catch (IOException ignored) {
        // Best effort release; callers must not reuse this descriptor.
      }
    }
  }

  private static final MethodHandle GETEUID = nativeMethod("geteuid", ValueLayout.JAVA_INT);
  private static final MethodHandle SHUTDOWN = nativeMethod("shutdown", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

  static int effectiveUid() throws IOException {
    requireLinux();
    return integer(GETEUID);
  }

  static void shutdown(int descriptor) {
    try {
      integer(SHUTDOWN, descriptor, 2);
    } catch (IOException ignored) {
      // A disconnected control socket may already have been shut down.
    }
  }

  static int openSocket() throws IOException {
    requireLinux();
    int descriptor = integer(SOCKET, AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (descriptor < 0) {
      throw failure("socket");
    }
    return descriptor;
  }

  static void bind(int descriptor, byte[] pathname) throws IOException {
    socketAddressOperation(BIND, descriptor, pathname, "bind");
  }

  static void connect(int descriptor, byte[] pathname) throws IOException {
    socketAddressOperation(CONNECT, descriptor, pathname, "connect");
  }

  private static void socketAddressOperation(MethodHandle method, int descriptor, byte[] pathname, String operation)
      throws IOException {
    if (pathname.length == 0 || pathname.length > 107) {
      throw new IOException("Unix socket path must be 1 to 107 UTF-8 bytes");
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment address = arena.allocate(110, 2);
      address.fill((byte) 0);
      address.set(ValueLayout.JAVA_SHORT.withOrder(ByteOrder.nativeOrder()), 0, (short) AF_UNIX);
      MemorySegment.copy(pathname, 0, address, ValueLayout.JAVA_BYTE, 2, pathname.length);
      if (integer(method, descriptor, address, 2 + pathname.length + 1) != 0) {
        throw failure(operation);
      }
    }
  }

  static void listen(int descriptor, int backlog) throws IOException {
    if (integer(LISTEN, descriptor, backlog) != 0) {
      throw failure("listen");
    }
  }

  static int accept(int descriptor) throws IOException {
    int accepted = integer(ACCEPT, descriptor, MemorySegment.NULL, MemorySegment.NULL, SOCK_CLOEXEC);
    if (accepted < 0) {
      throw failure("accept");
    }
    return accepted;
  }

  static void setSocketTimeout(int descriptor, int milliseconds) throws IOException {
    if (milliseconds < 1) {
      throw new IllegalArgumentException("Socket timeout must be positive");
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment timeout = arena.allocate(16, 8);
      timeout.set(ValueLayout.JAVA_LONG, 0, milliseconds / 1000L);
      timeout.set(ValueLayout.JAVA_LONG, 8, (milliseconds % 1000L) * 1000L);
      if (integer(SETSOCKOPT, descriptor, SOL_SOCKET, SO_RCVTIMEO, timeout, 16) != 0
          || integer(SETSOCKOPT, descriptor, SOL_SOCKET, SO_SNDTIMEO, timeout, 16) != 0) {
        throw failure("setsockopt(SO_RCVTIMEO/SO_SNDTIMEO)");
      }
    }
  }

  static void validateRegion(int descriptor, long size) throws IOException {
    long actualSize = longValue(LSEEK, descriptor, 0L, 2);
    int seals = integer(FCNTL, descriptor, 1034, 0);
    int required = F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_SEAL;
    if (actualSize != size || (seals & required) != required) {
      throw new IOException("Received shared-memory descriptor has invalid size or seals");
    }
  }

  static PeerCredentials peerCredentials(int descriptor) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment credentials = arena.allocate(12, 4);
      MemorySegment length = arena.allocate(ValueLayout.JAVA_INT);
      length.set(ValueLayout.JAVA_INT, 0, 12);
      if (integer(GETSOCKOPT, descriptor, SOL_SOCKET, SO_PEERCRED, credentials, length) != 0
          || length.get(ValueLayout.JAVA_INT, 0) != 12) {
        throw failure("getsockopt(SO_PEERCRED)");
      }
      return new PeerCredentials(
          credentials.get(ValueLayout.JAVA_INT, 0),
          credentials.get(ValueLayout.JAVA_INT, 4),
          credentials.get(ValueLayout.JAVA_INT, 8));
    }
  }

  static void sendDescriptors(int socket, int first, int second) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment payload = arena.allocate(1);
      payload.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
      MemorySegment iovec = arena.allocate(16, 8);
      iovec.set(ValueLayout.ADDRESS, 0, payload);
      iovec.set(ValueLayout.JAVA_LONG, 8, 1L);
      MemorySegment control = arena.allocate(24, 8);
      control.fill((byte) 0);
      control.set(ValueLayout.JAVA_LONG, 0, 24L);
      control.set(ValueLayout.JAVA_INT, 8, SOL_SOCKET);
      control.set(ValueLayout.JAVA_INT, 12, SCM_RIGHTS);
      control.set(ValueLayout.JAVA_INT, 16, first);
      control.set(ValueLayout.JAVA_INT, 20, second);
      MemorySegment header = messageHeader(arena, iovec, control);
      if (longValue(SENDMSG, socket, header, 0) != 1) {
        throw failure("sendmsg(SCM_RIGHTS)");
      }
    }
  }

  static int[] receiveDescriptors(int socket) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment payload = arena.allocate(1);
      MemorySegment iovec = arena.allocate(16, 8);
      iovec.set(ValueLayout.ADDRESS, 0, payload);
      iovec.set(ValueLayout.JAVA_LONG, 8, 1L);
      MemorySegment control = arena.allocate(32, 8);
      control.fill((byte) 0);
      MemorySegment header = messageHeader(arena, iovec, control);
      long count = longValue(RECVMSG, socket, header, MSG_CMSG_CLOEXEC);
      if (count != 1) {
        throw failure("recvmsg(SCM_RIGHTS)");
      }
      long controlLength = control.get(ValueLayout.JAVA_LONG, 0);
      boolean rights = control.get(ValueLayout.JAVA_INT, 8) == SOL_SOCKET
          && control.get(ValueLayout.JAVA_INT, 12) == SCM_RIGHTS;
      int descriptorCount = rights && controlLength >= 16 && controlLength <= control.byteSize()
          ? (int) ((controlLength - 16) / Integer.BYTES) : 0;
      int first = descriptorCount >= 1 ? control.get(ValueLayout.JAVA_INT, 16) : -1;
      int second = descriptorCount >= 2 ? control.get(ValueLayout.JAVA_INT, 20) : -1;
      boolean valid = header.get(ValueLayout.JAVA_LONG, 40) == 24
          && controlLength == 24 && descriptorCount == 2
          && first >= 0 && second >= 0
          && payload.get(ValueLayout.JAVA_BYTE, 0) == 1
          && (header.get(ValueLayout.JAVA_INT, 48) & 0x08) == 0;
      if (!valid) {
        for (int index = 0; index < descriptorCount; index++) {
          int received = control.get(ValueLayout.JAVA_INT, 16 + (long) index * Integer.BYTES);
          if (received >= 0) {
            close(received);
          }
        }
        throw new IOException("Invalid descriptor transfer");
      }
      return new int[] {first, second};
    }
  }

  private static MemorySegment messageHeader(Arena arena, MemorySegment iovec, MemorySegment control) {
    MemorySegment message = arena.allocate(56, 8);
    message.fill((byte) 0);
    message.set(ValueLayout.ADDRESS, 16, iovec);
    message.set(ValueLayout.JAVA_LONG, 24, 1L);
    message.set(ValueLayout.ADDRESS, 32, control);
    message.set(ValueLayout.JAVA_LONG, 40, control.byteSize());
    return message;
  }

  static void sendByte(int descriptor, int value) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment buffer = arena.allocate(1);
      buffer.set(ValueLayout.JAVA_BYTE, 0, (byte) value);
      long sent = longValue(SEND, descriptor, buffer, 1L, 0);
      if (sent != 1) {
        throw failure("send");
      }
    }
  }

  static int receiveByte(int descriptor) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment buffer = arena.allocate(1);
      long count = longValue(RECV, descriptor, buffer, 1L, 0);
      if (count < 0) {
        throw failure("recv");
      }
      if (count == 0) {
        throw new IOException("Peer closed during native control handshake");
      }
      return Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, 0));
    }
  }

  private static void requireLinux() throws IOException {
    if (!supported()) {
      throw new IOException("Linux native shared memory supports x86_64 and aarch64 only");
    }
  }

  private static MethodHandle nativeMethod(String name, java.lang.foreign.MemoryLayout result,
      java.lang.foreign.MemoryLayout... arguments) {
    MemorySegment symbol = LIBC.find(name).orElseThrow(() -> new IllegalStateException("Missing libc symbol: " + name));
    return LINKER.downcallHandle(symbol, FunctionDescriptor.of(result, arguments), ERRNO);
  }

  private static MethodHandle nativeVariadicMethod(String name, java.lang.foreign.MemoryLayout result,
      java.lang.foreign.MemoryLayout... arguments) {
    MemorySegment symbol = LIBC.find(name).orElseThrow(() -> new IllegalStateException("Missing libc symbol: " + name));
    return LINKER.downcallHandle(symbol, FunctionDescriptor.of(result, arguments),
        Linker.Option.firstVariadicArg(2), ERRNO);
  }

  private static int integer(MethodHandle method, Object... arguments) throws IOException {
    return (int) invoke(method, arguments);
  }

  private static long longValue(MethodHandle method, Object... arguments) throws IOException {
    return (long) invoke(method, arguments);
  }

  private static MemorySegment pointer(MethodHandle method, Object... arguments) throws IOException {
    return (MemorySegment) invoke(method, arguments);
  }

  private static Object invoke(MethodHandle method, Object... arguments) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
      Object[] invocation = new Object[arguments.length + 1];
      invocation[0] = state;
      System.arraycopy(arguments, 0, invocation, 1, arguments.length);
      Object result = method.invokeWithArguments(invocation);
      LAST_ERRNO.set((int) ERRNO_HANDLE.get(state, 0L));
      return result;
    } catch (Throwable failure) {
      if (failure instanceof Error error) {
        throw error;
      }
      throw new IOException("Linux native call failed", failure);
    }
  }

  private static IOException failure(String operation) {
    return new IOException("Linux native operation failed: " + operation + " (errno=" + LAST_ERRNO.get() + ")");
  }

  record PeerCredentials(int pid, int uid, int gid) {}
}
