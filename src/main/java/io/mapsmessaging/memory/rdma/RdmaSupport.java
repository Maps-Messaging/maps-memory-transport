/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.rdma;

import java.io.IOException;
import java.util.Locale;

public final class RdmaSupport {

  private RdmaSupport() {}

  public static RdmaAvailability probe() {
    if (!isLinux()) {
      return new RdmaAvailability(false, false, 0, "RDMA transport is currently supported only on Linux");
    }

    try (RdmaNative nativeAccess = new RdmaNative()) {
      try {
        int deviceCount = nativeAccess.deviceCount();
        if (deviceCount == 0) {
          return new RdmaAvailability(true, true, 0, "rdma-core libraries are available but no RDMA devices were found");
        }
        return new RdmaAvailability(true, true, deviceCount, "RDMA is available with " + deviceCount + " device(s)");
      } catch (IOException exception) {
        return new RdmaAvailability(true, true, 0, "rdma-core loaded but device enumeration failed: " + message(exception));
      }
    } catch (IOException | RuntimeException exception) {
      return new RdmaAvailability(true, false, 0, "RDMA native libraries are unavailable: " + message(exception));
    }
  }

  public static void requireAvailable() throws RdmaUnavailableException {
    RdmaAvailability availability = probe();
    if (!availability.available()) {
      throw new RdmaUnavailableException(availability.detail());
    }
  }

  private static boolean isLinux() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
  }

  private static String message(Throwable throwable) {
    String message = throwable.getMessage();
    return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
  }
}
