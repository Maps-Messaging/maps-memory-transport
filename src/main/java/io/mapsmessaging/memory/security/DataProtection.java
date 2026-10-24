/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.security;

/** Record protection negotiated for a secure memory transport connection. */
public enum DataProtection {
  HMAC_SHA_256,
  AES_256_GCM,
  CHACHA20_POLY1305
}
