/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

public interface MemoryTransport extends Closeable {

  int write(ByteBuffer source) throws IOException;

  int read(ByteBuffer destination) throws IOException;

  boolean hasData();

  boolean canWrite();

  String remoteAddress();
}
