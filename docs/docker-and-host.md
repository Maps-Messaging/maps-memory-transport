# Linux Docker and host-process communication

This guide explains how to connect Maps Memory Transport processes when one
or more of them run inside Docker containers. It covers **host ↔ container**,
**container ↔ container**, and **host ↔ host** connections on the **same Linux
kernel**. See [architecture](architecture.md) and [connection examples](connections.md)
for the transport API and complete send/receive loops.

## Which transport to use

Use the **native Linux shared-memory provider** with a
`UnixDomainSocketAddress`. The server creates a filesystem Unix-domain
socket for control and peer admission, then transfers two **anonymous memfd
descriptors** with `SCM_RIGHTS`. The shared-memory data buffers are not
ordinary files and do not need a shared `/dev/shm` mount.

This works between containers or between a container and a host process
because they use the **same Linux kernel**. The mechanism cannot connect
processes on different Docker hosts, or a macOS/Windows host-native process
directly to the Linux VM inside Docker Desktop.

The legacy `InetSocketAddress`/file-backed shared-memory API is not the
appropriate provider for this deployment, even if TCP loopback is reachable
through Docker networking. If peers are on different hosts, use a network
transport such as TCP or a separately configured RDMA transport.

## Security and permissions: read before running

1. The control socket directory must **already exist**. The server checks
   the directory's permissions and rejects group/other traversal. Use a
   private directory with mode `0700` owned by the server's Linux UID.
2. The Linux rendezvous verifies `SO_PEERCRED` from the kernel and requires
   the client's **effective kernel UID** to match the server's effective UID.
   A `user: "1000:1000"` Docker setting generally accomplishes this in
   ordinary rootful Docker **without** user-namespace remapping. With
   `userns-remap`, rootless Docker or idmapped mounts, numeric UIDs inside
   containers may not correspond to the same kernel UID. Check the actual
   namespace and filesystem mapping.
3. Both endpoints must refer to the **same socket pathname**, visible inside
   their own mount namespaces. Mount the **directory**, not the socket file:
   the socket may be recreated when the server restarts.
4. Do not grant access by running the containers `--privileged`, changing
   socket directory permissions to `0777`, or disabling peer validation.
   Processes with the same UID already share a local trust boundary. For
   additional cryptographic identity verification and record protection,
   configure `MemorySecurity` on **both** peers.
5. The native Java 25 FFM calls require native access permission, for
   example `--enable-native-access=io.mapsmessaging.memory` for an explicitly
   named module or `--enable-native-access=ALL-UNNAMED` when running the
   library on the classpath. Choose according to your actual launcher setup.

Normal Docker bridge networking is fine: data exchange does **not** use
Docker TCP ports. There is no need for `network_mode: host`,
`ipc: host`, `--privileged`, a published port or a common `/dev/shm`.

## Common Java code

The following API usage is the same whether the two JVMs run on the host or
in containers. A server process must begin listening before its client
attempts to connect. These fragments are **separate programs**, not code
to run sequentially in one thread.

**Server process:**

```java
import io.mapsmessaging.memory.MemoryConnection;
import io.mapsmessaging.memory.MemoryEndpointConfig;
import io.mapsmessaging.memory.MemoryServer;
import io.mapsmessaging.memory.MemoryTransportType;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Path;

UnixDomainSocketAddress socket =
    UnixDomainSocketAddress.of(Path.of("/run/maps-ipc/transport.sock"));

MemoryEndpointConfig config = MemoryEndpointConfig.builder()
    .transport(MemoryTransportType.SHARED_MEMORY)
    .address(socket)
    .slotSize(1024)
    .slotCount(32)
    .maxConnections(16)
    .maxNegotiations(8)
    .build();

try (MemoryServer server = MemoryServer.listen(config)) {
  while (!server.isClosed()) {
    try (MemoryConnection peer = server.accept()) {
      // Process peer.read(...) and peer.write(...) using ByteBuffer.
      // For simultaneous peers, hand each connection off to your own
      // lifecycle-managed worker instead of serially processing here.
    }
  }
}
```

**Client process:**

```java
import io.mapsmessaging.memory.MemoryClient;
import io.mapsmessaging.memory.MemoryConnection;
import io.mapsmessaging.memory.MemoryConnectionConfig;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Path;

UnixDomainSocketAddress socket =
    UnixDomainSocketAddress.of(Path.of("/run/maps-ipc/transport.sock"));

try (MemoryConnection peer = MemoryClient.connect(
    MemoryConnectionConfig.linuxSharedMemory(socket))) {
  // Use peer.write(...) / peer.read(...) with normal partial-I/O loops.
}
```

These examples establish the connection; refer to
[connection examples](connections.md) for complete I/O and secure
Ed25519/AES-GCM configuration. Do not construct a `MemorySecurity`
object on just one peer: both peers must explicitly trust each other's
persistent signing identities and use compatible protection modes.

## A. Host server ↔ Docker client

Use an absolute host path for a private directory, for example
`/srv/maps-ipc`.

**Prepare on the Linux host** (example service UID 1000):

```bash
sudo install -d -m 0700 -o 1000 -g 1000 /srv/maps-ipc
sudo stat -c '%u:%g %a %n' /srv/maps-ipc
```

Start the host JVM as UID 1000, binding
`/srv/maps-ipc/transport.sock`. Its container client sees the directory
mounted at `/run/maps-ipc`:

