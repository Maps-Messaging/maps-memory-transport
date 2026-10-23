/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SecureHandshakeTest {

  @Test
  void authenticatesAndProtectsEachDirectionInAllModes() throws Exception {
    for (DataProtection mode : DataProtection.values()) {
      KeyPair clientKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      KeyPair serverKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      MemorySecurity client = security("client", clientKey, serverKey, mode);
      MemorySecurity server = security("server", serverKey, clientKey, mode);
      SecureHandshake outbound = SecureHandshake.begin(client);
      SecureHandshake inbound = SecureHandshake.begin(server);
      try (SecureSession clientSession = outbound.finish(inbound.offer(), false);
           SecureSession serverSession = inbound.finish(outbound.offer(), true)) {
        assertThrows(IOException.class, () -> clientSession.protect(new byte[] {1}));
        serverSession.verifyConfirmation(clientSession.confirmation());
        clientSession.verifyConfirmation(serverSession.confirmation());
        byte[] request = "request".getBytes(StandardCharsets.UTF_8);
        byte[] response = "response".getBytes(StandardCharsets.UTF_8);
        byte[] protectedRequest = clientSession.protect(request);
        assertFalse(Arrays.equals(request, protectedRequest));
        assertArrayEquals(request, serverSession.unprotect(protectedRequest));
        assertArrayEquals(response, clientSession.unprotect(serverSession.protect(response)));
        assertThrows(IOException.class, () -> serverSession.unprotect(protectedRequest));
      }
    }
  }

  @Test
  void rejectsUntrustedPeerAndTamperedOffer() throws Exception {
    KeyPair clientKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair serverKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    MemorySecurity client = security("client", clientKey, serverKey, DataProtection.AES_256_GCM);
    MemorySecurity server = security("server", serverKey, clientKey, DataProtection.AES_256_GCM);
    byte[] signed = SecureHandshake.begin(server).offer();
    signed[15] ^= 1;
    assertThrows(Exception.class, () -> SecureHandshake.begin(client).finish(signed, false));

    KeyPair strangerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    MemorySecurity stranger = security("stranger", strangerKey, serverKey, DataProtection.AES_256_GCM);
    assertThrows(IOException.class, () -> SecureHandshake.begin(client)
        .finish(SecureHandshake.begin(stranger).offer(), false));
  }

  @Test
  void rejectsTamperedRecordAndIncorrectConfirmation() throws Exception {
    for (DataProtection mode : DataProtection.values()) {
      KeyPair aKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      KeyPair bKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      SecureHandshake a = SecureHandshake.begin(security("a", aKey, bKey, mode));
      SecureHandshake b = SecureHandshake.begin(security("b", bKey, aKey, mode));
      try (SecureSession outgoing = a.finish(b.offer(), false);
           SecureSession incoming = b.finish(a.offer(), true)) {
        byte[] wrong = outgoing.confirmation();
        wrong[0] ^= 1;
        assertThrows(IOException.class, () -> incoming.verifyConfirmation(wrong));
      }
      SecureHandshake c = SecureHandshake.begin(security("a", aKey, bKey, mode));
      SecureHandshake d = SecureHandshake.begin(security("b", bKey, aKey, mode));
      try (SecureSession outgoing = c.finish(d.offer(), false);
           SecureSession incoming = d.finish(c.offer(), true)) {
        outgoing.verifyConfirmation(incoming.confirmation());
        incoming.verifyConfirmation(outgoing.confirmation());
        byte[] record = outgoing.protect(new byte[] {10, 20, 30});
        record[record.length - 1] ^= 1;
        assertThrows(IOException.class, () -> incoming.unprotect(record));
        assertThrows(IllegalStateException.class, () -> incoming.unprotect(record));
      }
    }
  }

  @Test
  void rejectsProtectionMismatchAndRepeatedHandshakeUse() throws Exception {
    KeyPair aKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair bKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    SecureHandshake a = SecureHandshake.begin(security("a", aKey, bKey, DataProtection.HMAC_SHA_256));
    SecureHandshake b = SecureHandshake.begin(security("b", bKey, aKey, DataProtection.CHACHA20_POLY1305));
    assertThrows(IOException.class, () -> a.finish(b.offer(), false));
    assertThrows(IllegalStateException.class, () -> a.finish(b.offer(), false));
  }

  private static MemorySecurity security(String id, KeyPair own, KeyPair trusted, DataProtection protection) {
    return MemorySecurity.builder()
        .identity(id, own.getPrivate(), own.getPublic())
        .trust((peerIdentity, key) -> key.equals(trusted.getPublic())
            && (peerIdentity.equals("a") || peerIdentity.equals("b")
                || peerIdentity.equals("client") || peerIdentity.equals("server")))
        .protection(protection)
        .build();
  }
}
