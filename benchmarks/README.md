# JMH benchmarks

These benchmarks measure the shared-memory transport properties that matter for its intended use: low latency, high sustained throughput and low allocation overhead.

## Build

First install the library snapshot from the repository root:

```bash
mvn --batch-mode --no-transfer-progress clean install
```

Then build the standalone JMH harness:

```bash
mvn --batch-mode --no-transfer-progress -f benchmarks/pom.xml clean package
```

## Run

Run the full shared-memory benchmark set:

```bash
java -jar benchmarks/target/benchmarks.jar
```

Round-trip latency only:

```bash
java -jar benchmarks/target/benchmarks.jar SharedMemoryRoundTripBenchmark
```

Sustained one-way throughput only:

```bash
java -jar benchmarks/target/benchmarks.jar SharedMemoryThroughputBenchmark
```

Add the JMH GC profiler to expose allocation rate and GC pressure:

```bash
java -jar benchmarks/target/benchmarks.jar -prof gc
```

## What is measured

### Round-trip latency

`SharedMemoryRoundTripBenchmark` uses two live `SharedMemoryTransport` endpoints and a dedicated echo thread. Each measured invocation sends one payload from side A to side B and waits for the same payload to return.

JMH runs this in `SampleTime` mode, so the report includes latency percentiles rather than only a mean. Pay particular attention to p50, p95, p99 and p99.9.

Payload sizes:

- 64 B
- 256 B
- 1 KiB
- 4 KiB
- 64 KiB

Both heap and direct `ByteBuffer` variants are measured.

### Sustained throughput

`SharedMemoryThroughputBenchmark` transfers 1 MiB per measured invocation while a dedicated reader continuously drains the peer ring. The benchmark waits for the full MiB to be consumed before completing, so it measures sustainable end-to-end transfer rather than merely filling the ring.

`@OperationsPerInvocation(1_048_576)` makes the JMH throughput score represent bytes/second. Divide by 1,048,576 for MiB/s.

## Interpreting results

Do not compare numbers from different hosts as if they were equivalent. Record at least:

- CPU model and core count
- operating system and kernel
- Java version and JVM flags
- whether the buffers are heap or direct
- payload/chunk size
- CPU governor / power mode where relevant

The default benchmark settings use two JVM forks, three warmup iterations and five measured iterations. Override those from the JMH command line for longer validation runs.

RDMA benchmarks belong on an RDMA-capable host and should be added when that hardware is available.
