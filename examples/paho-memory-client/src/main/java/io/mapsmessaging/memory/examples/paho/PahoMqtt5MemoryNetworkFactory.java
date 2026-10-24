/*
 * Copyright [ 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.examples.paho;

import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.client.internal.NetworkModule;
import org.eclipse.paho.mqttv5.client.spi.NetworkModuleFactory;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

public final class PahoMqtt5MemoryNetworkFactory implements NetworkModuleFactory {
  @Override
  public Set<String> getSupportedUriSchemes() {
    return MemoryNetworkConfig.SCHEMES;
  }

  @Override
  public void validateURI(URI uri) {
    MemoryNetworkConfig.validate(uri);
  }

  @Override
  public NetworkModule createNetworkModule(URI uri, MqttConnectionOptions options, String clientId) throws MqttException {
    try {
      return new MemoryNetworkModule(uri.toString(), MemoryNetworkConfig.resolve(uri));
    } catch (IOException failure) {
      throw new MqttException(failure);
    }
  }
}
