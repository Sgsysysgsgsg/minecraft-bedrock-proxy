package dev.bedrockbridge.config;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.Map;

/**
 * Loads and holds BedrockBridge configuration from config.yml.
 * Mirrors how Geyser handles its config.
 */
@Getter
public class BedrockBridgeConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockBridgeConfig.class);

    // --- Proxy listener (what the Bedrock client connects TO) ---
    private String proxyBindAddress = "0.0.0.0";
    private int proxyPort = 19150; // Use a different port so it doesn't clash with a local BDS

    // --- Remote server (the real Bedrock server we forward to) ---
    private String remoteAddress = "127.0.0.1";
    private int remotePort = 19132;

    // --- LAN Broadcast settings ---
    private boolean lanBroadcastEnabled = true;
    private String lanMotd = "BedrockBridge Server";
    private String lanSubMotd = "Powered by BedrockBridge";
    private int lanBroadcastIntervalMs = 1500;

    // --- General ---
    private int maxPlayers = 20;
    private boolean debugLogging = false;

    public InetSocketAddress getProxyBindAddress() {
        return new InetSocketAddress(proxyBindAddress, proxyPort);
    }

    public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress(remoteAddress, remotePort);
    }

    /**
     * Load from config.yml, creating a default one if it doesn't exist.
     */
    public static BedrockBridgeConfig load(Path configDir) throws IOException {
        Path configFile = configDir.resolve("config.yml");

        if (!Files.exists(configFile)) {
            LOGGER.info("No config.yml found, creating default...");
            Files.createDirectories(configDir);
            writeDefaultConfig(configFile);
        }

        BedrockBridgeConfig config = new BedrockBridgeConfig();
        try (InputStream in = Files.newInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(in);
            if (data != null) {
                config.applyMap(data);
            }
        }

        LOGGER.info("Config loaded. Proxy: {}:{} → Remote: {}:{}",
                config.proxyBindAddress, config.proxyPort,
                config.remoteAddress, config.remotePort);

        return config;
    }

    @SuppressWarnings("unchecked")
    private void applyMap(Map<String, Object> data) {
        // Proxy section
        if (data.containsKey("proxy")) {
            Map<String, Object> proxy = (Map<String, Object>) data.get("proxy");
            proxyBindAddress = getString(proxy, "bind-address", proxyBindAddress);
            proxyPort = getInt(proxy, "port", proxyPort);
        }

        // Remote server section
        if (data.containsKey("remote")) {
            Map<String, Object> remote = (Map<String, Object>) data.get("remote");
            remoteAddress = getString(remote, "address", remoteAddress);
            remotePort = getInt(remote, "port", remotePort);
        }

        // LAN section
        if (data.containsKey("lan")) {
            Map<String, Object> lan = (Map<String, Object>) data.get("lan");
            lanBroadcastEnabled = getBool(lan, "enabled", lanBroadcastEnabled);
            lanMotd = getString(lan, "motd", lanMotd);
            lanSubMotd = getString(lan, "sub-motd", lanSubMotd);
            lanBroadcastIntervalMs = getInt(lan, "broadcast-interval-ms", lanBroadcastIntervalMs);
        }

        maxPlayers = getInt(data, "max-players", maxPlayers);
        debugLogging = getBool(data, "debug-logging", debugLogging);
    }

    private static String getString(Map<String, Object> map, String key, String def) {
        return map.containsKey(key) ? String.valueOf(map.get(key)) : def;
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        return map.containsKey(key) ? ((Number) map.get(key)).intValue() : def;
    }

    private static boolean getBool(Map<String, Object> map, String key, boolean def) {
        return map.containsKey(key) ? (Boolean) map.get(key) : def;
    }

    private static void writeDefaultConfig(Path path) throws IOException {
        String defaultConfig = """
                # ╔══════════════════════════════════════╗
                # ║       BedrockBridge config.yml       ║
                # ╚══════════════════════════════════════╝

                # The address/port BedrockBridge listens on.
                # Bedrock clients connect here.
                proxy:
                  bind-address: "0.0.0.0"
                  port: 19150

                # The real Bedrock server to forward players to.
                remote:
                  address: "127.0.0.1"
                  port: 19132

                # LAN broadcast - makes the proxy show up as a LAN world
                # in the Bedrock client's Play > Worlds tab.
                lan:
                  enabled: true
                  motd: "BedrockBridge Server"
                  sub-motd: "Powered by BedrockBridge"
                  broadcast-interval-ms: 1500

                # Max players shown in the server listing
                max-players: 20

                # Enable verbose debug logging
                debug-logging: false
                """;
        Files.writeString(path, defaultConfig);
        LOGGER.info("Default config.yml written to {}", path.toAbsolutePath());
    }
}
