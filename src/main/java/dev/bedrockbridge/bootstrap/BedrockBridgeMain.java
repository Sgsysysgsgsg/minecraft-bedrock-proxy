package dev.bedrockbridge.bootstrap;

import dev.bedrockbridge.proxy.BedrockBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BedrockBridge - A Bedrock-to-Bedrock proxy.
 *
 * Architecture (mirrors Geyser's design):
 *
 *   [Bedrock Client]
 *         |
 *         | RakNet/UDP
 *         v
 *   [BedrockBridge Proxy]  <-- listens on 0.0.0.0:19132 (or configured port)
 *    - UpstreamSession       <-- represents the connected Bedrock client
 *    - DownstreamSession     <-- our connection TO the remote Bedrock server
 *         |
 *         | RakNet/UDP
 *         v
 *   [Remote Bedrock Server] (e.g. a BDS, Nukkit, or PocketMine server)
 *
 * LAN Broadcasting:
 *   BedrockBridge also runs a LAN advertiser that sends UDP broadcasts
 *   on 255.255.255.255:19132 so Bedrock clients on the same network see
 *   the proxy as a LAN world (just like in your screenshot).
 */
public class BedrockBridgeMain {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockBridgeMain.class);

    public static void main(String[] args) {
        LOGGER.info("╔══════════════════════════════════════╗");
        LOGGER.info("║         BedrockBridge Proxy          ║");
        LOGGER.info("║  Bedrock Client → Bedrock Server     ║");
        LOGGER.info("╚══════════════════════════════════════╝");

        BedrockBridge bridge = new BedrockBridge();

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down BedrockBridge...");
            bridge.shutdown();
        }, "shutdown-hook"));

        try {
            bridge.start();
        } catch (Exception e) {
            LOGGER.error("Fatal error starting BedrockBridge", e);
            System.exit(1);
        }
    }
}
