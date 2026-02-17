package com.bedrockproxy;

import java.net.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents one connected client's proxy session.
 *
 * Each client gets their own UDP socket to communicate with the remote server.
 * Incoming packets from the remote server are forwarded back to the client
 * through the shared local socket.
 */
public class ClientSession {

    private final InetAddress clientAddress;
    private final int clientPort;
    private final InetAddress remoteAddress;
    private final int remotePort;
    private final DatagramSocket localSocket; // shared socket to send back to client
    private DatagramSocket remoteSocket;      // dedicated socket for this client's remote connection

    private final AtomicLong lastSeen = new AtomicLong(System.currentTimeMillis());
    private volatile boolean running = true;
    private Thread receiveThread;

    public ClientSession(InetAddress clientAddress, int clientPort,
                         InetAddress remoteAddress, int remotePort,
                         DatagramSocket localSocket) throws SocketException {
        this.clientAddress = clientAddress;
        this.clientPort = clientPort;
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
        this.localSocket = localSocket;

        // Create a dedicated socket for talking to the remote server
        this.remoteSocket = new DatagramSocket();
        this.remoteSocket.setSoTimeout(30000); // 30s timeout
    }

    /**
     * Starts the background thread that listens for packets from the remote server
     * and forwards them back to the client.
     */
    public void start() {
        receiveThread = new Thread(this::receiveFromRemote);
        receiveThread.setDaemon(true);
        receiveThread.setName("Session-" + clientAddress.getHostAddress() + ":" + clientPort);
        receiveThread.start();
    }

    /**
     * Forwards a packet from the client to the remote server.
     */
    public void sendToRemote(byte[] data) {
        try {
            DatagramPacket packet = new DatagramPacket(data, data.length, remoteAddress, remotePort);
            remoteSocket.send(packet);
        } catch (Exception e) {
            if (running) {
                System.out.println("[Session] Error sending to remote: " + e.getMessage());
            }
        }
    }

    /**
     * Continuously receives packets from the remote server and forwards to client.
     */
    private void receiveFromRemote() {
        byte[] buffer = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running) {
            try {
                remoteSocket.receive(packet);
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());

                // Forward to original client through the shared local socket
                DatagramPacket toClient = new DatagramPacket(data, data.length, clientAddress, clientPort);
                localSocket.send(toClient);

                updateLastSeen();
            } catch (SocketTimeoutException e) {
                // No data in 30s - session might be dead
                if (System.currentTimeMillis() - lastSeen.get() > 60_000) {
                    System.out.println("[Session] Timeout for " + clientAddress.getHostAddress() + ":" + clientPort);
                    break;
                }
            } catch (Exception e) {
                if (running) {
                    System.out.println("[Session] Error receiving from remote: " + e.getMessage());
                }
                break;
            }
        }

        close();
    }

    public void updateLastSeen() {
        lastSeen.set(System.currentTimeMillis());
    }

    public long getLastSeen() {
        return lastSeen.get();
    }

    public void close() {
        running = false;
        if (remoteSocket != null && !remoteSocket.isClosed()) {
            remoteSocket.close();
        }
    }
}
