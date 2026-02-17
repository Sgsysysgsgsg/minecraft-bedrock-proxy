package dev.bedrockbridge.network.upstream;

import dev.bedrockbridge.session.ProxySession;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles packets coming FROM the Bedrock client (upstream direction).
 *
 * Beta6 API note:
 *   - PacketCompressionAlgorithm moved to org.cloudburstmc.protocol.bedrock.data
 *   - onDisconnect() replaces addDisconnectHandler() for disconnect callbacks
 */
public class UpstreamPacketHandler implements BedrockPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpstreamPacketHandler.class);

    private final ProxySession session;
    private HandshakePhase phase = HandshakePhase.HANDSHAKE;

    public UpstreamPacketHandler(ProxySession session) {
        this.session = session;
    }

    // ─── Disconnect callback (Beta6: replaces addDisconnectHandler) ──────────

    @Override
    public void onDisconnect(String reason) {
        session.onUpstreamDisconnect(reason);
    }

    // ─── Handshake ────────────────────────────────────────────────────────────

    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        LOGGER.debug("[{}] ← RequestNetworkSettings (protocol {})",
                session.getSessionId(), packet.getProtocolVersion());

        NetworkSettingsPacket settings = new NetworkSettingsPacket();
        settings.setCompressionThreshold(0);
        settings.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB);
        settings.setClientThrottleEnabled(false);
        settings.setClientThrottleThreshold(0);
        settings.setClientThrottleScalar(0f);
        session.sendUpstream(settings);

        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(LoginPacket packet) {
        LOGGER.info("[{}] ← Login (chain: {} entries)",
                session.getSessionId(), packet.getChain().size());

        phase = HandshakePhase.LOGIN;
        session.setPendingLogin(packet);
        session.connectToRemoteServer();

        return PacketSignal.HANDLED;
    }

    // ─── Passthrough ──────────────────────────────────────────────────────────

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        if (phase == HandshakePhase.PLAYING && session.isConnected()) {
            LOGGER.debug("[{}] ← Forwarding: {}", session.getSessionId(),
                    packet.getClass().getSimpleName());
            session.sendDownstream(packet);
        }
        return PacketSignal.HANDLED;
    }

    public void setPhase(HandshakePhase phase) {
        this.phase = phase;
    }

    public enum HandshakePhase {
        HANDSHAKE,
        LOGIN,
        PLAYING
    }
}
