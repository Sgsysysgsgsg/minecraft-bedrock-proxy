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
 * Most packets are forwarded directly to the client (transparent passthrough).
 * Some packets (PlayStatus, ServerToClientHandshake) need special handling
 * to complete the proxy handshake properly.
 */
public class DownstreamPacketHandler implements BedrockPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownstreamPacketHandler.class);

    private final ProxySession session;
    private boolean loginForwarded = false;

    public DownstreamPacketHandler(ProxySession session) {
        this.session = session;
    }

    /**
     * Called when our downstream connection to the server is established.
     * Forward the pending Login packet to the server.
     */
    public void onDownstreamConnected() {
        LoginPacket login = session.getPendingLogin();
        if (login != null && !loginForwarded) {
            loginForwarded = true;
            LOGGER.info("[{}] → Forwarding Login to remote server", session.getSessionId());
            session.sendDownstream(login);
        }
    }

    // ─── Server Handshake Handling ────────────────────────────────────────────

    /**
     * Server sends network settings — forward to client so client knows
     * what compression to use.
     */
    @Override
    public PacketSignal handle(NetworkSettingsPacket packet) {
        LOGGER.debug("[{}] → NetworkSettings from server", session.getSessionId());
        session.sendUpstream(packet);
        return PacketSignal.HANDLED;
    }

    /**
     * Server sends ServerToClientHandshake (encryption handshake).
     * Forward to client and also send ClientToServerHandshake back to server.
     */
    @Override
    public PacketSignal handle(ServerToClientHandshakePacket packet) {
        LOGGER.debug("[{}] → ServerToClientHandshake", session.getSessionId());

        // Forward handshake to the client
        session.sendUpstream(packet);

        // Reply to server completing the handshake
        ClientToServerHandshakePacket handshake = new ClientToServerHandshakePacket();
        session.sendDownstream(handshake);

        return PacketSignal.HANDLED;
    }

    /**
     * PlayStatus: tracks whether the player is fully in-game.
     * LOGIN_SUCCESS means we can start full passthrough.
     */
    @Override
    public PacketSignal handle(PlayStatusPacket packet) {
        LOGGER.debug("[{}] → PlayStatus: {}", session.getSessionId(), packet.getStatus());

        // Forward to client
        session.sendUpstream(packet);

        // Once the server says login succeeded, unlock full packet passthrough
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

    /**
     * Server kicked the player — forward the reason to the client.
     */
    @Override
    public PacketSignal handle(DisconnectPacket packet) {
        LOGGER.info("[{}] → Disconnect from server: {}", session.getSessionId(), packet.getKickMessage());
        session.sendUpstream(packet);
        session.disconnect();
        return PacketSignal.HANDLED;
    }

    // ─── Passthrough all other server packets → client ────────────────────────

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        LOGGER.debug("[{}] → Forwarding downstream packet: {}",
                session.getSessionId(), packet.getClass().getSimpleName());
        session.sendUpstream(packet);
        return PacketSignal.HANDLED;
    }
}
