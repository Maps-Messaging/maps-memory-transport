/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

import java.io.IOException;

public class PeerGenerationChangedException extends IOException {

  private final long previousGeneration;
  private final long currentGeneration;

  public PeerGenerationChangedException(long previousGeneration, long currentGeneration) {
    super("Memory transport peer generation changed from " + previousGeneration + " to " + currentGeneration);
    this.previousGeneration = previousGeneration;
    this.currentGeneration = currentGeneration;
  }

  public long getPreviousGeneration() {
    return previousGeneration;
  }

  public long getCurrentGeneration() {
    return currentGeneration;
  }
}
