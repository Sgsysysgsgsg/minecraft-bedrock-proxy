package dev.bedrockbridge.lan;

import dev.bedrockbridge.proxy.BedrockBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * LanBroadcaster periodically sends a UDP broadcast on the LAN that makes
 * the BedrockBridge proxy show up in the Bedrock client's
 * Play > Worlds tab as a LAN world.
 *
 * This is exactly what the screenshot shows — "Another Geyser server" appearing
 * as a LAN world. We replicate that mechanism.
 *
 * How Bedrock LAN discovery works:
 *  - Bedrock Edition listens for UDP packets on port 19132
 *  - The packet format is a RakNet UNCONNECTED_PONG with a specific MOTD string
 *  - MOTD format: "MCPE;<name>;<protocol>;<version>;<players>;<maxPlayers>;<serverId>;<subMotd>;<gameType>;<nintendo>"
 *
 * Broadcast address: 255.255.255.255 (or network broadcast like 192.168.1.255)
 */
public class LanBroadcaster {

    private static final Logger LOGGER = LoggerFactory.getLogger(LanBroadcaster.class);

    // RakNet UNCONNECTED_PONG packet ID
    private static final byte UNCONNECTED_PONG = 0x1C;

    // RakNet offline message magic bytes (always the same)
    private static final byte[] OFFLINE_MESSAGE_DATA_ID = {
            0x00, (byte) 0xFF, (byte) 0xFF, 0x00,
            (byte) 0xFE, (byte) 0xFE, (byte) 0xFE, (byte) 0xFE,
            (byte) 0xFD, (byte) 0xFD, (byte) 0xFD, (byte) 0xFD,
            0x12, 0x34, 0x56, 0x78
    };

    private final BedrockBridge bridge;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> broadcastTask;
    private DatagramSocket socket;

    // Unique server GUID (generated once per run)
    private final long serverGuid = System.currentTimeMillis();

    public LanBroadcaster(BedrockBridge bridge) {
        this.bridge = bridge;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lan-broadcaster");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(1000);

            int intervalMs = bridge.getConfig().getLanBroadcastIntervalMs();
            broadcastTask = scheduler.scheduleAtFixedRate(
                    this::broadcast,
                    0,
                    intervalMs,
                    TimeUnit.MILLISECONDS
            );

            LOGGER.info("LAN broadcaster started (interval: {}ms)", intervalMs);
        } catch (SocketException e) {
            LOGGER.error("Failed to start LAN broadcaster", e);
        }
    }

    public void stop() {
        if (broadcastTask != null) {
            broadcastTask.cancel(false);
        }
        scheduler.shutdownNow();
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        LOGGER.info("LAN broadcaster stopped");
    }

    private void broadcast() {
        try {
            byte[] payload = buildUnconnectedPong();
            DatagramPacket packet = new DatagramPacket(
                    payload,
                    payload.length,
                    InetAddress.getByName("255.255.255.255"),
                    19132   // Bedrock's discovery port — always 19132
            );
            socket.send(packet);
            LOGGER.trace("LAN broadcast sent ({} bytes)", payload.length);
        } catch (IOException e) {
            LOGGER.warn("LAN broadcast failed: {}", e.getMessage());
        }
    }

    /**
     * Builds a minimal RakNet UNCONNECTED_PONG packet.
     *
     * Packet layout:
     *   [1 byte]  Packet ID = 0x1C
     *   [8 bytes] Ping time (millis)
     *   [8 bytes] Server GUID
     *   [16 bytes] Offline message magic
     *   [2 bytes] MOTD string length
     *   [N bytes] MOTD string (UTF-8)
     */
    private byte[] buildUnconnectedPong() {
        String motd = buildMotdString();
        byte[] motdBytes = motd.getBytes(StandardCharsets.UTF_8);

        int size = 1 + 8 + 8 + 16 + 2 + motdBytes.length;
        byte[] buf = new byte[size];
        int pos = 0;

        // Packet ID
        buf[pos++] = UNCONNECTED_PONG;

        // Ping time (8 bytes, big-endian)
        long pingTime = System.currentTimeMillis();
        buf[pos++] = (byte) (pingTime >> 56);
        buf[pos++] = (byte) (pingTime >> 48);
        buf[pos++] = (byte) (pingTime >> 40);
        buf[pos++] = (byte) (pingTime >> 32);
        buf[pos++] = (byte) (pingTime >> 24);
        buf[pos++] = (byte) (pingTime >> 16);
        buf[pos++] = (byte) (pingTime >> 8);
        buf[pos++] = (byte) (pingTime);

        // Server GUID (8 bytes)
        buf[pos++] = (byte) (serverGuid >> 56);
        buf[pos++] = (byte) (serverGuid >> 48);
        buf[pos++] = (byte) (serverGuid >> 40);
        buf[pos++] = (byte) (serverGuid >> 32);
        buf[pos++] = (byte) (serverGuid >> 24);
        buf[pos++] = (byte) (serverGuid >> 16);
        buf[pos++] = (byte) (serverGuid >> 8);
        buf[pos++] = (byte) (serverGuid);

        // Offline magic bytes (16 bytes)
        System.arraycopy(OFFLINE_MESSAGE_DATA_ID, 0, buf, pos, 16);
        pos += 16;

        // MOTD length (2 bytes, big-endian)
        buf[pos++] = (byte) (motdBytes.length >> 8);
        buf[pos++] = (byte) (motdBytes.length);

        // MOTD string
        System.arraycopy(motdBytes, 0, buf, pos, motdBytes.length);

        return buf;
    }

    /**
     * Builds the Bedrock MOTD string.
     * Format: "MCPE;<name>;<protocol>;<version>;<players>;<maxPlayers>;<serverId>;<subMotd>;<gameType>;1"
     *
     * This is what Bedrock clients parse to show the server name, player count etc.
     */
    private String buildMotdString() {
        var config = bridge.getConfig();
        int onlinePlayers = bridge.getSessions().size();
        int protocol = BedrockBridge.BEDROCK_CODEC.getProtocolVersion();
        String version = BedrockBridge.BEDROCK_CODEC.getMinecraftVersion();

        return "MCPE" +
                ";" + config.getLanMotd() +
                ";" + protocol +
                ";" + version +
                ";" + onlinePlayers +
                ";" + config.getMaxPlayers() +
                ";" + serverGuid +
                ";" + config.getLanSubMotd() +
                ";Survival" +
                ";1" +          // 1 = shows as LAN world
                ";" + config.getProxyPort() +
                ";19133";
    }
}
