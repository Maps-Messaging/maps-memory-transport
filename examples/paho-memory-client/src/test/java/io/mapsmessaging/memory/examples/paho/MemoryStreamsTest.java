/*
 * Copyright [ 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 * (the "License"); you may not use this file except in compliance with the License.
 */
package io.mapsmessaging.memory.examples.paho;

import io.mapsmessaging.memory.MemoryTransport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStreamsTest {
  @Test
  void input_never_reports_zero_for_nonempty_read_and_preserves_byte_values() throws Exception {
    PartialTransport transport = new PartialTransport(new byte[] {(byte) 255, 0, 7});
    try (MemoryStreams streams = new MemoryStreams(transport)) {
      assertEquals(255, streams.input().read());
      byte[] received = new byte[8];
      int count = streams.input().read(received, 2, 4);
      assertEquals(2, count);
      assertArrayEquals(new byte[] {0, 0, 0, 7, 0, 0, 0, 0}, received);
      assertEquals(-1, streams.input().read());
      assertEquals(0, streams.input().read(received, 0, 0));
    }
    assertEquals(1, transport.closes.get());
  }

  @Test
  void output_retries_zero_and_partial_writes_without_duplication() throws Exception {
    PartialTransport transport = new PartialTransport(new byte[0]);
    try (MemoryStreams streams = new MemoryStreams(transport)) {
      streams.output().write(new byte[] {9, 1, 2, 3, 4, 8}, 1, 4);
      streams.output().write(255);
      assertArrayEquals(new byte[] {1, 2, 3, 4, (byte) 255}, transport.written.toByteArray());
      streams.input().close();
      assertThrows(IOException.class, () -> streams.output().write(1));
    }
    assertEquals(1, transport.closes.get());
  }

  @Test
  void interrupted_blocked_write_reports_progress_and_closes_connection() throws Exception {
    PartialTransport transport = new PartialTransport(new byte[0]);
    transport.blockAfterFirstWrite = true;
    CountDownLatch blocked = transport.blocked;
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<Boolean> interrupted = new AtomicReference<>();
    try (MemoryStreams streams = new MemoryStreams(transport)) {
      Thread writer = Thread.ofVirtual().start(() -> {
        try {
          streams.output().write(new byte[] {1, 2, 3, 4});
        } catch (Throwable exception) {
          failure.set(exception);
          interrupted.set(Thread.currentThread().isInterrupted());
        }
      });
      assertTrue(blocked.await(2, TimeUnit.SECONDS));
      writer.interrupt();
      writer.join(2000);
      assertFalse(writer.isAlive());
      InterruptedIOException exception = assertInstanceOf(InterruptedIOException.class, failure.get());
      assertEquals(2, exception.bytesTransferred);
      assertEquals(Boolean.TRUE, interrupted.get());
      assertEquals(1, transport.closes.get());
    }
  }

  @Test
  void close_unblocks_pending_read() throws Exception {
    PartialTransport transport = new PartialTransport(new byte[0]);
    transport.blockReads = true;
    AtomicReference<Throwable> failure = new AtomicReference<>();
    MemoryStreams streams = new MemoryStreams(transport);
    try {
      Thread reader = Thread.ofVirtual().start(() -> {
        try {
          streams.input().read();
        } catch (Throwable exception) {
          failure.set(exception);
        }
      });
      assertTrue(transport.blocked.await(2, TimeUnit.SECONDS));
      streams.close();
      reader.join(2000);
      assertFalse(reader.isAlive());
      assertInstanceOf(IOException.class, failure.get());
    } finally {
      streams.close();
    }
  }

  private static final class PartialTransport implements MemoryTransport {
    private final ByteBuffer incoming;
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();
    private final AtomicInteger closes = new AtomicInteger();
    private final CountDownLatch blocked = new CountDownLatch(1);
    private boolean zeroRead = true;
    private boolean zeroWrite = true;
    private boolean blockAfterFirstWrite;
    private boolean blockReads;

    private PartialTransport(byte[] incoming) {
      this.incoming = ByteBuffer.wrap(incoming);
    }

    @Override
    public int read(ByteBuffer destination) {
      if (blockReads) {
        blocked.countDown();
        return 0;
      }
      if (zeroRead) {
        zeroRead = false;
        return 0;
      }
      if (!incoming.hasRemaining()) {
        return -1;
      }
      int count = Math.min(destination.remaining(), Math.min(2, incoming.remaining()));
      for (int index = 0; index < count; index++) {
        destination.put(incoming.get());
      }
      return count;
    }

    @Override
    public int write(ByteBuffer source) {
      if (blockAfterFirstWrite && written.size() > 0) {
        blocked.countDown();
        return 0;
      }
      if (zeroWrite) {
        zeroWrite = false;
        return 0;
      }
      zeroWrite = true;
      int count = Math.min(2, source.remaining());
      for (int index = 0; index < count; index++) {
        written.write(source.get());
      }
      return count;
    }

    @Override
    public boolean hasData() { return incoming.hasRemaining(); }
    @Override
    public boolean canWrite() { return true; }
    @Override
    public String remoteAddress() { return "test-peer"; }
    @Override
    public void close() { closes.incrementAndGet(); }
  }
}
