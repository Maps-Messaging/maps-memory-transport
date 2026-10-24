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
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Single-use, directional protected-record state. Each session enforces ordered,
 * non-repeating sequence numbers and authenticates the complete header.
 * All records are bounded; malformed or unauthenticated data fails closed.
 */
public final class SecureSession implements AutoCloseable {

  private static final int HEADER_BYTES = 13;
  private static final int TAG_BYTES = 16;
  private static final int HMAC_BYTES = 32;
  public static final int MAX_PLAINTEXT_BYTES = 1024 * 1024;
  private final DataProtection protection;
  private final byte[] transcript;
  private final byte[] sendKey;
  private final byte[] receiveKey;
  private final byte[] sendNonce;
  private final byte[] receiveNonce;
  private final byte[] confirmationSend;
  private final byte[] confirmationReceive;
  private final AtomicBoolean closed = new AtomicBoolean();
  private boolean peerConfirmed;
  private long sendSequence;
  private long receiveSequence;

  private SecureSession(DataProtection protection, byte[] transcript, byte[] sendKey, byte[] receiveKey,
      byte[] sendNonce, byte[] receiveNonce, byte[] confirmationSend, byte[] confirmationReceive) {
    this.protection = protection;
    this.transcript = transcript.clone();
    this.sendKey = sendKey;
    this.receiveKey = receiveKey;
    this.sendNonce = sendNonce;
    this.receiveNonce = receiveNonce;
    this.confirmationSend = confirmationSend;
    this.confirmationReceive = confirmationReceive;
  }

  static SecureSession derive(byte[] root, byte[] transcript, DataProtection protection, boolean server)
      throws GeneralSecurityException {
    byte[] clientKey = SecureHandshake.expand(root, label("client record key", transcript), 32);
    byte[] serverKey = SecureHandshake.expand(root, label("server record key", transcript), 32);
    byte[] clientNonce = SecureHandshake.expand(root, label("client record nonce", transcript), 12);
    byte[] serverNonce = SecureHandshake.expand(root, label("server record nonce", transcript), 12);
    byte[] clientConfirmation = SecureHandshake.expand(root, label("client confirm", transcript), 32);
    byte[] serverConfirmation = SecureHandshake.expand(root, label("server confirm", transcript), 32);
    return server
        ? new SecureSession(protection, transcript, serverKey, clientKey, serverNonce, clientNonce,
            serverConfirmation, clientConfirmation)
        : new SecureSession(protection, transcript, clientKey, serverKey, clientNonce, serverNonce,
            clientConfirmation, serverConfirmation);
  }

  /** Send this 32-byte proof to the peer, then require a valid peer proof before application I/O. */
  public synchronized byte[] confirmation() throws GeneralSecurityException {
    ensureActive();
    return hmac(confirmationSend, transcript);
  }

  public synchronized void verifyConfirmation(byte[] confirmation) throws GeneralSecurityException, IOException {
    ensureActive();
    if (confirmation == null || confirmation.length != 32
        || !MessageDigest.isEqual(hmac(confirmationReceive, transcript), confirmation)) {
      close();
      throw new IOException("Secure-memory session key confirmation failed");
    }
    peerConfirmed = true;
  }

  public synchronized byte[] protect(byte[] plaintext) throws GeneralSecurityException, IOException {
    ensureConfirmed();
    if (plaintext == null || plaintext.length > MAX_PLAINTEXT_BYTES) {
      throw new IOException("Invalid protected-record payload length");
    }
    if (sendSequence == Long.MAX_VALUE) {
      close();
      throw new IOException("Secure-memory send sequence exhausted");
    }
    long sequence = sendSequence++;
    ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
    header.put((byte) 1).putLong(sequence).putInt(plaintext.length);
    byte[] associatedData = SecureHandshake.combine(transcript, header.array());
    byte[] ciphertext;
    if (protection == DataProtection.HMAC_SHA_256) {
      ciphertext = SecureHandshake.combine(plaintext, hmac(sendKey,
          SecureHandshake.combine(associatedData, plaintext)));
    } else {
      Cipher cipher = cipher(true, sendKey, sendNonce, sequence);
      cipher.updateAAD(associatedData);
      ciphertext = cipher.doFinal(plaintext);
    }
    return SecureHandshake.combine(header.array(), ciphertext);
  }

