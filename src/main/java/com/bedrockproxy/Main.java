package com.bedrockproxy;

import java.io.*;
import java.util.Properties;

public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║       BedrockProxy v5.0                  ║");
        System.out.println("║  Auto version — any client can join      ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println();

        String remoteHost;
        int    remotePort;
        int    localPort;
        String serverName;

        if (args.length >= 2) {
            remoteHost = args[0];
            remotePort = Integer.parseInt(args[1]);
            localPort  = args.length >= 3 ? Integer.parseInt(args[2]) : 19132;
            serverName = args.length >= 4 ? args[3] : "BedrockProxy";
        } else {
            Properties cfg = loadOrCreateConfig();
            remoteHost = cfg.getProperty("server.host", "");
            remotePort = Integer.parseInt(cfg.getProperty("server.port", "19132"));
            localPort  = Integer.parseInt(cfg.getProperty("local.port",  "19132"));
            serverName = cfg.getProperty("server.name", "BedrockProxy");

            if (remoteHost.isEmpty() || remoteHost.equals("play.example.com")) {
                System.out.println("ERROR: Edit config.properties, set server.host, then restart.");
                return;
            }
        }

        System.out.println("Remote  : " + remoteHost + ":" + remotePort);
        System.out.println("Local   : 0.0.0.0:" + localPort);
        System.out.println("Name    : " + serverName);
        System.out.println();

        // Step 1: ping the real server to get its MOTD + protocol version
        System.out.println("[Init] Pinging remote server to detect version...");
        ServerInfo info = ServerPinger.ping(remoteHost, remotePort, 5000);

        if (info != null) {
            System.out.println("[Init] Remote server version : " + info.version + " (protocol " + info.protocol + ")");
            System.out.println("[Init] Remote server name    : " + info.motdLine1);
        } else {
            System.out.println("[Init] WARNING: Could not ping remote server.");
            System.out.println("[Init] Using passthrough mode — version mismatch may occur.");
            // Use a very permissive fallback
            info = new ServerInfo();
            info.protocol  = 0; // will be overridden per-client in passthrough
            info.version   = "1.26.0";
            info.motdLine1 = serverName;
            info.motdLine2 = serverName;
            info.maxPlayers = 100;
        }

        // Override the display name with what the user configured
        info.motdLine1 = serverName;
        info.motdLine2 = serverName;

        System.out.println();
        System.out.println("[Proxy] Advertising as: " + info.version + " (protocol " + info.protocol + ")");
        System.out.println("[Proxy] Starting... (CTRL+C to stop)");
        System.out.println("─────────────────────────────────────────────────");

        new BedrockProxy(remoteHost, remotePort, localPort, info).start();
    }

    static Properties loadOrCreateConfig() {
        Properties p = new Properties();
        File f = new File("config.properties");
        if (!f.exists()) {
            try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
                pw.println("# BedrockProxy Configuration");
                pw.println("server.host=play.example.com");
                pw.println("server.port=19132");
                pw.println("local.port=19132");
                pw.println("server.name=My Server");
                System.out.println("Created config.properties — edit it and restart.");
            } catch (IOException e) {
                System.out.println("Could not create config: " + e.getMessage());
            }
        } else {
            try (FileInputStream fis = new FileInputStream(f)) {
                p.load(fis);
            } catch (IOException e) {
                System.out.println("Could not read config: " + e.getMessage());
            }
        }
        return p;
    }
}
