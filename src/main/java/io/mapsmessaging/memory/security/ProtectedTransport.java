/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.security;

import io.mapsmessaging.memory.ConnectionCloseNotifier;
import io.mapsmessaging.memory.MemoryTransport;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Framed authenticated records over the existing byte-stream ring.
 * A plaintext byte is reported written only after this wrapper has accepted
 * ownership of its complete authenticated record.
 */
public final class ProtectedTransport implements MemoryTransport, ConnectionCloseNotifier {

  private static final int MAX_FRAME = 13 + SecureSession.MAX_PLAINTEXT_BYTES + 32;
  // Keep each protected record within the smallest supported ring slot (256 - 24 bytes).
  private static final int PLAINTEXT_CHUNK = 128;
  private final MemoryTransport delegate;
  private final SecureSession session;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
  private ByteBuffer incomingFrame;
  private ByteBuffer readable;
  private ByteBuffer outgoingFrame;

  public ProtectedTransport(MemoryTransport delegate, SecureSession session) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.session = Objects.requireNonNull(session, "session");
  }

  @Override
  public synchronized int write(ByteBuffer source) throws IOException {
    ensureOpen();
    flushPending();
    if (outgoingFrame != null || !delegate.canWrite() || !source.hasRemaining()) {
      return 0;
    }
    int length = Math.min(PLAINTEXT_CHUNK, source.remaining());
    byte[] plaintext = new byte[length];
    ByteBuffer copy = source.duplicate();
    copy.get(plaintext);
    try {
      byte[] record = session.protect(plaintext);
      outgoingFrame = ByteBuffer.allocate(Integer.BYTES + record.length);
      outgoingFrame.putInt(record.length).put(record).flip();
      source.position(source.position() + length);
      flushPending();
      return length;
    } catch (GeneralSecurityException | RuntimeException exception) {
      fail(exception);
      throw new IOException("Protected record encryption failed", exception);
    }
  }

  @Override
  public synchronized int read(ByteBuffer destination) throws IOException {
    ensureOpen();
    if (!destination.hasRemaining()) {
      return 0;
    }
    if (readable != null && readable.hasRemaining()) {
      return copyPlaintext(destination);
    }
    if (incomingFrame == null) {
      delegate.read(lengthBuffer);
      if (lengthBuffer.hasRemaining()) {
        return 0;
      }
      lengthBuffer.flip();
      int length = lengthBuffer.getInt();
      lengthBuffer.clear();
      if (length < 29 || length > MAX_FRAME) {
        fail(new IOException("Invalid authenticated frame size"));
        throw new IOException("Invalid authenticated frame size");
      }
      incomingFrame = ByteBuffer.allocate(length);
    }
    delegate.read(incomingFrame);
    if (incomingFrame.hasRemaining()) {
      return 0;
    }
    try {
      readable = ByteBuffer.wrap(session.unprotect(incomingFrame.array()));
      incomingFrame = null;
      return copyPlaintext(destination);
    } catch (GeneralSecurityException | RuntimeException exception) {
      fail(exception);
      throw new IOException("Protected record verification failed", exception);
    } catch (IOException exception) {
      fail(exception);
      throw exception;
    }
  }

  private int copyPlaintext(ByteBuffer destination) {
    int count = Math.min(destination.remaining(), readable.remaining());
    int limit = readable.limit();
    readable.limit(readable.position() + count);
    try {
      destination.put(readable);
    } finally {
      readable.limit(limit);
    }
    if (!readable.hasRemaining()) {
      readable = null;
    }
    return count;
  }

  private void flushPending() throws IOException {
    if (outgoingFrame == null) {
      return;
    }
    try {
      delegate.write(outgoingFrame);
      if (!outgoingFrame.hasRemaining()) {
        outgoingFrame = null;
      }
    } catch (IOException | RuntimeException exception) {
      fail(exception);
      throw new IOException("Failed to write authenticated frame", exception);
    }
  }

  @Override
  public synchronized boolean hasData() {
    return !closed.get() && ((readable != null && readable.hasRemaining()) || delegate.hasData());
  }

  @Override
  public synchronized boolean canWrite() {
    if (closed.get()) {
      return false;
    }
    // A pending frame may be drained by a following write, even while
    // the underlying ring still has outstanding data.
    return delegate.canWrite();
  }

  @Override
  public String remoteAddress() {
    return delegate.remoteAddress();
  }

  @Override
  public void onClose(Runnable listener) {
    if (delegate instanceof ConnectionCloseNotifier notifier) {
      notifier.onClose(listener);
    } else if (closed.get()) {
      listener.run();
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    session.close();
    delegate.close();
  }

  private void fail(Throwable failure) {
    try {
      close();
    } catch (IOException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  private void ensureOpen() throws IOException {
    if (closed.get()) {
      throw new IOException("Protected transport is closed");
    }
  }
}
