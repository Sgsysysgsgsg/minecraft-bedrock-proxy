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

    // RakNet offline message ID - required for proper Bedrock handshake
    private static final byte[] OFFLINE_MESSAGE_ID = {
        (byte)0x00, (byte)0xff, (byte)0xff, (byte)0x00,
        (byte)0xfe, (byte)0xfe, (byte)0xfe, (byte)0xfe,
        (byte)0xfd, (byte)0xfd, (byte)0xfd, (byte)0xfd,
        (byte)0x12, (byte)0x34, (byte)0x56, (byte)0x78
    };

    // RakNet packet IDs
    private static final byte ID_UNCONNECTED_PING       = 0x01;
    private static final byte ID_UNCONNECTED_PONG       = 0x1c;
    private static final byte ID_OPEN_CONNECTION_REQ_1  = 0x05;

    private final String remoteHost;
    private final int remotePort;
    private final int localPort;
    private final String serverName;

    // Maps client address -> remote socket used for that client's session
    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();

    // Unique server GUID
    private final long serverGuid = new Random().nextLong();
    private final AtomicLong pingCounter = new AtomicLong(0);

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

        // Main local socket that clients connect to
        localSocket = new DatagramSocket(localPort);
        localSocket.setSoTimeout(0); // block indefinitely

        System.out.println("[Proxy] Listening on UDP port " + localPort);
        System.out.println("[Proxy] Forwarding to " + remoteHost + ":" + remotePort);

        // Start LAN broadcaster thread
        Thread lanThread = new Thread(() -> runLanBroadcaster(serverName));
        lanThread.setDaemon(true);
        lanThread.setName("LAN-Broadcaster");
        lanThread.start();

        // Start session cleaner
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(this::cleanSessions, 30, 30, TimeUnit.SECONDS);

        // Main receive loop
        byte[] buffer = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        System.out.println("[Proxy] Ready! Waiting for connections...");

        while (running) {
            try {
                localSocket.receive(packet);
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                InetAddress clientAddr = packet.getAddress();
                int clientPort2 = packet.getPort();
                String clientKey = clientAddr.getHostAddress() + ":" + clientPort2;

                // Handle ping locally for LAN discovery
                if (data.length > 0 && data[0] == ID_UNCONNECTED_PING) {
                    byte[] pong = buildPongResponse(data, serverName);
                    DatagramPacket pongPacket = new DatagramPacket(pong, pong.length, clientAddr, clientPort2);
                    localSocket.send(pongPacket);
                    System.out.println("[Ping] Responded to ping from " + clientKey);
                    continue;
                }

                // Get or create session for this client
                ClientSession session = sessions.computeIfAbsent(clientKey, k -> {
                    try {
                        ClientSession s = new ClientSession(clientAddr, clientPort2, remoteAddress, remotePort, localSocket);
                        s.start();
                        System.out.println("[Session] New client: " + k);
                        return s;
                    } catch (Exception e) {
                        System.out.println("[Error] Could not create session: " + e.getMessage());
                        return null;
                    }
                });

                if (session != null) {
                    session.sendToRemote(data);
                    session.updateLastSeen();
                }

            } catch (Exception e) {
                if (running) {
                    System.out.println("[Error] Receive error: " + e.getMessage());
                }
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
     * Builds a RakNet Unconnected Pong response with MCPE server info.
     * The MOTD string determines what shows up in the LAN game list on PS5/Xbox.
     */
    private byte[] buildPongResponse(byte[] pingData, String name) {
        // Parse ping time from ping packet (bytes 1-8)
        long pingTime = 0;
        if (pingData.length >= 9) {
            for (int i = 1; i <= 8; i++) {
                pingTime = (pingTime << 8) | (pingData[i] & 0xFF);
            }
        }

        // MCPE MOTD format: MCPE;name;protocol;version;players;maxPlayers;guid;subname;mode;
        String motd = "MCPE;" + name + ";594;1.20.0;0;10;" + Math.abs(serverGuid) + ";" + name + ";Survival;1;19132;19133;";

        byte[] motdBytes = motd.getBytes();
        // Pong: ID(1) + time(8) + serverGuid(8) + offlineMsgId(16) + motdLen(2) + motd
        int len = 1 + 8 + 8 + 16 + 2 + motdBytes.length;
        byte[] pong = new byte[len];
        int pos = 0;

        pong[pos++] = ID_UNCONNECTED_PONG;

        // Echo ping time
        for (int i = 7; i >= 0; i--) pong[pos++] = (byte)((pingTime >> (i * 8)) & 0xFF);

        // Server GUID
        long g = serverGuid;
        for (int i = 7; i >= 0; i--) pong[pos++] = (byte)((g >> (i * 8)) & 0xFF);

        // Offline message ID magic bytes
        System.arraycopy(OFFLINE_MESSAGE_ID, 0, pong, pos, OFFLINE_MESSAGE_ID.length);
        pos += OFFLINE_MESSAGE_ID.length;

        // MOTD length (big-endian short)
        pong[pos++] = (byte)((motdBytes.length >> 8) & 0xFF);
        pong[pos++] = (byte)(motdBytes.length & 0xFF);

        // MOTD bytes
        System.arraycopy(motdBytes, 0, pong, pos, motdBytes.length);

        return pong;
    }

    /**
     * Broadcasts LAN advertisement packets on the local network.
     * This is how PS5 and Xbox discover servers in the Friends tab.
     */
    private void runLanBroadcaster(String name) {
        try (DatagramSocket broadcastSocket = new DatagramSocket()) {
            broadcastSocket.setBroadcast(true);

            // Build a minimal ping packet
            byte[] pingPacket = new byte[1 + 8 + 16];
            pingPacket[0] = ID_UNCONNECTED_PING;
            long time = System.currentTimeMillis();
            for (int i = 7; i >= 0; i--) pingPacket[1 + (7 - i)] = (byte)((time >> (i * 8)) & 0xFF);
            System.arraycopy(OFFLINE_MESSAGE_ID, 0, pingPacket, 9, 16);

            // Build our own pong to broadcast (some platforms use broadcast pong)
            String motd = "MCPE;" + name + ";594;1.20.0;0;10;" + Math.abs(serverGuid) + ";" + name + ";Survival;1;" + localPort + ";19133;";
            byte[] motdBytes = motd.getBytes();

            System.out.println("[LAN] Broadcasting server '" + name + "' every 2 seconds...");

            while (running) {
                try {
                    // Broadcast to 255.255.255.255
                    InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
                    DatagramPacket broadcastPacket = new DatagramPacket(
                        motdBytes, motdBytes.length, broadcastAddr, 19132
                    );

                    // We send on alternate port to not conflict with ourselves
                    // The actual discovery happens via ping/pong on localPort
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    // Broadcast may fail on some systems, that's OK
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
                System.out.println("[Session] Cleaned up idle session: " + entry.getKey());
                return true;
            }
            return false;
        });
    }
}
