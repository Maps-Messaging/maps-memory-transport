/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * Authenticates a peer over the already OS-authenticated native control channel.
 * The fixed client/server ordering prevents either side waiting for the other.
 * No native data transport may be published before this method succeeds.
 */
public final class SecureWire {

  private static final int MAX_OFFER_BYTES = SecureHandshake.MAX_OFFER_BYTES;
  private static final int CONFIRMATION_BYTES = 32;

  @FunctionalInterface
  public interface ByteReader {
    int read() throws IOException;
  }

  @FunctionalInterface
  public interface ByteWriter {
    void write(int value) throws IOException;
  }

  private SecureWire() {}

  public static SecureSession authenticate(MemorySecurity security, boolean server,
      ByteReader reader, ByteWriter writer) throws IOException {
    Objects.requireNonNull(security, "security");
    Objects.requireNonNull(reader, "reader");
    Objects.requireNonNull(writer, "writer");
    SecureSession session = null;
    try {
      SecureHandshake handshake = SecureHandshake.begin(security);
      byte[] offered = handshake.offer();
      byte[] received;
      if (server) {
        writeOffer(offered, writer);
        received = readOffer(reader);
      } else {
        received = readOffer(reader);
        writeOffer(offered, writer);
      }
      session = handshake.finish(received, server);
      if (server) {
        send(session.confirmation(), writer);
        session.verifyConfirmation(readFixed(reader, CONFIRMATION_BYTES));
      } else {
        session.verifyConfirmation(readFixed(reader, CONFIRMATION_BYTES));
        send(session.confirmation(), writer);
      }
      return session;
    } catch (GeneralSecurityException | RuntimeException exception) {
      if (session != null) {
        session.close();
      }
      throw new IOException("Authenticated memory handshake failed", exception);
    } catch (IOException exception) {
      if (session != null) {
        session.close();
      }
      throw exception;
    }
  }

  private static void writeOffer(byte[] bytes, ByteWriter writer) throws IOException {
    if (bytes.length == 0 || bytes.length > MAX_OFFER_BYTES) {
      throw new IOException("Handshake offer exceeds maximum length");
    }
    writer.write(bytes.length >>> 8);
    writer.write(bytes.length);
    send(bytes, writer);
  }

  private static byte[] readOffer(ByteReader reader) throws IOException {
    int length = (checkedByte(reader) << 8) | checkedByte(reader);
    if (length == 0 || length > MAX_OFFER_BYTES) {
      throw new IOException("Unacceptable handshake offer size");
    }
    return readFixed(reader, length);
  }

  private static byte[] readFixed(ByteReader reader, int length) throws IOException {
    byte[] output = new byte[length];
    for (int index = 0; index < length; index++) {
      output[index] = (byte) checkedByte(reader);
    }
    return output;
  }

  private static int checkedByte(ByteReader reader) throws IOException {
    int value = reader.read();
    if (value < 0 || value > 255) {
      throw new IOException("Invalid authenticated control byte");
    }
    return value;
  }

  private static void send(byte[] bytes, ByteWriter writer) throws IOException {
    for (byte value : bytes) {
      writer.write(Byte.toUnsignedInt(value));
    }
  }
}
