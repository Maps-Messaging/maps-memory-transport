/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import java.io.IOException;
import java.net.SocketAddress;

/** Internal platform/backend seam. Implementations own native connection resources. */
interface MemoryConnectionProvider {
  MemoryTransportType type();
  MemoryConnection connect(MemoryConnectionConfig config) throws IOException;
  MemoryServer listen(MemoryEndpointConfig config) throws IOException;
}
