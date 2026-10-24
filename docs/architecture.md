# Architecture and operation

Maps Memory Transport is a Java 25 library offering a common **byte-stream connection API**
over local shared memory or Linux RDMA. It is independent of the MapsMessaging server and
does not implement MQTT, sessions, destinations, routing or application-level messaging.

See [Connection examples](connections.md) for complete client and server programs,
and [Docker and host processes](docker-and-host.md) for container IPC deployment.

## Main abstractions

| Type | Responsibility |
| --- | --- |
| `MemoryServer` | Binds an endpoint, accepts multiple peers and owns active connections until they close |
| `MemoryClient` | Establishes an outbound managed connection |
| `MemoryConnection` | Per-peer `MemoryTransport` implementation: read, write, state and close |
| `MemoryEndpointConfig` | Listener address, ring dimensions, connection/queue limits, handshake timeout and optional security |
| `MemoryConnectionConfig` | Outbound address, connect timeout and optional security |
| `MemoryTransport` | Common `read(ByteBuffer)`, `write(ByteBuffer)`, readiness and close contract |

These APIs are similar in purpose to `ServerSocket` and `Socket`, but they **do not
extend** those classes. They expose `ByteBuffer` I/O rather than Java socket streams.

`read` and `write` are partial operations: they can return zero, and a successful
call need not consume all of the buffer. The caller must retain the buffer and
continue when `hasData()` or `canWrite()` permits progress. Do not interpret a
zero read as EOF. Connections should always be closed with try-with-resources.
Use one reader and one writer per direction, as the backing shared-memory rings
are single-producer/single-consumer (SPSC).

## How a local connection is established

1. `MemoryServer.listen(config)` binds a native local control endpoint.
2. `MemoryClient.connect(config)` connects to it; the listener checks the
   operating-system identity of the peer.
3. The peers negotiate the ring dimensions and acquire two independent shared
   memory regions, one for each direction.
4. Each process maps the regions locally and confirms successful attachment.
5. When `MemorySecurity` is configured, the peers additionally exchange signed
   ephemeral keys, check their trust policies and confirm shared session keys.
6. The resulting `MemoryConnection` becomes available to the application.
   Its I/O uses the shared-memory rings, not the rendezvous control channel.
7. Control-channel monitoring detects peer disconnects; closing a connection
   releases its mappings, descriptors/handles and associated listener state.

The actual native mechanisms differ:

| Platform | Control / identity | Data regions |
| --- | --- | --- |
| Linux | Unix-domain socket; `SO_PEERCRED` same-UID admission | Two anonymous `memfd` regions, sealed against resize and transferred via `SCM_RIGHTS` |
| macOS | Unix-domain socket; `getpeereid` same-UID admission | POSIX shared-memory descriptors, unlinked after creation and passed via `SCM_RIGHTS` |
| Windows | Named pipe with restricted ACL and peer-token checks | Two anonymous file mappings, whose handles are duplicated into the client process |

These local security checks establish a **same-user trust boundary**, not protection
against another malicious process operating as that user or a privileged administrator.
For isolation between distinct application identities, use a distinct service
account/UID and configure cryptographic peer trust as appropriate.

### Container-to-container communication on Linux

Containers running on the **same Linux kernel** can use the Linux native
implementation. Bind-mount one **private control-socket directory** into both
containers, use the same socket pathname and ensure the peer UIDs are equivalent
under the active user namespaces. Descriptors passed via `SCM_RIGHTS` refer to
the same anonymous memory from both processes; a shared `/dev/shm`, host
networking and privileged containers are not required for this model.

Docker cross-container operation depends on directory permissions and UID mapping
and should be verified in the intended deployment. This does not enable shared
memory between containers on **different hosts**.

## Secure connections

The configured `MemorySecurity` policy supplies a persistent Ed25519 signing
identity, an explicit `PeerTrust` verifier and a `DataProtection` mode.

The secure handshake uses a signed ephemeral X25519 key agreement, derives
directional session secrets with HKDF-SHA256, and requires key confirmation
before returning a usable secured transport. `ProtectedTransport` applies
bounded, sequenced, authenticated record framing to the underlying stream.

| Protection | Behaviour |
| --- | --- |
| `AES_256_GCM` | Encryption and integrity protection; default |
| `CHACHA20_POLY1305` | Encryption and integrity protection |
| `HMAC_SHA_256` | Integrity and authentication **only**; plaintext remains visible |

Both endpoints must configure compatible modes and explicitly trust the
other party's signing key and claimed identity. A secured native rendezvous
uses a different protocol version from an unsecured rendezvous, and does not
silently downgrade. Legacy file-backed shared-memory endpoints cannot be
configured with `MemorySecurity`.

A `MemorySecurity` instance holds key references and trust policy, not a session
key. For real applications, load the persistent identity from your existing
key store or identity manager. Generating a fresh Ed25519 identity each run,
as demonstrated in the examples, is for testing only.

## Legacy local shared memory

The original `SharedMemoryTransport` and the convenience
`MemoryServer.listenSharedMemory(InetSocketAddress)` use named, file-backed
shared regions with a loopback-TCP rendezvous. They remain available for
compatibility, but they **are not equivalent to the native secure providers**.

To select the native provider, configure a `UnixDomainSocketAddress`
(Linux/macOS) or `WindowsPipeAddress` (Windows), rather than an
`InetSocketAddress`.

## RDMA

On supported Linux hosts, `RdmaTransport` uses rdma-core `rsocket`,
with the same byte-stream interface. Underneath rsocket are RDMA-native
operations, but queue pairs, memory registration and completion queues are
not exposed as a separate public API.

`MemoryTransportType.RDMA` selects the managed RDMA listener and client
connection path. The optional `MemorySecurity` configuration authenticates
the peer and protects records in the same manner as native shared memory.
A device, usable RDMA address and rdma-core libraries are required; use
`RdmaSupport.probe()` before relying on this path. Hardware integration
tests require `MAPS_RDMA_TEST_ADDRESS`.

## Lifecycle and operational limits

A configured listener supports `maxConnections`, `maxPendingConnections`,
`maxNegotiations` and `handshakeTimeoutMillis`. These are important
especially when clients stall during negotiation. The configured defaults
are 64 active peers, 64 pending connections, 16 negotiations and a
5-second handshake timeout. A zero capacity limit opts out of that limit.

A `MemoryServer` owns its accepted peers while active and closes them
during shutdown. A `MemoryConnection` has an observable lifecycle via
`state()` and `isClosed()`. A normal application should treat a failed
read/write as a connection failure, release the connection and reconnect
using a new connection object.

The native transports monitor their control channel and close connections
when peers disappear. RDMA reports native readiness and I/O failures.
Do not assume that a successful `canWrite()` promises that an entire message
will fit in the next call.

## Testing and boundaries

Run `mvn clean test` on JDK 25. The platform suites run native tests for
the current OS and skip those that cannot run there. Test real RDMA hardware
with `MAPS_RDMA_TEST_ADDRESS=<local-RDMA-IP>`.

Linux, macOS and Windows shared-memory paths have been exercised in their
respective platform testing, while RDMA hardware integration remains a
separate verification requirement. The source also contains negative tests
for malformed control offers, protected-record tampering and partial I/O.
Passing the unit/integration suites is not a substitute for a deployment
security review or prolonged native-resource soak testing.
