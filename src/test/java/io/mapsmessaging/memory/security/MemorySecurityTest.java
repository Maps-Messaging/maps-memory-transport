/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import org.junit.jupiter.api.Test;

class MemorySecurityTest {

  @Test
  void defaultsToAesGcmWithAnExplicitPeerTrustPolicy() throws Exception {
    KeyPair identity = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair peer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

    PeerTrust trust = (peerName, signingKey) ->
        "server-b".equals(peerName) && peer.getPublic().equals(signingKey);

    MemorySecurity security = MemorySecurity.builder()
        .identity("server-a", identity.getPrivate(), identity.getPublic())
        .trust(trust)
        .build();

    assertEquals("server-a", security.identity());
    assertEquals(DataProtection.AES_256_GCM, security.protection());
    assertTrue(security.trust().isTrusted("server-b", peer.getPublic()));
    assertFalse(security.trust().isTrusted("unknown", peer.getPublic()));
    PublicKey wrongKey = identity.getPublic();
    assertFalse(security.trust().isTrusted("server-b", wrongKey));
  }

  @Test
  void supportsAllAgreedProtectionModes() throws Exception {
    KeyPair identity = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    for (DataProtection mode : DataProtection.values()) {
      MemorySecurity security = MemorySecurity.builder()
          .identity("server-a", identity.getPrivate(), identity.getPublic())
          .trust((peerName, signingKey) -> false)
          .protection(mode)
          .build();
      assertEquals(mode, security.protection());
    }
  }

  @Test
  void refusesMissingTrustAndInvalidIdentityAlgorithm() throws Exception {
    KeyPair ed25519 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();

    assertThrows(NullPointerException.class, () -> MemorySecurity.builder()
        .identity("server-a", ed25519.getPrivate(), ed25519.getPublic())
        .build());
    assertThrows(IllegalArgumentException.class, () -> MemorySecurity.builder()
        .identity("server-a", rsa.getPrivate(), rsa.getPublic())
        .trust((peerName, signingKey) -> false)
        .build());
    assertThrows(IllegalArgumentException.class, () -> MemorySecurity.builder()
        .identity(" ", ed25519.getPrivate(), ed25519.getPublic())
        .trust((peerName, signingKey) -> false)
        .build());
  }
}
