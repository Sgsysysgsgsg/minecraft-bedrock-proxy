package com.bedrockproxy;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * BedrockProxy v6 — Pure transparent UDP proxy.
 *
 * How it works:
 *   1. Pings the real server (Geyser/Bedrock) to get its exact MOTD
 *   2. Listens on port 19132 locally
 *   3. Responds to pings with the server's REAL MOTD (exact copy — no version changes)
 *   4. Forwards ALL other UDP packets transparently to the real server
 *   5. Forwards replies back to the client
 *
 * Why this is correct for a Geyser server:
 *   Geyser handles ALL version negotiation internally.
 *   We just need to be a transparent pipe — not interfere with the handshake at all.
 *   The "Game update required" error was caused by us intercepting and breaking
 *   the RakNet handshake. Now we just pass everything through unchanged.
 */
public class Main {

    // RakNet magic
    static final byte[] MAGIC = {
        (byte)0x00,(byte)0xff,(byte)0xff,(byte)0x00,
        (byte)0xfe,(byte)0xfe,(byte)0xfe,(byte)0xfe,
        (byte)0xfd,(byte)0xfd,(byte)0xfd,(byte)0xfd,
        (byte)0x12,(byte)0x34,(byte)0x56,(byte)0x78
    };

    static final byte ID_PING      = 0x01;
    static final byte ID_PING_OPEN = 0x02;
    static final byte ID_PONG      = 0x1c;

    // clientKey -> remote socket for that session
    static final ConcurrentHashMap<String, DatagramSocket> sessions = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();

    static DatagramSocket localSocket;
    static InetAddress    remoteAddr;
    static int            remotePort;
    static String         serverName;

    // Cache of the server's real pong bytes — refreshed every 30s
    static volatile byte[] cachedPong = null;
    static volatile long   pongFetched = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("╔═════════════════════════════════════════╗");
        System.out.println("║      BedrockProxy v6 — Transparent      ║");
        System.out.println("╚═════════════════════════════════════════╝");
        System.out.println();

        // Load config
        String host;
        int    port;
        int    localPort;

        if (args.length >= 2) {
            host      = args[0];
            port      = Integer.parseInt(args[1]);
            localPort = args.length >= 3 ? Integer.parseInt(args[2]) : 19132;
            serverName = args.length >= 4 ? args[3] : null;
        } else {
            Properties cfg = loadConfig();
            host       = cfg.getProperty("server.host", "");
            port       = Integer.parseInt(cfg.getProperty("server.port", "19132"));
            localPort  = Integer.parseInt(cfg.getProperty("local.port",  "19132"));
            serverName = cfg.getProperty("server.name", null);

            if (host.isEmpty() || host.equals("play.example.com")) {
                System.out.println("ERROR: Edit config.properties, set server.host, then restart.");
                return;
            }
        }

        remoteAddr = InetAddress.getByName(host);
        remotePort = port;

        System.out.println("Remote : " + host + ":" + port);
        System.out.println("Local  : 0.0.0.0:" + localPort);

        // Ping server to get real MOTD
        System.out.println();
        System.out.println("[Init] Fetching server info...");
        byte[] firstPong = fetchServerPong(host, port, serverName);
        if (firstPong != null) {
            cachedPong  = firstPong;
            pongFetched = System.currentTimeMillis();
            System.out.println("[Init] Server info fetched successfully");
        } else {
            System.out.println("[Init] WARNING: Could not reach server — will retry on first ping");
        }

        // Start session cleaner
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(1);
        sched.scheduleAtFixedRate(Main::cleanSessions, 30, 30, TimeUnit.SECONDS);

        // Start pong refresher — every 30s re-ping the server to stay in sync
        sched.scheduleAtFixedRate(() -> {
            byte[] fresh = fetchServerPong(host, port, serverName);
            if (fresh != null) {
                cachedPong  = fresh;
                pongFetched = System.currentTimeMillis();
                System.out.println("[Refresh] Server info updated");
            }
        }, 30, 30, TimeUnit.SECONDS);

