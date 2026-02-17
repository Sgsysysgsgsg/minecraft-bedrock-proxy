package com.bedrockproxy;

import java.io.*;
import java.util.Properties;

/**
 * BedrockProxy - A LAN proxy for Bedrock Edition servers
 * Allows PS5/Xbox/Mobile players to join external Bedrock servers via LAN discovery
 *
 * Usage: java -jar BedrockProxy.jar
 *   or:  java -jar BedrockProxy.jar <server-ip> <server-port>
 */
public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║        BedrockProxy v1.0             ║");
        System.out.println("║  LAN Proxy for Bedrock Edition       ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println();

        String remoteHost;
        int remotePort;
        int localPort;
        String serverName;

        // Load config or use args or defaults
        if (args.length >= 2) {
            remoteHost = args[0];
            remotePort = Integer.parseInt(args[1]);
            localPort = args.length >= 3 ? Integer.parseInt(args[2]) : 19132;
            serverName = args.length >= 4 ? args[3] : "My Bedrock Server";
        } else {
            // Try loading config file
            Properties config = loadConfig();
            remoteHost = config.getProperty("server.host", "");
            remotePort = Integer.parseInt(config.getProperty("server.port", "19132"));
            localPort = Integer.parseInt(config.getProperty("local.port", "19132"));
            serverName = config.getProperty("server.name", "My Bedrock Server");

            if (remoteHost.isEmpty()) {
                System.out.println("No server configured! Please edit config.properties");
                System.out.println("Or run: java -jar BedrockProxy.jar <server-ip> <server-port>");
                System.out.println();
                System.out.println("Creating default config.properties...");
                createDefaultConfig();
                System.out.println("Edit config.properties and restart.");
                return;
            }
        }

        System.out.println("Remote Server : " + remoteHost + ":" + remotePort);
        System.out.println("Local Port    : " + localPort);
        System.out.println("LAN Name      : " + serverName);
        System.out.println();
        System.out.println("Starting proxy... Your PS5/Xbox will see this server");
        System.out.println("in the 'Friends' tab under 'LAN Games'.");
        System.out.println();
        System.out.println("Press CTRL+C to stop.");
        System.out.println("────────────────────────────────────────");

        BedrockProxy proxy = new BedrockProxy(remoteHost, remotePort, localPort, serverName);
        proxy.start();
    }

    private static Properties loadConfig() {
        Properties props = new Properties();
        File configFile = new File("config.properties");
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
                System.out.println("Loaded config.properties");
            } catch (IOException e) {
                System.out.println("Failed to load config: " + e.getMessage());
            }
        }
        return props;
    }

    private static void createDefaultConfig() {
        try (PrintWriter pw = new PrintWriter(new FileWriter("config.properties"))) {
            pw.println("# BedrockProxy Configuration");
            pw.println("# Edit these values and restart");
            pw.println();
            pw.println("# The external Bedrock server IP address");
            pw.println("server.host=play.example.com");
            pw.println();
            pw.println("# The external Bedrock server port (default: 19132)");
            pw.println("server.port=19132");
            pw.println();
            pw.println("# The local port to listen on (default: 19132)");
            pw.println("local.port=19132");
            pw.println();
            pw.println("# The name shown in LAN Games list on PS5/Xbox");
            pw.println("server.name=My Bedrock Server");
        } catch (IOException e) {
            System.out.println("Could not write config: " + e.getMessage());
        }
    }
}
