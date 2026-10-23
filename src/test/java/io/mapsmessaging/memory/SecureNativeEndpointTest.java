/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.mapsmessaging.memory.security.DataProtection;
import io.mapsmessaging.memory.security.MemorySecurity;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecureNativeEndpointTest {

  @Test
  void securelyTransfersApplicationRecordsForEveryProtectionMode() throws Exception {
    assumeTrue(supported(), "Native local transport unavailable");
    for (DataProtection mode : DataProtection.values()) {
      KeyPair clientKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      KeyPair serverKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      MemorySecurity clientSecurity = security("client", clientKey, serverKey, "server", mode);
      MemorySecurity serverSecurity = security("server", serverKey, clientKey, "client", mode);
      Path directory = privateDirectory();
      SocketAddress address = address(directory);
      try (MemoryServer server = MemoryServer.listen(endpoint(address, serverSecurity));
           MemoryConnection client = MemoryClient.connect(connection(address, clientSecurity));
           MemoryConnection accepted = server.accept()) {
        byte[] data = new byte[8192];
        for (int index = 0; index < data.length; index++) {
          data[index] = (byte) index;
        }
        assertArrayEquals(data, exchange(client, accepted, data));
        byte[] reply = new byte[] {4, 9, 17, 23};
        assertArrayEquals(reply, exchange(accepted, client, reply));
      } finally {
        cleanup(address, directory);
      }
    }
  }

  @Test
  void failsClosedWhenPeerHasNoMatchingSecurity() throws Exception {
    assumeTrue(supported(), "Native local transport unavailable");
    KeyPair serverKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair clientKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    MemorySecurity serverSecurity = security("server", serverKey, clientKey, "client", DataProtection.AES_256_GCM);
    Path directory = privateDirectory();
    SocketAddress address = address(directory);
    try (MemoryServer server = MemoryServer.listen(endpoint(address, serverSecurity))) {
      assertThrows(IOException.class, () -> MemoryClient.connect(
          new MemoryConnectionConfig(MemoryTransportType.SHARED_MEMORY, address, 1_000, null)));
    } finally {
      cleanup(address, directory);
    }
  }

  private static byte[] exchange(MemoryConnection sender, MemoryConnection receiver, byte[] message)
      throws Exception {
    ByteBuffer output = ByteBuffer.wrap(message);
    ByteBuffer input = ByteBuffer.allocate(message.length);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while ((output.hasRemaining() || input.hasRemaining()) && System.nanoTime() < deadline) {
      if (output.hasRemaining() && sender.canWrite()) {
        sender.write(output);
      }
      if (receiver.hasData()) {
        receiver.read(input);
      }
      if (output.hasRemaining() || input.hasRemaining()) {
        Thread.sleep(1);
      }
    }
    assertTrue(!output.hasRemaining() && !input.hasRemaining(), "Secure transport transfer timed out");
    return input.array();
  }

  private static MemorySecurity security(String id, KeyPair own, KeyPair peer, String peerName,
      DataProtection mode) {
    return MemorySecurity.builder()
        .identity(id, own.getPrivate(), own.getPublic())
        .trust((claimed, signingKey) -> claimed.equals(peerName) && signingKey.equals(peer.getPublic()))
        .protection(mode)
        .build();
  }

  private static MemoryEndpointConfig endpoint(SocketAddress address, MemorySecurity security) {
    return MemoryEndpointConfig.builder()
        .address(address)
        .slotSize(512)
        .slotCount(32)
        .maxNegotiations(8)
        .security(security)
        .build();
  }

  private static MemoryConnectionConfig connection(SocketAddress address, MemorySecurity security) {
    return new MemoryConnectionConfig(MemoryTransportType.SHARED_MEMORY, address, 5_000, security);
  }

  private static boolean supported() {
    String platform = System.getProperty("os.name", "");
    return platform.equalsIgnoreCase("Linux") || platform.equalsIgnoreCase("Mac OS X")
        || platform.startsWith("Windows");
  }

  private static Path privateDirectory() throws IOException {
    if (System.getProperty("os.name", "").startsWith("Windows")) {
      return null;
    }
    return Files.createTempDirectory("maps-secure-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  }

  private static SocketAddress address(Path directory) {
    if (directory == null) {
      return WindowsPipeAddress.of("secure-" + UUID.randomUUID());
    }
    return UnixDomainSocketAddress.of(directory.resolve("secure.sock"));
  }

  private static void cleanup(SocketAddress address, Path directory) throws IOException {
    if (directory != null) {
      Files.deleteIfExists(((UnixDomainSocketAddress) address).getPath());
      Files.deleteIfExists(directory);
    }
  }
}
