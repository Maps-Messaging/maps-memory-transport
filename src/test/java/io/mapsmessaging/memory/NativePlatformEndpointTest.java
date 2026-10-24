/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NativePlatformEndpointTest {

  @Test
  void macOsSupportsIndependentBidirectionalPeers() throws Exception {
    assumeTrue(macOs(), "macOS native test");
    Path directory = privateDirectory();
    Path socket = directory.resolve("endpoint.sock");
    try {
      verifyMultiplePeers(UnixDomainSocketAddress.of(socket));
    } finally {
      Files.deleteIfExists(socket);
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void windowsSupportsIndependentBidirectionalPeers() throws Exception {
    assumeTrue(windows(), "Windows native test");
    verifyMultiplePeers(WindowsPipeAddress.of("peer-" + UUID.randomUUID()));
  }

  @Test
  void macOsCommunicatesWithChildJvmAndDetectsCrash() throws Exception {
    assumeTrue(macOs(), "macOS native test");
    Path directory = privateDirectory();
    Path socket = directory.resolve("endpoint.sock");
    try {
      verifyChildProcess(UnixDomainSocketAddress.of(socket), socket.toString());
      verifyChildCrash(UnixDomainSocketAddress.of(socket), socket.toString());
    } finally {
      Files.deleteIfExists(socket);
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void windowsCommunicatesWithChildJvmAndDetectsCrash() throws Exception {
    assumeTrue(windows(), "Windows native test");
    String id = "process-" + UUID.randomUUID();
    verifyChildProcess(WindowsPipeAddress.of(id), id);
    verifyChildCrash(WindowsPipeAddress.of(id), id);
  }

  @Test
  void macOsClosesAnIdleListener() throws Exception {
    assumeTrue(macOs(), "macOS native test");
    Path directory = privateDirectory();
    Path socket = directory.resolve("idle.sock");
    try {
      MemoryServer server = MemoryServer.listen(endpoint(UnixDomainSocketAddress.of(socket)));
      server.close();
      assertTrue(server.isClosed());
      assertThrows(IOException.class, server::accept);
    } finally {
      Files.deleteIfExists(socket);
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void windowsClosesAnIdleListener() throws Exception {
    assumeTrue(windows(), "Windows native test");
    MemoryServer server = MemoryServer.listen(endpoint(WindowsPipeAddress.of("idle-" + UUID.randomUUID())));
    server.close();
    assertTrue(server.isClosed());
    assertThrows(IOException.class, server::accept);
  }

  @Test
  void nativeWindowsAddressFailsClosedOnOtherPlatforms() {
    assumeTrue(!windows(), "Windows native provider is available");
    WindowsPipeAddress address = WindowsPipeAddress.of("unavailable");
    assertThrows(IOException.class, () -> MemoryServer.listen(endpoint(address)));
    assertThrows(IOException.class, () -> MemoryClient.connect(connection(address)));
  }

  @Test
  void windowsPipeNamesRejectUnsafeCharacters() {
    assertThrows(IllegalArgumentException.class, () -> WindowsPipeAddress.of("../escape"));
    assertThrows(IllegalArgumentException.class, () -> WindowsPipeAddress.of("has spaces"));
    assertThrows(IllegalArgumentException.class, () -> WindowsPipeAddress.of(""));
  }

  @Test
  void macOsRejectsPublicControlDirectories() throws Exception {
    assumeTrue(macOs(), "macOS native test");
    Path directory = privateDirectory();
    try {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
      UnixDomainSocketAddress address = UnixDomainSocketAddress.of(directory.resolve("endpoint.sock"));
      assertThrows(IOException.class, () -> MemoryServer.listen(endpoint(address)));
    } finally {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
      Files.deleteIfExists(directory);
    }
  }

  private static void verifyMultiplePeers(SocketAddress address) throws Exception {
    try (MemoryServer server = MemoryServer.listen(endpoint(address))) {
      List<MemoryConnection> outgoing = new ArrayList<>();
      List<MemoryConnection> incoming = new ArrayList<>();
      try {
        for (int index = 0; index < 4; index++) {
          outgoing.add(MemoryClient.connect(connection(address)));
          incoming.add(server.accept());
        }
        for (int index = 0; index < 4; index++) {
          byte[] request = {(byte) index, 11, 19};
          byte[] response = {(byte) (index + 20), 42};
          assertTrue(outgoing.get(index).write(ByteBuffer.wrap(request)) > 0);
          assertArrayEquals(request, receive(incoming.get(index), request.length));
          assertTrue(incoming.get(index).write(ByteBuffer.wrap(response)) > 0);
          assertArrayEquals(response, receive(outgoing.get(index), response.length));
        }
      } finally {
        closeAll(outgoing);
        closeAll(incoming);
      }
    }
  }

  private static void verifyChildProcess(SocketAddress address, String addressValue) throws Exception {
    try (MemoryServer server = MemoryServer.listen(endpoint(address))) {
      Process child = startChild(addressValue, false);
      try (MemoryConnection peer = server.accept()) {
        assertArrayEquals(new byte[] {7, 8, 9}, receive(peer, 3));
        assertTrue(peer.write(ByteBuffer.wrap(new byte[] {4, 5, 6})) > 0);
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Child JVM did not terminate");
        assertTrue(child.exitValue() == 0, "Child JVM failed: " + child.exitValue());
      } finally {
        terminate(child);
      }
    }
  }

  private static void verifyChildCrash(SocketAddress address, String addressValue) throws Exception {
    try (MemoryServer server = MemoryServer.listen(endpoint(address))) {
      Process child = startChild(addressValue, true);
      try (MemoryConnection peer = server.accept()) {
        assertArrayEquals(new byte[] {7, 8, 9}, receive(peer, 3));
        child.destroyForcibly();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Unable to terminate child JVM");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!peer.isClosed() && System.nanoTime() < deadline) {
          Thread.sleep(20);
        }
        assertTrue(peer.isClosed(), "Peer crash was not detected");
      } finally {
        terminate(child);
      }
    }
  }

  private static byte[] receive(MemoryConnection connection, int length) throws Exception {
    ByteBuffer destination = ByteBuffer.allocate(length);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (destination.hasRemaining() && System.nanoTime() < deadline) {
      if (connection.hasData()) {
        connection.read(destination);
      } else {
        Thread.sleep(1);
      }
    }
    assertFalse(destination.hasRemaining(), "Timed out waiting for shared-memory data");
    return destination.array();
  }

  private static MemoryEndpointConfig endpoint(SocketAddress address) {
    return MemoryEndpointConfig.builder()
        .transport(MemoryTransportType.SHARED_MEMORY)
        .address(address)
        .slotSize(1024)
        .slotCount(8)
        .maxConnections(8)
        .maxNegotiations(4)
        .build();
  }

  private static MemoryConnectionConfig connection(SocketAddress address) {
    return new MemoryConnectionConfig(MemoryTransportType.SHARED_MEMORY, address, 5_000, null);
  }

  private static Path privateDirectory() throws IOException {
    return Files.createTempDirectory(Path.of("/tmp"), "maps-shm-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  }

  private static Process startChild(String address, boolean hold) throws IOException {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    return new ProcessBuilder(java.toString(), "--enable-native-access=ALL-UNNAMED",
        "-cp", System.getProperty("java.class.path"),
        NativePlatformChild.class.getName(),
        windows() ? "windows" : "macos", address, hold ? "hold" : "exchange")
        .inheritIO()
        .start();
  }

  private static void terminate(Process process) throws Exception {
    if (process.isAlive()) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
  }

  private static void closeAll(List<MemoryConnection> connections) throws IOException {
    IOException failure = null;
    for (MemoryConnection connection : connections) {
      try {
        connection.close();
      } catch (IOException exception) {
        if (failure == null) {
          failure = exception;
        } else {
          failure.addSuppressed(exception);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static boolean windows() {
    return System.getProperty("os.name", "").startsWith("Windows");
  }

  private static boolean macOs() {
    return System.getProperty("os.name", "").equalsIgnoreCase("Mac OS X");
  }
}
