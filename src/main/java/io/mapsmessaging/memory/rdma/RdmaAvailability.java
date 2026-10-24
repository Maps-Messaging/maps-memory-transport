/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.rdma;

public record RdmaAvailability(boolean supportedPlatform, boolean librariesAvailable, int deviceCount, String detail) {

  public boolean available() {
    return supportedPlatform && librariesAvailable && deviceCount > 0;
  }
}
