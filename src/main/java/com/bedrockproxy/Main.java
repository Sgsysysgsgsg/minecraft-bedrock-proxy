package com.bedrockproxy;

import java.io.*;
import java.util.Properties;

public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║       BedrockProxy v4.0                  ║");
        System.out.println("║  Powered by CloudburstMC RakNet          ║");
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
            Properties cfg = loadConfig();
            remoteHost = cfg.getProperty("server.host", "");
            remotePort = Integer.parseInt(cfg.getProperty("server.port", "19132"));
            localPort  = Integer.parseInt(cfg.getProperty("local.port",  "19132"));
            serverName = cfg.getProperty("server.name", "BedrockProxy");

            if (remoteHost.isEmpty() || remoteHost.equals("play.example.com")) {
                System.out.println("ERROR: Edit config.properties and set server.host, then restart.");
                createDefaultConfig();
                return;
            }
        }

        System.out.println("Remote  : " + remoteHost + ":" + remotePort);
        System.out.println("Local   : 0.0.0.0:" + localPort);
        System.out.println("Name    : " + serverName);
        System.out.println();
        System.out.println("Starting... (CTRL+C to stop)");
        System.out.println("─────────────────────────────────────────────");

        BedrockProxy proxy = new BedrockProxy(remoteHost, remotePort, localPort, serverName);
        proxy.start();
    }

    static Properties loadConfig() {
        Properties p = new Properties();
        File f = new File("config.properties");
        if (!f.exists()) createDefaultConfig();
        try (FileInputStream fis = new FileInputStream(f)) {
            p.load(fis);
        } catch (IOException e) {
            System.out.println("Warning: could not read config.properties");
        }
        return p;
    }

    static void createDefaultConfig() {
        try (PrintWriter pw = new PrintWriter(new FileWriter("config.properties"))) {
            pw.println("# BedrockProxy Configuration");
            pw.println();
            pw.println("# Your Bedrock server IP or hostname");
            pw.println("server.host=play.example.com");
            pw.println();
            pw.println("# Your Bedrock server port (default 19132)");
            pw.println("server.port=19132");
            pw.println();
            pw.println("# Local port to listen on (keep 19132 for auto LAN discovery)");
            pw.println("local.port=19132");
            pw.println();
            pw.println("# Name shown in LAN Games list on PS5/Xbox/Mobile");
            pw.println("server.name=My Server");
            System.out.println("Created config.properties — edit it and restart.");
        } catch (IOException e) {
            System.out.println("Could not create config.properties: " + e.getMessage());
        }
    }
}
