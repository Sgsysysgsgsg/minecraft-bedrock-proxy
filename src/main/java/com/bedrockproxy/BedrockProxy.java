package com.bedrockproxy;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core Bedrock UDP proxy.
 *
 * Bedrock Edition uses RakNet over UDP on port 19132.
 * This proxy:
 *   1. Listens on a local UDP port (same as a normal Bedrock server)
 *   2. Broadcasts a LAN advertisement so PS5/Xbox discover it automatically
 *   3. Forwards all UDP packets between the client and the real remote server
 */
public class BedrockProxy {

    // RakNet offline message ID magic bytes - required for all unconnected packets
    private static final byte[] OFFLINE_MESSAGE_ID = {
        (byte)0x00, (byte)0xff, (byte)0xff, (byte)0x00,
        (byte)0xfe, (byte)0xfe, (byte)0xfe, (byte)0xfe,
        (byte)0xfd, (byte)0xfd, (byte)0xfd, (byte)0xfd,
        (byte)0x12, (byte)0x34, (byte)0x56, (byte)0x78
    };

    // RakNet packet IDs
    private static final byte ID_UNCONNECTED_PING      = 0x01; // normal ping
    private static final byte ID_UNCONNECTED_PING_OPEN = 0x02; // PS5/Xbox also sends this variant
    private static final byte ID_UNCONNECTED_PONG      = 0x1c;

    // ── VERSION: Bedrock 1.26.0 ──────────────────────────────────────────────
    // Protocol number for 1.21.130 / 1.26.0. Update this if Mojang releases a
    // new version with a new protocol number (check config.properties too).
    private static final int    PROTOCOL_VERSION = 686;
    private static final String GAME_VERSION     = "1.26.0";
    // ─────────────────────────────────────────────────────────────────────────

    private final String remoteHost;
    private final int remotePort;
    private final int localPort;
    private final String serverName;

    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final long serverGuid = new Random().nextLong();
    private volatile boolean running = false;
    private DatagramSocket localSocket;

