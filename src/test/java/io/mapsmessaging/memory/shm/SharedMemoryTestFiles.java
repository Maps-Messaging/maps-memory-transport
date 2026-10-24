/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.shm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

final class SharedMemoryTestFiles {

  private SharedMemoryTestFiles() {}

  static void awaitFile(Path path, int timeoutSeconds) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    try (WatchService watcher = path.getFileSystem().newWatchService()) {
      path.toAbsolutePath().getParent().register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
      while (!Files.exists(path)) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new AssertionError("timed out waiting for " + path);
        }
        WatchKey key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
        if (key != null) {
          key.pollEvents();
          if (!key.reset()) {
            throw new AssertionError("marker directory is no longer accessible: " + path.getParent());
          }
        }
      }
    }
  }
}
