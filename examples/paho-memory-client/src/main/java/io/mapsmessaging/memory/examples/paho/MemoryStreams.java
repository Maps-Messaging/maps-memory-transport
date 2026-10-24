/*
 * Copyright [ 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.examples.paho;

import io.mapsmessaging.memory.MemoryTransport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Blocking stream contract over nonblocking memory I/O. One reader and one writer may run concurrently. */
final class MemoryStreams implements Closeable {
  private final MemoryTransport transport;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final InputStream input = new MemoryInput();
  private final OutputStream output = new MemoryOutput();

  MemoryStreams(MemoryTransport transport) {
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  InputStream input() {
    return input;
  }

  OutputStream output() {
    return output;
  }

  @Override
  public void close() throws IOException {
    if (closed.compareAndSet(false, true)) {
      transport.close();
    }
  }

  private void ensureOpen() throws IOException {
    if (closed.get()) {
      throw new IOException("Memory stream is closed");
    }
  }

  private static void checkInterrupted() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedIOException("Interrupted waiting for memory transport I/O");
    }
  }

  private static long pause(long delay) throws InterruptedIOException {
    checkInterrupted();
    LockSupport.parkNanos(delay);
    checkInterrupted();
    return Math.min(1_000_000, delay * 2);
  }

  private void failed(IOException failure) {
    try {
      close();
    } catch (IOException cleanup) {
      failure.addSuppressed(cleanup);
    }
  }

  private final class MemoryInput extends InputStream {
    private final byte[] singleByte = new byte[1];
    private boolean eof;

    @Override
    public synchronized int read() throws IOException {
      int count = read(singleByte, 0, 1);
      return count < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
    }

    @Override
    public synchronized int read(byte[] destination, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, destination.length);
      if (length == 0) {
        return 0;
      }
      if (eof) {
        return -1;
      }
      ByteBuffer buffer = ByteBuffer.wrap(destination, offset, length);
      long delay = 1_000;
      try {
        while (true) {
          ensureOpen();
          checkInterrupted();
          int count = transport.read(buffer);
          if (count < 0) {
            eof = true;
            MemoryStreams.this.close();
            return -1;
          }
          if (count > 0) {
            return count;
          }
          delay = pause(delay);
        }
      } catch (IOException failure) {
        failed(failure);
        throw failure;
      }
    }

    @Override
    public void close() throws IOException {
      MemoryStreams.this.close();
    }
  }

  private final class MemoryOutput extends OutputStream {
    private final byte[] singleByte = new byte[1];

    @Override
    public synchronized void write(int value) throws IOException {
      singleByte[0] = (byte) value;
      write(singleByte, 0, 1);
    }

    @Override
    public synchronized void write(byte[] source, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, source.length);
      ByteBuffer buffer = ByteBuffer.wrap(source, offset, length);
      long delay = 1_000;
      try {
        ensureOpen();
        while (buffer.hasRemaining()) {
          ensureOpen();
          checkInterrupted();
          int count = transport.write(buffer);
          if (count < 0) {
            throw new IOException("Memory transport rejected the write");
          }
          if (count == 0) {
            delay = pause(delay);
          } else {
            delay = 1_000;
          }
        }
      } catch (IOException failure) {
        if (failure instanceof InterruptedIOException interrupted) {
          interrupted.bytesTransferred = buffer.position() - offset;
        }
        // A failed/interrupted write can leave a protected frame pending; never reuse that connection.
        failed(failure);
        throw failure;
      }
    }

    @Override
    public void close() throws IOException {
      MemoryStreams.this.close();
    }
  }
}
