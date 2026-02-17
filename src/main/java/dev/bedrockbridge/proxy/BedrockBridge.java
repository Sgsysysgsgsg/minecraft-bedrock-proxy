package dev.bedrockbridge.proxy;

import dev.bedrockbridge.config.BedrockBridgeConfig;
import dev.bedrockbridge.lan.LanBroadcaster;
import dev.bedrockbridge.network.upstream.UpstreamPacketHandler;
import dev.bedrockbridge.session.ProxySession;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.Getter;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v729.Bedrock_v729;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 *
 * Beta6 note on BedrockPong:
 *   In CloudburstMC Protocol 3.0.0 Beta6, BedrockPong was refactored and no longer
 *   has the old setter API (setEdition, setMotd, etc.) nor a builder().
 *   The RAK_ADVERTISEMENT channel option accepts a raw ByteBuf containing the
 *   MOTD string in Bedrock's advertisement format, so we build it directly.
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
            LOGGER.info("LAN broadcaster started - proxy will appear as a LAN world");
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

        ChannelFuture future = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channelFactory(RakChannelFactory.server(io.netty.channel.socket.nio.NioDatagramChannel.class))
                .option(RakChannelOption.RAK_ADVERTISEMENT, buildAdvertisementBuf(0))
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
     * Builds the RakNet advertisement ByteBuf that is sent to clients during server ping.
     *
     * In Beta6, BedrockPong no longer provides a fluent API. The RAK_ADVERTISEMENT
     * option accepts a ByteBuf of the raw MOTD advertisement string directly.
     *
     * Bedrock MOTD format:
     *   MCPE;<motd>;<protocol>;<version>;<players>;<maxPlayers>;<serverId>;<subMotd>;<gameType>;1;<port4>;<port6>
     */
    public ByteBuf buildAdvertisementBuf(int onlinePlayers) {
        String motd = buildMotdString(onlinePlayers);
        byte[] motdBytes = motd.getBytes(StandardCharsets.UTF_8);

        ByteBuf buf = Unpooled.buffer(2 + motdBytes.length);
        buf.writeShort(motdBytes.length);
        buf.writeBytes(motdBytes);
        return buf;
    }

    /**
     * Builds the MOTD string in Bedrock's advertisement format.
     * Also used by LanBroadcaster for the UDP broadcast payload.
     */
    public String buildMotdString(int onlinePlayers) {
        long serverId = System.currentTimeMillis(); // unique enough for our purposes
        return "MCPE" +
                ";" + config.getLanMotd() +
                ";" + BEDROCK_CODEC.getProtocolVersion() +
                ";" + BEDROCK_CODEC.getMinecraftVersion() +
                ";" + onlinePlayers +
                ";" + config.getMaxPlayers() +
                ";" + serverId +
                ";" + config.getLanSubMotd() +
                ";Survival" +
                ";1" +
                ";" + config.getProxyPort() +
                ";19133";
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
