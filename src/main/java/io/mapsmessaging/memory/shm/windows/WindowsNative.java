/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm.windows;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/** Windows x64/ARM64 native memory and pipe operations; all HANDLE ownership is explicit. */
final class WindowsNative {

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32", Arena.global());
  private static final SymbolLookup ADVAPI = SymbolLookup.libraryLookup("advapi32", Arena.global());
  private static final Linker.Option LAST_ERROR_OPTION = Linker.Option.captureCallState("GetLastError");
  private static final java.lang.invoke.VarHandle LAST_ERROR_HANDLE =
      Linker.Option.captureStateLayout().varHandle(
          java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError"));
  private static final ThreadLocal<Integer> LAST_ERROR = ThreadLocal.withInitial(() -> 0);
  private static final int PIPE_ACCESS_DUPLEX = 3;
  private static final int PIPE_TYPE_BYTE = 0;
  private static final int PIPE_READMODE_BYTE = 0;
  private static final int PIPE_NOWAIT = 1;
  private static final int PIPE_REJECT_REMOTE_CLIENTS = 8;
  private static final int PIPE_UNLIMITED_INSTANCES = 255;
  private static final int GENERIC_READ_WRITE = 0xc0000000;
  private static final int OPEN_EXISTING = 3;
  private static final int PAGE_READWRITE = 4;
  private static final int FILE_MAP_WRITE = 2;
  private static final int PROCESS_DUP_HANDLE = 0x40;
  private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
  private static final int TOKEN_QUERY = 8;
  private static final int DUPLICATE_SAME_ACCESS = 2;
  private static final long INVALID_HANDLE = -1L;

  private static final MethodHandle CREATE_PIPE = kernel("CreateNamedPipeW", ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
  private static final MethodHandle CONNECT_PIPE = kernel("ConnectNamedPipe", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS);
  private static final MethodHandle DISCONNECT_PIPE = kernel("DisconnectNamedPipe", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS);
  private static final MethodHandle CREATE_FILE = kernel("CreateFileW", ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
  private static final MethodHandle GET_CLIENT_PID = kernel("GetNamedPipeClientProcessId", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS);
  private static final MethodHandle GET_SERVER_PID = kernel("GetNamedPipeServerProcessId", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS);
  private static final MethodHandle CURRENT_PROCESS = kernel("GetCurrentProcess", ValueLayout.ADDRESS);
  private static final MethodHandle OPEN_PROCESS = kernel("OpenProcess", ValueLayout.ADDRESS,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
  private static final MethodHandle OPEN_PROCESS_TOKEN = advapi("OpenProcessToken", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
  private static final MethodHandle TOKEN_INFORMATION = advapi("GetTokenInformation", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
  private static final MethodHandle EQUAL_SID = advapi("EqualSid", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS);
  private static final MethodHandle CONVERT_SDDL = advapi("ConvertStringSecurityDescriptorToSecurityDescriptorW",
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS);
  private static final MethodHandle LOCAL_FREE = kernel("LocalFree", ValueLayout.ADDRESS, ValueLayout.ADDRESS);
  private static final MethodHandle CREATE_MAPPING = kernel("CreateFileMappingW", ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
  private static final MethodHandle MAP_VIEW = kernel("MapViewOfFile", ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG);
  private static final MethodHandle UNMAP_VIEW = kernel("UnmapViewOfFile", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS);
  private static final MethodHandle DUPLICATE_HANDLE = kernel("DuplicateHandle", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT);
  private static final MethodHandle CLOSE_HANDLE = kernel("CloseHandle", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS);
  private static final MethodHandle CANCEL_IO = kernel("CancelIoEx", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS);
  private static final MethodHandle PEEK_PIPE = kernel("PeekNamedPipe", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
  private static final MethodHandle READ_FILE = kernel("ReadFile", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS);
  private static final MethodHandle WRITE_FILE = kernel("WriteFile", ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS);

  private WindowsNative() {}

  static boolean supported() {
    String os = System.getProperty("os.name", "");
    String arch = System.getProperty("os.arch", "");
    return os.startsWith("Windows") && (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("aarch64"));
  }

  static MemorySegment newPipe(String name) throws IOException {
    requireWindows();
    try (Arena arena = Arena.ofConfined();
         LocalSecurity security = securityDescriptor(arena)) {
      MemorySegment attributes = securityAttributes(arena, security.value());
      MemorySegment pipeName = wide(arena, name);
      MemorySegment handle = pointer(CREATE_PIPE, pipeName, PIPE_ACCESS_DUPLEX,
          PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_NOWAIT | PIPE_REJECT_REMOTE_CLIENTS,
          PIPE_UNLIMITED_INSTANCES, 4096, 4096, 0, attributes);
      if (invalid(handle)) {
        throw failure("CreateNamedPipeW");
      }
      return handle;
    }
  }

  static boolean connectPipe(MemorySegment pipe) throws IOException {
    if (integer(CONNECT_PIPE, pipe, MemorySegment.NULL) != 0) {
      return true;
    }
    int error = LAST_ERROR.get();
    if (error == 535) {
      return true;
    }
    if (error == 536) {
      return false;
    }
    throw failure("ConnectNamedPipe");
  }

  static MemorySegment openPipe(String name) throws IOException {
    requireWindows();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment path = wide(arena, name);
      MemorySegment pipe = pointer(CREATE_FILE, path, GENERIC_READ_WRITE, 0,
          MemorySegment.NULL, OPEN_EXISTING, 0, MemorySegment.NULL);
      if (invalid(pipe)) {
        throw failure("CreateFileW(named pipe)");
      }
      return pipe;
    }
  }

  static void verifyPeer(MemorySegment pipe, boolean serverSide) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment pid = arena.allocate(ValueLayout.JAVA_INT);
      MethodHandle processIdMethod = serverSide ? GET_CLIENT_PID : GET_SERVER_PID;
      if (integer(processIdMethod, pipe, pid) == 0) {
        throw failure("GetNamedPipePeerProcessId");
      }
      int remotePid = pid.get(ValueLayout.JAVA_INT, 0);
      MemorySegment peer = pointer(OPEN_PROCESS, PROCESS_QUERY_LIMITED_INFORMATION
          | (serverSide ? PROCESS_DUP_HANDLE : 0), 0, remotePid);
      if (invalid(peer)) {
        throw failure("OpenProcess(peer)");
      }
      try {
        compareProcessIdentity(currentProcess(), peer);
      } finally {
        close(peer);
      }
    }
  }

  static MemorySegment createRegion(long size) throws IOException {
    requireWindows();
    if (size <= 0 || size > 256L * 1024 * 1024) {
      throw new IOException("Invalid Windows shared-memory size");
    }
    try (Arena arena = Arena.ofConfined();
         LocalSecurity descriptor = securityDescriptor(arena)) {
      MemorySegment attributes = securityAttributes(arena, descriptor.value());
      MemorySegment result = pointer(CREATE_MAPPING, MemorySegment.ofAddress(INVALID_HANDLE),
          attributes, PAGE_READWRITE, (int) (size >>> 32), (int) size, MemorySegment.NULL);
      if (invalid(result)) {
        throw failure("CreateFileMappingW");
      }
      return result;
    }
  }

  static MemorySegment duplicateIntoPeer(MemorySegment pipe, MemorySegment mapping) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment pid = arena.allocate(ValueLayout.JAVA_INT);
      if (integer(GET_CLIENT_PID, pipe, pid) == 0) {
        throw failure("GetNamedPipeClientProcessId");
      }
      MemorySegment process = pointer(OPEN_PROCESS, PROCESS_DUP_HANDLE, 0,
          pid.get(ValueLayout.JAVA_INT, 0));
      if (invalid(process)) {
        throw failure("OpenProcess(PROCESS_DUP_HANDLE)");
      }
      try {
        MemorySegment duplicated = arena.allocate(ValueLayout.ADDRESS);
        if (integer(DUPLICATE_HANDLE, currentProcess(), mapping, process, duplicated,
            0, 0, DUPLICATE_SAME_ACCESS) == 0) {
          throw failure("DuplicateHandle");
        }
        return duplicated.get(ValueLayout.ADDRESS, 0);
      } finally {
        close(process);
      }
    }
  }

  static void closeRemoteHandle(MemorySegment pipe, MemorySegment remoteHandle) {
    if (invalid(remoteHandle)) {
      return;
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment pid = arena.allocate(ValueLayout.JAVA_INT);
      if (integer(GET_CLIENT_PID, pipe, pid) == 0) {
        return;
      }
      MemorySegment remoteProcess = pointer(OPEN_PROCESS, PROCESS_DUP_HANDLE, 0,
          pid.get(ValueLayout.JAVA_INT, 0));
      if (invalid(remoteProcess)) {
        return;
      }
      try {
        MemorySegment returned = arena.allocate(ValueLayout.ADDRESS);
        int options = DUPLICATE_SAME_ACCESS | 1; // DUPLICATE_CLOSE_SOURCE
        if (integer(DUPLICATE_HANDLE, remoteProcess, remoteHandle, currentProcess(),
            returned, 0, 0, options) != 0) {
          close(returned.get(ValueLayout.ADDRESS, 0));
        }
      } finally {
        close(remoteProcess);
      }
    } catch (IOException ignored) {
      // A terminated peer releases its own handles.
    }
  }

  static MemorySegment map(MemorySegment mapping, long size, Arena lifetime) throws IOException {
    MemorySegment pointer = pointer(MAP_VIEW, mapping, FILE_MAP_WRITE, 0, 0, size);
    if (invalid(pointer)) {
      throw failure("MapViewOfFile");
    }
    return pointer.reinterpret(size, lifetime, view -> {
      try {
        if (integer(UNMAP_VIEW, view) == 0) {
          throw new IllegalStateException("UnmapViewOfFile failed");
        }
      } catch (IOException exception) {
        throw new IllegalStateException("UnmapViewOfFile failed", exception);
      }
    });
  }

  static void sendByte(MemorySegment pipe, int value) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment data = arena.allocate(ValueLayout.JAVA_BYTE);
      data.set(ValueLayout.JAVA_BYTE, 0, (byte) value);
      MemorySegment written = arena.allocate(ValueLayout.JAVA_INT);
      if (integer(WRITE_FILE, pipe, data, 1, written, MemorySegment.NULL) == 0
          || written.get(ValueLayout.JAVA_INT, 0) != 1) {
        throw failure("WriteFile(pipe)");
      }
    }
  }

  static int receiveByte(MemorySegment pipe, int timeoutMillis) throws IOException {
    long deadline = System.nanoTime() + (long) timeoutMillis * 1_000_000L;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment available = arena.allocate(ValueLayout.JAVA_INT);
      MemorySegment buffer = arena.allocate(ValueLayout.JAVA_BYTE);
      MemorySegment received = arena.allocate(ValueLayout.JAVA_INT);
      while (System.nanoTime() < deadline) {
        if (integer(PEEK_PIPE, pipe, MemorySegment.NULL, 0,
            MemorySegment.NULL, available, MemorySegment.NULL) == 0) {
          throw failure("PeekNamedPipe");
        }
        if (available.get(ValueLayout.JAVA_INT, 0) > 0) {
          if (integer(READ_FILE, pipe, buffer, 1, received, MemorySegment.NULL) == 0
              || received.get(ValueLayout.JAVA_INT, 0) != 1) {
            throw failure("ReadFile(pipe)");
          }
          return Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, 0));
        }
        try {
          Thread.sleep(2);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while receiving Windows control data", exception);
        }
      }
    }
    throw new IOException("Windows pipe handshake timed out");
  }

  static void cancel(MemorySegment pipe) {
    if (!invalid(pipe)) {
      try {
        integer(CANCEL_IO, pipe, MemorySegment.NULL);
      } catch (IOException ignored) {
        // There may be no pending native I/O to cancel.
      }
    }
  }

  static void closePipe(MemorySegment pipe, boolean serverSide) {
    if (!invalid(pipe)) {
      if (serverSide) {
        try {
          integer(DISCONNECT_PIPE, pipe);
        } catch (IOException ignored) {
          // Peer may already have disconnected.
        }
      }
      close(pipe);
    }
  }

  static void close(MemorySegment handle) {
    if (!invalid(handle)) {
      try {
        integer(CLOSE_HANDLE, handle);
      } catch (IOException ignored) {
        // Best effort resource cleanup.
      }
    }
  }

  private static void compareProcessIdentity(MemorySegment own, MemorySegment remote) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment ours = tokenForProcess(own, arena);
      MemorySegment theirs = tokenForProcess(remote, arena);
      if (integer(EQUAL_SID, ours, theirs) == 0) {
        throw new IOException("Windows named-pipe peer belongs to a different account");
      }
    }
  }

  private static MemorySegment tokenForProcess(MemorySegment process, Arena arena) throws IOException {
    MemorySegment tokenOut = arena.allocate(ValueLayout.ADDRESS);
    if (integer(OPEN_PROCESS_TOKEN, process, TOKEN_QUERY, tokenOut) == 0) {
      throw failure("OpenProcessToken");
    }
    MemorySegment token = tokenOut.get(ValueLayout.ADDRESS, 0);
    try {
      MemorySegment resultLength = arena.allocate(ValueLayout.JAVA_INT);
      MemorySegment user = arena.allocate(256, 8);
      if (integer(TOKEN_INFORMATION, token, 1, user, 256, resultLength) == 0) {
        throw failure("GetTokenInformation(TokenUser)");
      }
      return user.get(ValueLayout.ADDRESS, 0);
    } finally {
      close(token);
    }
  }

  private static LocalSecurity securityDescriptor(Arena arena) throws IOException {
    MemorySegment pointer = arena.allocate(ValueLayout.ADDRESS);
    if (integer(CONVERT_SDDL, wide(arena, "D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;OW)"),
        1, pointer, MemorySegment.NULL) == 0) {
      throw failure("ConvertStringSecurityDescriptorToSecurityDescriptorW");
    }
    return new LocalSecurity(pointer.get(ValueLayout.ADDRESS, 0));
  }

  private record LocalSecurity(MemorySegment value) implements AutoCloseable {
    @Override
    public void close() throws IOException {
      MemorySegment result = pointer(LOCAL_FREE, value);
      if (result.address() != 0) {
        throw failure("LocalFree");
      }
    }
  }

  private static MemorySegment securityAttributes(Arena arena, MemorySegment descriptor) {
    MemorySegment attributes = arena.allocate(24, 8);
    attributes.fill((byte) 0);
    attributes.set(ValueLayout.JAVA_INT, 0, 24);
    attributes.set(ValueLayout.ADDRESS, 8, descriptor);
    return attributes;
  }

  private static MemorySegment wide(Arena arena, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
    MemorySegment result = arena.allocate(bytes.length + 2L, 2);
    result.fill((byte) 0);
    MemorySegment.copy(bytes, 0, result, ValueLayout.JAVA_BYTE, 0, bytes.length);
    return result;
  }

  private static MemorySegment currentProcess() throws IOException {
    return pointer(CURRENT_PROCESS);
  }

  private static boolean invalid(MemorySegment handle) {
    return handle == null || handle.address() == 0 || handle.address() == INVALID_HANDLE;
  }

  private static void requireWindows() throws IOException {
    if (!supported()) {
      throw new IOException("Native Windows shared memory requires Windows 64-bit");
    }
  }

  private static MethodHandle kernel(String name, MemoryLayout returnType,
      MemoryLayout... arguments) {
    return LINKER.downcallHandle(KERNEL.find(name).orElseThrow(), FunctionDescriptor.of(returnType, arguments), LAST_ERROR_OPTION);
  }

  private static MethodHandle advapi(String name, MemoryLayout returnType,
      MemoryLayout... arguments) {
    return LINKER.downcallHandle(ADVAPI.find(name).orElseThrow(), FunctionDescriptor.of(returnType, arguments), LAST_ERROR_OPTION);
  }

  private static Object invoke(MethodHandle function, Object... args) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
      Object[] all = new Object[args.length + 1];
      all[0] = state;
      System.arraycopy(args, 0, all, 1, args.length);
      Object value = function.invokeWithArguments(all);
      LAST_ERROR.set((int) LAST_ERROR_HANDLE.get(state, 0L));
      return value;
    } catch (Throwable exception) {
      if (exception instanceof Error error) {
        throw error;
      }
      throw new IOException("Windows native call failed", exception);
    }
  }

  private static int integer(MethodHandle method, Object... args) throws IOException {
    return (int) invoke(method, args);
  }

  private static MemorySegment pointer(MethodHandle method, Object... args) throws IOException {
    return (MemorySegment) invoke(method, args);
  }

  private static IOException failure(String operation) {
    return new IOException(operation + " failed with Win32 error " + LAST_ERROR.get());
  }
}
