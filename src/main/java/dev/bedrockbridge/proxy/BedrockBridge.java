package dev.bedrockbridge.proxy;

import dev.bedrockbridge.config.BedrockBridgeConfig;
import dev.bedrockbridge.lan.LanBroadcaster;
import dev.bedrockbridge.network.upstream.UpstreamPacketHandler;
import dev.bedrockbridge.session.ProxySession;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.Getter;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v729.Bedrock_v729;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main orchestrator for BedrockBridge.
 *
 * Responsibilities:
 *  1. Load config
 *  2. Start the Bedrock server listener (upstream) using CloudburstMC Protocol
 *  3. Start the LAN broadcaster so nearby clients can discover us
 *  4. Manage active ProxySessions (one per connected client)
 */
@Getter
public class BedrockBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockBridge.class);

    // Use the latest supported Bedrock codec version.
    // Update this constant when new Bedrock versions release.
    public static final BedrockCodec BEDROCK_CODEC = Bedrock_v729.CODEC;

    private BedrockBridgeConfig config;
    private LanBroadcaster lanBroadcaster;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    // Active sessions: UUID -> ProxySession
    private final Map<UUID, ProxySession> sessions = new ConcurrentHashMap<>();

    public void start() throws Exception {
        // 1. Load config
        Path configDir = Paths.get(".");
        config = BedrockBridgeConfig.load(configDir);

        // 2. Start LAN broadcaster
        if (config.isLanBroadcastEnabled()) {
            lanBroadcaster = new LanBroadcaster(this);
            lanBroadcaster.start();
            LOGGER.info("LAN broadcaster started — proxy will appear as a LAN world");
        }

        // 3. Start the upstream Bedrock server (clients connect here)
        startUpstreamListener();

        LOGGER.info("BedrockBridge is running!");
        LOGGER.info("Bedrock clients can connect to: {}:{}",
                config.getProxyBindAddress().getHostString(),
                config.getProxyBindAddress().getPort());
        LOGGER.info("Forwarding to remote server: {}:{}",
                config.getRemoteAddress().getHostString(),
                config.getRemoteAddress().getPort());

        // Block main thread
        Thread.currentThread().join();
    }

    private void startUpstreamListener() throws Exception {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        InetSocketAddress bindAddress = config.getProxyBindAddress();

        // Build a BedrockPong for the server listing
        BedrockPong pong = buildPong(0);

        ChannelFuture future = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channelFactory(RakChannelFactory.server(io.netty.channel.socket.nio.NioDatagramChannel.class))
                .option(RakChannelOption.RAK_ADVERTISEMENT, pong.toByteBuf())
                .childHandler(new BedrockServerInitializer() {
                    @Override
                    protected void initSession(BedrockServerSession serverSession) {
                        LOGGER.info("New client connecting from {}", serverSession.getSocketAddress());

                        // Set the codec so we can decode/encode packets
                        serverSession.setCodec(BEDROCK_CODEC);

                        // Create a proxy session for this client
                        ProxySession session = new ProxySession(BedrockBridge.this, serverSession);
                        sessions.put(session.getSessionId(), session);

                        // Set the upstream packet handler and give session a reference to it
                        UpstreamPacketHandler upstreamHandler = new UpstreamPacketHandler(session);
                        session.setUpstreamHandler(upstreamHandler);
                        serverSession.setPacketHandler(upstreamHandler);
                    }
                })
                .bind(bindAddress)
                .sync();

        LOGGER.info("Upstream listener bound to {}", bindAddress);
    }

    /**
     * Builds a BedrockPong used for server listing and LAN broadcast.
     */
    public BedrockPong buildPong(int onlinePlayers) {
        BedrockPong pong = new BedrockPong();
        pong.setEdition("MCPE");
        pong.setMotd(config.getLanMotd());
        pong.setSubMotd(config.getLanSubMotd());
        pong.setPlayerCount(onlinePlayers);
        pong.setMaximumPlayerCount(config.getMaxPlayers());
        pong.setGameType("Survival");
        pong.setProtocolVersion(BEDROCK_CODEC.getProtocolVersion());
        pong.setVersion(BEDROCK_CODEC.getMinecraftVersion());
        pong.setIpv4Port(config.getProxyPort());
        pong.setIpv6Port(19133);
        return pong;
    }

    /**
     * Called when a session fully disconnects — remove from active map.
     */
    public void removeSession(UUID sessionId) {
        sessions.remove(sessionId);
        LOGGER.info("Session {} removed. Active sessions: {}", sessionId, sessions.size());
    }

    public void shutdown() {
        if (lanBroadcaster != null) {
            lanBroadcaster.stop();
        }
        sessions.values().forEach(ProxySession::disconnect);
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }
}
