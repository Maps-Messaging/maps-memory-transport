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
import java.util.concurrent.atomic.AtomicReference;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@Threads(1)
public class SharedMemoryRoundTripBenchmark {

  @State(Scope.Benchmark)
  public static class TransportState {

    @Param({"64", "256", "1024", "4096", "65536"})
    int payloadSize;

    @Param
    BufferKind bufferKind;

    private SharedMemoryTransport sideA;
    private SharedMemoryTransport sideB;
    private ByteBuffer source;
    private ByteBuffer response;
    private Path path;
    private Thread echoThread;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Throwable> echoFailure = new AtomicReference<>();

    @Setup(Level.Trial)
    public void setup() throws Exception {
      String name = "jmh-rtt-" + UUID.randomUUID();
      sideA = new SharedMemoryTransport(name, true);
      sideB = new SharedMemoryTransport(name, false);
      path = sideA.path();

      source = bufferKind.allocate(payloadSize);
      for (int i = 0; i < payloadSize; i++) {
        source.put((byte) (i * 31));
      }
      source.flip();
      response = bufferKind.allocate(payloadSize);

      running.set(true);
      echoThread = Thread.ofPlatform().name("shm-jmh-echo").start(this::echoLoop);
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
      running.set(false);
      if (echoThread != null) {
        echoThread.join(5_000);
      }
      if (sideB != null) {
        sideB.close();
      }
      if (sideA != null) {
        sideA.close();
      }
      if (path != null) {
        Files.deleteIfExists(path);
      }
      Throwable failure = echoFailure.get();
      if (failure != null) {
        throw new IllegalStateException("echo thread failed", failure);
      }
    }

    void prepareInvocation() {
      source.position(0);
      source.limit(payloadSize);
      response.clear();
      response.limit(payloadSize);
    }

    void roundTrip() throws Exception {
      writeFully(sideA, source);
      readFully(sideA, response);
      checkEchoFailure();
    }

    private void echoLoop() {
      ByteBuffer inbound = bufferKind.allocate(payloadSize);
      try {
        while (running.get()) {
          inbound.clear();
          inbound.limit(payloadSize);
          while (running.get() && inbound.hasRemaining()) {
            int read = sideB.read(inbound);
            if (read == 0) {
              checkEchoFailure();
              Thread.onSpinWait();
            }
          }
          if (!running.get()) {
            return;
          }
          inbound.flip();
          writeFully(sideB, inbound);
        }
      } catch (Throwable throwable) {
        echoFailure.compareAndSet(null, throwable);
      }
    }

    private void readFully(SharedMemoryTransport transport, ByteBuffer destination)
        throws Exception {
      while (destination.hasRemaining()) {
        int read = transport.read(destination);
        if (read == 0) {
          checkEchoFailure();
          Thread.onSpinWait();
        }
      }
    }

    private void writeFully(SharedMemoryTransport transport, ByteBuffer source)
        throws Exception {
      while (source.hasRemaining()) {
        int written = transport.write(source);
        if (written == 0) {
          checkEchoFailure();
          Thread.onSpinWait();
        }
      }
    }

    private void checkEchoFailure() throws IOException {
      Throwable failure = echoFailure.get();
      if (failure != null) {
        throw new IOException("echo thread failed", failure);
      }
    }
  }

  @Benchmark
  public void roundTripLatency(TransportState state) throws Exception {
    state.prepareInvocation();
    state.roundTrip();
  }
}
