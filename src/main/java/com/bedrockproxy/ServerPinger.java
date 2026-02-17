package com.bedrockproxy;

import java.net.*;
import java.util.Arrays;

/**
 * Sends a RakNet unconnected ping (0x01) to the real Bedrock server
 * and parses the pong response to extract:
 *   - Protocol version number
 *   - Game version string (e.g. "1.21.90")
 *   - Server name / MOTD
 *   - Player counts
 *
 * This lets the proxy advertise the EXACT same version as the real server,
 * so clients see the correct version and don't get "Game update required".
 */
public class ServerPinger {

    // RakNet magic bytes
    private static final byte[] MAGIC = {
        (byte)0x00,(byte)0xff,(byte)0xff,(byte)0x00,
        (byte)0xfe,(byte)0xfe,(byte)0xfe,(byte)0xfe,
        (byte)0xfd,(byte)0xfd,(byte)0xfd,(byte)0xfd,
        (byte)0x12,(byte)0x34,(byte)0x56,(byte)0x78
    };

    private static final byte ID_UNCONNECTED_PING = 0x01;
    private static final byte ID_UNCONNECTED_PONG = 0x1c;

    /**
     * Ping the server and return parsed info, or null on failure/timeout.
     */
    public static ServerInfo ping(String host, int port, int timeoutMs) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            InetAddress addr = InetAddress.getByName(host);

            // Build unconnected ping: 0x01 | time(8) | magic(16) | clientGuid(8)
            byte[] ping = new byte[33];
            ping[0] = ID_UNCONNECTED_PING;
            long t = System.currentTimeMillis();
            for (int i = 0; i < 8; i++) ping[1 + i] = (byte)((t >> ((7 - i) * 8)) & 0xFF);
            System.arraycopy(MAGIC, 0, ping, 9, 16);
            long guid = new java.util.Random().nextLong();
            for (int i = 0; i < 8; i++) ping[25 + i] = (byte)((guid >> ((7 - i) * 8)) & 0xFF);

            socket.send(new DatagramPacket(ping, ping.length, addr, port));

            // Wait for pong
            byte[] buf = new byte[65535];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            socket.receive(pkt);

            byte[] data = Arrays.copyOf(pkt.getData(), pkt.getLength());
            if (data.length < 1 || data[0] != ID_UNCONNECTED_PONG) {
                return null;
            }

            return parsePong(data);

        } catch (SocketTimeoutException e) {
            System.out.println("[Ping] Timeout pinging " + host + ":" + port);
            return null;
        } catch (Exception e) {
            System.out.println("[Ping] Error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parse a RakNet Unconnected Pong packet.
     *
     * Layout: 0x1c | pingTime(8) | serverGuid(8) | magic(16) | motdLen(2) | motd(n)
     *
     * MOTD format:
     *   MCPE;name;protocol;version;players;maxPlayers;guid;subname;mode;modeNum;port4;port6;
     */
    private static ServerInfo parsePong(byte[] data) {
        try {
            int pos = 1; // skip packet ID

            // Skip ping time (8)
            pos += 8;

            // Read server GUID (8)
            long serverGuid = 0;
            for (int i = 0; i < 8; i++) serverGuid = (serverGuid << 8) | (data[pos++] & 0xFF);

            // Skip magic (16)
            pos += 16;

            // Read MOTD length (2 bytes big-endian)
            int motdLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;

            if (pos + motdLen > data.length) {
                motdLen = data.length - pos;
            }

            String motd = new String(data, pos, motdLen, java.nio.charset.StandardCharsets.UTF_8);

            // Parse MCPE MOTD fields split by ;
            // MCPE;name;protocol;version;players;maxPlayers;guid;subname;mode;modeNum;port4;port6;
            String[] parts = motd.split(";", -1);

            ServerInfo info = new ServerInfo();
            info.guid = serverGuid;

            if (parts.length > 1) info.motdLine1  = parts[1];
            if (parts.length > 2) {
                try { info.protocol = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException ignored) {}
            }
            if (parts.length > 3) info.version    = parts[3].trim();
            if (parts.length > 4) {
                try { info.players = Integer.parseInt(parts[4].trim()); } catch (NumberFormatException ignored) {}
            }
            if (parts.length > 5) {
                try { info.maxPlayers = Integer.parseInt(parts[5].trim()); } catch (NumberFormatException ignored) {}
            }
            if (parts.length > 7) info.motdLine2  = parts[7];

            return info;

        } catch (Exception e) {
            System.out.println("[Ping] Failed to parse pong: " + e.getMessage());
            return null;
        }
    }
}
