# BedrockProxy 🎮

A lightweight LAN proxy that lets PS5, Xbox, and mobile players join **external Bedrock Edition servers** by making them appear as a LAN game.

---

## How It Works

```
PS5 / Xbox / Mobile
       │
       │  (sees as LAN game)
       ▼
 [BedrockProxy] ← running on your PC/laptop
       │
       │  (forwards all traffic)
       ▼
 External Bedrock Server (anywhere on internet)
```

---

## Requirements

- A **PC or laptop** on the same Wi-Fi as your PS5/Xbox
- **Java 11 or newer** installed → https://adoptium.net
- The PC and PS5 must be on the **same network/router**

---

## Quick Start

### Step 1 – Edit `config.properties`

Open `config.properties` in Notepad and set your server:

```properties
server.host=play.yourserver.com
server.port=19132
server.name=My Server
```

### Step 2 – Run the proxy

**Windows:** Double-click `start.bat`

**Mac/Linux:** 
```bash
chmod +x start.sh
./start.sh
```

**Or manually:**
```bash
java -jar BedrockProxy.jar
```

### Step 3 – Join on PS5/Xbox

1. Open Minecraft on your PS5/Xbox
2. Go to **Play → Friends tab**
3. Look under **"LAN Games"**
4. Your server will appear — tap it to join!

---

## Command Line Usage

You can also pass server details directly without editing the config:

```bash
java -jar BedrockProxy.jar <server-ip> <server-port>
# Example:
java -jar BedrockProxy.jar play.hypixel.net 19132
```

Or with a custom name:
```bash
java -jar BedrockProxy.jar play.hypixel.net 19132 19132 "Hypixel Server"
```

---

## Building from Source

Requires Maven and Java 11+:

```bash
mvn clean package
# Output: target/BedrockProxy.jar
```

---

## Troubleshooting

**PS5 doesn't see the server:**
- Make sure your PC and PS5 are on the **same Wi-Fi network**
- Check Windows Firewall — allow Java through the firewall
- Try disabling your firewall temporarily to test
- Make sure port **19132 UDP** is not already in use

**Can't connect after seeing it:**
- Verify the remote server IP and port in `config.properties`
- Make sure the remote server is actually online
- Some servers require a specific version — check the server's requirements

**Port already in use error:**
- Another Minecraft server or proxy is using port 19132
- Close it, or change `local.port` in config.properties to 19133

---

## Notes

- Keep the proxy running while you play — closing it will disconnect you
- The proxy PC doesn't need to be powerful, any old laptop works
- Works with PS5, Xbox One/Series, Nintendo Switch, iOS, and Android
