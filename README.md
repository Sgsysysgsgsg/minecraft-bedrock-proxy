# BedrockBridge

**A Bedrock-to-Bedrock proxy — like Geyser, but for Bedrock clients joining Bedrock servers.**

```
[Bedrock Client] ──RakNet/UDP──► [BedrockBridge] ──RakNet/UDP──► [Bedrock Server]
                                        │
                                 LAN Broadcast
                                 (shows up as
                                  a LAN world)
```

---

## What it does

Geyser lets **Bedrock clients join Java servers**.  
BedrockBridge lets **Bedrock clients connect to a remote Bedrock server via LAN discovery** — just like in the screenshot where "Another Geyser server" appears as a LAN world.

### Features
- 🔁 **Transparent proxy** — forwards all Bedrock packets between client and server
- 📡 **LAN broadcaster** — the proxy appears in your Worlds tab as a LAN world automatically
- ⚙️ **Simple YAML config** — point it at any Bedrock server (BDS, Nukkit, PocketMine, etc.)
- 🏗️ **Geyser-style architecture** — easy to extend with packet interception

---

## Architecture

```
src/main/java/dev/bedrockbridge/
├── bootstrap/
│   └── BedrockBridgeMain.java        # Entry point
├── proxy/
│   └── BedrockBridge.java            # Core orchestrator (like Geyser.java)
├── config/
│   └── BedrockBridgeConfig.java      # Loads config.yml
├── session/
│   └── ProxySession.java             # One per connected client
│                                       ties upstream + downstream together
├── network/
│   ├── upstream/
│   │   └── UpstreamPacketHandler.java  # Handles packets FROM the client
│   └── downstream/
│       └── DownstreamPacketHandler.java # Handles packets FROM the server
└── lan/
    └── LanBroadcaster.java             # UDP broadcast so clients see us as LAN
```

### Packet flow

```
CLIENT                    BEDROCKBRIDGE               REMOTE SERVER
  │                            │                            │
  │── RequestNetworkSettings ─►│                            │
  │◄─ NetworkSettingsPacket ───│                            │
  │── LoginPacket ────────────►│── LoginPacket ────────────►│
  │                            │◄─ ServerToClientHandshake ─│
  │◄─ ServerToClientHandshake ─│── ClientToServerHandshake ►│
  │                            │◄─ PlayStatus(LOGIN_SUCCESS)─│
  │◄─ PlayStatus ──────────────│                            │
  │         [PASSTHROUGH MODE]                              │
  │── AnyPacket ──────────────►│── AnyPacket ──────────────►│
  │◄─ AnyPacket ───────────────│◄─ AnyPacket ───────────────│
```

---

## Building

Requirements: Java 17+, Gradle

```bash
git clone https://github.com/Sgsysysgsgsg/BedrockBridge
cd BedrockBridge
./gradlew build
```

Output: `build/libs/BedrockBridge.jar`

---

## Running

```bash
java -jar BedrockBridge.jar
```

On first run, a `config.yml` is generated. Edit it to point at your server:

```yaml
proxy:
  bind-address: "0.0.0.0"
  port: 19150           # Port clients connect to (must be different from 19132 if server is local)

remote:
  address: "your.server.ip"
  port: 19132           # Your Bedrock server's port

lan:
  enabled: true
  motd: "My Server"     # Name shown in the Worlds tab
  sub-motd: "Join us!"
  broadcast-interval-ms: 1500

max-players: 20
```

---

## Libraries used

| Library | Purpose |
|---|---|
| [CloudburstMC/Protocol](https://github.com/CloudburstMC/Protocol) | Bedrock protocol encode/decode (same as Geyser) |
| [CloudburstMC/Network](https://github.com/CloudburstMC/Network) | RakNet transport (Bedrock uses RakNet over UDP) |
| [Netty](https://netty.io/) | Async networking |
| [SnakeYAML](https://github.com/snakeyaml/snakeyaml) | Config file parsing |
| [Logback](https://logback.qos.ch/) | Logging |
| [Lombok](https://projectlombok.org/) | Boilerplate reduction |

---

## Extending BedrockBridge

### Intercepting packets

To inspect or modify packets mid-flight, override the specific packet handler in `UpstreamPacketHandler` or `DownstreamPacketHandler`:

```java
// Example: log every chat message the client sends
@Override
public PacketSignal handle(TextPacket packet) {
    LOGGER.info("Chat: {}", packet.getMessage());
    session.sendDownstream(packet); // still forward it
    return PacketSignal.HANDLED;
}
```

### Adding a plugin API (future)

A `PacketInterceptor` interface can be added so plugins can hook into the pipeline without modifying core classes — similar to how Geyser extensions work.

---

## Roadmap

- [ ] Encryption support (online-mode servers)
- [ ] Multi-server support (connect different players to different backends)
- [ ] Plugin/extension API
- [ ] Web dashboard for monitoring sessions
- [ ] Docker image
