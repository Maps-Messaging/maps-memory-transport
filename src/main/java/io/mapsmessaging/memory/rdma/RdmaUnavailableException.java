/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.rdma;

import java.io.IOException;

public final class RdmaUnavailableException extends IOException {

  public RdmaUnavailableException(String message) {
    super(message);
  }

  public RdmaUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
