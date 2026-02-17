package dev.bedrockbridge.network.downstream;

import dev.bedrockbridge.network.upstream.UpstreamPacketHandler;
import dev.bedrockbridge.session.ProxySession;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles packets coming FROM the remote Bedrock server (downstream direction).
 *
 * Beta6 API note:
 *   - onDisconnect() replaces addDisconnectHandler() for disconnect callbacks
 */
public class DownstreamPacketHandler implements BedrockPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownstreamPacketHandler.class);

    private final ProxySession session;
    private boolean loginForwarded = false;

    public DownstreamPacketHandler(ProxySession session) {
        this.session = session;
    }

    // ─── Disconnect callback (Beta6: replaces addDisconnectHandler) ──────────

    @Override
    public void onDisconnect(String reason) {
        session.onDownstreamDisconnect(reason);
    }

    // ─── Called when downstream is ready ─────────────────────────────────────

    public void onDownstreamConnected() {
        LoginPacket login = session.getPendingLogin();
        if (login != null && !loginForwarded) {
            loginForwarded = true;
            LOGGER.info("[{}] → Forwarding Login to remote server", session.getSessionId());
            session.sendDownstream(login);
        }
    }

    // ─── Server handshake packets ─────────────────────────────────────────────

    @Override
    public PacketSignal handle(NetworkSettingsPacket packet) {
        LOGGER.debug("[{}] → NetworkSettings from server", session.getSessionId());
        session.sendUpstream(packet);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ServerToClientHandshakePacket packet) {
        LOGGER.debug("[{}] → ServerToClientHandshake", session.getSessionId());
        session.sendUpstream(packet);

        // Complete the encryption handshake back to the server
        ClientToServerHandshakePacket handshake = new ClientToServerHandshakePacket();
        session.sendDownstream(handshake);

        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(PlayStatusPacket packet) {
        LOGGER.debug("[{}] → PlayStatus: {}", session.getSessionId(), packet.getStatus());
        session.sendUpstream(packet);

        if (packet.getStatus() == PlayStatusPacket.Status.LOGIN_SUCCESS ||
                packet.getStatus() == PlayStatusPacket.Status.PLAYER_SPAWN) {
            UpstreamPacketHandler upstreamHandler = session.getUpstreamHandler();
            if (upstreamHandler != null) {
                upstreamHandler.setPhase(UpstreamPacketHandler.HandshakePhase.PLAYING);
                LOGGER.info("[{}] ✓ Player fully connected — passthrough active", session.getSessionId());
            }
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(DisconnectPacket packet) {
        LOGGER.info("[{}] → Server kicked player: {}", session.getSessionId(), packet.getKickMessage());
        session.sendUpstream(packet);
        session.disconnect();
        return PacketSignal.HANDLED;
    }

    // ─── Passthrough all other server packets → client ────────────────────────

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        LOGGER.debug("[{}] → Forwarding: {}", session.getSessionId(),
                packet.getClass().getSimpleName());
        session.sendUpstream(packet);
        return PacketSignal.HANDLED;
    }
}
