package com.bedrockproxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason;
import org.cloudburstmc.netty.channel.raknet.RakPing;
import org.cloudburstmc.netty.channel.raknet.RakPong;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.handler.codec.raknet.common.UnconnectedPingEncoder;
import org.cloudburstmc.netty.handler.codec.raknet.common.UnconnectedPongDecoder;

import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BedrockProxy v4 — uses CloudburstMC's RakNet library (same as Geyser).
 *
 * Architecture:
 *   [Client device]
 *       ↕  RakNet (full protocol, handled by CloudburstMC)
 *   [RakNet Server — listens on localPort]
 *       ↕  per-client RakNet connection (also CloudburstMC)
 *   [Real Bedrock server — remoteHost:remotePort]
 *
 * This completely solves the "client is having trouble" error because
 * CloudburstMC handles all of: ping/pong, OCR1, OCR2, OCReply1, OCReply2,
 * connection request, reliability, ordering, ACKs — everything.
 */
public class BedrockProxy {

    // Bedrock 1.26.0
    private static final int    PROTOCOL_VERSION = 686;
    private static final String GAME_VERSION     = "1.26.0";

    private final String remoteHost;
    private final int    remotePort;
    private final int    localPort;
    private final String serverName;
    private final long   serverGuid = new Random().nextLong();

    // clientChannel → upstreamChannel (connection to real server)
    private final ConcurrentHashMap<Channel, Channel> clientToServer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Channel, Channel> serverToClient = new ConcurrentHashMap<>();

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public BedrockProxy(String remoteHost, int remotePort, int localPort, String serverName) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.localPort  = localPort;
        this.serverName = serverName;
    }

    public void start() throws Exception {
        bossGroup  = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        // Build MOTD string — shown in LAN Games list
        String motd = buildMotd();

        try {
            ServerBootstrap server = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_ADVERTISEMENT, buildAdvertisement(motd))
                .option(RakChannelOption.RAK_GUID, serverGuid)
                .option(RakChannelOption.RAK_MAX_CONNECTIONS, 1024)
                .option(RakChannelOption.RAK_SUPPORTED_PROTOCOLS, new int[]{PROTOCOL_VERSION})
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel clientCh) {
                        System.out.println("[Connect] Client: " + clientCh.remoteAddress());
                        // When a new client connects, open a RakNet connection to the real server
                        connectToRemote(clientCh);
                    }
                });

            Channel serverChannel = server.bind(new InetSocketAddress("0.0.0.0", localPort)).sync().channel();
            System.out.println("[Server] Listening on port " + localPort);
            System.out.println("[Server] Protocol " + PROTOCOL_VERSION + " (" + GAME_VERSION + ")");
            System.out.println("[Server] LAN name: " + serverName);
            System.out.println("[Server] Ready! Check Friends → LAN Games on your device.");

            serverChannel.closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    /**
     * Opens a RakNet client connection to the real Bedrock server on behalf of a client.
     */
    private void connectToRemote(Channel clientCh) {
        Bootstrap upstream = new Bootstrap()
            .group(workerGroup)
            .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
            .option(RakChannelOption.RAK_GUID, new Random().nextLong())
            .option(RakChannelOption.RAK_SUPPORTED_PROTOCOLS, new int[]{PROTOCOL_VERSION})
            .handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel serverCh) {
                    // Forward packets from real server → client
                    serverCh.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            if (clientCh.isActive()) {
                                clientCh.writeAndFlush(msg);
                            } else {
                                ctx.channel().close();
                            }
                        }

                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) {
                            System.out.println("[Disconnect] Server closed: " + clientCh.remoteAddress());
                            clientCh.close();
                            serverToClient.remove(ctx.channel());
                            clientToServer.remove(clientCh);
                        }
                    });
                }
            });

        upstream.connect(new InetSocketAddress(remoteHost, remotePort))
            .addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    Channel serverCh = future.channel();
                    clientToServer.put(clientCh, serverCh);
                    serverToClient.put(serverCh, clientCh);
                    System.out.println("[Session] Linked " + clientCh.remoteAddress() + " ↔ " + remoteHost + ":" + remotePort);

                    // Now that upstream is ready, hook client → server forwarding
                    clientCh.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            if (serverCh.isActive()) {
                                serverCh.writeAndFlush(msg);
                            }
                        }

                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) {
                            System.out.println("[Disconnect] Client left: " + clientCh.remoteAddress());
                            serverCh.close();
                            clientToServer.remove(clientCh);
                            serverToClient.remove(serverCh);
                        }
                    });
                } else {
                    System.out.println("[Error] Cannot reach " + remoteHost + ":" + remotePort
                        + " — " + future.cause().getMessage());
                    clientCh.close();
                }
            });
    }

    /**
     * Builds the MCPE advertisement ByteBuf that CloudburstMC's RakNet uses for pong replies.
     * This is what shows up in the LAN Games list.
     */
    private ByteBuf buildAdvertisement(String motd) {
        byte[] bytes = motd.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuf buf = io.netty.buffer.Unpooled.buffer(bytes.length);
        buf.writeBytes(bytes);
        return buf;
    }

    private String buildMotd() {
        return "MCPE"
            + ";" + serverName
            + ";" + PROTOCOL_VERSION
            + ";" + GAME_VERSION
            + ";0;100"
            + ";" + Math.abs(serverGuid)
            + ";" + serverName
            + ";Survival;1;" + localPort + ";19133;";
    }

    public void stop() {
        if (bossGroup  != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
    }
}
