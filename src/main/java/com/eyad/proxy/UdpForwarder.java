package com.eyad.proxy;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class UdpForwarder {

    private final int listenPort;
    private final String backendHost;
    private final int backendPort;

    public UdpForwarder(int listenPort, String backendHost, int backendPort) {
        this.listenPort = listenPort;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
    }

    public void start() throws Exception {

        DatagramSocket proxySocket = new DatagramSocket(listenPort);
        DatagramSocket backendSocket = new DatagramSocket();

        InetAddress backendAddress = InetAddress.getByName(backendHost);

        byte[] buffer = new byte[4096];

        System.out.println("Proxy listening on port " + listenPort);

        while (true) {

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            proxySocket.receive(packet);

            DatagramPacket forwardPacket = new DatagramPacket(
                    packet.getData(),
                    packet.getLength(),
                    backendAddress,
                    backendPort
            );

            backendSocket.send(forwardPacket);

            DatagramPacket backendResponse = new DatagramPacket(buffer, buffer.length);
            backendSocket.receive(backendResponse);

            DatagramPacket responsePacket = new DatagramPacket(
                    backendResponse.getData(),
                    backendResponse.getLength(),
                    packet.getAddress(),
                    packet.getPort()
            );

            proxySocket.send(responsePacket);
        }
    }
}