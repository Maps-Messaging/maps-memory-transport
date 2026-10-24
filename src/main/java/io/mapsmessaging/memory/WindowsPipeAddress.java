/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import java.net.SocketAddress;
import java.util.Objects;
import java.util.regex.Pattern;

/** Local Windows named-pipe rendezvous address; no shared-memory mapping names are exposed. */
public final class WindowsPipeAddress extends SocketAddress {

  private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
  private final String name;

  public static WindowsPipeAddress of(String name) {
    return new WindowsPipeAddress(name);
  }

  private WindowsPipeAddress(String name) {
    if (name == null || !NAME_PATTERN.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid Windows named-pipe endpoint name");
    }
    this.name = name;
  }

  public String name() {
    return name;
  }

  public String pipeName() {
    return "\\\\.\\pipe\\maps-memory-" + name;
  }

  @Override
  public String toString() {
    return "shm-windows:" + name;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof WindowsPipeAddress address && name.equals(address.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name);
  }
}
