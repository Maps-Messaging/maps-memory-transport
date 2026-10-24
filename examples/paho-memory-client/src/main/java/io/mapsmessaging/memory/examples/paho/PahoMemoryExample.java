/*
 * Copyright [ 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.examples.paho;

import io.mapsmessaging.memory.MemoryConnectionConfig;
import io.mapsmessaging.memory.WindowsPipeAddress;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Publishes an MQTT 5 QoS 1 message through a configured memory transport. */
public final class PahoMemoryExample {
  private PahoMemoryExample() {
  }

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 4) {
      throw new IllegalArgumentException("Usage: <shm|pipe|rdma> <local-path|pipe-name|host:port> <topic> <message>");
    }
    String type = arguments[0];
    MemoryConnectionConfig config = switch (type) {
      case "shm" -> MemoryConnectionConfig.linuxSharedMemory(UnixDomainSocketAddress.of(arguments[1]));
      case "pipe" -> MemoryConnectionConfig.windowsSharedMemory(WindowsPipeAddress.of(arguments[1]));
      case "rdma" -> {
        URI address = URI.create("rdma://" + arguments[1]);
        if (address.getHost() == null || address.getPort() < 1 || address.getPort() > 65535 || address.getUserInfo() != null) {
          throw new IllegalArgumentException("RDMA requires a host and port");
        }
        yield MemoryConnectionConfig.rdma(new InetSocketAddress(address.getHost(), address.getPort()));
      }
      default -> throw new IllegalArgumentException("Unknown transport: " + type);
    };
    String serverURI = (type.equals("rdma") ? "rdma" : "shm") + "://example";
    try (MemoryNetworkConfig.Registration registration = MemoryNetworkConfig.register(serverURI, config)) {
      MqttClient client = new MqttClient(serverURI, "mem-" + UUID.randomUUID().toString().substring(0, 16), new MemoryPersistence());
      client.setTimeToWait(5000);
      try {
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setConnectionTimeout(5);
        client.connect(options);
        client.publish(arguments[2], arguments[3].getBytes(StandardCharsets.UTF_8), 1, false);
      } finally {
        try {
          if (client.isConnected()) {
            client.disconnect();
          }
        } finally {
          client.close();
        }
      }
    }
  }
}
