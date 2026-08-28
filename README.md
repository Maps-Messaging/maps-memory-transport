# Maps Memory Transport

High-performance shared-memory and RDMA transports for Java.

## Scope

This library provides transport primitives only. It has no dependency on the MapsMessaging server, MAPS protocol, sessions, destinations, selectors, routing or clustering.

The supported public API is intentionally small:

- `io.mapsmessaging.memory.MemoryTransport`
- `io.mapsmessaging.memory.PeerGenerationChangedException`
- `io.mapsmessaging.memory.shm.SharedMemoryTransport`
- `io.mapsmessaging.memory.rdma.RdmaAvailability`
- `io.mapsmessaging.memory.rdma.RdmaSupport`
- `io.mapsmessaging.memory.rdma.RdmaUnavailableException`
- `io.mapsmessaging.memory.rdma.RdmaTransport`
- `io.mapsmessaging.memory.rdma.RdmaListener`

Internal ring layout and RDMA FFM plumbing are not exported and may evolve independently of the public API.

## Shared memory

```java
try (SharedMemoryTransport a = new SharedMemoryTransport("server-a-b", true);
     SharedMemoryTransport b = new SharedMemoryTransport("server-a-b", false)) {
  a.write(source);
  b.read(destination);
}
```

Each shared region contains two single-producer/single-consumer rings, one per direction. Reads and writes are stream-oriented and may span multiple fixed-size slots.

## RDMA

RDMA support uses Java FFM with `librdmacm` and `libibverbs`.

The established transport uses the rsocket stream API provided by `librdmacm`. Rsockets presents socket-like byte-stream semantics while internally using RDMA writes, queue pairs and completion queues. This maps directly to the same partial read/write `MemoryTransport` contract used by shared memory without exposing raw verbs details to callers.

Availability can be probed without throwing:

```java
RdmaAvailability availability = RdmaSupport.probe();
if (availability.available()) {
  // RDMA transport may be created.
}
```

`RdmaSupport.probe()` distinguishes unsupported platforms, missing rdma-core libraries, systems with rdma-core but no RDMA devices, and systems where RDMA is usable. `RdmaSupport.requireAvailable()` provides the checked-exception path when RDMA is mandatory.

Client connection:

```java
try (RdmaTransport transport = RdmaTransport.connect("10.10.20.12", 7471)) {
  transport.write(source);
  transport.read(destination);
}
```

Server listener:

```java
try (RdmaListener listener = RdmaListener.bind("10.10.20.11", 7471);
     RdmaTransport transport = listener.accept()) {
  transport.read(destination);
}
```

The library performs a small versioned Maps memory-transport handshake after the rsocket connection is established. Each connection gets independent local and peer session identifiers, preventing accidental attachment to a non-Maps rsocket service.

Native device-specific tests skip cleanly when RDMA is unavailable, allowing the normal test suite to run on development machines and CI workers without RDMA hardware.

To run the real RDMA loopback/integration path on a Linux host with an RDMA-capable interface, set `MAPS_RDMA_TEST_ADDRESS` to the local IP assigned to that RDMA interface before running the test suite. The integration test transfers payloads larger than the internal I/O buffer in both directions.

Raw one-sided memory mapping remains an internal future optimisation. It can be added later without changing the public `MemoryTransport` API.

## Java

Java 25 is required.

## Branching

- `main` is the release trunk.
- `development` is the integration branch.
- feature branches are created from `development` when isolation or review requires one.
- release branches are created from `main`.

## License

Apache License 2.0 with the Commons Clause. See `LICENSE` and `LICENSE-COMMONS-CLAUSE`.
