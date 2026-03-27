package io.whatap.turboquant.demo.sender;

import io.whatap.io.DataOutputX;
import io.whatap.lang.pack.Pack;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

public class YardTcpSender implements Closeable {
    private final String host;
    private final int port;
    private Socket socket;
    private DataOutputStream out;
    private final AtomicLong totalPacks = new AtomicLong(0);
    private final AtomicLong totalBytes = new AtomicLong(0);

    public YardTcpSender(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void connect() throws IOException {
        this.socket = new Socket(host, port);
        this.socket.setTcpNoDelay(true);
        this.socket.setSoTimeout(30000);
        this.out = new DataOutputStream(socket.getOutputStream());
        System.out.println("[YardTcpSender] Connected to " + host + ":" + port);
    }

    public synchronized void send(long pcode, int oid, Pack pack) throws IOException {
        if (out == null) {
            throw new IOException("Not connected");
        }

        DataOutputX dout = new DataOutputX();
        dout.writePack(pack);
        byte[] payload = dout.toByteArray();

        // Write 22-byte NetHead
        out.writeByte(1);            // source = AGENT
        out.writeByte(0);            // code = normal
        out.writeLong(pcode);        // pcode (8 bytes)
        out.writeInt(oid);           // oid (4 bytes)
        out.writeInt(0);             // transfer_key (4 bytes)
        out.writeInt(payload.length); // dataLen (4 bytes)

        // Write payload
        out.write(payload);
        out.flush();

        totalPacks.incrementAndGet();
        totalBytes.addAndGet(22 + payload.length);
    }

    public long getTotalPacks() { return totalPacks.get(); }
    public long getTotalBytes() { return totalBytes.get(); }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void close() throws IOException {
        if (out != null) {
            try { out.close(); } catch (IOException e) { /* ignore */ }
        }
        if (socket != null) {
            try { socket.close(); } catch (IOException e) { /* ignore */ }
        }
        System.out.println("[YardTcpSender] Disconnected");
    }
}
