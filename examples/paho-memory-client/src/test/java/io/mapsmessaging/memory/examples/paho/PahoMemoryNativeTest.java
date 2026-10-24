/*
 * Copyright [ 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.examples.paho;

import io.mapsmessaging.memory.MemoryConnection;
import io.mapsmessaging.memory.MemoryConnectionConfig;
import io.mapsmessaging.memory.MemoryEndpointConfig;
import io.mapsmessaging.memory.MemoryServer;
import io.mapsmessaging.memory.MemoryTransportType;
import io.mapsmessaging.memory.WindowsPipeAddress;
import io.mapsmessaging.memory.rdma.RdmaSupport;
import io.mapsmessaging.memory.security.DataProtection;
import io.mapsmessaging.memory.security.MemorySecurity;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Paho clients against MemoryServer directly; the peer below is a deliberately minimal MQTT test stub. */
class PahoMemoryNativeTest {
  @TestFactory
  @Tag("native-memory")
  List<DynamicTest> mqtt3_and_mqtt5_over_native_memory() {
    List<DynamicTest> tests = new ArrayList<>();
    for (int version : new int[] {3, 5}) {
      tests.add(DynamicTest.dynamicTest("MQTT " + version + " unsecured", () -> nativeExchange(version, null)));
      for (DataProtection protection : DataProtection.values()) {
        tests.add(DynamicTest.dynamicTest("MQTT " + version + " " + protection, () -> nativeExchange(version, protection)));
      }
    }
    return tests;
  }

  @Test
  void mqtt5_over_authenticated_rdma_when_hardware_is_configured() throws Exception {
    String address = System.getenv("MAPS_RDMA_TEST_ADDRESS");
    assumeTrue(address != null && !address.isBlank(), "Set MAPS_RDMA_TEST_ADDRESS to exercise actual RDMA");
    assumeTrue(RdmaSupport.probe().available(), "RDMA is unavailable");
    exchange(5, MemoryTransportType.RDMA, new InetSocketAddress(address, 0), DataProtection.AES_256_GCM);
  }

