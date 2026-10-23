/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.security;

import java.security.PrivateKey;
import java.security.interfaces.EdECKey;
import java.security.PublicKey;
import java.util.Objects;

/**
 * Immutable identity, trust and minimum protection policy for a future authenticated
 * memory connection. Private keys may be provider-backed and need not be exportable.
 * Session secrets and ephemeral agreement keys are never stored here.
 */
public final class MemorySecurity {

  private final String identity;
  private final PrivateKey signingKey;
  private final PublicKey verificationKey;
  private final PeerTrust trust;
  private final DataProtection protection;

  private MemorySecurity(Builder builder) {
    identity = requireIdentity(builder.identity);
    signingKey = Objects.requireNonNull(builder.signingKey, "signingKey");
    verificationKey = Objects.requireNonNull(builder.verificationKey, "verificationKey");
    trust = Objects.requireNonNull(builder.trust, "trust");
    protection = Objects.requireNonNull(builder.protection, "protection");
    requireEd25519(signingKey, "signingKey");
    requireEd25519(verificationKey, "verificationKey");
  }

  public static Builder builder() {
    return new Builder();
  }

  public String identity() {
    return identity;
  }

  public PrivateKey signingKey() {
    return signingKey;
  }

  public PublicKey verificationKey() {
    return verificationKey;
  }

  public PeerTrust trust() {
    return trust;
  }

  public DataProtection protection() {
    return protection;
  }

  private static String requireIdentity(String identity) {
    if (identity == null || identity.isBlank()) {
      throw new IllegalArgumentException("identity must not be blank");
    }
    return identity;
  }

  private static void requireEd25519(java.security.Key key, String field) {
    if (!(key instanceof EdECKey edKey) || !"Ed25519".equals(edKey.getParams().getName())) {
      throw new IllegalArgumentException(field + " must use Ed25519");
    }
  }

  public static final class Builder {

    private String identity;
    private PrivateKey signingKey;
    private PublicKey verificationKey;
    private PeerTrust trust;
    private DataProtection protection = DataProtection.AES_256_GCM;

    private Builder() {}

    public Builder identity(String identity, PrivateKey signingKey, PublicKey verificationKey) {
      this.identity = identity;
      this.signingKey = signingKey;
      this.verificationKey = verificationKey;
      return this;
    }

    public Builder trust(PeerTrust trust) {
      this.trust = trust;
      return this;
    }

    public Builder protection(DataProtection protection) {
      this.protection = Objects.requireNonNull(protection, "protection");
      return this;
    }

    public MemorySecurity build() {
      return new MemorySecurity(this);
    }
  }
}
