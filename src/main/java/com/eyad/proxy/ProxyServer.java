package com.eyad.proxy;

public class ProxyServer {

    public static void main(String[] args) throws Exception {

        String backendHost = "127.0.0.1";
        int backendPort = 19133;

        int proxyPort = 19132;

        System.out.println("Starting EyadProxy...");
        System.out.println("Forwarding Bedrock players to " + backendHost + ":" + backendPort);

        UdpForwarder forwarder = new UdpForwarder(proxyPort, backendHost, backendPort);
        forwarder.start();
    }
}