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
 * Beta6 API note:
 *   - addDisconnectHandler() was REMOVED in Beta6. Disconnect events now fire via
 *     BedrockPacketHandler#onDisconnect() on the handler attached to the session.
 *   - isClosed() was REMOVED. Use isConnected() instead.
 */
@Getter
@Setter
public class ProxySession {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxySession.class);

    private final UUID sessionId = UUID.randomUUID();
    private final BedrockBridge bridge;
    private final BedrockServerSession upstreamSession;
    private BedrockClientSession downstreamSession;

    private LoginPacket pendingLogin;
    private UpstreamPacketHandler upstreamHandler;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean disconnected = new AtomicBoolean(false);

    public ProxySession(BedrockBridge bridge, BedrockServerSession upstreamSession) {
        this.bridge = bridge;
        this.upstreamSession = upstreamSession;
    }

    /** Called by UpstreamPacketHandler#onDisconnect() */
    public void onUpstreamDisconnect(String reason) {
        LOGGER.info("[{}] Client disconnected: {}", sessionId, reason);
        disconnectInternal();
    }

    /** Called by DownstreamPacketHandler#onDisconnect() */
    public void onDownstreamDisconnect(String reason) {
        LOGGER.info("[{}] Remote server disconnected: {}", sessionId, reason);
        disconnectInternal();
    }

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

                        DownstreamPacketHandler downstreamHandler = new DownstreamPacketHandler(ProxySession.this);
                        downstreamSession.setPacketHandler(downstreamHandler);

                        connected.set(true);
                        LOGGER.info("[{}] Connected to remote server {}", sessionId, remoteAddress);
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

    public void sendDownstream(BedrockPacket packet) {
        if (downstreamSession != null && downstreamSession.isConnected()) {
            downstreamSession.sendPacket(packet);
        }
    }

    public void sendUpstream(BedrockPacket packet) {
        if (upstreamSession.isConnected()) {
            upstreamSession.sendPacket(packet);
        }
    }

    public void disconnect() {
        disconnectInternal();
    }

    private void disconnectInternal() {
        if (disconnected.getAndSet(true)) return;
        connected.set(false);
        if (downstreamSession != null && downstreamSession.isConnected()) {
            downstreamSession.disconnect();
        }
        if (upstreamSession.isConnected()) {
            upstreamSession.disconnect("Proxy session ended");
        }
        bridge.removeSession(sessionId);
    }

    public boolean isConnected() {
        return connected.get();
    }
}
