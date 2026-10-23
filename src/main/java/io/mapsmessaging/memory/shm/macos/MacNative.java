/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm.macos;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.UUID;
import java.lang.foreign.MemoryLayout;

/**
 * macOS-only native bindings. The ancillary message and sockaddr structures are
 * defined for the macOS 64-bit ABIs supported by JDK 25 (x86_64 and aarch64).
 * Every native descriptor remains caller-owned until it is explicitly transferred.
 */
final class MacNative {

  static final int AF_UNIX = 1;
  static final int SOCK_STREAM = 1;
  static final int SOCK_CLOEXEC = 0;
  static final int SOL_SOCKET = 0xffff;
  static final int SO_RCVTIMEO = 0x1006;
  static final int SO_SNDTIMEO = 0x1005;
  static final int SCM_RIGHTS = 1;
  private static final int O_CREAT = 0x200;
  private static final int O_EXCL = 0x800;
  private static final int O_RDWR = 2;
  static final int PROT_READ = 1;
  static final int PROT_WRITE = 2;
  static final int MAP_SHARED = 1;
  static final int MSG_CMSG_CLOEXEC = 0;
  private static final int MSG_CTRUNC = 0x20;
  static final int EINTR = 4;
  static final int EAGAIN = 11;

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIBC = LINKER.defaultLookup();
  private static final Linker.Option ERRNO = Linker.Option.captureCallState("errno");
  private static final ThreadLocal<Integer> LAST_ERRNO = ThreadLocal.withInitial(() -> 0);
  private static final java.lang.invoke.VarHandle ERRNO_HANDLE =
      Linker.Option.captureStateLayout().varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("errno"));

  private static final MethodHandle FCNTL = nativeVariadicMethod("fcntl", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
  private static final MethodHandle FSTAT = nativeMethod("fstat", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
  private static final MethodHandle SHM_OPEN = nativeMethod("shm_open", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
  private static final MethodHandle SHM_UNLINK = nativeMethod("shm_unlink", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
  private static final MethodHandle GETPEEREID = nativeMethod("getpeereid", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
  private static final MethodHandle FTRUNCATE = nativeMethod("ftruncate", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG);
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
  private static final MethodHandle ACCEPT = nativeMethod("accept", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
  private static final MethodHandle POLL = nativeMethod("poll", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
  private static final MethodHandle CONNECT = nativeMethod("connect", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
  private static final MethodHandle SETSOCKOPT = nativeMethod("setsockopt", ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
  private static final MethodHandle SENDMSG = nativeMethod("sendmsg", ValueLayout.JAVA_LONG,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
  private static final MethodHandle RECVMSG = nativeMethod("recvmsg", ValueLayout.JAVA_LONG,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
  private static final MethodHandle SEND = nativeMethod("send", ValueLayout.JAVA_LONG,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);
  private static final MethodHandle RECV = nativeMethod("recv", ValueLayout.JAVA_LONG,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);

  private MacNative() {}

  static boolean supported() {
    String os = System.getProperty("os.name", "");
    String architecture = System.getProperty("os.arch", "");
    return os.equalsIgnoreCase("Mac OS X")
        && (architecture.equals("amd64") || architecture.equals("x86_64")
            || architecture.equals("aarch64"));
  }

  static int createMemoryFile(String name, long size) throws IOException {
    requireMac();
    if (size <= 0) {
      throw new IllegalArgumentException("Memory file size must be positive");
    }
    String unique = "/maps-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nameSegment = arena.allocateFrom(unique);
      int descriptor = integer(SHM_OPEN, nameSegment, O_CREAT | O_EXCL | O_RDWR, 0600);
      if (descriptor < 0) {
        throw failure("shm_open");
      }
      try {
        closeOnExec(descriptor);
        if (integer(SHM_UNLINK, nameSegment) != 0) {
          throw failure("shm_unlink");
        }
        if (integer(FTRUNCATE, descriptor, size) != 0) {
          throw failure("ftruncate");
        }
        return descriptor;
      } catch (IOException | RuntimeException exception) {
        close(descriptor);
        throw exception;
      }
    }
  }

  static MemorySegment map(int descriptor, long size, Arena lifetime) throws IOException {
    requireMac();
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
    requireMac();
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
    requireMac();
    int descriptor = integer(SOCKET, AF_UNIX, SOCK_STREAM, 0);
    if (descriptor < 0) {
      throw failure("socket");
    }
    try {
      closeOnExec(descriptor);
      return descriptor;
    } catch (IOException exception) {
      close(descriptor);
      throw exception;
    }
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
      address.set(ValueLayout.JAVA_BYTE, 0, (byte) (2 + pathname.length + 1));
      address.set(ValueLayout.JAVA_BYTE, 1, (byte) AF_UNIX);
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

  static boolean acceptReady(int descriptor, int timeoutMillis) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment pollDescriptor = arena.allocate(8, 4);
      pollDescriptor.fill((byte) 0);
      pollDescriptor.set(ValueLayout.JAVA_INT, 0, descriptor);
      pollDescriptor.set(ValueLayout.JAVA_SHORT, 4, (short) 1);
      int ready = integer(POLL, pollDescriptor, 1, timeoutMillis);
      if (ready < 0) {
        if (LAST_ERRNO.get() == EINTR) {
          return false;
        }
        throw failure("poll(accept)");
      }
      return ready > 0;
    }
  }

  static int accept(int descriptor) throws IOException {
    int accepted = integer(ACCEPT, descriptor, MemorySegment.NULL, MemorySegment.NULL);
    if (accepted < 0) {
      throw failure("accept");
    }
    try {
      closeOnExec(accepted);
      return accepted;
    } catch (IOException exception) {
      close(accepted);
      throw exception;
    }
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

  static PeerCredentials peerCredentials(int descriptor) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment uid = arena.allocate(ValueLayout.JAVA_INT);
      MemorySegment gid = arena.allocate(ValueLayout.JAVA_INT);
      if (integer(GETPEEREID, descriptor, uid, gid) != 0) {
        throw failure("getpeereid");
      }
      return new PeerCredentials(-1, uid.get(ValueLayout.JAVA_INT, 0), gid.get(ValueLayout.JAVA_INT, 0));
    }
  }

  static void validateRegion(int descriptor, long size) throws IOException {
    if (size <= 0 || size > 256L * 1024 * 1024) {
      throw new IOException("Invalid mapped region size");
    }
    long actualSize;
    try (Arena arena = Arena.ofConfined()) {
      // Darwin LP64 stat64: st_size is at byte offset 96.
      MemorySegment stat = arena.allocate(144, 8);
      stat.fill((byte) 0);
      if (integer(FSTAT, descriptor, stat) != 0) {
        throw failure("fstat");
      }
      actualSize = stat.get(ValueLayout.JAVA_LONG, 96);
    }
    if (actualSize != size) {
      throw new IOException("Received macOS shared-memory region has an unexpected size: expected="
          + size + ", actual=" + actualSize);
    }
  }

  private static void closeOnExec(int descriptor) throws IOException {
    if (integer(FCNTL, descriptor, 2, 1) < 0) {
      throw failure("fcntl(F_SETFD, FD_CLOEXEC)");
    }
  }

  static void sendDescriptors(int socket, int first, int second) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment payload = arena.allocate(1);
      payload.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
      MemorySegment iovec = arena.allocate(16, 8);
      iovec.set(ValueLayout.ADDRESS, 0, payload);
      iovec.set(ValueLayout.JAVA_LONG, 8, 1L);
      MemorySegment control = arena.allocate(20, 4);
      control.fill((byte) 0);
      control.set(ValueLayout.JAVA_INT, 0, 20);
      control.set(ValueLayout.JAVA_INT, 4, SOL_SOCKET);
      control.set(ValueLayout.JAVA_INT, 8, SCM_RIGHTS);
      control.set(ValueLayout.JAVA_INT, 12, first);
      control.set(ValueLayout.JAVA_INT, 16, second);
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
      long controlLength = Integer.toUnsignedLong(control.get(ValueLayout.JAVA_INT, 0));
      boolean rights = control.get(ValueLayout.JAVA_INT, 4) == SOL_SOCKET
          && control.get(ValueLayout.JAVA_INT, 8) == SCM_RIGHTS;
      int descriptorCount = rights && controlLength >= 12 && controlLength <= control.byteSize()
          ? (int) ((controlLength - 12) / Integer.BYTES) : 0;
      int first = descriptorCount >= 1 ? control.get(ValueLayout.JAVA_INT, 12) : -1;
      int second = descriptorCount >= 2 ? control.get(ValueLayout.JAVA_INT, 16) : -1;
      boolean valid = Integer.toUnsignedLong(header.get(ValueLayout.JAVA_INT, 40)) == 20
          && controlLength == 20 && descriptorCount == 2
          && first >= 0 && second >= 0
          && payload.get(ValueLayout.JAVA_BYTE, 0) == 1
          && (header.get(ValueLayout.JAVA_INT, 44) & MSG_CTRUNC) == 0;
      if (!valid) {
        for (int index = 0; index < descriptorCount; index++) {
          int received = control.get(ValueLayout.JAVA_INT, 12 + (long) index * Integer.BYTES);
          if (received >= 0) {
            close(received);
          }
        }
        throw new IOException("Invalid descriptor transfer");
      }
      try {
        closeOnExec(first);
        closeOnExec(second);
      } catch (IOException exception) {
        close(first);
        close(second);
        throw exception;
      }
      return new int[] {first, second};
    }
  }

  private static MemorySegment messageHeader(Arena arena, MemorySegment iovec, MemorySegment control) {
    MemorySegment message = arena.allocate(48, 8);
    message.fill((byte) 0);
    message.set(ValueLayout.ADDRESS, 16, iovec);
    message.set(ValueLayout.JAVA_INT, 24, 1);
    message.set(ValueLayout.ADDRESS, 32, control);
    message.set(ValueLayout.JAVA_INT, 40, (int) control.byteSize());
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

  private static void requireMac() throws IOException {
    if (!supported()) {
      throw new IOException("macOS native shared memory supports x86_64 and aarch64 only");
    }
  }

  private static MethodHandle nativeMethod(String name, MemoryLayout result,
      MemoryLayout... arguments) {
    MemorySegment symbol = LIBC.find(name).orElseThrow(() -> new IllegalStateException("Missing libc symbol: " + name));
    return LINKER.downcallHandle(symbol, FunctionDescriptor.of(result, arguments), ERRNO);
  }

  private static MethodHandle nativeVariadicMethod(String name, MemoryLayout result,
      MemoryLayout... arguments) {
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
      throw new IOException("macOS native call failed", failure);
    }
  }

  private static IOException failure(String operation) {
    return new IOException("macOS native operation failed: " + operation + " (errno=" + LAST_ERRNO.get() + ")");
  }

  record PeerCredentials(int pid, int uid, int gid) {}
}
