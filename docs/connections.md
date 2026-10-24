# Connection examples

These examples use the **managed** API in Java 25. They start a listener,
connect a client, accept the connection, exchange bytes in both directions
and close their resources. For the underlying implementation, see
[Architecture and operation](architecture.md).

## 1. Simple local shared-memory connection

Save as `SimpleMemoryExample.java`. The example selects the appropriate
native local endpoint for Linux, macOS or Windows and starts both sides
in one JVM. It also works with separate JVMs when the client uses the
server's chosen address.

```java
import io.mapsmessaging.memory.MemoryClient;
import io.mapsmessaging.memory.MemoryConnection;
import io.mapsmessaging.memory.MemoryConnectionConfig;
import io.mapsmessaging.memory.MemoryEndpointConfig;
import io.mapsmessaging.memory.MemoryServer;
import io.mapsmessaging.memory.MemoryTransportType;
import io.mapsmessaging.memory.WindowsPipeAddress;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

public final class SimpleMemoryExample {

  public static void main(String[] arguments) throws Exception {
    boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    Path directory = windows ? null : Files.createTempDirectory(
        "maps-example-",
        PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")));

    SocketAddress address = windows
        ? WindowsPipeAddress.of("demo-" + UUID.randomUUID())
        : UnixDomainSocketAddress.of(directory.resolve("transport.sock"));

    try {
      MemoryEndpointConfig listenerConfig = MemoryEndpointConfig.builder()
          .transport(MemoryTransportType.SHARED_MEMORY)
          .address(address)
          .slotSize(1024)
          .slotCount(16)
          .maxConnections(8)
          .build();

      MemoryConnectionConfig clientConfig = new MemoryConnectionConfig(
          MemoryTransportType.SHARED_MEMORY, address, 5_000, null);

      try (MemoryServer server = MemoryServer.listen(listenerConfig);
           MemoryConnection client = MemoryClient.connect(clientConfig);
           MemoryConnection peer = server.accept()) {

        send(client, "Hello from client");
        System.out.println("Server received: " + receive(peer, 17));

        send(peer, "Hello from server");
        System.out.println("Client received: " + receive(client, 17));
      }
    } finally {
      if (directory != null) {
        Files.deleteIfExists(((UnixDomainSocketAddress) address).getPath());
        Files.deleteIfExists(directory);
      }
    }
  }

  private static void send(MemoryConnection connection, String message)
      throws Exception {
    ByteBuffer source = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (source.hasRemaining()) {
      if (System.nanoTime() >= deadline) {
        throw new java.io.IOException("Write timed out");
      }
      if (connection.canWrite()) {
        connection.write(source);
      } else {
        Thread.sleep(1);
      }
    }
  }

  private static String receive(MemoryConnection connection, int bytes)
      throws Exception {
    ByteBuffer destination = ByteBuffer.allocate(bytes);
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (destination.hasRemaining()) {
      if (System.nanoTime() >= deadline) {
        throw new java.io.IOException("Read timed out");
      }
      if (connection.hasData()) {
        connection.read(destination);
      } else {
        Thread.sleep(1);
      }
    }
    return new String(destination.array(), StandardCharsets.UTF_8);
  }
}
```

The example fixes the response length because the `MemoryTransport`
interface is a **byte stream**, not a message API. Real applications should
use their existing application framing, or encode and validate a bounded
length prefix. `read()` and `write()` can be partial or return zero.

For independent processes, create the listener first and start the client
separately with the same socket path (Unix) or pipe name (Windows). On
Linux/macOS the socket's parent directory must exist with private permissions.

## 2. Authenticated and encrypted local connection

The following snippet shows the security configuration and the complete
connection lifecycle. It uses an Ed25519 key pair for each side, pins
the **other** side's public key through `PeerTrust` and enables
`AES_256_GCM` record protection.

For clarity this starts both endpoints in one JVM. In separate processes,
load persistent, independently provisioned identities from your existing
keystore. **Do not regenerate production identities at startup.**

Save as `SecureMemoryExample.java`.