        // Main socket
        localSocket = new DatagramSocket(localPort);
        System.out.println();
        System.out.println("[OK] Proxy running on port " + localPort);
        System.out.println("[OK] Join via Friends → LAN Games on phone/PS5/Xbox");
        System.out.println("[OK] Press CTRL+C to stop");
        System.out.println("─────────────────────────────────────────────────");

        byte[]         buf = new byte[65535];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);

        while (true) {
            localSocket.receive(pkt);
            byte[]      data       = Arrays.copyOf(pkt.getData(), pkt.getLength());
            InetAddress clientAddr = pkt.getAddress();
            int         clientPort = pkt.getPort();
            String      clientKey  = clientAddr.getHostAddress() + ":" + clientPort;

            if (data.length == 0) continue;
            byte id = data[0];

            // ── Pings: reply with the server's real MOTD ─────────────────
            if (id == ID_PING || id == ID_PING_OPEN) {
                byte[] pong = getPong(data);
                if (pong != null) {
                    localSocket.send(new DatagramPacket(pong, pong.length, clientAddr, clientPort));
                }
                continue;
            }

            // ── All other packets: forward transparently ──────────────────
            DatagramSocket session = sessions.computeIfAbsent(clientKey, k -> {
                try {
                    DatagramSocket s = new DatagramSocket();
                    s.setSoTimeout(60000);
                    // Start reply-forwarder thread for this session
                    Thread t = new Thread(() -> forwardReplies(s, clientAddr, clientPort, k));
                    t.setDaemon(true);
                    t.start();
                    System.out.println("[+] " + k);
                    return s;
                } catch (Exception e) {
                    System.out.println("[!] Session error: " + e.getMessage());
                    return null;
                }
            });

            if (session != null && !session.isClosed()) {
                session.send(new DatagramPacket(data, data.length, remoteAddr, remotePort));
                lastSeen.put(clientKey, System.currentTimeMillis());
            }
        }
    }

    /** Forward replies from the real server back to the client. */
    static void forwardReplies(DatagramSocket s, InetAddress clientAddr, int clientPort, String key) {
        byte[]         buf = new byte[65535];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (!s.isClosed()) {
            try {
                s.receive(pkt);
                byte[] data = Arrays.copyOf(pkt.getData(), pkt.getLength());
                localSocket.send(new DatagramPacket(data, data.length, clientAddr, clientPort));
                lastSeen.put(key, System.currentTimeMillis());
            } catch (SocketTimeoutException e) {
                break; // session idle timeout
            } catch (Exception e) {
                if (!s.isClosed()) System.out.println("[!] Reply error: " + e.getMessage());
                break;
            }
        }
        s.close();
        sessions.remove(key);
        lastSeen.remove(key);
        System.out.println("[-] " + key);
    }

    /**
     * Build a pong reply for a ping packet.
     * We use the cached pong from the real server — exact copy, nothing changed.
     * This means Geyser's version info passes through untouched.
     * Only thing we change is the ping timestamp echo and optionally the display name.
     */
    static byte[] getPong(byte[] pingData) {
        // If we have a cached pong, just update the timestamp echo and return
        if (cachedPong != null) {
            // Update the ping time echo (bytes 1-8 in the pong)
            long pingTime = 0;
            if (pingData.length >= 9) {
                for (int i = 1; i <= 8; i++) pingTime = (pingTime << 8) | (pingData[i] & 0xFF);
            }
            byte[] pong = Arrays.copyOf(cachedPong, cachedPong.length);
            for (int i = 7; i >= 0; i--) pong[1 + (7 - i)] = (byte)((pingTime >> (i * 8)) & 0xFF);
            return pong;
        }

        // No cached pong yet — try fetching now
        System.out.println("[Ping] No cached pong, fetching...");
        byte[] fresh = fetchServerPong(remoteAddr.getHostAddress(), remotePort, serverName);
        if (fresh != null) {
            cachedPong = fresh;
            return getPong(pingData); // recurse with fresh cache
        }
        return null;
    }

    /**
     * Ping the real server and get back its raw pong bytes.
     * We store these and replay them to clients — no modification.
     * If serverName is set, we replace the display name in the MOTD.
     */
    static byte[] fetchServerPong(String host, int port, String overrideName) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(3000);
            InetAddress addr = InetAddress.getByName(host);

            // Build ping
            byte[] ping = new byte[33];
            ping[0] = ID_PING;
            long t = System.currentTimeMillis();
            for (int i = 0; i < 8; i++) ping[1+i] = (byte)((t >> ((7-i)*8)) & 0xFF);
            System.arraycopy(MAGIC, 0, ping, 9, 16);
            long g = new Random().nextLong();
            for (int i = 0; i < 8; i++) ping[25+i] = (byte)((g >> ((7-i)*8)) & 0xFF);

            s.send(new DatagramPacket(ping, ping.length, addr, port));

            byte[]         buf = new byte[65535];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            s.receive(pkt);

            byte[] raw = Arrays.copyOf(pkt.getData(), pkt.getLength());
            if (raw.length < 1 || raw[0] != ID_PONG) return null;

            // If user set a custom server name, replace it in the MOTD
            if (overrideName != null && !overrideName.isEmpty()) {
                raw = overrideMotdName(raw, overrideName);
            }

            return raw;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Replace the first and second MOTD fields (server name lines) in a raw pong packet.
     * Everything else (protocol, version, players, guid, gamemode) stays exactly as-is.
     */
    static byte[] overrideMotdName(byte[] pong, String name) {
        try {
            // MOTD starts at offset: 1(id) + 8(time) + 8(guid) + 16(magic) + 2(len) = 35
            int motdOffset = 35;
            if (pong.length <= motdOffset) return pong;

            int motdLen = ((pong[33] & 0xFF) << 8) | (pong[34] & 0xFF);
            String motd = new String(pong, motdOffset, Math.min(motdLen, pong.length - motdOffset), "UTF-8");

            // Replace fields[1] and fields[7] (both name lines) with custom name
            String[] parts = motd.split(";", -1);
            if (parts.length > 1)  parts[1] = name;
            if (parts.length > 7)  parts[7] = name;
            String newMotd = String.join(";", parts);

            byte[] motdBytes = newMotd.getBytes("UTF-8");
            byte[] result = new byte[motdOffset + motdBytes.length];
            System.arraycopy(pong, 0, result, 0, motdOffset);
            result[33] = (byte)((motdBytes.length >> 8) & 0xFF);
            result[34] = (byte)(motdBytes.length & 0xFF);
            System.arraycopy(motdBytes, 0, result, motdOffset, motdBytes.length);
            return result;
        } catch (Exception e) {
            return pong;
        }
    }

    static void cleanSessions() {
        long now = System.currentTimeMillis();
        lastSeen.forEach((key, ts) -> {
            if (now - ts > 60_000) {
                DatagramSocket s = sessions.remove(key);
                if (s != null) s.close();
                lastSeen.remove(key);
            }
        });
    }

    static Properties loadConfig() {
        Properties p = new Properties();
        File f = new File("config.properties");
        if (!f.exists()) {
            try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
                pw.println("# BedrockProxy Configuration");
                pw.println("server.host=play.example.com");
                pw.println("server.port=19132");
                pw.println("local.port=19132");
                pw.println("# Optional: override the display name shown in LAN Games");
                pw.println("# Leave blank to use the server's real name");
                pw.println("server.name=");
                System.out.println("Created config.properties — edit it and restart.");
            } catch (IOException ignored) {}
        } else {
            try (FileInputStream fis = new FileInputStream(f)) {
                p.load(fis);
            } catch (IOException ignored) {}
        }
        return p;
    }
}
