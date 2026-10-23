/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory;

/** Internal lifecycle notification without exposing native handles or mapping resources. */
public interface ConnectionCloseNotifier {
  void onClose(Runnable listener);
}
