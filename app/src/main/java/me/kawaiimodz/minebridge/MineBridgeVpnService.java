package me.kawaiimodz.minebridge;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class MineBridgeVpnService extends VpnService {
    public static final String ACTION_VPN_STATE_CHANGED = "me.kawaiimodz.minebridge.VPN_STATE_CHANGED";
    public static final String ACTION_DISCONNECT = "me.kawaiimodz.minebridge.DISCONNECT";
    public static final String EXTRA_STATE = "extra_state";
    public static final String EXTRA_VIRTUAL_IP = "extra_virtual_ip";
    public static final String EXTRA_PING = "extra_ping";
    public static final String EXTRA_ERROR = "extra_error";

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_FAILED = 3;

    private static final String TAG = "MineBridgeVPN";
    private static final byte PACKET_TYPE_AUTH_REQ = 0x01;
    private static final byte PACKET_TYPE_AUTH_RESP = (byte) 0x81;
    private static final byte PACKET_TYPE_DATA = 0x02;
    private static final byte PACKET_TYPE_PING = 0x03;
    private static final byte PACKET_TYPE_PONG = (byte) 0x83;

    private ParcelFileDescriptor vpnInterface;
    private DatagramSocket udpSocket;
    private FileInputStream vpnInputStream;
    private FileOutputStream vpnOutputStream;

    private Thread vpnThread;
    private Thread outboundThread;
    private Thread inboundThread;
    private Thread pingThread;

    private volatile boolean isRunning = false;
    private String assignedVirtualIp = null;
    private long lastPingSendTime = 0;
    private byte[] cipherKey;

    private static void cryptBuffer(byte[] data, int offset, int length, byte[] key) {
        if (key == null || key.length == 0) return;
        for (int i = 0; i < length; i++) {
            data[offset + i] = (byte) (data[offset + i] ^ key[i % key.length] ^ (i & 0xFF));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        if (ACTION_DISCONNECT.equals(intent.getAction())) {
            disconnect();
            return START_NOT_STICKY;
        }

        String serverIp = intent.getStringExtra("SERVER_IP");
        int serverPort = intent.getIntExtra("SERVER_PORT", 24710);
        String username = intent.getStringExtra("USERNAME");
        String password = intent.getStringExtra("PASSWORD");

        if (!isRunning) {
            isRunning = true;
            broadcastState(STATE_CONNECTING, null, 0, null);
            startVpnThread(serverIp, serverPort, username, password);
        }
        return START_NOT_STICKY;
    }

    private void startVpnThread(String serverIp, int serverPort, String username, String password) {
        vpnThread = new Thread(() -> {
            try {
                if (serverIp == null || serverIp.trim().isEmpty()) {
                    broadcastState(STATE_FAILED, null, 0, "Server IP/Domain is not configured in Settings!");
                    disconnect();
                    return;
                }

                cipherKey = (password != null ? password : "").getBytes(StandardCharsets.UTF_8);

                udpSocket = new DatagramSocket();
                boolean protectedOk = protect(udpSocket);
                Log.d(TAG, "UDP Socket protected: " + protectedOk);

                udpSocket.setSoTimeout(2000); // 2 seconds timeout per attempt

                InetAddress serverAddr = InetAddress.getByName(serverIp.trim());

                // 1. Send AUTH Handshake Packet with Retry Loop (up to 3 attempts)
                byte[] authPacket = buildAuthPacket(username, password);
                DatagramPacket sendAuth = new DatagramPacket(authPacket, authPacket.length, serverAddr, serverPort);

                byte[] respBuf = new byte[64];
                DatagramPacket recvAuth = new DatagramPacket(respBuf, respBuf.length);

                boolean handshakeOk = false;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    if (!isRunning) return;
                    try {
                        Log.d(TAG, "Sending Auth Packet attempt " + attempt + " to " + serverAddr.getHostAddress() + ":" + serverPort);
                        udpSocket.send(sendAuth);
                        udpSocket.receive(recvAuth);
                        handshakeOk = true;
                        break;
                    } catch (SocketTimeoutException ste) {
                        Log.w(TAG, "Auth attempt " + attempt + " timed out, retrying...");
                    }
                }

                if (!isRunning) return;

                if (!handshakeOk) {
                    broadcastState(STATE_FAILED, null, 0, "No response from Server (Check IP, Port 24710, or Firewall)");
                    disconnect();
                    return;
                }

                byte[] respData = recvAuth.getData();
                if (respData[0] == PACKET_TYPE_AUTH_RESP) {
                    cryptBuffer(respData, 1, recvAuth.getLength() - 1, cipherKey);
                }

                if (respData[0] != PACKET_TYPE_AUTH_RESP || respData[1] != 0x00) {
                    broadcastState(STATE_FAILED, null, 0, "Authentication failed (Incorrect Password or Server rejected)");
                    disconnect();
                    return;
                }

                // Parse assigned Virtual IP from bytes [2..5]
                int b1 = respData[2] & 0xFF;
                int b2 = respData[3] & 0xFF;
                int b3 = respData[4] & 0xFF;
                int b4 = respData[5] & 0xFF;
                assignedVirtualIp = b1 + "." + b2 + "." + b3 + "." + b4;

                Log.d(TAG, "Handshake Success! Assigned Virtual IP: " + assignedVirtualIp);

                // 2. Establish TUN Interface with assigned IP
                Builder builder = new Builder();
                builder.addAddress(assignedVirtualIp, 24);
                builder.addRoute("10.8.0.0", 24);
                builder.setMtu(1400);
                builder.setSession("MineBridgeVPN");

                vpnInterface = builder.establish();
                if (vpnInterface == null) {
                    broadcastState(STATE_FAILED, null, 0, "Failed to establish TUN Interface");
                    disconnect();
                    return;
                }

                vpnInputStream = new FileInputStream(vpnInterface.getFileDescriptor());
                vpnOutputStream = new FileOutputStream(vpnInterface.getFileDescriptor());

                // Remove socket timeout for data transfer loop
                udpSocket.setSoTimeout(0);

                broadcastState(STATE_CONNECTED, assignedVirtualIp, 0, null);

                // 3. Start Inbound, Outbound, and Ping threads
                startOutboundLoop(serverAddr, serverPort);
                startInboundLoop();
                startPingLoop(serverAddr, serverPort);

            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "VPN Handshake/Connection failed", e);
                    broadcastState(STATE_FAILED, null, 0, "Connection error: " + e.getLocalizedMessage());
                }
                disconnect();
            }
        });
        vpnThread.start();
    }

    private byte[] buildAuthPacket(String username, String password) {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (deviceId == null) {
            deviceId = "unknown_device";
        }

        byte[] userBytes = (username != null ? username : "").getBytes(StandardCharsets.UTF_8);
        byte[] passBytes = (password != null ? password : "").getBytes(StandardCharsets.UTF_8);
        byte[] deviceBytes = deviceId.getBytes(StandardCharsets.UTF_8);

        ByteBuffer bb = ByteBuffer.allocate(1 + 2 + userBytes.length + 2 + passBytes.length + 2 + deviceBytes.length);
        bb.put(PACKET_TYPE_AUTH_REQ);
        bb.putShort((short) userBytes.length);
        bb.put(userBytes);
        bb.putShort((short) passBytes.length);
        bb.put(passBytes);
        bb.putShort((short) deviceBytes.length);
        bb.put(deviceBytes);

        byte[] authArray = bb.array();
        cryptBuffer(authArray, 1, authArray.length - 1, cipherKey);
        return authArray;
    }

    private void startOutboundLoop(InetAddress serverAddr, int serverPort) {
        outboundThread = new Thread(() -> {
            try {
                byte[] buffer = new byte[32767];
                while (isRunning && vpnInputStream != null) {
                    int length = vpnInputStream.read(buffer);
                    if (length > 0 && isRunning) {
                        // Header [0x02] + Encrypted IP packet payload
                        byte[] outData = new byte[length + 1];
                        outData[0] = PACKET_TYPE_DATA;
                        System.arraycopy(buffer, 0, outData, 1, length);
                        cryptBuffer(outData, 1, length, cipherKey);

                        DatagramPacket packet = new DatagramPacket(outData, outData.length, serverAddr, serverPort);
                        if (udpSocket != null && !udpSocket.isClosed()) {
                            udpSocket.send(packet);
                        }
                    }
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "Outbound error", e);
                }
            }
        });
        outboundThread.start();
    }

    private void startInboundLoop() {
        inboundThread = new Thread(() -> {
            try {
                byte[] buffer = new byte[32767];
                while (isRunning && vpnOutputStream != null) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    if (udpSocket != null && !udpSocket.isClosed()) {
                        udpSocket.receive(packet);
                    } else {
                        break;
                    }

                    if (!isRunning) break;

                    byte[] data = packet.getData();
                    int len = packet.getLength();
                    if (len > 1) {
                        byte packetType = data[0];
                        if (packetType == PACKET_TYPE_DATA) {
                            // Decrypt raw IP packet (skipping header byte 0) before writing to TUN
                            cryptBuffer(data, 1, len - 1, cipherKey);
                            vpnOutputStream.write(data, 1, len - 1);
                        } else if (packetType == PACKET_TYPE_PONG && isRunning) {
                            long ping = System.currentTimeMillis() - lastPingSendTime;
                            broadcastState(STATE_CONNECTED, assignedVirtualIp, (int) ping, null);
                        }
                    }
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "Inbound error", e);
                }
            }
        });
        inboundThread.start();
    }

    private void startPingLoop(InetAddress serverAddr, int serverPort) {
        pingThread = new Thread(() -> {
            while (isRunning) {
                try {
                    byte[] pingPacket = new byte[]{PACKET_TYPE_PING, 0x01, 0x02, 0x03};
                    cryptBuffer(pingPacket, 1, pingPacket.length - 1, cipherKey);

                    lastPingSendTime = System.currentTimeMillis();
                    DatagramPacket packet = new DatagramPacket(pingPacket, pingPacket.length, serverAddr, serverPort);
                    if (udpSocket != null && !udpSocket.isClosed()) {
                        udpSocket.send(packet);
                    }
                    Thread.sleep(5000); // Send Ping every 5 seconds
                } catch (Exception e) {
                    break;
                }
            }
        });
        pingThread.start();
    }

    private void broadcastState(int state, String virtualIp, int ping, String errorMessage) {
        if (!isRunning && state == STATE_CONNECTED) {
            // Do not broadcast connected if service is stopped
            return;
        }

        Intent intent = new Intent(ACTION_VPN_STATE_CHANGED);
        intent.putExtra(EXTRA_STATE, state);
        if (virtualIp != null) intent.putExtra(EXTRA_VIRTUAL_IP, virtualIp);
        if (ping > 0) intent.putExtra(EXTRA_PING, ping);
        if (errorMessage != null) intent.putExtra(EXTRA_ERROR, errorMessage);

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public synchronized void disconnect() {
        if (!isRunning) {
            broadcastState(STATE_DISCONNECTED, null, 0, null);
            stopSelf();
            return;
        }
        isRunning = false;

        broadcastState(STATE_DISCONNECTED, null, 0, null);

        // Close streams first to unblock blocking read/write
        if (vpnInputStream != null) {
            try { vpnInputStream.close(); } catch (Exception ignored) {}
            vpnInputStream = null;
        }
        if (vpnOutputStream != null) {
            try { vpnOutputStream.close(); } catch (Exception ignored) {}
            vpnOutputStream = null;
        }

        // Close UDP Socket to unblock udpSocket.receive()
        if (udpSocket != null) {
            try { udpSocket.close(); } catch (Exception ignored) {}
            udpSocket = null;
        }

        // Close VPN Interface
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (Exception ignored) {}
            vpnInterface = null;
        }

        // Interrupt threads
        if (pingThread != null) {
            pingThread.interrupt();
            pingThread = null;
        }
        if (outboundThread != null) {
            outboundThread.interrupt();
            outboundThread = null;
        }
        if (inboundThread != null) {
            inboundThread.interrupt();
            inboundThread = null;
        }
        if (vpnThread != null) {
            vpnThread.interrupt();
            vpnThread = null;
        }

        stopSelf();
    }

    @Override
    public void onRevoke() {
        Log.d(TAG, "VPN connection revoked by system or user");
        disconnect();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        disconnect();
        super.onDestroy();
    }
}