  private void nativeExchange(int version, DataProtection protection) throws Exception {
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    Path directory;
    if (windows) {
      Files.createDirectories(Path.of("target/data"));
      directory = Files.createTempDirectory(Path.of("target/data"), "paho-");
    } else {
      directory = Files.createTempDirectory(Path.of("/tmp"), "mp-",
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    try {
      SocketAddress address;
      if (windows) {
        address = WindowsPipeAddress.of("paho-" + UUID.randomUUID());
      } else {
        address = UnixDomainSocketAddress.of(directory.resolve("s"));
      }
      exchange(version, MemoryTransportType.SHARED_MEMORY, address, protection);
    } finally {
      try (Stream<Path> paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private void exchange(int version, MemoryTransportType type, SocketAddress address, DataProtection protection) throws Exception {
    MemorySecurity[] security = protection == null ? new MemorySecurity[] {null, null} : MemoryStreamsIntegrationTest.security(protection);
    MemoryEndpointConfig serverConfig = MemoryEndpointConfig.builder().transport(type).address(address).slotSize(256).slotCount(4)
        .handshakeTimeoutMillis(3000).security(security[0]).build();
    byte[] payload = new byte[40_000];
    for (int index = 0; index < payload.length; index++) {
      payload[index] = (byte) index;
    }
    String scheme = type == MemoryTransportType.RDMA ? "rdma" : "shm";
    String uri = scheme + "://test-" + UUID.randomUUID();
    CountDownLatch releasePeer = new CountDownLatch(1);
    try (MemoryServer server = MemoryServer.listen(serverConfig)) {
      MemoryConnectionConfig clientConfig = new MemoryConnectionConfig(type, server.localAddress(), 3000, security[1]);
      FutureTask<Void> peer = new FutureTask<>(() -> {
        try (MemoryConnection accepted = server.accept(); MemoryStreams streams = new MemoryStreams(accepted)) {
          mqttPeer(streams, version, payload);
          if (!releasePeer.await(10, TimeUnit.SECONDS)) {
            throw new IOException("Paho client did not finish before the test deadline");
          }
        }
        return null;
      });
      Thread thread = Thread.ofVirtual().start(peer);
      try (MemoryNetworkConfig.Registration registration = MemoryNetworkConfig.register(uri, clientConfig)) {
        if (version == 3) {
          mqtt3(uri, payload);
        } else {
          mqtt5(uri, payload);
        }
        releasePeer.countDown();
        peer.get(5, TimeUnit.SECONDS);
      } finally {
        releasePeer.countDown();
        peer.cancel(true);
        thread.join(2000);
      }
    }
  }

  private static void mqtt3(String uri, byte[] payload) throws Exception {
    org.eclipse.paho.client.mqttv3.MqttClient client = new org.eclipse.paho.client.mqttv3.MqttClient(uri, "paho-memory-3",
        new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
    client.setTimeToWait(5000);
    try {
      org.eclipse.paho.client.mqttv3.MqttConnectOptions options = new org.eclipse.paho.client.mqttv3.MqttConnectOptions();
      options.setConnectionTimeout(5);
      options.setMqttVersion(org.eclipse.paho.client.mqttv3.MqttConnectOptions.MQTT_VERSION_3_1_1);
      client.connect(options);
      client.publish("memory/example", payload, 1, false);
      client.disconnect();
    } finally {
      if (client.isConnected()) {
        client.disconnectForcibly();
      }
      client.close();
    }
  }

  private static void mqtt5(String uri, byte[] payload) throws Exception {
    org.eclipse.paho.mqttv5.client.MqttClient client = new org.eclipse.paho.mqttv5.client.MqttClient(uri, "paho-memory-5",
        new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
    client.setTimeToWait(5000);
    try {
      org.eclipse.paho.mqttv5.client.MqttConnectionOptions options = new org.eclipse.paho.mqttv5.client.MqttConnectionOptions();
      options.setConnectionTimeout(5);
      client.connect(options);
      client.publish("memory/example", payload, 1, false);
      client.disconnect();
    } finally {
      if (client.isConnected()) {
        client.disconnectForcibly();
      }
      client.close();
    }
  }

  private static void mqttPeer(MemoryStreams streams, int version, byte[] expectedPayload) throws Exception {
    Frame connect = readFrame(streams.input());
    assertEquals(0x10, connect.header);
    assertEquals(version == 3 ? 4 : 5, Byte.toUnsignedInt(connect.body[6]));
    streams.output().write(version == 3 ? new byte[] {0x20, 2, 0, 0} : new byte[] {0x20, 3, 0, 0, 0});
    Frame publish = readFrame(streams.input());
    assertEquals(0x32, publish.header);
    int topicLength = (Byte.toUnsignedInt(publish.body[0]) << 8) | Byte.toUnsignedInt(publish.body[1]);
    assertEquals("memory/example", new String(publish.body, 2, topicLength, StandardCharsets.UTF_8));
    int idOffset = 2 + topicLength;
    int payloadOffset = idOffset + 2;
    if (version == 5) {
      assertEquals(0, publish.body[payloadOffset]);
      payloadOffset++;
    }
    assertArrayEquals(expectedPayload, Arrays.copyOfRange(publish.body, payloadOffset, publish.body.length));
    streams.output().write(new byte[] {0x40, 2, publish.body[idOffset], publish.body[idOffset + 1]});
  }

  private static Frame readFrame(InputStream input) throws IOException {
    int header = input.read();
    if (header < 0) {
      throw new EOFException("Peer closed before MQTT header");
    }
    int length = 0;
    int multiplier = 1;
    for (int count = 0; count < 4; count++) {
      int digit = input.read();
      if (digit < 0) {
        throw new EOFException("Peer closed in MQTT length");
      }
      length += (digit & 127) * multiplier;
      if (length > 1024 * 1024) {
        throw new IOException("MQTT test frame is too large");
      }
      if ((digit & 128) == 0) {
        byte[] body = input.readNBytes(length);
        if (body.length != length) {
          throw new EOFException("Truncated MQTT test frame");
        }
        return new Frame(header, body);
      }
      multiplier *= 128;
    }
    throw new IOException("Malformed MQTT remaining length");
  }

  private record Frame(int header, byte[] body) {
  }
}
