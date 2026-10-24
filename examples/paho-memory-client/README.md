# Paho MQTT over Maps Memory Transport

This standalone example artifact provides Paho MQTT 3.1/3.1.1 and MQTT 5 network
factories for `shm` and `rdma`. It has its own POM and depends on Paho 1.2.5 and
`memory-transport`. The core transport POM, API, module descriptor and build remain
independent of Paho. This is not a module of the root Maven build.

The factories use `MemoryClient.connect(MemoryConnectionConfig)` directly. They do
not depend on the MapsMessaging server or its endpoint implementation.

## Build

With JDK 25, first install the core library from the repository root, then build
the example separately:

```sh
mvn install -DskipTests -Dgpg.skip=true
mvn -f examples/paho-memory-client/pom.xml verify
```

The root build does not build, publish or install this example. Deploying the
standalone example artifact is a separate explicit operation. Its coordinates are:

```xml
<dependency>
  <groupId>io.mapsmessaging</groupId>
  <artifactId>memory-transport-paho-example</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

For a consuming project's tests, add `<scope>test</scope>`. Keep the example JAR
and dependencies on the runtime classpath: both Paho SPI service entries are in
this JAR. A shaded consumer must merge `META-INF/services` resources.

## Configure a connection

The Paho URI is a logical profile name, not an exposed memory mapping name. Register
an immutable library configuration before creating the Paho client. Keep the
registration alive until that client has closed, including automatic reconnects.
Duplicate registrations fail; closing an old registration cannot remove a newer
one. Unregistered profiles fail explicitly with no TCP or unsecured fallback.

```java
String serverURI = "shm://local-mqtt";
MemoryConnectionConfig config = new MemoryConnectionConfig(
    MemoryTransportType.SHARED_MEMORY,
    UnixDomainSocketAddress.of("/run/user/1000/maps/mqtt.sock"),
    5000,
    null);

try (MemoryNetworkConfig.Registration registration = MemoryNetworkConfig.register(serverURI, config)) {
  MqttClient client = new MqttClient(serverURI, "memory-example", new MemoryPersistence());
  try {
    client.connect();
    client.publish("example/topic", "hello".getBytes(StandardCharsets.UTF_8), 1, false);
    client.disconnect();
  } finally {
    client.close();
  }
}
```

For MQTT 3, import `MqttClient`/`MemoryPersistence` from
`org.eclipse.paho.client.mqttv3`; for MQTT 5 use
`org.eclipse.paho.mqttv5.client`. The two SPI factories use the same memory stream
implementation. MQTT username/password, session, QoS and reconnect settings stay
in the normal Paho connection options.

On Windows replace the address with `WindowsPipeAddress.of("mqtt-local")`. On
Linux/macOS use a Unix-domain address and pre-create its parent with owner-only
traversal permissions. The library performs its normal OS peer-admission checks.
Native shared-memory peers use the same OS account. The older loopback/file-backed
mode can be selected explicitly using `MemoryConnectionConfig.sharedMemory(...)`.

For RDMA use a registered `rdma://profile` and `MemoryTransportType.RDMA` with an
`InetSocketAddress`. The hosts need the library's supported RDMA platform,
rdma-core and a usable RDMA interface. No fallback transport is selected.

## Transport security

Pass the library's existing `MemorySecurity` object as the fourth constructor
argument instead of `null`, on both client and listener. Configure matching
protection modes and explicitly trust the peer's Ed25519 identity. This example
does not generate persistent identities, enroll peers, load keys from URIs or add
another identity store. See [the core connection guide](../../docs/connections.md)
for construction and key/trust examples. HMAC authenticates but does not encrypt;
AES-256-GCM and ChaCha20-Poly1305 provide authenticated encryption.

Paho TLS options do not configure memory transport security. Select protection
through `MemoryConnectionConfig.security()`; leave Paho socket/TLS factories unset.
The configured memory connect timeout controls transport establishment. Paho's
connection timeout also bounds its MQTT CONNECT/CONNACK exchange. Reads/writes
wait for progress without returning spurious zero-length reads or dropping partial
writes. Interruption and close stop blocked stream operations; a failed write closes
the connection so pending protected records cannot be reused incorrectly.

## Runnable publisher

`PahoMemoryExample` publishes a QoS 1 MQTT 5 message. Supply four arguments:

```text
shm /run/user/1000/maps/mqtt.sock example/topic hello
pipe mqtt-local example/topic hello
rdma 192.0.2.10:7471 example/topic hello
```

Run it with the example and dependencies on the classpath and
`--enable-native-access=ALL-UNNAMED`. The command-line publisher selects unsecured
transport; use the configuration API above for secured connections. Its peer must
speak MQTT over the selected memory transport; `MemoryServer` alone is a byte
transport, not an MQTT broker.

## Tests

```sh
mvn -f examples/paho-memory-client/pom.xml test
```

- Stream tests cover partial/zero progress, EOF, byte values, interruption and close.
- SPI tests resolve both Paho factories and check secure configuration forwarding.
- Mapped-ring tests transfer large payloads in both directions, unsecured and with
  every record-protection mode, without socket rendezvous.
- `PahoMemoryNativeTest` uses real Paho clients with `MemoryServer` directly. Its
  bounded, minimal MQTT test peer accepts CONNECT and a QoS 1 PUBLISH, validates the
  payload and sends CONNACK/PUBACK. It is a transport example test, not a broker or
  MQTT conformance implementation. No server endpoint classes are used.
- The RDMA test runs only with `MAPS_RDMA_TEST_ADDRESS` and usable RDMA hardware.

On an execution host that prohibits native sockets, run the socket-independent
checks explicitly and record the native test limitation:

```sh
mvn -f examples/paho-memory-client/pom.xml \
  -Dtest=MemoryStreamsTest,MemoryNetworkFactoryTest,MemoryStreamsIntegrationTest test
```

Native tests are not disabled by default. Run them on Linux, macOS or Windows
with native access permitted before claiming target-platform interoperability.