```yaml
services:
  maps-client:
    image: example/maps-memory-client:latest # Replace with your own image
    user: "1000:1000"
    volumes:
      - type: bind
        source: /srv/maps-ipc
        target: /run/maps-ipc
    # Your application must actually use this value; it is not a library
    # environment variable recognized automatically.
    environment:
      MAPS_MEMORY_SOCKET: /run/maps-ipc/transport.sock
```

The host server path is `/srv/maps-ipc/transport.sock`, while the
container client path is `/run/maps-ipc/transport.sock`. They reference
the **same socket inode** through the bind mount. Start the host server
first; then start the container client.

## B. Docker server ↔ host client

Use the same private directory, but reverse the roles:

```yaml
services:
  maps-server:
    image: example/maps-memory-server:latest # Replace with your own image
    user: "1000:1000"
    volumes:
      - type: bind
        source: /srv/maps-ipc
        target: /run/maps-ipc
    environment:
      MAPS_MEMORY_SOCKET: /run/maps-ipc/transport.sock
```

Start the container server first. The host client, also running as UID
1000, connects to `/srv/maps-ipc/transport.sock`. The server must
have permission to create the socket in the mounted directory.

## C. Docker server ↔ Docker client

Both containers mount **the same host directory** into their own
`/run/maps-ipc` paths. This Compose file is an illustrative deployment
configuration; the images and environment-to-configuration mapping are
application-specific:

```yaml
services:
  maps-server:
    image: example/maps-memory-server:latest
    user: "1000:1000"
    volumes:
      - type: bind
        source: /srv/maps-ipc
        target: /run/maps-ipc
    environment:
      MAPS_MEMORY_SOCKET: /run/maps-ipc/transport.sock

  maps-client:
    image: example/maps-memory-client:latest
    user: "1000:1000"
    depends_on:
      - maps-server
    volumes:
      - type: bind
        source: /srv/maps-ipc
        target: /run/maps-ipc
    environment:
      MAPS_MEMORY_SOCKET: /run/maps-ipc/transport.sock
```

`depends_on` controls startup ordering, **not readiness**. Configure
the client to retry until the server's control socket is listening, or
use an application-specific health check. Avoid granting untrusted
containers the same UID and socket directory merely to make a connection
work: doing so intentionally places them inside the same Unix identity
boundary.

## D. Host process ↔ host process

No Docker configuration is needed. Create the private directory, run
both JVMs as the same effective UID, start the listener, and use
the same `UnixDomainSocketAddress` path for both processes. This is
also a straightforward way to verify the connection before testing
container namespaces.

## Secure deployment between containers

`MemorySecurity` works across a Docker boundary using the same API as
host-to-host. Put persistent Ed25519 identity and trust configuration in
each application via its normal secure key-management mechanism. Do not
bake private keys into public images or share the same private signing
key between endpoints just to satisfy the UID admission requirement.

Example configuration shape (variables are illustrative; your
application must load the actual keys and trust policy):

```java
MemoryEndpointConfig config = MemoryEndpointConfig.builder()
    .address(socketAddress)
    .security(serverSecurity)
    .build();

MemoryConnectionConfig clientConfig = new MemoryConnectionConfig(
    MemoryTransportType.SHARED_MEMORY, socketAddress, 5_000, clientSecurity);
```

Both endpoints must use the native Unix-domain socket provider and match
`DataProtection`. `AES_256_GCM` and `CHACHA20_POLY1305` encrypt and
authenticate records. `HMAC_SHA_256` authenticates but **does not encrypt**.

## Diagnostics

| Symptom | Likely cause / check |
| --- | --- |
| Socket not found / connection refused | Server not listening, wrong bind-mounted directory, or stale socket path |
| Permission denied on directory/socket | UID/GID mismatch, mount ownership, directory modes or user namespace mappings |
| Peer credentials rejected | Different effective **kernel** UIDs, even if both containers report the same UID internally |
| Native handshake times out | Server not ready or peer aborted; check both JVM logs and applicable configured timeouts |
| Security handshake rejected | One side omitted `MemorySecurity`, keys/claimed identities not trusted, or modes differ |
| Works host-to-host but not in containers | Check mounts, user namespaces, read-only bind mounts, AppArmor/seccomp policy and actual FFM permissions |
| Works on one machine but not across nodes | memfd/SCM_RIGHTS require a shared Linux kernel; use a network transport across hosts |

Useful diagnostics:

```bash
# On host
stat -c '%u:%g %a %n' /srv/maps-ipc
ls -ld /srv/maps-ipc
ls -l /srv/maps-ipc/transport.sock

# In each container
id
ls -ld /run/maps-ipc
ls -l /run/maps-ipc/transport.sock

# Host, if available: inspect the listening Unix socket
ss -xl | grep transport.sock
```

Container metadata alone does not prove compatible effective kernel UIDs.
When user namespaces are involved, inspect the UID maps (for example
`/proc/self/uid_map`) and the host-side mapping before changing permissions.

## What is verified and what is not

The native Linux implementation and separate-process shared-memory tests
have passed on Linux. The host/container and container/container deployment
patterns here follow Linux Unix-domain socket, descriptor-passing and
Docker bind-mount semantics, but **the current test suite does not yet
establish container-to-container success for all combinations of user
namespaces, rootless Docker, and security profiles**. Test the exact
deployment configuration before treating it as validated. Windows/macOS
native host processes do not directly share this Linux memfd mechanism
with Docker Desktop Linux containers.