  public synchronized byte[] unprotect(byte[] record) throws GeneralSecurityException, IOException {
    ensureConfirmed();
    if (record == null || record.length < HEADER_BYTES + TAG_BYTES
        || record.length > HEADER_BYTES + MAX_PLAINTEXT_BYTES + HMAC_BYTES) {
      close();
      throw new IOException("Invalid protected-record length");
    }
    ByteBuffer buffer = ByteBuffer.wrap(record);
    int version = Byte.toUnsignedInt(buffer.get());
    long sequence = buffer.getLong();
    int plaintextLength = buffer.getInt();
    int tagLength = protection == DataProtection.HMAC_SHA_256 ? HMAC_BYTES : TAG_BYTES;
    if (version != 1 || sequence != receiveSequence || plaintextLength < 0
        || plaintextLength > MAX_PLAINTEXT_BYTES || buffer.remaining() != plaintextLength + tagLength) {
      close();
      throw new IOException("Unexpected protected-record version, sequence or size");
    }
    byte[] associatedData = SecureHandshake.combine(transcript, Arrays.copyOf(record, HEADER_BYTES));
    byte[] plaintext;
    try {
      if (protection == DataProtection.HMAC_SHA_256) {
        plaintext = Arrays.copyOfRange(record, HEADER_BYTES, HEADER_BYTES + plaintextLength);
        byte[] actualTag = Arrays.copyOfRange(record, HEADER_BYTES + plaintextLength, record.length);
        byte[] expectedTag = hmac(receiveKey, SecureHandshake.combine(associatedData, plaintext));
        if (!MessageDigest.isEqual(actualTag, expectedTag)) {
          throw new IOException("Protected-record MAC verification failed");
        }
      } else {
        Cipher cipher = cipher(false, receiveKey, receiveNonce, sequence);
        cipher.updateAAD(associatedData);
        plaintext = cipher.doFinal(record, HEADER_BYTES, record.length - HEADER_BYTES);
      }
    } catch (AEADBadTagException exception) {
      close();
      throw new IOException("Protected-record AEAD verification failed", exception);
    } catch (IOException exception) {
      close();
      throw exception;
    }
    receiveSequence++;
    return plaintext;
  }

  private void ensureConfirmed() throws IOException {
    ensureActive();
    if (!peerConfirmed) {
      throw new IOException("Secure-memory peer key confirmation is required");
    }
  }

  private Cipher cipher(boolean encrypt, byte[] key, byte[] baseNonce, long sequence)
      throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance(protection == DataProtection.AES_256_GCM
        ? "AES/GCM/NoPadding" : "ChaCha20-Poly1305");
    byte[] nonce = baseNonce.clone();
    for (int index = 0; index < Long.BYTES; index++) {
      nonce[nonce.length - 1 - index] ^= (byte) (sequence >>> (index * 8));
    }
    if (protection == DataProtection.AES_256_GCM) {
      cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
    } else {
      cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
    }
    Arrays.fill(nonce, (byte) 0);
    return cipher;
  }

  private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data);
  }

  private static byte[] label(String purpose, byte[] transcript) {
    return SecureHandshake.combine(purpose.getBytes(StandardCharsets.US_ASCII), transcript);
  }

  private void ensureActive() {
    if (closed.get()) {
      throw new IllegalStateException("Secure-memory session is closed");
    }
  }

  @Override
  public synchronized void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    Arrays.fill(sendKey, (byte) 0);
    Arrays.fill(receiveKey, (byte) 0);
    Arrays.fill(sendNonce, (byte) 0);
    Arrays.fill(receiveNonce, (byte) 0);
    Arrays.fill(confirmationSend, (byte) 0);
    Arrays.fill(confirmationReceive, (byte) 0);
  }
}
