/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.mapsmessaging.memory.MemoryTransport;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayDeque;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class ProtectedTransportHardeningTest {

  @Test
  void shortWritesDoNotPrematurelyAdvanceSourceOrDropFinalFrame() throws Exception {
    SessionPair pair = createSessions();
    byte[] message = new byte[487];
    for (int index = 0; index < message.length; index++) {
      message[index] = (byte) (index * 17);
    }
    BytePipe pipe = new BytePipe(1);
    try (ProtectedTransport sender = new ProtectedTransport(pipe, pair.client());
         ProtectedTransport receiver = new ProtectedTransport(new BytePipeReader(pipe), pair.server())) {
      ByteBuffer source = ByteBuffer.wrap(message);
      ByteBuffer destination = ByteBuffer.allocate(message.length);
      int attempts = 0;
      while ((source.hasRemaining() || destination.hasRemaining()) && attempts++ < 100_000) {
        if (source.hasRemaining() && sender.canWrite()) {
          sender.write(source);
        }
        if (receiver.hasData()) {
          receiver.read(destination);
        }
      }
      assertFalse(source.hasRemaining(), "Source was not completely delivered");
      assertFalse(destination.hasRemaining(), "Destination did not receive all authenticated records");
      assertArrayEquals(message, destination.array());
    }
  }

  @Test
  void incompleteFrameCannotBeMistakenForPlaintext() throws Exception {
    SessionPair pair = createSessions();
    BytePipe pipe = new BytePipe(1);
    try (ProtectedTransport sender = new ProtectedTransport(pipe, pair.client());
         ProtectedTransport receiver = new ProtectedTransport(new BytePipeReader(pipe), pair.server())) {
      ByteBuffer source = ByteBuffer.wrap(new byte[] {11, 22, 33});
      assertEquals(0, sender.write(source));
      assertEquals(0, source.position());
      assertEquals(0, receiver.read(ByteBuffer.allocate(3)));
    }
  }

  @Test
  void malformedFrameLengthsFailClosed() throws Exception {
    int[] malformed = {0, -1, 1, 28, Integer.MAX_VALUE};
    for (int length : malformed) {
      SessionPair pair = createSessions();
      BytePipe pipe = new BytePipe(4096);
      ByteBuffer header = ByteBuffer.allocate(4).putInt(length);
      header.flip();
      pipe.write(header);
      try (ProtectedTransport receiver = new ProtectedTransport(new BytePipeReader(pipe), pair.server())) {
        assertThrows(IOException.class, () -> receiver.read(ByteBuffer.allocate(64)));
        assertThrows(IOException.class, () -> receiver.read(ByteBuffer.allocate(64)));
      } finally {
        pair.client().close();
      }
    }
  }

  @Test
  void corruptedCiphertextNeverReachesApplication() throws Exception {
    SessionPair pair = createSessions();
    BytePipe pipe = new BytePipe(4096);
    byte[] encrypted = pair.client().protect(new byte[] {3, 7, 11});
    encrypted[encrypted.length - 1] ^= 0x40;
    ByteBuffer frame = ByteBuffer.allocate(4 + encrypted.length);
    frame.putInt(encrypted.length).put(encrypted).flip();
    pipe.write(frame);
    try (ProtectedTransport receiver = new ProtectedTransport(new BytePipeReader(pipe), pair.server())) {
      ByteBuffer destination = ByteBuffer.allocate(3);
      assertThrows(IOException.class, () -> receiver.read(destination));
      assertEquals(0, destination.position());
      assertThrows(IOException.class, () -> receiver.read(destination));
    } finally {
      pair.client().close();
    }
  }

  @Test
  void readOnlyOrNonWritableDelegateDoesNotConsumeCallerBytes() throws Exception {
    SessionPair pair = createSessions();
    BytePipe pipe = new BytePipe(1);
    pipe.close();
    try (ProtectedTransport sender = new ProtectedTransport(pipe, pair.client())) {
      ByteBuffer source = ByteBuffer.wrap(new byte[] {1, 2, 3});
      assertEquals(0, sender.write(source));
      assertEquals(0, source.position());
      assertFalse(sender.canWrite());
    } finally {
      pair.server().close();
    }
  }

  private static SessionPair createSessions() throws Exception {
    KeyPair clientKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair serverKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    MemorySecurity client = MemorySecurity.builder()
        .identity("client", clientKey.getPrivate(), clientKey.getPublic())
        .trust((id, key) -> "server".equals(id) && key.equals(serverKey.getPublic()))
        .protection(DataProtection.AES_256_GCM).build();
    MemorySecurity server = MemorySecurity.builder()
        .identity("server", serverKey.getPrivate(), serverKey.getPublic())
        .trust((id, key) -> "client".equals(id) && key.equals(clientKey.getPublic()))
        .protection(DataProtection.AES_256_GCM).build();
    SecureHandshake outgoing = SecureHandshake.begin(client);
    SecureHandshake incoming = SecureHandshake.begin(server);
    SecureSession clientSession = outgoing.finish(incoming.offer(), false);
    SecureSession serverSession = incoming.finish(outgoing.offer(), true);
    clientSession.verifyConfirmation(serverSession.confirmation());
    serverSession.verifyConfirmation(clientSession.confirmation());
    return new SessionPair(clientSession, serverSession);
  }

  private record SessionPair(SecureSession client, SecureSession server) {}

  private static final class BytePipe implements MemoryTransport {
    private final Queue<Byte> bytes = new ArrayDeque<>();
    private final int limit;
    private boolean closed;

    private BytePipe(int limit) {
      this.limit = limit;
    }

    @Override
    public int write(ByteBuffer source) throws IOException {
      if (closed) {
        throw new IOException("Pipe closed");
      }
      int count = Math.min(limit, source.remaining());
      for (int index = 0; index < count; index++) {
        bytes.add(source.get());
      }
      return count;
    }

    @Override
    public int read(ByteBuffer destination) {
      int count = 0;
      while (destination.hasRemaining() && !bytes.isEmpty()) {
        destination.put(bytes.remove());
        count++;
      }
      return count;
    }

    @Override
    public boolean hasData() {
      return !bytes.isEmpty();
    }

    @Override
    public boolean canWrite() {
      return !closed;
    }

    @Override
    public String remoteAddress() {
      return "test-pipe";
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final class BytePipeReader implements MemoryTransport {
    private final BytePipe pipe;

    private BytePipeReader(BytePipe pipe) {
      this.pipe = pipe;
    }

    @Override
    public int write(ByteBuffer source) {
      throw new UnsupportedOperationException("Read-only test endpoint");
    }

    @Override
    public int read(ByteBuffer destination) {
      return pipe.read(destination);
    }

    @Override
    public boolean hasData() {
      return pipe.hasData();
    }

    @Override
    public boolean canWrite() {
      return false;
    }

    @Override
    public String remoteAddress() {
      return "test-reader";
    }

    @Override
    public void close() {}
  }
}
