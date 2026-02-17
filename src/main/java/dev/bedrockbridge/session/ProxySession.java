package dev.bedrockbridge.session;

import dev.bedrockbridge.network.downstream.DownstreamPacketHandler;
import dev.bedrockbridge.network.upstream.UpstreamPacketHandler;
import dev.bedrockbridge.proxy.BedrockBridge;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.Getter;
import lombok.Setter;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ProxySession ties together:
 *   - upstreamSession: the BedrockServerSession representing the connected Bedrock CLIENT
 *   - downstreamSession: our BedrockClientSession connecting TO the remote Bedrock SERVER
 *
 * Flow:
 *   Client → [upstream] → BedrockBridge → [downstream] → Remote Server
 *   Remote Server → [downstream] → BedrockBridge → [upstream] → Client
 *
 * Packets received from the client are forwarded to the server via downstream.
 * Packets received from the server are forwarded to the client via upstream.
 */
@Getter
@Setter
public class ProxySession {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxySession.class);

    private final UUID sessionId = UUID.randomUUID();
    private final BedrockBridge bridge;
    private final BedrockServerSession upstreamSession;   // The Bedrock client connection (they connect TO us)
    private BedrockClientSession downstreamSession;       // Our connection TO the real server

    // Stored here so DownstreamPacketHandler can replay it once downstream is ready
    private LoginPacket pendingLogin;

    // Reference to upstream handler so downstream can unlock passthrough
    private UpstreamPacketHandler upstreamHandler;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean disconnected = new AtomicBoolean(false);

    public ProxySession(BedrockBridge bridge, BedrockServerSession upstreamSession) {
        this.bridge = bridge;
        this.upstreamSession = upstreamSession;

        // Handle upstream disconnect (client disconnected from us)
        upstreamSession.addDisconnectHandler(reason -> {
            LOGGER.info("[{}] Client disconnected: {}", sessionId, reason);
            disconnectInternal();
        });
    }

    /**
     * Called once the upstream (client) has completed its login handshake.
     * We now open a downstream connection to the real server.
     */
    public void connectToRemoteServer() {
        InetSocketAddress remoteAddress = bridge.getConfig().getRemoteAddress();
        LOGGER.info("[{}] Connecting to remote server {}...", sessionId, remoteAddress);

        new Bootstrap()
                .group(new NioEventLoopGroup(1))
                .channelFactory(RakChannelFactory.client(io.netty.channel.socket.nio.NioDatagramChannel.class))
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                .handler(new BedrockClientInitializer() {
                    @Override
                    protected void initSession(BedrockClientSession clientSession) {
                        downstreamSession = clientSession;
                        downstreamSession.setCodec(BedrockBridge.BEDROCK_CODEC);

                        // Handle downstream disconnect (server dropped us)
                        downstreamSession.addDisconnectHandler(reason -> {
                            LOGGER.info("[{}] Remote server disconnected: {}", sessionId, reason);
                            disconnectInternal();
                        });

                        // Set handler that forwards server packets → client
                        DownstreamPacketHandler downstreamHandler = new DownstreamPacketHandler(ProxySession.this);
                        downstreamSession.setPacketHandler(downstreamHandler);

                        connected.set(true);
                        LOGGER.info("[{}] Connected to remote server {}", sessionId, remoteAddress);

                        // Now that downstream is ready, forward the pending Login
                        downstreamHandler.onDownstreamConnected();
                    }
                })
                .connect(remoteAddress)
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        LOGGER.error("[{}] Failed to connect to remote server: {}",
                                sessionId, future.cause().getMessage());
                        upstreamSession.disconnect("Could not connect to the remote server.");
                        disconnectInternal();
                    }
                });
    }

    /**
     * Send a packet downstream (proxy → remote server).
     */
    public void sendDownstream(BedrockPacket packet) {
        if (downstreamSession != null && !downstreamSession.isClosed()) {
            downstreamSession.sendPacket(packet);
        }
    }

    /**
     * Send a packet upstream (proxy → client).
     */
    public void sendUpstream(BedrockPacket packet) {
        if (!upstreamSession.isClosed()) {
            upstreamSession.sendPacket(packet);
        }
    }

    /**
     * Disconnect both sides and clean up.
     */
    public void disconnect() {
        disconnectInternal();
    }

    private void disconnectInternal() {
        if (disconnected.getAndSet(true)) return; // already disconnecting

        connected.set(false);

        if (downstreamSession != null && !downstreamSession.isClosed()) {
            downstreamSession.disconnect();
        }
        if (!upstreamSession.isClosed()) {
            upstreamSession.disconnect("Proxy session ended");
        }

        bridge.removeSession(sessionId);
    }

    public boolean isConnected() {
        return connected.get();
    }
}
