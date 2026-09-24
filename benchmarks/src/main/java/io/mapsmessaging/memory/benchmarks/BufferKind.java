/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 */
package io.mapsmessaging.memory.benchmarks;

import java.nio.ByteBuffer;

enum BufferKind {
  HEAP {
    @Override
    ByteBuffer allocate(int size) {
      return ByteBuffer.allocate(size);
    }
  },
  DIRECT {
    @Override
    ByteBuffer allocate(int size) {
      return ByteBuffer.allocateDirect(size);
    }
  };

  abstract ByteBuffer allocate(int size);
}
