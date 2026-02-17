package dev.bedrockbridge.lan;

import dev.bedrockbridge.proxy.BedrockBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * LanBroadcaster sends UDP broadcasts so Bedrock clients on the same network
 * see the proxy as a LAN world in Play > Worlds tab.
 *
 * Sends to BOTH:
 *  1. 255.255.255.255 (global broadcast)
 *  2. All subnet broadcast addresses on the machine's real network interfaces
 *     e.g. 192.168.1.255 — Windows often ignores 255.255.255.255 but
 *     responds to the subnet broadcast.
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

    // Unique server GUID for this run
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

            int intervalMs = bridge.getConfig().getLanBroadcastIntervalMs();
            broadcastTask = scheduler.scheduleAtFixedRate(
                    this::broadcast,
                    0,
                    intervalMs,
                    TimeUnit.MILLISECONDS
            );

            LOGGER.info("LAN broadcaster started (interval: {}ms)", intervalMs);
            logBroadcastTargets();
        } catch (SocketException e) {
            LOGGER.error("Failed to start LAN broadcaster", e);
        }
    }

    public void stop() {
        if (broadcastTask != null) broadcastTask.cancel(false);
        scheduler.shutdownNow();
        if (socket != null && !socket.isClosed()) socket.close();
        LOGGER.info("LAN broadcaster stopped");
    }

    private void broadcast() {
        byte[] payload = buildUnconnectedPong();

        // Always try the global broadcast address
        sendTo(payload, "255.255.255.255");

        // Also broadcast to every subnet broadcast address on this machine
        // (fixes Windows where 255.255.255.255 is often blocked)
        for (String subnetBroadcast : getSubnetBroadcastAddresses()) {
            sendTo(payload, subnetBroadcast);
        }
    }

    private void sendTo(byte[] payload, String address) {
        try {
            InetAddress addr = InetAddress.getByName(address);
            DatagramPacket packet = new DatagramPacket(payload, payload.length, addr, 19132);
            socket.send(packet);
            LOGGER.trace("LAN broadcast → {} ({} bytes)", address, payload.length);
        } catch (IOException e) {
            LOGGER.trace("LAN broadcast to {} failed: {}", address, e.getMessage());
        }
    }

    /**
     * Enumerate all network interfaces and collect their broadcast addresses.
     * This finds e.g. "192.168.1.255" from a "192.168.1.x/24" interface.
     */
    private List<String> getSubnetBroadcastAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return addresses;

            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;

                for (InterfaceAddress ifaceAddr : iface.getInterfaceAddresses()) {
                    InetAddress broadcast = ifaceAddr.getBroadcast();
                    if (broadcast != null) {
                        String broadcastStr = broadcast.getHostAddress();
                        if (!broadcastStr.equals("255.255.255.255")) {
                            addresses.add(broadcastStr);
                        }
                    }
                }
            }
        } catch (SocketException e) {
            LOGGER.warn("Could not enumerate network interfaces: {}", e.getMessage());
        }
        return addresses;
    }

    private void logBroadcastTargets() {
        List<String> targets = new ArrayList<>();
        targets.add("255.255.255.255");
        targets.addAll(getSubnetBroadcastAddresses());
        LOGGER.info("LAN broadcast targets: {}", targets);
    }

    /**
     * Builds a RakNet UNCONNECTED_PONG packet containing the Bedrock MOTD.
     *
     * Layout:
     *   [1]  Packet ID = 0x1C
     *   [8]  Ping time (ms, big-endian)
     *   [8]  Server GUID
     *   [16] Offline magic bytes
     *   [2]  MOTD string length (big-endian)
     *   [N]  MOTD string (UTF-8)
     */
    private byte[] buildUnconnectedPong() {
        String motd = buildMotdString();
        byte[] motdBytes = motd.getBytes(StandardCharsets.UTF_8);

        int size = 1 + 8 + 8 + 16 + 2 + motdBytes.length;
        byte[] buf = new byte[size];
        int pos = 0;

        buf[pos++] = UNCONNECTED_PONG;

        long pingTime = System.currentTimeMillis();
        for (int i = 7; i >= 0; i--) buf[pos + i] = (byte) (pingTime >> (8 * (7 - i)));
        pos += 8;

        for (int i = 7; i >= 0; i--) buf[pos + i] = (byte) (serverGuid >> (8 * (7 - i)));
        pos += 8;

        System.arraycopy(OFFLINE_MESSAGE_DATA_ID, 0, buf, pos, 16);
        pos += 16;

        buf[pos++] = (byte) (motdBytes.length >> 8);
        buf[pos++] = (byte) (motdBytes.length);

        System.arraycopy(motdBytes, 0, buf, pos, motdBytes.length);

        return buf;
    }

    /**
     * Bedrock MOTD format:
     * MCPE;<name>;<protocol>;<version>;<players>;<maxPlayers>;<guid>;<subMotd>;<gameType>;1;<port4>;<port6>
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
                ";1" +
                ";" + config.getProxyPort() +
                ";19133";
    }
}
