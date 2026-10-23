# Maps Memory Transport

High-performance shared-memory and RDMA transports for Java.

## Documentation

- **[How the transport works](docs/architecture.md)**: endpoints, platform
  backends, shared-memory ring layout, authenticated negotiation, lifecycle,
  Docker and RDMA.
- **[Connection examples](docs/connections.md)**: runnable simple and secure
  Linux/macOS/Windows programs, partial-I/O handling and managed RDMA usage.

These are the current API guides. Historical phase notes below document the
implementation's evolution and may describe earlier restrictions that have
since been replaced.

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


## Benchmarks

A standalone JMH harness lives under `benchmarks/`. It measures shared-memory round-trip latency, sustained one-way throughput, heap versus direct buffers, and can report allocation/GC behaviour using JMH profilers.

See [benchmarks/README.md](benchmarks/README.md) for build and run commands.

## Java

Java 25 is required.

## Branching

- `main` is the release trunk.
- `development` is the integration branch.
- feature branches are created from `development` when isolation or review requires one.
- release branches are created from `main`.

## License

Apache License 2.0 with the Commons Clause. See `LICENSE` and `LICENSE-COMMONS-CLAUSE`.


## Managed connections

The library also provides a transport-oriented API for accepting multiple independent
peers without exposing shared-memory filenames, ring side selection, or RDMA handles:

```java
try (MemoryServer server = MemoryServer.listenSharedMemory(
    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
  InetSocketAddress endpoint = (InetSocketAddress) server.localAddress();
  try (MemoryConnection client = MemoryClient.connectSharedMemory(endpoint);
       MemoryConnection accepted = server.accept()) {
    client.write(source);
    accepted.read(destination);
  }
}
```

RDMA uses `MemoryServer.listenRdma(address)` and
`MemoryClient.connectRdma(address)`; both return the same `MemoryConnection`
type. Each accepted peer has an independent transport.

Local shared-memory setup uses a loopback TCP rendezvous connection to negotiate a
random region name and the ring layout. A background task refreshes the existing
mapped-file heartbeat and closes a connection when the other process disappears,
restarts, or stops refreshing its heartbeat. The server side removes its region file
when that connection closes. The rendezvous endpoint must be a resolved loopback
address, and peers must have permission to access the same mapped-file namespace.

The present RDMA implementation detects native transport failure through its normal
I/O readiness and read/write operations. It does not provide a separate application
heartbeat or an RDMA handshake timeout. Unclean termination of the process that
owns a shared-memory region can leave an orphaned mapped file, which is not yet
garbage-collected automatically.

## Managed endpoint configuration (Phase 1)

The configured entry points add explicit connection and negotiation limits while leaving
the existing convenience methods and their defaults unchanged:

```java
MemoryEndpointConfig config = MemoryEndpointConfig.builder()
    .transport(MemoryTransportType.SHARED_MEMORY)
    .address(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    .maxConnections(64)
    .maxPendingConnections(64)
    .maxNegotiations(16)
    .handshakeTimeoutMillis(5_000)
    .build();

try (MemoryServer server = MemoryServer.listen(config)) {
  InetSocketAddress address = (InetSocketAddress) server.localAddress();
  try (MemoryConnection outgoing = MemoryClient.connect(MemoryConnectionConfig.sharedMemory(address));
       MemoryConnection incoming = server.accept()) {
    outgoing.write(source);
    incoming.read(destination);
  }
}
```

Connection and negotiation limits of zero mean unlimited, for callers explicitly opting
out of bounded behaviour. The configured defaults are 64 active peers, 64 pending
accepts, and 16 simultaneous local negotiations. The legacy factories preserve their
original behaviour.

The provider interface is package-private. Linux memfd, Windows and macOS native
providers are not part of this phase. Passing a non-null `MemorySecurity` currently
fails explicitly: cryptographic handshake and native security are not yet implemented.
The existing managed local rendezvous remains loopback TCP and mapped files until the
secure platform-provider stages. Do not use it across an untrusted process boundary.

## Linux memfd shared-memory endpoints

On Linux x86_64 and aarch64, the configured shared-memory endpoint accepts a
`UnixDomainSocketAddress` and uses a Unix-domain control socket, kernel-provided
`SO_PEERCRED` credentials and two anonymous memfd mappings. Each direction has
its own SPSC ring; descriptors are transferred using `SCM_RIGHTS`, and the receiver
checks that each received memfd has the expected size and shrink/grow seals.
Both sides acknowledge attachment before application I/O begins.

```java
Path directory = Path.of("/run/user/1000/mapsmessaging");
UnixDomainSocketAddress address =
    UnixDomainSocketAddress.of(directory.resolve("transport.sock"));

MemoryEndpointConfig config = MemoryEndpointConfig.builder()
    .transport(MemoryTransportType.SHARED_MEMORY)
    .address(address)
    .build();

try (MemoryServer server = MemoryServer.listen(config)) {
  MemoryConnection inbound = server.accept();
  // Use the ordinary MemoryTransport read/write contract.
}

try (MemoryConnection outbound =
         MemoryClient.connect(MemoryConnectionConfig.linuxSharedMemory(address))) {
  // Exchange data with the server through the two negotiated rings.
}
```

