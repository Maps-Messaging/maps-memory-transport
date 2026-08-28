# Maps Memory Transport

High-performance shared-memory and RDMA transports for Java.

## Scope

This library provides transport primitives only. It has no dependency on the MapsMessaging server, MAPS protocol, sessions, destinations, selectors, routing or clustering.

The supported public API is intentionally small:

- `io.mapsmessaging.memory.MemoryTransport`
- `io.mapsmessaging.memory.shm.SharedMemoryTransport`
- `io.mapsmessaging.memory.rdma.RdmaAvailability`
- `io.mapsmessaging.memory.rdma.RdmaSupport`
- `io.mapsmessaging.memory.rdma.RdmaUnavailableException`

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

RDMA support uses Java FFM with `librdmacm` and `libibverbs` and follows the same logical transport contract as shared memory.

Availability can be probed without throwing:

```java
RdmaAvailability availability = RdmaSupport.probe();
if (availability.available()) {
  // RDMA transport may be created.
}
```

`RdmaSupport.probe()` distinguishes unsupported platforms, missing rdma-core libraries, systems with rdma-core but no RDMA devices, and systems where RDMA is usable. `RdmaSupport.requireAvailable()` provides the checked-exception path when RDMA is mandatory.

Native device-specific tests skip cleanly when RDMA is unavailable, allowing the normal test suite to run on development machines and CI workers without RDMA hardware.

Queue-pair establishment and one-sided data movement are the next implementation stage.

## Java

Java 25 is required.

## Branching

- `main` is the release trunk.
- `development` is the integration branch.
- feature branches are created from `development` when isolation or review requires one.
- release branches are created from `main`.

## License

Apache License 2.0 with the Commons Clause. See `LICENSE` and `LICENSE-COMMONS-CLAUSE`.
