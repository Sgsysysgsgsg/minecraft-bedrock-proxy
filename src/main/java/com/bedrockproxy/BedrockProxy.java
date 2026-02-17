package com.bedrockproxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.*;

/**
 * BedrockProxy v5
 *
 * Key improvements over v4:
 *  1. Pings the real server on startup to get its EXACT protocol + version
 *  2. Advertises that version to clients → no more "Game update required"
 *  3. Re-pings the server every 30s to stay in sync if server updates
 *  4. Uses CloudburstMC RakNet for full protocol correctness (same as Geyser)
 */
public class BedrockProxy {

    private final String     remoteHost;
    private final int        remotePort;
    private final int        localPort;
    private final String     serverName;
    private final long       proxyGuid = new Random().nextLong();

    private volatile ServerInfo serverInfo;

    // client channel → upstream channel
    private final ConcurrentHashMap<Channel, Channel> clientToServer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Channel, Channel> serverToClient = new ConcurrentHashMap<>();

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel        serverChannel;

    public BedrockProxy(String remoteHost, int remotePort, int localPort, ServerInfo info) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.localPort  = localPort;
        this.serverName = info.motdLine1;
        this.serverInfo = info;
    }

    public void start() throws Exception {
        bossGroup  = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        // Refresh server info every 30 seconds so version stays accurate
        ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor();
        refresher.scheduleAtFixedRate(this::refreshServerInfo, 30, 30, TimeUnit.SECONDS);

        try {
            serverChannel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_ADVERTISEMENT, buildAdvertisement())
                .option(RakChannelOption.RAK_GUID, proxyGuid)
                .option(RakChannelOption.RAK_MAX_CONNECTIONS, 1024)
                // Accept ALL protocol versions — let the real server decide
                .option(RakChannelOption.RAK_SUPPORTED_PROTOCOLS,
                        new int[]{
                            // Support a broad range of protocols so any version can pass through
                            340, 361, 388, 389, 390, 407, 408, 419, 422, 428,
                            431, 440, 448, 465, 471, 475, 486, 503, 527, 534,
                            544, 545, 554, 557, 560, 567, 568, 575, 582, 589,
                            594, 618, 622, 630, 649, 662, 671, 685, 686
                        })
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel clientCh) {
                        System.out.println("[+] Client: " + clientCh.remoteAddress());
                        connectUpstream(clientCh);
                    }
                })
                .bind(new InetSocketAddress("0.0.0.0", localPort))
                .sync()
                .channel();

            System.out.println("[OK] Proxy is running on port " + localPort);
            System.out.println("[OK] Version: " + serverInfo.version + " (protocol " + serverInfo.protocol + ")");
            System.out.println("[OK] Join via Friends → LAN Games on any device");

            serverChannel.closeFuture().sync();
        } finally {
            refresher.shutdown();
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    /**
     * Re-ping the real server to get updated MOTD/version and update the advertisement.
     * Called every 30 seconds so if the server updates, the proxy stays in sync.
     */
    private void refreshServerInfo() {
        ServerInfo fresh = ServerPinger.ping(remoteHost, remotePort, 3000);
        if (fresh != null) {
            fresh.motdLine1 = serverName;
            fresh.motdLine2 = serverName;
            serverInfo = fresh;

            // Update the RakNet advertisement live
            if (serverChannel != null && serverChannel.isActive()) {
                serverChannel.config().setOption(
                    RakChannelOption.RAK_ADVERTISEMENT, buildAdvertisement()
                );
            }
            System.out.println("[Refresh] Server version: " + fresh.version
                + " (protocol " + fresh.protocol + ") players: " + fresh.players + "/" + fresh.maxPlayers);
        }
    }

    /**
     * Open a RakNet connection to the real server on behalf of this client.
     */
    private void connectUpstream(Channel clientCh) {
        new Bootstrap()
            .group(workerGroup)
            .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
            .option(RakChannelOption.RAK_GUID, new Random().nextLong())
            .option(RakChannelOption.RAK_SUPPORTED_PROTOCOLS,
                    new int[]{ serverInfo.protocol })
            .handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel serverCh) {
                    // Real server → client
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
                            System.out.println("[-] Server closed for " + clientCh.remoteAddress());
                            clientCh.close();
                            cleanup(clientCh, ctx.channel());
                        }
                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            System.out.println("[!] Upstream error: " + cause.getMessage());
                            ctx.channel().close();
                        }
                    });
                }
            })
            .connect(new InetSocketAddress(remoteHost, remotePort))
            .addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    Channel serverCh = future.channel();
                    clientToServer.put(clientCh, serverCh);
                    serverToClient.put(serverCh, clientCh);
                    System.out.println("[↔] " + clientCh.remoteAddress() + " ↔ " + remoteHost + ":" + remotePort);

                    // Client → real server
                    clientCh.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            if (serverCh.isActive()) {
                                serverCh.writeAndFlush(msg);
                            }
                        }
                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) {
                            System.out.println("[-] Client left: " + clientCh.remoteAddress());
                            serverCh.close();
                            cleanup(clientCh, serverCh);
                        }
                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            System.out.println("[!] Client error: " + cause.getMessage());
                            ctx.channel().close();
                        }
                    });
                } else {
                    System.out.println("[!] Cannot reach " + remoteHost + ":" + remotePort
                        + " — " + future.cause().getMessage());
                    clientCh.close();
                }
            });
    }

    private void cleanup(Channel a, Channel b) {
        clientToServer.remove(a);
        serverToClient.remove(b);
        clientToServer.remove(b);
        serverToClient.remove(a);
    }

    /** Build the MCPE advertisement ByteBuf for CloudburstMC's RakNet pong handler. */
    private ByteBuf buildAdvertisement() {
        String motd = serverInfo.buildMotd(serverName, localPort);
        return Unpooled.wrappedBuffer(motd.getBytes(StandardCharsets.UTF_8));
    }

    public void stop() {
        if (bossGroup  != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
    }
}
