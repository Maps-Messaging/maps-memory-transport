# Maps Memory Transport

High-performance shared-memory and RDMA transports for Java.

## Scope

This library provides transport primitives only. It has no dependency on the MapsMessaging server, MAPS protocol, sessions, destinations, selectors, routing or clustering.

The supported public API is intentionally small:

- `io.mapsmessaging.memory.MemoryTransport`
- `io.mapsmessaging.memory.shm.SharedMemoryTransport`

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

RDMA support is being built on the same logical transport contract using Java FFM with `librdmacm` and `libibverbs`. The native bootstrap is present; queue-pair establishment and one-sided data movement are the next implementation stage.

## Java

Java 25 is required.

## Branching

- `main` is the release trunk.
- `development` is the integration branch.
- feature branches are created from `development`.
- release branches are created from `main`.

## License

Apache License 2.0 with the Commons Clause. See `LICENSE` and `LICENSE-COMMONS-CLAUSE`.
