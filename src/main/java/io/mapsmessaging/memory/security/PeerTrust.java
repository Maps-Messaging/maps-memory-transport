/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.security;

import java.security.PublicKey;

/** Application-supplied policy for authenticating a remote signing identity. */
@FunctionalInterface
public interface PeerTrust {

  /**
   * Returns true only when the public key is explicitly authorised for the claimed peer.
   * Implementations must reject unknown peers and unexpected keys.
   */
  boolean isTrusted(String peerIdentity, PublicKey signingKey);
}
