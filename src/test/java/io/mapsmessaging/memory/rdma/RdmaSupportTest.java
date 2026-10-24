/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.rdma;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

class RdmaSupportTest {

  @Test
  void probeAlwaysReturnsDiagnosticStatus() {
    RdmaAvailability availability = assertDoesNotThrow(RdmaSupport::probe);

    assertNotNull(availability);
    assertNotNull(availability.detail());
    assertFalse(availability.detail().isBlank());
    assertTrue(availability.deviceCount() >= 0);
    if (availability.available()) {
      assertTrue(availability.supportedPlatform());
      assertTrue(availability.librariesAvailable());
      assertTrue(availability.deviceCount() > 0);
    }
  }

  @Test
  void requireAvailableMatchesProbeResult() {
    RdmaAvailability availability = RdmaSupport.probe();

    if (availability.available()) {
      assertDoesNotThrow(RdmaSupport::requireAvailable);
    } else {
      RdmaUnavailableException exception = assertThrows(RdmaUnavailableException.class, RdmaSupport::requireAvailable);
      assertFalse(exception.getMessage().isBlank());
    }
  }

  @Test
  void nativeDeviceEnumerationWorksWhenRdmaIsAvailable() throws Exception {
    RdmaAvailability availability = RdmaSupport.probe();
    assumeTrue(availability.available(), availability.detail());

    try (RdmaNative nativeAccess = new RdmaNative()) {
      assertTrue(nativeAccess.deviceCount() > 0);
    }
  }
}
