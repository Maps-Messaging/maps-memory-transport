/*
 * Copyright [ 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.examples.paho;

import io.mapsmessaging.memory.MemoryClient;
import io.mapsmessaging.memory.MemoryConnectionConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** The two Paho interfaces have the same stream contract; all native work remains in MemoryClient. */
final class MemoryNetworkModule implements org.eclipse.paho.client.mqttv3.internal.NetworkModule,
    org.eclipse.paho.mqttv5.client.internal.NetworkModule {
  private final String serverURI;
  private final MemoryConnectionConfig config;
  private MemoryStreams streams;

  MemoryNetworkModule(String serverURI, MemoryConnectionConfig config) {
    this.serverURI = serverURI;
    this.config = config;
  }

  @Override
  public synchronized void start() throws IOException {
    if (streams != null) {
      throw new IOException("Memory network module is already started");
    }
    streams = new MemoryStreams(MemoryClient.connect(config));
  }

  @Override
  public synchronized InputStream getInputStream() throws IOException {
    return requireStarted().input();
  }

  @Override
  public synchronized OutputStream getOutputStream() throws IOException {
    return requireStarted().output();
  }

  @Override
  public synchronized void stop() throws IOException {
    if (streams != null) {
      try {
        streams.close();
      } finally {
        streams = null;
      }
    }
  }

  @Override
  public String getServerURI() {
    return serverURI;
  }

  private MemoryStreams requireStarted() throws IOException {
    if (streams == null) {
      throw new IOException("Memory network module is not started");
    }
    return streams;
  }
}