The listening directory must already exist and disallow group/other traversal.
The socket is restricted to owner access. The native admission policy requires
the connecting process to have the same effective Unix UID as the server;
no client-supplied UID is trusted. Linux processes running under the same UID
are within the same local trust boundary, not isolated from each other.

The legacy `InetSocketAddress` shared-memory factories retain their original
file-backed implementation for compatibility. Linux memfd requires an explicit
`UnixDomainSocketAddress`; unsupported platforms fail rather than falling back
to named files.

The FFM native calls require native-access permission. Surefire and the
cross-process test launcher configure this on JDK 25. To run the Linux tests:

```bash
mvn test -Dtest=LinuxMemfdEndpointTest
```

Windows and macOS skip Linux-native integration tests. The Linux backend does
not yet implement optional Ed25519 authentication or cryptographic record
protection. Those remain a separate security phase.

## Windows and macOS managed shared-memory providers

Managed connections select a native shared-memory provider from the explicit
local address type. Existing legacy `InetSocketAddress` file-backed endpoints
retain their previous behaviour for compatibility.

### Windows

Use a `WindowsPipeAddress` to establish connections through a restricted local
named pipe. The implementation checks the OS account SID of each peer, creates
two unnamed memory-mapping objects and duplicates mapping handles directly into
the authorised client process. The client never opens mappings by shared name.

```java
WindowsPipeAddress address = WindowsPipeAddress.of("mqtt-local");
try (MemoryServer server = MemoryServer.listen(
    MemoryEndpointConfig.builder().address(address).build())) {
  try (MemoryConnection client =
           MemoryClient.connect(MemoryConnectionConfig.windowsSharedMemory(address));
       MemoryConnection accepted = server.accept()) {
    client.write(source);
    accepted.read(destination);
  }
}
```

Windows security relies on native token verification and a restricted named-pipe
security descriptor; the library requires the client and listener processes to
run as the same Windows account. The pipe explicitly rejects remote clients.
The anonymous mappings are owned by the connected peers and the OS reclaims
them when all mapping views and handles close, including following process exit.
Same-account processes and administrators remain within the platform trust boundary.

### macOS

Use an `UnixDomainSocketAddress`. The backend uses `getpeereid`, separate POSIX
shared-memory objects for each transfer direction and `SCM_RIGHTS` descriptor
passing. Each shared-memory object is unlinked immediately after creation,
preventing later attachment by a pathname.

```java
UnixDomainSocketAddress address =
    UnixDomainSocketAddress.of(Path.of("/tmp/maps-private/transport.sock"));

try (MemoryServer server = MemoryServer.listen(
    MemoryEndpointConfig.builder().address(address).build())) {
  try (MemoryConnection client = MemoryClient.connect(
           new MemoryConnectionConfig(MemoryTransportType.SHARED_MEMORY,
               address, 5_000, null));
       MemoryConnection accepted = server.accept()) {
    client.write(source);
    accepted.read(destination);
  }
}
```

Create the parent control-socket directory with owner-only traversal rights
before binding. The UID reported by the operating system must match the local
effective UID. Shared-memory descriptors and mappings are per connection.

### Platform testing

On Windows:

```text
mvn clean test -Dtest=NativePlatformEndpointTest
```

On macOS:

```text
mvn clean test -Dtest=NativePlatformEndpointTest
```

The test suite exercises four simultaneous clients, bidirectional exchange,
a second JVM, forced peer termination, and applicable access checks. Other
platform-specific tests skip cleanly. The Maven Surefire configuration enables
FFM native access for JDK 25; child JVMs enable it explicitly.

Windows and macOS implementations use the same `MemoryConnection` lifecycle
notification interface as Linux. Optional X25519/Ed25519 authentication and
cryptographic record protection are separate from mandatory local OS admission
and remain part of the subsequent security phase.

The new native provider addresses select secure local rendezvous explicitly.
They do not fall back to legacy file-backed mappings if native support is
missing or peer verification fails.

## Phase 4: authenticated cryptographic protocol (foundation)

The `SecureHandshake` and `SecureSession` APIs provide a standalone
mutually authenticated X25519/Ed25519 cryptographic protocol with:
- Signed ephemeral key offers verified against `PeerTrust`
- Transcript-bound HKDF-SHA256 session secrets and separate directional keys
- Explicit session-key confirmation required before record processing
- AES-256-GCM, ChaCha20-Poly1305, or HMAC-SHA-256 authenticated records
- Sequence-based replay rejection, bounded record length and key cleanup
- Tamper, trust, mismatch and replay regression tests

