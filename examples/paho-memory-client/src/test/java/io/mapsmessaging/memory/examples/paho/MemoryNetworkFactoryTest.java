/*
 * Copyright [ 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.examples.paho;

import io.mapsmessaging.memory.MemoryClient;
import io.mapsmessaging.memory.MemoryConnection;
import io.mapsmessaging.memory.MemoryConnectionConfig;
import io.mapsmessaging.memory.MemoryTransportType;
import io.mapsmessaging.memory.security.MemorySecurity;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryNetworkFactoryTest {
  @Test
  void both_paho_versions_discover_shm_and_rdma_factories() {
    assertTrue(ServiceLoader.load(org.eclipse.paho.client.mqttv3.spi.NetworkModuleFactory.class).stream()
        .map(ServiceLoader.Provider::get).anyMatch(factory -> factory.getSupportedUriSchemes().containsAll(java.util.Set.of("shm", "rdma"))));
    assertTrue(ServiceLoader.load(org.eclipse.paho.mqttv5.client.spi.NetworkModuleFactory.class).stream()
        .map(ServiceLoader.Provider::get).anyMatch(factory -> factory.getSupportedUriSchemes().containsAll(java.util.Set.of("shm", "rdma"))));
  }

  @Test
  void scoped_registration_never_overwrites_or_removes_another_configuration() throws Exception {
    String uri = "shm://registry-test";
    MemoryConnectionConfig config = MemoryConnectionConfig.sharedMemory(new InetSocketAddress("127.0.0.1", 9999));
    MemoryNetworkConfig.Registration first = MemoryNetworkConfig.register(uri, config);
    assertThrows(IllegalStateException.class, () -> MemoryNetworkConfig.register(uri, config));
    first.close();
    try (MemoryNetworkConfig.Registration second = MemoryNetworkConfig.register(uri, config)) {
      first.close();
      assertSame(config, MemoryNetworkConfig.resolve(URI.create(uri)));
    }
    assertThrows(java.io.IOException.class, () -> MemoryNetworkConfig.resolve(URI.create(uri)));
  }

  @Test
  void factory_passes_secure_config_unchanged_to_managed_client() throws Exception {
    KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    MemorySecurity security = MemorySecurity.builder().identity("client-a", keys.getPrivate(), keys.getPublic())
        .trust((peer, key) -> peer.equals("server-a") && key.equals(keys.getPublic())).build();
    MemoryConnectionConfig config = new MemoryConnectionConfig(MemoryTransportType.RDMA, new InetSocketAddress("127.0.0.1", 9999), 1234, security);
    MemoryConnection connection = mock(MemoryConnection.class);
    try (MemoryNetworkConfig.Registration registration = MemoryNetworkConfig.register("rdma://secure-test", config);
         MockedStatic<MemoryClient> clients = mockStatic(MemoryClient.class)) {
      clients.when(() -> MemoryClient.connect(config)).thenReturn(connection);
      org.eclipse.paho.client.mqttv3.internal.NetworkModule mqtt3 = org.eclipse.paho.client.mqttv3.internal.NetworkModuleService.createInstance(
          "rdma://secure-test", new org.eclipse.paho.client.mqttv3.MqttConnectOptions(), "mqtt3");
      mqtt3.start();
      mqtt3.stop();
      org.eclipse.paho.mqttv5.client.internal.NetworkModule mqtt5 = org.eclipse.paho.mqttv5.client.internal.NetworkModuleService.createInstance(
          "rdma://secure-test", new org.eclipse.paho.mqttv5.client.MqttConnectionOptions(), "mqtt5");
      mqtt5.start();
      mqtt5.stop();
      clients.verify(() -> MemoryClient.connect(config), times(2));
      verify(connection, times(2)).close();
    }
  }

  @Test
  void missing_registration_and_mismatched_transport_fail_closed() {
    MemoryConnectionConfig rdma = MemoryConnectionConfig.rdma(new InetSocketAddress("127.0.0.1", 9999));
    assertThrows(IllegalArgumentException.class, () -> MemoryNetworkConfig.register("shm://wrong", rdma));
    assertThrows(IllegalArgumentException.class, () -> MemoryNetworkConfig.register("tcp://wrong", rdma));
    assertThrows(IllegalArgumentException.class, () -> MemoryNetworkConfig.register("rdma://user:password@wrong", rdma));
    assertThrows(IllegalArgumentException.class, () -> MemoryNetworkConfig.register("rdma://wrong?security=false", rdma));
    assertThrows(java.io.IOException.class, () -> MemoryNetworkConfig.resolve(URI.create("rdma://not-registered")));
  }
}
