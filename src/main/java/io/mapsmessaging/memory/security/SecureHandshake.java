/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mutually authenticated ephemeral X25519 key agreement. Callers exchange both
 * offers over an existing bounded rendezvous and verify them before publishing
 * shared-memory descriptors or enabling application traffic.
 */
public final class SecureHandshake {

  private static final byte[] DOMAIN = "MapsMemoryAuthenticatedHandshake/v1".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] OFFER_DOMAIN = "MapsMemoryOffer/v1".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] SESSION_DOMAIN = "MapsMemorySession/v1".getBytes(StandardCharsets.US_ASCII);
  public static final int MAX_OFFER_BYTES = 4096;
  private static final int NONCE_BYTES = 32;
  private final MemorySecurity local;
  private final KeyPair ephemeral;
  private final byte[] nonce;
  private final byte[] encodedOffer;
  private boolean consumed;

  private SecureHandshake(MemorySecurity local) throws GeneralSecurityException {
    this.local = local;
    KeyPairGenerator generator = KeyPairGenerator.getInstance("X25519");
    ephemeral = generator.generateKeyPair();
    nonce = new byte[NONCE_BYTES];
    new SecureRandom().nextBytes(nonce);
    encodedOffer = encodeOffer();
  }

  public static SecureHandshake begin(MemorySecurity security) throws GeneralSecurityException {
    return new SecureHandshake(java.util.Objects.requireNonNull(security, "security"));
  }

  public byte[] offer() {
    return encodedOffer.clone();
  }

  /**
   * Finishes authenticated agreement. Client and server must use opposite
   * roles and provide the corresponding exact, signed offer bytes.
   */
  public synchronized SecureSession finish(byte[] peerOffer, boolean serverRole)
      throws GeneralSecurityException, IOException {
    if (consumed) {
      throw new IllegalStateException("Handshake instance is single-use");
    }
    consumed = true;
    Offer peer = decodeOffer(peerOffer);
    if (peer.protection != local.protection()) {
      throw new IOException("Secure-memory protection mode mismatch");
    }
    if (!local.trust().isTrusted(peer.identity, peer.identityKey)) {
      throw new IOException("Untrusted secure-memory peer");
    }
    if (MessageDigest.isEqual(encodedOffer, peerOffer)) {
      throw new IOException("Reflected secure-memory handshake offer");
    }
    byte[] clientOffer = serverRole ? peerOffer : encodedOffer;
    byte[] serverOffer = serverRole ? encodedOffer : peerOffer;
    byte[] transcript = hash(combine(DOMAIN, lengthPrefix(clientOffer), lengthPrefix(serverOffer)));
    KeyAgreement agreement = KeyAgreement.getInstance("X25519");
    agreement.init(ephemeral.getPrivate());
    agreement.doPhase(peer.ephemeralKey, true);
    byte[] secret = agreement.generateSecret();
    try {
      if (allZero(secret)) {
        throw new IOException("Invalid X25519 shared secret");
      }
      byte[] root = extract(transcript, secret);
      try {
        return SecureSession.derive(root, transcript, local.protection(), serverRole);
      } finally {
        Arrays.fill(root, (byte) 0);
      }
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  private byte[] encodeOffer() throws GeneralSecurityException {
    byte[] identity = local.identity().getBytes(StandardCharsets.UTF_8);
    byte[] verification = local.verificationKey().getEncoded();
    byte[] exchange = ephemeral.getPublic().getEncoded();
    if (identity.length == 0 || identity.length > 256 || verification == null || verification.length > 512
        || exchange.length > 512) {
      throw new GeneralSecurityException("Invalid handshake identity or public key encoding");
    }
    ByteBuffer unsigned = ByteBuffer.allocate(1 + 1 + 2 + identity.length + 2 + verification.length
        + 2 + exchange.length + NONCE_BYTES);
    unsigned.put((byte) 1);
    unsigned.put((byte) local.protection().ordinal());
    putBytes(unsigned, identity);
    putBytes(unsigned, verification);
    putBytes(unsigned, exchange);
    unsigned.put(nonce);
    byte[] body = unsigned.array();
    Signature signature = Signature.getInstance("Ed25519");
    signature.initSign(local.signingKey());
    signature.update(OFFER_DOMAIN);
    signature.update(body);
    byte[] signed = signature.sign();
    ByteBuffer result = ByteBuffer.allocate(body.length + 2 + signed.length);
    result.put(body);
    putBytes(result, signed);
    return result.array();
  }

  private static Offer decodeOffer(byte[] encoded) throws GeneralSecurityException, IOException {
    if (encoded == null || encoded.length < 100 || encoded.length > MAX_OFFER_BYTES) {
      throw new IOException("Invalid handshake offer length");
    }
    ByteBuffer buffer = ByteBuffer.wrap(encoded);
    if (Byte.toUnsignedInt(buffer.get()) != 1) {
      throw new IOException("Unsupported secure-memory handshake version");
    }
    int mode = Byte.toUnsignedInt(buffer.get());
    if (mode >= DataProtection.values().length) {
      throw new IOException("Unsupported secure-memory protection mode");
    }
    byte[] identityBytes = readBytes(buffer, 256);
    byte[] keyBytes = readBytes(buffer, 512);
    byte[] exchangeBytes = readBytes(buffer, 512);
    if (buffer.remaining() < NONCE_BYTES + 2 + 64) {
      throw new IOException("Truncated authenticated handshake offer");
    }
    buffer.position(buffer.position() + NONCE_BYTES);
    int signedBodyLength = buffer.position();
    byte[] signatureBytes = readBytes(buffer, 128);
    if (signatureBytes.length != 64 || buffer.hasRemaining()) {
      throw new IOException("Invalid secure-memory signature framing");
    }
    PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
    PublicKey exchangeKey = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(exchangeBytes));
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(key);
    verifier.update(OFFER_DOMAIN);
    verifier.update(encoded, 0, signedBodyLength);
    if (!verifier.verify(signatureBytes)) {
      throw new IOException("Invalid Ed25519 handshake signature");
    }
    String identity = new String(identityBytes, StandardCharsets.UTF_8);
    if (!java.util.Arrays.equals(identity.getBytes(StandardCharsets.UTF_8), identityBytes)) {
      throw new IOException("Invalid UTF-8 peer identity");
    }
    if (identity.isBlank() || identityBytes.length == 0) {
      throw new IOException("Invalid peer identity");
    }
    return new Offer(identity, key, exchangeKey, DataProtection.values()[mode]);
  }

  private static void putBytes(ByteBuffer buffer, byte[] data) {
    buffer.putShort((short) data.length);
    buffer.put(data);
  }

  private static byte[] readBytes(ByteBuffer buffer, int maximum) throws IOException {
    if (buffer.remaining() < 2) {
      throw new IOException("Truncated handshake field");
    }
    int length = Short.toUnsignedInt(buffer.getShort());
    if (length == 0 || length > maximum || length > buffer.remaining()) {
      throw new IOException("Invalid handshake field length");
    }
    byte[] bytes = new byte[length];
    buffer.get(bytes);
    return bytes;
  }

  private static byte[] lengthPrefix(byte[] value) {
    ByteBuffer output = ByteBuffer.allocate(Integer.BYTES + value.length);
    output.putInt(value.length).put(value);
    return output.array();
  }

  static byte[] combine(byte[]... arrays) {
    int size = 0;
    for (byte[] array : arrays) {
      size = Math.addExact(size, array.length);
    }
    ByteBuffer buffer = ByteBuffer.allocate(size);
    for (byte[] array : arrays) {
      buffer.put(array);
    }
    return buffer.array();
  }

  static byte[] hash(byte[] input) throws GeneralSecurityException {
    return MessageDigest.getInstance("SHA-256").digest(input);
  }

  static byte[] extract(byte[] salt, byte[] input) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(salt, "HmacSHA256"));
    return mac.doFinal(input);
  }

  static byte[] expand(byte[] root, byte[] info, int length) throws GeneralSecurityException {
    if (length < 1 || length > 255 * 32) {
      throw new GeneralSecurityException("Invalid HKDF output length");
    }
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(root, "HmacSHA256"));
    byte[] result = new byte[length];
    byte[] previous = new byte[0];
    int offset = 0;
    for (int counter = 1; offset < length; counter++) {
      mac.reset();
      mac.update(previous);
      mac.update(SESSION_DOMAIN);
      mac.update(info);
      mac.update((byte) counter);
      byte[] next = mac.doFinal();
      int copied = Math.min(next.length, length - offset);
      System.arraycopy(next, 0, result, offset, copied);
      offset += copied;
      Arrays.fill(previous, (byte) 0);
      previous = next;
    }
    Arrays.fill(previous, (byte) 0);
    return result;
  }

  private static boolean allZero(byte[] value) {
    int aggregate = 0;
    for (byte element : value) {
      aggregate |= element;
    }
    return aggregate == 0;
  }

  private record Offer(String identity, PublicKey identityKey, PublicKey ephemeralKey, DataProtection protection) {}
}