The authenticated handshake is now wired into the Linux, macOS and Windows
native rendezvous. Configured `MemorySecurity` connections use protocol version
2, exchange signed offers over the local control socket or named pipe, confirm
directional session keys, and wrap the shared-memory ring in
`ProtectedTransport`. Applications cannot read unauthenticated plaintext.
Protected records include a bounded length, session-bound sequence number
and authentication tag. HMAC mode authenticates but does not encrypt payloads.

Existing unconfigured native peers continue to use protocol version 1.
Configured security is rejected on legacy file-backed endpoints and RDMA;
secure endpoints never silently fall back to unprotected mode. Both ends must
configure `MemorySecurity` and explicitly trust the other's Ed25519 identity.

Run on each target operating system:
`mvn clean test -Dtest=SecureHandshakeTest,SecureNativeEndpointTest`

Windows and macOS native security integration still requires target-platform
runtime verification before a production security claim is appropriate.

## Phase 5: managed, authenticated RDMA

The library retains its native rdma-core `rsocket` transport and existing
`RdmaTransport`/`RdmaListener` APIs. Managed connections now use the configured
capacity and handshake limits, negotiate on independent platform workers,
and close rejected peers without publishing them to the application.

When `MemorySecurity` is present on both endpoints, the RDMA connection first
establishes its ordinary transport session, then exchanges authenticated
Ed25519/X25519 offers and confirms session keys using `SecureWire`.
Application data uses `ProtectedTransport`, with the same AES-256-GCM,
ChaCha20-Poly1305 or HMAC-SHA-256 record protection as native shared memory.
Security failures fail closed; no plain RDMA fallback is attempted.

```java
MemoryEndpointConfig endpoint = MemoryEndpointConfig.builder()
    .transport(MemoryTransportType.RDMA)
    .address(new InetSocketAddress("192.0.2.10", 7471))
    .maxConnections(64)
    .maxNegotiations(8)
    .handshakeTimeoutMillis(10_000)
    .security(serverSecurity)
    .build();

try (MemoryServer server = MemoryServer.listen(endpoint)) {
  MemoryConnection peer = server.accept();
}

MemoryConnection client = MemoryClient.connect(
    new MemoryConnectionConfig(MemoryTransportType.RDMA,
        new InetSocketAddress("192.0.2.10", 7471), 10_000, clientSecurity));
```

The listener and client must use matching protection modes and explicit
trust policies. HMAC protection authenticates records but does not encrypt
them. Run on an RDMA-enabled Linux host with an address bound to an RDMA
interface:

```bash
MAPS_RDMA_TEST_ADDRESS=192.0.2.10 mvn test -Dtest=ManagedRdmaIntegrationTest
```

The test requires the RDMA device and environment variable; otherwise it
skips. This uses the pre-existing rsocket transport and does not introduce
a separate direct ibverbs queue-pair API or device-registration interface.

## Phase 6 hardening: partial-record and negotiation ownership

Protected record writes preserve the caller's source position until a complete
ciphertext frame has been accepted by the underlying transport. Retrying a
short write resumes the pending frame without encrypting or submitting duplicate
plaintext. `canWrite()` remains true when a pending frame must be drained.

Managed RDMA listeners register accepted transports for shutdown before starting
the peer negotiation worker, preventing shutdown from overlooking that peer.

Run the deterministic partial-write regression suite on any JDK 25 platform:

```bash
mvn test -Dtest=ProtectedTransportHardeningTest,SecureHandshakeTest
```

The deterministic regression tests cover one-byte partial writes, final-frame
delivery, partial reads, malformed and oversized frame lengths, tampering,
closed underlying transports, invalid/truncated control offers, and native
control-write failure propagation. They run without RDMA hardware.

These tests do not constitute full native ABI or production security
certification. Hardware-specific RDMA disconnect/failure injection, Windows
and macOS native security validation, cross-process crash races, fuzzing with
sanitizers and extended soak tests remain verification gates.

### Phase closeout status (October 2026)

| Phase | Implemented scope | Verification gate |
| --- | --- | --- |
| 1: Managed endpoint foundation | Connection lifecycle, configured limits, provider contracts | Previously passed managed endpoint regression tests |
| 2: Linux native memory | memfd, Unix socket rendezvous, credential admission | Linux memfd cross-process tests previously passed |
| 3: Windows/macOS | Native named pipes / macOS POSIX shared memory and integration | Linux compatibility passed; native platform execution still unconfirmed |
| 4: Authenticated records | Ed25519/X25519, derived keys, AEAD/HMAC, protected native endpoints | Linux secure native and crypto tests previously passed; Windows/macOS pending |
| 5: Managed RDMA | rdma-core rsocket plus bounded managed acceptance and authenticated records | RDMA hardware integration test pending |
| 6: Hardening | Framing, malformed input, backpressure, teardown ownership regression coverage | Local JDK 25 regression rerun pending; native fuzz/soak pending |

No phase with an outstanding hardware or operating-system test is labelled
production-verified.
