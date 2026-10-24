/*
 * Copyright [ 2020 - 2026 ] MapsMessaging B.V.
 *
 * Licensed under the Apache License, Version 2.0 with the Commons Clause
 */
package io.mapsmessaging.memory.benchmarks;

import io.mapsmessaging.memory.shm.SharedMemoryTransport;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@Threads(1)
public class SharedMemoryThroughputBenchmark {

  private static final int TRANSFER_SIZE = 1024 * 1024;

  @State(Scope.Benchmark)
  public static class TransportState {

    @Param({"64", "256", "1024", "4096", "65536"})
    int chunkSize;

    @Param
    BufferKind bufferKind;

    private SharedMemoryTransport writer;
    private SharedMemoryTransport reader;
    private ByteBuffer source;
    private ByteBuffer sinkBuffer;
    private Path path;
    private Thread sinkThread;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicReference<Throwable> sinkFailure = new AtomicReference<>();

    @Setup(Level.Trial)
    public void setup() throws Exception {
      String name = "jmh-throughput-" + UUID.randomUUID();
      writer = new SharedMemoryTransport(name, true);
      reader = new SharedMemoryTransport(name, false);
      path = writer.path();

      source = bufferKind.allocate(chunkSize);
      for (int i = 0; i < chunkSize; i++) {
        source.put((byte) (i * 17));
      }
      source.flip();
      sinkBuffer = bufferKind.allocate(Math.max(chunkSize, 64 * 1024));

      running.set(true);
      sinkThread = Thread.ofPlatform().name("shm-jmh-sink").start(this::sinkLoop);
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
      running.set(false);
      if (sinkThread != null) {
        sinkThread.join(5_000);
      }
      if (reader != null) {
        reader.close();
      }
      if (writer != null) {
        writer.close();
      }
      if (path != null) {
        Files.deleteIfExists(path);
      }
      Throwable failure = sinkFailure.get();
      if (failure != null) {
        throw new IllegalStateException("sink thread failed", failure);
      }
    }

    void transferOneMiB() throws Exception {
      long startConsumed = consumed.get();
      int remaining = TRANSFER_SIZE;

      while (remaining > 0) {
        int chunk = Math.min(chunkSize, remaining);
        source.position(0);
        source.limit(chunk);
        writeFully(source);
        remaining -= chunk;
      }

      long target = startConsumed + TRANSFER_SIZE;
      while (consumed.get() < target) {
        checkSinkFailure();
        Thread.onSpinWait();
      }
    }

    private void sinkLoop() {
      try {
        while (running.get()) {
          sinkBuffer.clear();
          int read = reader.read(sinkBuffer);
          if (read == 0) {
            Thread.onSpinWait();
          } else {
            consumed.addAndGet(read);
          }
        }
      } catch (Throwable throwable) {
        sinkFailure.compareAndSet(null, throwable);
      }
    }

    private void writeFully(ByteBuffer buffer) throws Exception {
      while (buffer.hasRemaining()) {
        int written = writer.write(buffer);
        if (written == 0) {
          checkSinkFailure();
          Thread.onSpinWait();
        }
      }
    }

    private void checkSinkFailure() throws IOException {
      Throwable failure = sinkFailure.get();
      if (failure != null) {
        throw new IOException("sink thread failed", failure);
      }
    }
  }

  @Benchmark
  @OperationsPerInvocation(TRANSFER_SIZE)
  public void sustainedOneWayThroughput(TransportState state) throws Exception {
    state.transferOneMiB();
  }
}
