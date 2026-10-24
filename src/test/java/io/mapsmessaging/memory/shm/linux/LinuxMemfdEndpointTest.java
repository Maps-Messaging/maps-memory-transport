/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm.linux;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.mapsmessaging.memory.MemoryClient;
import io.mapsmessaging.memory.MemoryConnection;
import io.mapsmessaging.memory.MemoryConnectionConfig;
import io.mapsmessaging.memory.MemoryEndpointConfig;
import io.mapsmessaging.memory.MemoryServer;
import io.mapsmessaging.memory.MemoryTransportType;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LinuxMemfdEndpointTest {

  @Test
  void sendsAndReceivesThroughAnonymousMemoryWithSeveralPeers() throws Exception {
    assumeTrue(linuxSupported(), "Linux 64-bit native backend only");
    Path directory = Files.createTempDirectory(Path.of("/tmp"), "ml-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Path socketPath = directory.resolve("endpoint.sock");
    UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
    try (MemoryServer server = MemoryServer.listen(endpoint(address))) {
      List<MemoryConnection> clients = new ArrayList<>();
      List<MemoryConnection> accepted = new ArrayList<>();
      try {
        for (int index = 0; index < 8; index++) {
          clients.add(MemoryClient.connect(MemoryConnectionConfig.linuxSharedMemory(address)));
          accepted.add(server.accept());
        }
        for (int index = 0; index < clients.size(); index++) {
          byte[] payload = new byte[] {(byte) index, 11, 19, 37};
          MemoryConnection client = clients.get(index);
          MemoryConnection peer = accepted.get(index);
          assertTrue(client.remoteAddress().startsWith("shm-linux:"));
          assertTrue(client.write(ByteBuffer.wrap(payload)) > 0);
          assertArrayEquals(payload, readExactly(peer, payload.length));

          byte[] reply = new byte[] {42, (byte) index};
          assertTrue(peer.write(ByteBuffer.wrap(reply)) > 0);
          assertArrayEquals(reply, readExactly(client, reply.length));
        }
      } finally {
        for (MemoryConnection connection : clients) {
          connection.close();
        }
        for (MemoryConnection connection : accepted) {
          connection.close();
        }
      }
    } finally {
      Files.deleteIfExists(socketPath);
      Files.deleteIfExists(directory);
    }
    assertFalse(Files.exists(socketPath));
  }

  @Test
  void communicatesWithSeparateJvmProcess() throws Exception {
    assumeTrue(linuxSupported(), "Linux 64-bit native backend only");
    Path directory = Files.createTempDirectory(Path.of("/tmp"), "ml-process-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Path socketPath = directory.resolve("endpoint.sock");
    UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
    try (MemoryServer server = MemoryServer.listen(endpoint(address))) {
      Path java = Path.of(System.getProperty("java.home"), "bin", "java");
      Process process = new ProcessBuilder(
          java.toString(), "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
          LinuxMemfdProcess.class.getName(), socketPath.toString())
          .inheritIO().start();
      try (MemoryConnection peer = server.accept()) {
        assertArrayEquals(new byte[] {3, 5, 7}, readExactly(peer, 3));
        assertTrue(peer.write(ByteBuffer.wrap(new byte[] {2, 4, 6})) > 0);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Child JVM did not finish");
        assertTrue(process.exitValue() == 0, "Child JVM failed: " + process.exitValue());
      } finally {
        if (process.isAlive()) {
          process.destroyForcibly();
          process.waitFor(10, TimeUnit.SECONDS);
        }
      }
    } finally {
      Files.deleteIfExists(socketPath);
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void detectsRemoteProcessTerminationAndReclaimsConnection() throws Exception {
    assumeTrue(linuxSupported(), "Linux 64-bit native backend only");
    Path directory = Files.createTempDirectory(Path.of("/tmp"), "ml-crash-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Path socketPath = directory.resolve("endpoint.sock");
    UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
    try (MemoryServer server = MemoryServer.listen(endpoint(address))) {
      Path java = Path.of(System.getProperty("java.home"), "bin", "java");
      Process process = new ProcessBuilder(
          java.toString(), "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
          LinuxMemfdProcess.class.getName(), socketPath.toString(), "hold")
          .inheritIO().start();
      try (MemoryConnection peer = server.accept()) {
        assertArrayEquals(new byte[] {3, 5, 7}, readExactly(peer, 3));
        process.destroyForcibly();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!peer.isClosed() && System.nanoTime() < deadline) {
          Thread.sleep(20);
        }
        assertTrue(peer.isClosed(), "Terminated peer was not detected");
      } finally {
        if (process.isAlive()) {
          process.destroyForcibly();
          process.waitFor(10, TimeUnit.SECONDS);
        }
      }
    } finally {
      Files.deleteIfExists(socketPath);
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void rejectsNativeBackendOnOtherPlatforms() throws Exception {
    assumeTrue(!linuxSupported(), "Linux backend available");
    UnixDomainSocketAddress address = UnixDomainSocketAddress.of(Path.of("unsupported-linux-memfd.sock"));
    assertThrows(IOException.class, () -> MemoryServer.listen(endpoint(address)));
  }

  @Test
  void rejectsPubliclyAccessibleControlDirectory() throws Exception {
    assumeTrue(linuxSupported(), "Linux 64-bit native backend only");
    Path directory = Files.createTempDirectory(Path.of("/tmp"), "ml-untrusted-");
    try {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
      UnixDomainSocketAddress address = UnixDomainSocketAddress.of(directory.resolve("endpoint.sock"));
      assertThrows(IOException.class, () -> MemoryServer.listen(endpoint(address)));
    } finally {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
      Files.deleteIfExists(directory);
    }
  }

  private static boolean linuxSupported() {
    String os = System.getProperty("os.name", "");
    String architecture = System.getProperty("os.arch", "");
    return os.equalsIgnoreCase("Linux")
        && (architecture.equals("amd64") || architecture.equals("x86_64")
            || architecture.equals("aarch64"));
  }

  private static MemoryEndpointConfig endpoint(UnixDomainSocketAddress address) {
    return MemoryEndpointConfig.builder()
        .transport(MemoryTransportType.SHARED_MEMORY)
        .address(address)
        .slotSize(1024)
        .slotCount(8)
        .maxConnections(16)
        .maxNegotiations(8)
        .build();
  }

  static byte[] readExactly(MemoryConnection connection, int bytes) throws Exception {
    ByteBuffer destination = ByteBuffer.allocate(bytes);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (destination.hasRemaining() && System.nanoTime() < deadline) {
      if (connection.hasData()) {
        connection.read(destination);
      } else {
        Thread.sleep(1);
      }
    }
    assertFalse(destination.hasRemaining(), "Timed out reading shared-memory payload");
    return destination.array();
  }
}
