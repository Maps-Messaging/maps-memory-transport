/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SecureWireHardeningTest {

  @Test
  void rejectsMalformedOfferLengthsWithoutAllocatingPeerControlledBuffers() throws Exception {
    int[] lengths = {0, SecureHandshake.MAX_OFFER_BYTES + 1, 65535};
    for (int length : lengths) {
      MemorySecurity security = security();
      int[] encoded = {length >>> 8, length & 0xff};
      AtomicInteger position = new AtomicInteger();
      IOException failure = assertThrows(IOException.class, () -> SecureWire.authenticate(
          security, false,
          () -> encoded[position.getAndIncrement()],
          value -> {}));
      assertTrue(failure.getMessage().contains("offer size"));
    }
  }

  @Test
  void rejectsTruncatedOffersAndInvalidControlBytes() throws Exception {
    MemorySecurity security = security();
    int[] truncated = {0, 32, 1, 2, 3};
    AtomicInteger index = new AtomicInteger();
    assertThrows(IOException.class, () -> SecureWire.authenticate(security, false,
        () -> {
          int offset = index.getAndIncrement();
          if (offset >= truncated.length) {
            throw new IOException("Control socket closed");
          }
          return truncated[offset];
        },
        value -> {}));

    AtomicInteger invalidIndex = new AtomicInteger();
    assertThrows(IOException.class, () -> SecureWire.authenticate(security, false,
        () -> invalidIndex.getAndIncrement() == 0 ? 512 : 0,
        value -> {}));
  }

  @Test
  void refusesWriterFailureBeforeSessionPublication() throws Exception {
    MemorySecurity security = security();
    assertThrows(IOException.class, () -> SecureWire.authenticate(
        security, true, () -> 0,
        value -> {
          throw new IOException("Simulated native control write failure");
        }));
  }

  private static MemorySecurity security() throws Exception {
    KeyPair identity = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    return MemorySecurity.builder()
        .identity("hardening", identity.getPrivate(), identity.getPublic())
        .trust((peer, key) -> false)
        .protection(DataProtection.AES_256_GCM)
        .build();
  }
}