```java
import io.mapsmessaging.memory.MemoryClient;
import io.mapsmessaging.memory.MemoryConnection;
import io.mapsmessaging.memory.MemoryConnectionConfig;
import io.mapsmessaging.memory.MemoryEndpointConfig;
import io.mapsmessaging.memory.MemoryServer;
import io.mapsmessaging.memory.MemoryTransportType;
import io.mapsmessaging.memory.WindowsPipeAddress;
import io.mapsmessaging.memory.security.DataProtection;
import io.mapsmessaging.memory.security.MemorySecurity;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class SecureMemoryExample {

  public static void main(String[] arguments) throws Exception {
    // Demonstration only: in production, load persistent keys from a keystore.
    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    KeyPair serverIdentity = generator.generateKeyPair();
    KeyPair clientIdentity = generator.generateKeyPair();

    MemorySecurity serverSecurity = MemorySecurity.builder()
        .identity("example-server", serverIdentity.getPrivate(), serverIdentity.getPublic())
        .trust((name, key) -> "example-client".equals(name)
            && key.equals(clientIdentity.getPublic()))
        .protection(DataProtection.AES_256_GCM)
        .build();

    MemorySecurity clientSecurity = MemorySecurity.builder()
        .identity("example-client", clientIdentity.getPrivate(), clientIdentity.getPublic())
        .trust((name, key) -> "example-server".equals(name)
            && key.equals(serverIdentity.getPublic()))
        .protection(DataProtection.AES_256_GCM)
        .build();

    boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    Path directory = windows ? null : Files.createTempDirectory(
        "maps-secure-example-",
        PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")));

    SocketAddress address = windows
        ? WindowsPipeAddress.of("secure-" + UUID.randomUUID())
        : UnixDomainSocketAddress.of(directory.resolve("secure.sock"));

    try {
      MemoryEndpointConfig serverConfig = MemoryEndpointConfig.builder()
          .transport(MemoryTransportType.SHARED_MEMORY)
          .address(address)
          .slotSize(1024)
          .slotCount(32)
          .maxConnections(8)
          .maxNegotiations(4)
          .handshakeTimeoutMillis(5_000)
          .security(serverSecurity)
          .build();

      MemoryConnectionConfig clientConfig = new MemoryConnectionConfig(
          MemoryTransportType.SHARED_MEMORY, address, 5_000, clientSecurity);

      try (MemoryServer server = MemoryServer.listen(serverConfig);
           MemoryConnection client = MemoryClient.connect(clientConfig);
           MemoryConnection accepted = server.accept()) {

        byte[] message = "Encrypted hello".getBytes(StandardCharsets.UTF_8);
        ByteBuffer outgoing = ByteBuffer.wrap(message);
        ByteBuffer incoming = ByteBuffer.allocate(message.length);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while ((outgoing.hasRemaining() || incoming.hasRemaining())
            && System.nanoTime() < deadline) {
          if (outgoing.hasRemaining() && client.canWrite()) {
            client.write(outgoing);
          }
          if (incoming.hasRemaining() && accepted.hasData()) {
            accepted.read(incoming);
          }
          Thread.sleep(1);
        }
        if (outgoing.hasRemaining() || incoming.hasRemaining()) {
          throw new java.io.IOException("Secure transfer timed out");
        }
        System.out.println("Authenticated and decrypted: "
            + new String(incoming.array(), StandardCharsets.UTF_8));
      }
    } finally {
      if (directory != null) {
        Files.deleteIfExists(((UnixDomainSocketAddress) address).getPath());
        Files.deleteIfExists(directory);
      }
    }
  }
}
```

The library performs the Ed25519-signed X25519 handshake and key
confirmation for you during `connect()`/`accept()`. The application never
passes raw session keys to `read()` or `write()`. The secure transport
authenticates record headers and rejects modified or out-of-order records.

Alternative protection modes:

- `DataProtection.CHACHA20_POLY1305`: authenticated encryption
- `DataProtection.HMAC_SHA_256`: integrity/authentication without encryption

Both peers must select the same mode. An unsecured client cannot silently
downgrade a secured native endpoint.

## 3. RDMA: same managed API

RDMA is available only where rdma-core and suitable RDMA hardware are
configured. After checking `RdmaSupport.probe()`, configure the endpoint
with `MemoryTransportType.RDMA` and an `InetSocketAddress`. The optional
`MemorySecurity` is configured in the same way as in the secure example:

```java
InetSocketAddress rdmaAddress = new InetSocketAddress("192.0.2.10", 7471);

MemoryEndpointConfig rdmaServer = MemoryEndpointConfig.builder()
    .transport(MemoryTransportType.RDMA)
    .address(rdmaAddress)
    .security(serverSecurity)
    .build();

MemoryConnectionConfig rdmaClient = new MemoryConnectionConfig(
    MemoryTransportType.RDMA, rdmaAddress, 10_000, clientSecurity);

// In the listener process:
try (MemoryServer server = MemoryServer.listen(rdmaServer)) {
  try (MemoryConnection accepted = server.accept()) {
    // Read and write ByteBuffers as above.
  }
}

// In a separate client process:
try (MemoryConnection connection = MemoryClient.connect(rdmaClient)) {
  // Read and write ByteBuffers as above.
}
```

The RDMA addresses above are documentation-only placeholders. Replace them
with an actual host/interface address. The server and client code blocks
represent **separate processes**; the blocking server `accept()` example
is not meant to precede an in-process client `connect()` sequentially.

## Configuration and safety notes

The listener's configured defaults are 64 active connections, 64 pending
connections and 16 negotiations; these limits can be tuned. `maxConnections`,
`maxPendingConnections` and `maxNegotiations` can be set to zero to disable
the respective limit. Always close connections on errors and shutdown.

The legacy loopback `InetSocketAddress` file-backed shared-memory transport
is maintained for compatibility. **It does not support the secure
`MemorySecurity` configuration.** Use a Unix-domain or Windows pipe
address to select the native local provider.

For Docker-to-Docker Linux communication, bind-mount the same private Unix
control-socket directory into both containers and ensure the process UIDs
match under container user-namespace mappings. The anonymous memfd regions
are shared by passed descriptors, not a common named data file.

Java 25 and native-access permission are required. Maven's configured
test launcher enables native access for the test JVM. Application launchers
using these FFM-based implementations must grant native access to the
library/module as appropriate.
