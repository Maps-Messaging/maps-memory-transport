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
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

final class RdmaNative implements AutoCloseable {

  private static final Linker LINKER = Linker.nativeLinker();

  private final Arena arena;
  private final SymbolLookup verbs;
  private final SymbolLookup rdmaCm;

  RdmaNative() throws IOException {
    arena = Arena.ofShared();
    try {
      verbs = loadLibrary(arena, "ibverbs", "libibverbs.so.1");
      rdmaCm = loadLibrary(arena, "rdmacm", "librdmacm.so.1");
      requireSymbol(verbs, "ibv_get_device_list");
      requireSymbol(verbs, "ibv_free_device_list");
      requireSymbol(rdmaCm, "rdma_create_event_channel");
      requireSymbol(rdmaCm, "rdma_destroy_event_channel");
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

  SymbolLookup verbs() {
    return verbs;
  }

  SymbolLookup rdmaCm() {
    return rdmaCm;
  }

  @Override
  public void close() {
    arena.close();
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
}
