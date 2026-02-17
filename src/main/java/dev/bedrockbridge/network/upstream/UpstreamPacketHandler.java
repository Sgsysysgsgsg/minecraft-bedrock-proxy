package dev.bedrockbridge.network.upstream;

import dev.bedrockbridge.session.ProxySession;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles packets coming FROM the Bedrock client (upstream direction).
 *
 * Most packets are simply forwarded downstream (passthrough proxy).
 * Special packets like RequestNetworkSettings and Login need handling
 * to complete the handshake properly before forwarding.
 *
 * This is similar to Geyser's UpstreamPacketHandler but for Bedrock→Bedrock.
 */
public class UpstreamPacketHandler implements BedrockPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpstreamPacketHandler.class);

    private final ProxySession session;
    private HandshakePhase phase = HandshakePhase.HANDSHAKE;

    public UpstreamPacketHandler(ProxySession session) {
        this.session = session;
    }

    // ─── Handshake Phase ──────────────────────────────────────────────────────

    /**
     * Step 1: Client requests our network settings (compression, etc.)
     * We respond with our settings, then it will send Login.
     */
    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        LOGGER.debug("[{}] ← RequestNetworkSettings (protocol {})",
                session.getSessionId(), packet.getProtocolVersion());

        // Reply with network settings (no compression for now — can enable later)
        NetworkSettingsPacket settings = new NetworkSettingsPacket();
        settings.setCompressionThreshold(0);      // 0 = compress everything
        settings.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB);
        settings.setClientThrottleEnabled(false);
        settings.setClientThrottleThreshold(0);
        settings.setClientThrottleScalar(0f);
        session.sendUpstream(settings);

        return PacketSignal.HANDLED;
    }

    /**
     * Step 2: Client sends Login packet with Xbox auth data.
     * We now open the downstream connection to the real server
     * and forward the login to it.
     */
    @Override
    public PacketSignal handle(LoginPacket packet) {
        LOGGER.info("[{}] ← Login (chain: {} entries)",
                session.getSessionId(), packet.getChain().size());

        phase = HandshakePhase.LOGIN;

        // Connect to the remote server. Once connected, the DownstreamPacketHandler
        // will handle the server-side handshake and forward it back to the client.
        session.connectToRemoteServer();

        // We'll forward the Login packet once the downstream connection is ready.
        // Store it temporarily so DownstreamPacketHandler can replay it.
        session.setPendingLogin(packet);

        return PacketSignal.HANDLED;
    }

    // ─── Post-Login: Passthrough all packets to the server ───────────────────

    /**
     * Default: forward all other upstream packets directly to the server.
     * This is the core "transparent proxy" behavior.
     */
    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        if (phase == HandshakePhase.PLAYING && session.isConnected()) {
            LOGGER.debug("[{}] ← Forwarding upstream packet: {}",
                    session.getSessionId(), packet.getClass().getSimpleName());
            session.sendDownstream(packet);
        }
        return PacketSignal.HANDLED;
    }

    public void setPhase(HandshakePhase phase) {
        this.phase = phase;
    }

    public enum HandshakePhase {
        HANDSHAKE,  // Waiting for RequestNetworkSettings
        LOGIN,      // Waiting for downstream to connect
        PLAYING     // Fully connected — pass all packets through
    }
}
