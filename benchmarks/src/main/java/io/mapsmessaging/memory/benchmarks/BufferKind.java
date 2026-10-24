/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 */
package io.mapsmessaging.memory.benchmarks;

import java.nio.ByteBuffer;

public enum BufferKind {
  HEAP {
    @Override
    public ByteBuffer allocate(int size) {
      return ByteBuffer.allocate(size);
    }
  },
  DIRECT {
    @Override
    public ByteBuffer allocate(int size) {
      return ByteBuffer.allocateDirect(size);
    }
  };

  public abstract ByteBuffer allocate(int size);
}
