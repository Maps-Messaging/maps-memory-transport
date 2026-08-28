/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm;

final class SharedMemoryCrashOwner {

  private SharedMemoryCrashOwner() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      Runtime.getRuntime().halt(2);
    }
    new SharedMemoryTransport(args[0], true, 1024, 8);
    Runtime.getRuntime().halt(0);
  }
}