    public BedrockProxy(String remoteHost, int remotePort, int localPort, String serverName) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.localPort = localPort;
        this.serverName = serverName;
    }

    public void start() throws Exception {
        running = true;

        InetAddress remoteAddress = InetAddress.getByName(remoteHost);

        localSocket = new DatagramSocket(localPort);
        localSocket.setSoTimeout(0);

        System.out.println("[Proxy] Listening on UDP :" + localPort);
        System.out.println("[Proxy] Forwarding to " + remoteHost + ":" + remotePort);
        System.out.println("[Proxy] Protocol: " + PROTOCOL_VERSION + " (Bedrock " + GAME_VERSION + ")");

        // LAN broadcaster - makes proxy show up in PS5/Xbox LAN Games list
        Thread lanThread = new Thread(this::runLanBroadcaster);
        lanThread.setDaemon(true);
        lanThread.setName("LAN-Broadcaster");
        lanThread.start();

        // Idle session cleaner
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(this::cleanSessions, 30, 30, TimeUnit.SECONDS);

        System.out.println("[Proxy] Ready! PS5/Xbox: Play → Friends → LAN Games");
        System.out.println("────────────────────────────────────────────────────");

        byte[] buffer = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running) {
            try {
                localSocket.receive(packet);
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                InetAddress clientAddr = packet.getAddress();
                int clientPort2 = packet.getPort();
                String clientKey = clientAddr.getHostAddress() + ":" + clientPort2;

                if (data.length == 0) continue;
                byte packetId = data[0];

                // Handle pings locally — both 0x01 (normal) and 0x02 (open connection ping)
                // PS5 sends 0x02, PC sends 0x01. We must respond to both.
                if (packetId == ID_UNCONNECTED_PING || packetId == ID_UNCONNECTED_PING_OPEN) {
                    byte[] pong = buildPongResponse(data);
                    localSocket.send(new DatagramPacket(pong, pong.length, clientAddr, clientPort2));
                    System.out.println("[Ping] " + clientKey + " → pong sent (0x" + String.format("%02x", packetId) + ")");
                    continue;
                }

                // All other packets: proxy through a dedicated per-client session
                ClientSession session = sessions.computeIfAbsent(clientKey, k -> {
                    try {
                        ClientSession s = new ClientSession(clientAddr, clientPort2, remoteAddress, remotePort, localSocket);
                        s.start();
                        System.out.println("[Session] New player: " + k);
                        return s;
                    } catch (Exception e) {
                        System.out.println("[Error] Session create failed: " + e.getMessage());
                        return null;
                    }
                });

                if (session != null) {
                    session.sendToRemote(data);
                    session.updateLastSeen();
                }

            } catch (Exception e) {
                if (running) System.out.println("[Error] " + e.getMessage());
            }
        }

        cleaner.shutdown();
        localSocket.close();
    }

    public void stop() {
        running = false;
        if (localSocket != null) localSocket.close();
    }

    /**
     * Builds a proper RakNet Unconnected Pong (0x1c).
     *
     * Packet layout:
     *   0x1c | pingTime(8) | serverGuid(8) | magic(16) | motdLen(2) | motd(n)
     *
     * MOTD format:
     *   MCPE;<name>;<protocol>;<version>;<players>;<maxPlayers>;<guid>;<subname>;<gamemode>;<gamemodeNum>;<port4>;<port6>;
     */
    private byte[] buildPongResponse(byte[] pingData) {
        // Echo the ping timestamp (bytes 1–8 of ping packet)
        long pingTime = 0;
        if (pingData.length >= 9) {
            for (int i = 1; i <= 8; i++) pingTime = (pingTime << 8) | (pingData[i] & 0xFF);
        }

        String motd = "MCPE"
            + ";" + serverName
            + ";" + PROTOCOL_VERSION   // <-- 686 for 1.26.0, was wrong as 594 before!
            + ";" + GAME_VERSION       // <-- "1.26.0", was wrong as "1.20.0" before!
            + ";0"                     // current players
            + ";10"                    // max players
            + ";" + Math.abs(serverGuid)
            + ";" + serverName
            + ";Survival"
            + ";1"
            + ";" + localPort
            + ";19133"
            + ";";

        byte[] motdBytes = motd.getBytes();
        byte[] pong = new byte[1 + 8 + 8 + 16 + 2 + motdBytes.length];
        int pos = 0;

        pong[pos++] = ID_UNCONNECTED_PONG;
        for (int i = 7; i >= 0; i--) pong[pos++] = (byte)((pingTime >> (i * 8)) & 0xFF);
        long g = serverGuid;
        for (int i = 7; i >= 0; i--) pong[pos++] = (byte)((g >> (i * 8)) & 0xFF);
        System.arraycopy(OFFLINE_MESSAGE_ID, 0, pong, pos, 16); pos += 16;
        pong[pos++] = (byte)((motdBytes.length >> 8) & 0xFF);
        pong[pos++] = (byte)(motdBytes.length & 0xFF);
        System.arraycopy(motdBytes, 0, pong, pos, motdBytes.length);

        return pong;
    }

    /**
     * Broadcasts proper RakNet unconnected ping packets every 2 seconds.
     *
     * This is what actually makes PS5/Xbox see the proxy in LAN Games.
     * Previously the broadcaster was sending raw MOTD text (wrong!) — now it
     * sends a valid RakNet ping so consoles know to query our port.
     *
     * Packet layout: 0x01 | time(8) | magic(16) | clientGuid(8)
     */
    private void runLanBroadcaster() {
        System.out.println("[LAN] Broadcaster started (every 2s → 255.255.255.255:19132)");

        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setBroadcast(true);
            InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");

            // Also try the subnet broadcast on common home networks
            InetAddress subnetBroadcast = null;
            try { subnetBroadcast = InetAddress.getByName("192.168.1.255"); } catch (Exception ignored) {}

            while (running) {
                try {
                    // Build: 0x01 | time(8) | magic(16) | clientGuid(8) = 33 bytes
                    byte[] ping = new byte[33];
                    ping[0] = ID_UNCONNECTED_PING;
                    long now = System.currentTimeMillis();
                    for (int i = 0; i < 8; i++) ping[1 + i] = (byte)((now >> ((7 - i) * 8)) & 0xFF);
                    System.arraycopy(OFFLINE_MESSAGE_ID, 0, ping, 9, 16);
                    long cg = serverGuid ^ 0xDEADBEEFL;
                    for (int i = 0; i < 8; i++) ping[25 + i] = (byte)((cg >> ((7 - i) * 8)) & 0xFF);

                    sock.send(new DatagramPacket(ping, ping.length, broadcastAddr, 19132));
                    if (subnetBroadcast != null) {
                        sock.send(new DatagramPacket(ping, ping.length, subnetBroadcast, 19132));
                    }

                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {
                    // broadcast failures are non-fatal
                }
            }
        } catch (Exception e) {
            System.out.println("[LAN] Broadcaster error: " + e.getMessage());
        }
    }

    private void cleanSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            ClientSession s = entry.getValue();
            if (now - s.getLastSeen() > 60_000) {
                s.close();
                System.out.println("[Session] Removed idle: " + entry.getKey());
                return true;
            }
            return false;
        });
    }
}
